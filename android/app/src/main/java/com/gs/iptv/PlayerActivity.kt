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
    private lateinit var catList: TvList
    private lateinit var chList: TvList
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
    private var status: String? = null    // aviso de conexão/erro
    private var openedInMs = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val osdTick = object : Runnable {
        override fun run() {
            // enquanto o canal não abre, o aviso continua na tela
            if (openStartedAt > 0) osdUntil = maxOf(osdUntil, System.currentTimeMillis() + 3_000)
            if (System.currentTimeMillis() >= osdUntil) { osd.visibility = View.GONE; return }
            osdInfo.text = infoText()
            ui.postDelayed(this, 1000)
        }
    }
    private val hideOverlayTask = Runnable { hideOverlay() }
    private lateinit var clock: TextView
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            ui.postDelayed(this, 15_000)
        }
    }

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

        Http.warm(Store.playlistUrl(this))
        // Aberto por um agendamento: o canal vem no próprio pedido
        val schedUrl = intent.getStringExtra("url")
        if (schedUrl != null) {
            queue = listOf(Item(intent.getStringExtra("name") ?: "Canal", schedUrl, "", "Agendado", Kind.LIVE, 0))
            Store.playQueue = queue
            intent.getStringExtra("title")?.let { Toast.makeText(this, "Agendamento: $it", Toast.LENGTH_LONG).show() }
        } else {
            queue = Store.playQueue
        }
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
        findViewById<Logo3DView>(R.id.cornerLogo).compact = true
        clock = findViewById(R.id.clock)
        clock.visibility = if (Store.clockOn(this)) View.VISIBLE else View.GONE
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
        // Direções do controle na lista por cima do vídeo
        chList.onUp = {}
        chList.onRight = {}
        chList.onLeft = { catBox.visibility = View.VISIBLE; catList.focusPosition(catAdapter.selected) }
        catList.onUp = {}
        catList.onLeft = {}
        catList.onRight = { chList.focusPosition(0) }

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
        smart = SmartPlay(this, p, onStatus = { msg -> status = msg; osdInfo.text = infoText(); showOsd() }) {
            openStartedAt = 0
            status = "${smart?.lastError ?: "Não foi possível abrir"} · aperte OK para escolher outro canal"
            osdInfo.text = status
            showOsd()
        }
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val item = queue.getOrNull(index) ?: return
                if (state == Player.STATE_READY && openStartedAt > 0) {
                    openedInMs = System.currentTimeMillis() - openStartedAt
                    Diag.channelOpened(item.name, openedInMs, smart?.currentRoute)
                    openStartedAt = 0
                    status = null
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
        if (clock.visibility == View.VISIBLE) ui.post(clockTick)
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
        status = null
        openedInMs = 0
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
        status?.let { return it }
        if (openStartedAt > 0) {
            val sec = (System.currentTimeMillis() - openStartedAt) / 1000
            return when {
                sec < 3 -> "Conectando ao servidor…"
                sec < 8 -> "Servidor demorando para responder… ${sec}s"
                else -> "Dificuldade para conectar ao servidor… ${sec}s"
            }
        }
        val opened = if (openedInMs > 0) String.format(Locale("pt", "BR"), "Abriu em %.1fs · ", openedInMs / 1000.0) else ""
        val speed = when {
            bitrate <= 0 -> "medindo…"
            bitrate >= 1_000_000 -> String.format(Locale("pt", "BR"), "%.1f Mbps", bitrate / 1_000_000.0)
            else -> "${bitrate / 1000} Kbps"
        }
        val q = player?.videoFormat?.let { f -> if (f.height > 0) " · ${f.height}p" else "" } ?: ""
        val state = when (player?.playbackState) { Player.STATE_BUFFERING -> " · carregando"; else -> "" }
        return "${opened}Velocidade: $speed$q$state"
    }

    // ---------- Lista de canais / categorias por cima do vídeo ----------
    private fun openOverlay(withCategories: Boolean) {
        val favs = Store.favorites(this)
        cats = Store.liveCategories(this)
        catAdapter.items = cats.map { it.first }
        val curCat = queue.getOrNull(index)?.group
        cats.indexOfFirst { it.first == curCat }.takeIf { it >= 0 }?.let { catAdapter.selected = it }

        chAdapter.favorites = favs
        chAdapter.items = queue
        chAdapter.playingUrl = queue.getOrNull(index)?.url
        chTitle.text = "Canais"
        overlay.visibility = View.VISIBLE
        catBox.visibility = if (withCategories) View.VISIBLE else View.GONE
        if (withCategories) catList.focusPosition(catAdapter.selected) else chList.focusPosition(index)
        bumpOverlayTimer()
    }

    private fun showCategory(i: Int) {
        val c = cats.getOrNull(i) ?: return
        chAdapter.items = c.second
        chTitle.text = c.first
        chList.scrollToPosition(0)
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
