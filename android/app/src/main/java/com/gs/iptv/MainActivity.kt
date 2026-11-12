package com.gs.iptv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var tab = Tab.TV
    private val tabViews = ArrayList<TextView>()

    private lateinit var liveView: View
    private lateinit var vodView: View
    private lateinit var empty: TextView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var clock: TextView
    private lateinit var preview: PlayerView
    private lateinit var nowName: TextView
    private lateinit var nowGroup: TextView
    private lateinit var liveBadge: View
    private lateinit var channelsList: RecyclerView
    private lateinit var gridList: RecyclerView

    private lateinit var liveCatAdapter: CategoryAdapter
    private lateinit var vodCatAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var posterAdapter: PosterAdapter

    private var liveCats: List<Pair<String, List<Item>>> = emptyList()
    private var vodCats: List<Pair<String, List<Item>>> = emptyList()

    private var player: ExoPlayer? = null
    private var current: Item? = null
    private var loaded = false

    private val ui = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            ui.postDelayed(this, 15_000)
        }
    }

    private val sendList = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK || !loaded) reload(force = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        liveView = findViewById(R.id.liveView)
        vodView = findViewById(R.id.vodView)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        clock = findViewById(R.id.clock)
        preview = findViewById(R.id.preview)
        nowName = findViewById(R.id.nowName)
        nowGroup = findViewById(R.id.nowGroup)
        liveBadge = findViewById(R.id.liveBadge)
        channelsList = findViewById(R.id.channels)
        gridList = findViewById(R.id.grid)
        findViewById<TextView>(R.id.brand).text = Store.listName(this).let { "GS IPTV  ·  $it" }

        buildTabs()
        setupLists()

        findViewById<View>(R.id.btnSearch).setOnClickListener { openSearch() }
        findViewById<View>(R.id.btnSend).setOnClickListener { openSendList() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { reload(force = true) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabViews.none { it.hasFocus() }) {
                    tabViews[tab.ordinal].requestFocus()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        ListServer.start(this)
        if (!Store.hasList(this)) openSendList() else reload(force = false)
    }

    // ---------------- Abas ----------------
    private fun buildTabs() {
        val bar = findViewById<LinearLayout>(R.id.tabs)
        Tab.values().forEach { t ->
            val tv = TextView(this).apply {
                text = t.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (t == Tab.KIDS) Color.parseColor("#FFE27A") else Color.WHITE)
                setBackgroundResource(R.drawable.bg_tab)
                gravity = Gravity.CENTER
                val h = dp(20); val v = dp(7)
                setPadding(h, v, h, v)
                isFocusable = true
                setOnFocusChangeListener { _, has -> if (has && t != tab) showTab(t) }
                setOnClickListener { showTab(t) }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            bar.addView(tv, lp)
            tabViews.add(tv)
        }
        tabViews[0].isSelected = true
    }

    private fun setupLists() {
        liveCatAdapter = CategoryAdapter { i -> showLiveCategory(i) }
        vodCatAdapter = CategoryAdapter { i -> showVodCategory(i) }
        channelAdapter = ChannelAdapter(
            onClick = { item, pos ->
                if (item.url == current?.url) openFullscreen(channelAdapter.items, pos) else playPreview(item)
            },
            onLongClick = { item, _ ->
                val added = Store.toggleFavorite(this, item.url)
                channelAdapter.favorites = Store.favorites(this)
                channelAdapter.refresh()
                toast(if (added) "Adicionado aos favoritos" else "Removido dos favoritos")
            },
        )
        posterAdapter = PosterAdapter { item, pos ->
            if (item.url.startsWith(Xtream.SERIES_PREFIX)) openSeries(item)
            else openFullscreen(posterAdapter.items, pos)
        }

        findViewById<RecyclerView>(R.id.liveCategories).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); adapter = liveCatAdapter
        }
        findViewById<RecyclerView>(R.id.vodCategories).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); adapter = vodCatAdapter
        }
        channelsList.layoutManager = LinearLayoutManager(this)
        channelsList.adapter = channelAdapter
        gridList.layoutManager = GridLayoutManager(this, 5)
        gridList.adapter = posterAdapter
        // Sem animação de troca: assim o foco do controle não "pula"
        listOf(R.id.liveCategories, R.id.vodCategories, R.id.channels, R.id.grid)
            .forEach { findViewById<RecyclerView>(it).itemAnimator = null }
    }

    private fun showTab(t: Tab) {
        tab = t
        tabViews.forEachIndexed { i, v -> v.isSelected = i == t.ordinal }
        if (!loaded) return
        val list = Store.forTab(t)
        val groups = LinkedHashMap<String, MutableList<Item>>()
        list.forEach { groups.getOrPut(it.group) { ArrayList() }.add(it) }

        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "Sua lista não tem conteúdo para a aba ${t.label}."

        if (t == Tab.TV) {
            liveView.visibility = View.VISIBLE
            vodView.visibility = View.GONE
            val favs = Store.favorites(this)
            liveCats = buildList {
                if (favs.isNotEmpty()) add("★ Favoritos" to list.filter { favs.contains(it.url) })
                add("Todos" to list)
                groups.forEach { (g, l) -> add(g to l) }
            }
            liveCatAdapter.items = liveCats.map { "${it.first}  (${it.second.size})" }
            showLiveCategory(0)
        } else {
            liveView.visibility = View.GONE
            vodView.visibility = View.VISIBLE
            pausePreview()
            vodCats = buildList {
                add("Todos" to list)
                groups.forEach { (g, l) -> add(g to l) }
            }
            vodCatAdapter.items = vodCats.map { "${it.first}  (${it.second.size})" }
            showVodCategory(0)
        }
    }

    private fun showLiveCategory(i: Int) {
        val cat = liveCats.getOrNull(i) ?: return
        channelAdapter.favorites = Store.favorites(this)
        channelAdapter.items = cat.second
        channelsList.scrollToPosition(0)
    }

    private fun showVodCategory(i: Int) {
        val cat = vodCats.getOrNull(i) ?: return
        posterAdapter.items = cat.second
        gridList.scrollToPosition(0)
    }

    // ---------------- Carregar lista ----------------
    private fun reload(force: Boolean) {
        findViewById<TextView>(R.id.brand).text = "GS IPTV  ·  ${Store.listName(this)}"
        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            loadingText.text = "Carregando lista…"
            empty.visibility = View.GONE
            try {
                Store.load(this@MainActivity, force) { msg -> runOnUiThread { loadingText.text = msg } }
                loaded = true
                showTab(tab)
                if (current == null) {
                    val last = Store.lastUrl(this@MainActivity)
                    Store.items.firstOrNull { it.url == last && it.kind == Kind.LIVE }?.let { playPreview(it) }
                }
                tabViews[tab.ordinal].requestFocus()
            } catch (e: Exception) {
                empty.visibility = View.VISIBLE
                empty.text = "Não foi possível carregar a lista.\n${e.message ?: ""}\n\nAperte o ícone do celular para enviar outra lista."
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    /** Série do Xtream: busca os episódios e mostra para escolher. */
    private fun openSeries(series: Item) {
        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            loadingText.text = "Abrindo ${series.name}…"
            try {
                val eps = withContext(Dispatchers.IO) { Xtream.episodes(this@MainActivity, series.url, series.name, series.logo) }
                if (eps.isEmpty()) { toast("Nenhum episódio encontrado"); return@launch }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(series.name)
                    .setItems(eps.map { it.name }.toTypedArray()) { _, i -> openFullscreen(eps, i) }
                    .setNegativeButton("Voltar", null)
                    .show()
            } catch (e: Exception) {
                toast("Não foi possível abrir a série")
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun openSendList() = sendList.launch(Intent(this, SendListActivity::class.java))

    // ---------------- Busca ----------------
    private fun openSearch() {
        if (!loaded) return
        val input = EditText(this).apply {
            hint = "Nome do canal ou filme"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.BLACK)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Buscar em ${tab.label}")
            .setView(input)
            .setPositiveButton("Buscar") { _, _ -> applySearch(input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> applySearch(input.text.toString()); dialog.dismiss(); true }
        dialog.show()
    }

    private fun applySearch(q: String) {
        val results = Store.search(q, tab)
        if (results.isEmpty()) { toast("Nada encontrado para \"$q\""); return }
        val entry = "Busca: $q" to results
        if (tab == Tab.TV) {
            liveCats = listOf(entry) + liveCats.filterNot { it.first.startsWith("Busca:") }
            liveCatAdapter.items = liveCats.map { "${it.first}  (${it.second.size})" }
            showLiveCategory(0)
            channelsList.post { channelsList.getChildAt(0)?.requestFocus() }
        } else {
            vodCats = listOf(entry) + vodCats.filterNot { it.first.startsWith("Busca:") }
            vodCatAdapter.items = vodCats.map { "${it.first}  (${it.second.size})" }
            showVodCategory(0)
            gridList.post { gridList.getChildAt(0)?.requestFocus() }
        }
    }

    // ---------------- Player ----------------
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = PlayerFactory.create(this)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                nowGroup.text = "Canal indisponível no momento"
            }
        })
        preview.player = p
        player = p
        return p
    }

    private fun playPreview(item: Item) {
        current = item
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(item.url))
        p.prepare()
        p.playWhenReady = true
        nowName.text = if (item.number > 0) "${item.number}  ${item.name}" else item.name
        nowGroup.text = item.group
        liveBadge.visibility = View.VISIBLE
        channelAdapter.playingUrl = item.url
        Store.setLastUrl(this, item.url)
    }

    private fun pausePreview() { player?.pause() }

    private fun openFullscreen(list: List<Item>, index: Int) {
        Store.playQueue = list
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", index))
    }

    override fun onStart() {
        super.onStart()
        ui.post(clockTick)
        // Volta a tocar a prévia ao retornar da tela cheia
        if (tab == Tab.TV) current?.let { playPreview(it) }
    }

    override fun onResume() {
        super.onResume()
        ListServer.listener = { toast("Lista nova recebida!"); reload(force = true) }
    }

    override fun onPause() {
        super.onPause()
        ListServer.listener = null
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(clockTick)
        preview.player = null
        player?.release()
        player = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
