package com.gs.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * Player em tela cheia.
 * Ao vivo: cima/baixo troca de canal · OK abre a lista de canais · esquerda abre as categorias.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var view: PlayerView
    private lateinit var osd: View
    private lateinit var osdName: TextView
    private lateinit var osdInfo: TextView
    private lateinit var overlay: View
    private lateinit var catBox: View
    private lateinit var catList: RecyclerView
    private lateinit var chList: RecyclerView
    private lateinit var chTitle: TextView
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var chAdapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private var smart: SmartPlay? = null
    private var queue: List<Item> = emptyList()
    private var index = 0
    private var cats: List<Pair<String, List<Item>>> = emptyList()

    private var bitrate = 0L          // velocidade estimada (bits/s)
    private var openStartedAt = 0L    // para medir quanto o canal demora para abrir
    private var osdUntil = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val osdTick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= osdUntil) { osd.visibility = View.GONE; return }
            osdInfo.text = infoText()
            ui.postDelayed(this, 1000)
        }
    }
    private val hideOverlayTask = Runnable { hideOverlay() }

    private val isLive get() = queue.getOrNull(index)?.kind == Kind.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player)
        osd = findViewById(R.id.osd)
        osdName = findViewById(R.id.osdName)
        osdInfo = findViewById(R.id.osdInfo)
        overlay = findViewById(R.id.overlay)
        catBox = findViewById(R.id.catBox)
        catList = findViewById(R.id.catList)
        chList = findViewById(R.id.chList)
        chTitle = findViewById(R.id.chTitle)

        queue = Store.playQueue
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
        if (queue.isEmpty()) { finish(); return }

        catAdapter = CategoryAdapter { i -> showCategory(i) }
        chAdapter = ChannelAdapter(
            onClick = { _, pos -> queue = chAdapter.items; hideOverlay(); play(pos) },
            onLongClick = { item, _ ->
                val added = Store.toggleFavorite(this, item.url)
                chAdapter.favorites = Store.favorites(this); chAdapter.refresh()
                Toast.makeText(this, if (added) "Adicionado aos favoritos" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
            },
        )
        catList.layoutManager = LinearLayoutManager(this); catList.adapter = catAdapter; catList.itemAnimator = null
        chList.layoutManager = LinearLayoutManager(this); chList.adapter = chAdapter; chList.itemAnimator = null

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (overlay.visibility == View.VISIBLE) hideOverlay() else finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onStart() {
        super.onStart()
        if (queue.isEmpty()) return
        val p = PlayerFactory.create(this)
        smart = SmartPlay(this, p) { osdInfo.text = "Não foi possível abrir este canal"; showOsd() }
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val item = queue.getOrNull(index) ?: return
                if (state == Player.STATE_READY && openStartedAt > 0) {
                    Diag.channelOpened(item.name, System.currentTimeMillis() - openStartedAt, smart?.currentRoute)
                    openStartedAt = 0
                } else if (state == Player.STATE_BUFFERING && openStartedAt == 0L) {
                    Diag.rebuffer(item.name)
                }
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long,
            ) { bitrate = bitrateEstimate }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                Diag.dropped(droppedFrames)
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) { Diag.video(format.width, format.height, format.bitrate, format.sampleMimeType) }
        })
        view.player = p
        player = p
        play(index)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacksAndMessages(null)
        view.player = null
        player?.release()
        player = null
        smart = null
    }

    private fun play(i: Int) {
        if (queue.isEmpty()) return
        index = (i + queue.size) % queue.size
        val item = queue[index]
        view.useController = item.kind != Kind.LIVE
        bitrate = 0
        openStartedAt = System.currentTimeMillis()
        smart?.play(item)
        if (item.kind == Kind.LIVE) Store.setLastUrl(this, item.url)
        osdName.text = if (item.number > 0) "${item.number}  ${item.name}" else item.name
        osdInfo.text = "Conectando…"
        showOsd()
    }

    /** Mostra nome + velocidade por 10 segundos. */
    private fun showOsd() {
        osd.visibility = View.VISIBLE
        osdUntil = System.currentTimeMillis() + 10_000
        ui.removeCallbacks(osdTick)
        ui.post(osdTick)
    }

    private fun infoText(): String {
        val speed = when {
            bitrate <= 0 -> "medindo…"
            bitrate >= 1_000_000 -> String.format(Locale("pt", "BR"), "%.1f Mbps", bitrate / 1_000_000.0)
            else -> "${bitrate / 1000} Kbps"
        }
        val q = player?.videoFormat?.let { f -> if (f.height > 0) " · ${f.height}p" else "" } ?: ""
        val state = when (player?.playbackState) { Player.STATE_BUFFERING -> " · carregando"; else -> "" }
        return "Velocidade: $speed$q$state"
    }

    // ---------- Lista de canais / categorias por cima do vídeo ----------
    private fun openOverlay(withCategories: Boolean) {
        val tv = Store.tab(Tab.TV)
        val favs = Store.favorites(this)
        cats = buildList {
            if (favs.isNotEmpty()) add("★ Favoritos" to tv.items.filter { favs.contains(it.url) })
            add("Todos" to tv.items)
            addAll(tv.groups)
        }
        catAdapter.items = cats.map { "${it.first}  (${it.second.size})" }
        val curCat = queue.getOrNull(index)?.group
        cats.indexOfFirst { it.first == curCat }.takeIf { it >= 0 }?.let { catAdapter.selected = it }

        chAdapter.favorites = favs
        chAdapter.items = queue
        chAdapter.playingUrl = queue.getOrNull(index)?.url
        chTitle.text = "Canais · ${queue.size}"
        overlay.visibility = View.VISIBLE
        catBox.visibility = if (withCategories) View.VISIBLE else View.GONE
        if (withCategories) focusRow(catList, catAdapter.selected) else focusRow(chList, index)
        bumpOverlayTimer()
    }

    private fun showCategory(i: Int) {
        val c = cats.getOrNull(i) ?: return
        chAdapter.items = c.second
        chTitle.text = "${c.first} · ${c.second.size}"
        chList.scrollToPosition(0)
    }

    private fun focusRow(list: RecyclerView, pos: Int) {
        val lm = list.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(pos.coerceAtLeast(0), 160)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() ?: list.requestFocus() }
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        ui.removeCallbacks(hideOverlayTask)
    }

    private fun bumpOverlayTimer() {
        ui.removeCallbacks(hideOverlayTask)
        ui.postDelayed(hideOverlayTask, 20_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isLive) {
            if (overlay.visibility == View.VISIBLE) {
                bumpOverlayTimer()
                // Esquerda na lista de canais abre as categorias
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && catBox.visibility != View.VISIBLE && chList.hasFocus()) {
                    catBox.visibility = View.VISIBLE
                    focusRow(catList, catAdapter.selected)
                    return true
                }
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { play(index - 1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(index + 1); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { openOverlay(false); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { openOverlay(true); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { showOsd(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
