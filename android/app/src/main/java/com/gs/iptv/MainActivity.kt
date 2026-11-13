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
    private lateinit var sectionPanel: View
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
    private var smart: SmartPlay? = null
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
        if (r.resultCode == RESULT_OK) onNewList() else if (!loaded) startup()
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
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refreshAll() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { openSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabViews.none { it.hasFocus() }) {
                    tabViews[tab.ordinal].requestFocus()
                } else {
                    confirmExit()
                }
            }
        })

        ListServer.start(this)
        GsApp.takeCrash(application)?.let { crash ->
            AlertDialog.Builder(this)
                .setTitle("O app fechou da última vez")
                .setMessage("Tire uma foto desta tela e envie para o suporte:\n\n" + crash.lines().take(14).joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
        }
        findViewById<TextView>(R.id.version).text = "v${BuildConfig.VERSION_NAME}"
        Store.justUpdated(this)?.let { v ->
            AlertDialog.Builder(this)
                .setTitle("GS IPTV atualizado")
                .setMessage("Agora você está na versão $v.")
                .setPositiveButton("OK", null)
                .show()
        }
        setupPanel()
        if (!Store.hasList(this)) openSendList() else startup()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Sair do GS IPTV?")
            .setMessage("Deseja realmente sair do app?")
            .setPositiveButton("Sair") { _, _ -> finish() }
            .setNegativeButton("Continuar", null)
            .show()
            .getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun openSettings() {
        val opts = Buffer.values()
        val cur = Buffer.current(this).ordinal
        AlertDialog.Builder(this)
            .setTitle("Buffer (reserva de vídeo)")
            .setSingleChoiceItems(opts.map { "${it.label}\n${it.desc}" }.toTypedArray(), cur) { d, i ->
                Buffer.set(this, opts[i])
                Diag.log("Buffer alterado para ${opts[i].label}")
                toast("Buffer: ${opts[i].label}")
                current?.let { playPreview(it) } // aplica no canal atual
                d.dismiss()
            }
            .setNegativeButton("Fechar", null)
            .show()
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
        val missing = kindsFor(t).filterNot { Store.isLoaded(it) }
        if (missing.isNotEmpty()) {
            // Seção ainda não baixada: se já tem cópia salva abre direto, senão mostra o painel
            if (missing.all { Store.hasFreshCache(this, it) }) { loadKinds(missing, force = false); return }
            liveView.visibility = View.GONE
            vodView.visibility = View.GONE
            empty.visibility = View.GONE
            pausePreview()
            showPanel(missing.first())
            return
        }
        sectionPanel.visibility = View.GONE
        val data = Store.tab(t)
        val list = data.items
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "Sua lista não tem conteúdo para a aba ${t.label}."

        if (t == Tab.TV) {
            liveView.visibility = View.VISIBLE
            vodView.visibility = View.GONE
            val favs = Store.favorites(this)
            liveCats = buildList {
                if (favs.isNotEmpty()) add("★ Favoritos" to list.filter { favs.contains(it.url) })
                add("Todos" to list)
                addAll(data.groups)
            }
            liveCatAdapter.items = liveCats.map { "${it.first}  (${it.second.size})" }
            showLiveCategory(0)
        } else {
            liveView.visibility = View.GONE
            vodView.visibility = View.VISIBLE
            pausePreview()
            vodCats = buildList {
                add("Todos" to list)
                addAll(data.groups)
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

    // ---------------- Carregar lista (uma seção de cada vez) ----------------
    private fun kindsFor(t: Tab): List<Kind> = when (t) {
        Tab.TV -> listOf(Kind.LIVE)
        Tab.MOVIES -> listOf(Kind.MOVIE)
        Tab.SERIES -> listOf(Kind.SERIES)
        Tab.KIDS, Tab.ANIME -> listOf(Kind.MOVIE, Kind.SERIES)
    }

    private fun setupPanel() {
        sectionPanel = findViewById(R.id.sectionPanel)
        findViewById<View>(R.id.btnLive).setOnClickListener { loadKinds(listOf(Kind.LIVE), Store.isLoaded(Kind.LIVE)) }
        findViewById<View>(R.id.btnMovie).setOnClickListener { loadKinds(listOf(Kind.MOVIE), Store.isLoaded(Kind.MOVIE)) }
        findViewById<View>(R.id.btnSeries).setOnClickListener { loadKinds(listOf(Kind.SERIES), Store.isLoaded(Kind.SERIES)) }
    }

    private fun updatePanel() {
        fun set(kind: Kind, status: Int, btn: Int) {
            val st = findViewById<TextView>(status)
            val b = findViewById<TextView>(btn)
            when {
                Store.isLoaded(kind) -> { st.text = "✓ ${Store.count(kind)} itens"; b.text = "Atualizar" }
                Store.hasFreshCache(this, kind) -> { st.text = "Salvo na TV"; b.text = "Abrir" }
                else -> { st.text = "Não baixado"; b.text = "Baixar" }
            }
        }
        set(Kind.LIVE, R.id.statusLive, R.id.btnLive)
        set(Kind.MOVIE, R.id.statusMovie, R.id.btnMovie)
        set(Kind.SERIES, R.id.statusSeries, R.id.btnSeries)
    }

    private fun showPanel(focus: Kind?) {
        updatePanel()
        sectionPanel.visibility = View.VISIBLE
        val id = when (focus) { Kind.LIVE -> R.id.btnLive; Kind.MOVIE -> R.id.btnMovie; Kind.SERIES -> R.id.btnSeries; null -> R.id.btnLive }
        sectionPanel.post { findViewById<View>(id).requestFocus() }
    }

    /** Ao abrir: só abre o que já está salvo na TV; o resto espera o botão Baixar. */
    private fun startup() {
        findViewById<TextView>(R.id.brand).text = "GS IPTV  ·  ${Store.listName(this)}"
        val cached = Kind.values().filter { Store.hasFreshCache(this, it) && !Store.isLoaded(it) }
        if (cached.isNotEmpty()) loadKinds(cached, force = false) else { showTab(tab); tabViews[tab.ordinal].requestFocus() }
    }

    private fun loadKinds(kinds: List<Kind>, force: Boolean) {
        lifecycleScope.launch {
            sectionPanel.visibility = View.GONE
            loading.visibility = View.VISIBLE
            empty.visibility = View.GONE
            var failed = false
            for (k in kinds) {
                loadingText.text = "Preparando ${Store.label(k)}…"
                try {
                    Store.loadSection(this@MainActivity, k, force) { msg -> runOnUiThread { loadingText.text = msg } }
                } catch (e: OutOfMemoryError) {
                    failed = true
                    toast("Os ${Store.label(k)} são muitos para a memória desta TV.")
                } catch (e: Exception) {
                    failed = true
                    toast("Não foi possível baixar ${Store.label(k)}: ${e.message ?: "erro de conexão"}")
                }
            }
            loading.visibility = View.GONE
            loaded = true
            if (kinds.contains(Kind.LIVE) && current == null) {
                val last = Store.lastUrl(this@MainActivity)
                Store.tab(Tab.TV).items.firstOrNull { it.url == last }?.let { playPreview(it) }
            }
            if (failed) showPanel(kinds.first()) else showTab(tab)
            if (sectionPanel.visibility != View.VISIBLE) tabViews[tab.ordinal].requestFocus()
        }
    }

    /** Atualizar: baixa de novo o que já está aberto, ou mostra o painel. */
    private fun refreshAll() {
        val open = Kind.values().filter { Store.isLoaded(it) }
        if (open.isEmpty()) showPanel(null) else loadKinds(open, force = true)
    }

    /** Lista ou login novo recebido pelo celular. */
    private fun onNewList() {
        toast("Login confirmado!")
        current = null
        player?.stop()
        liveBadge.visibility = View.GONE
        nowName.text = "Escolha um canal"
        nowGroup.text = ""
        tab = Tab.TV
        findViewById<TextView>(R.id.brand).text = "GS IPTV  ·  ${Store.listName(this)}"
        showTab(Tab.TV)
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
        lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) { Store.search(q, tab) }
            showSearch(q, results)
        }
    }

    private fun showSearch(q: String, results: List<Item>) {
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
    private fun ensurePlayer(): SmartPlay {
        smart?.let { return it }
        val p = PlayerFactory.create(this)
        val sp = SmartPlay(this, p) { nowGroup.text = "Canal indisponível no momento" }
        preview.player = p
        player = p
        smart = sp
        return sp
    }

    private fun playPreview(item: Item) {
        current = item
        ensurePlayer().play(item)
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
        ListServer.listener = { onNewList() }
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
        smart = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
