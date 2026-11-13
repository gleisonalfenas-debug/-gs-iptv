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
    private lateinit var channelsList: TvList
    private lateinit var cardFan: CardFan
    private lateinit var spiral: SpiralView
    private lateinit var highlightView: View
    private lateinit var liveCatList: TvList
    private lateinit var vodCatList: TvList

    private lateinit var liveCatAdapter: CategoryAdapter
    private lateinit var vodCatAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var liveCats: List<Pair<String, List<Item>>> = emptyList()
    private var vodCats: List<Pair<String, List<Item>>> = emptyList()

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
        channelsList = findViewById(R.id.channels)
        cardFan = findViewById(R.id.cardFan)
        spiral = findViewById(R.id.spiral)
        highlightView = findViewById(R.id.highlightView)
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
        Http.warm(Store.playlistUrl(this)) // já resolve o endereço do servidor
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
        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setItems(arrayOf(
                "Buffer: ${Buffer.current(this).label}",
                "DNS: ${DnsMode.current(this).label}",
            )) { _, i -> if (i == 0) openBufferSettings() else openDnsSettings() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun openDnsSettings() {
        val opts = DnsMode.values()
        AlertDialog.Builder(this)
            .setTitle("DNS (endereço do servidor)")
            .setSingleChoiceItems(opts.map { it.label }.toTypedArray(), DnsMode.current(this).ordinal) { d, i ->
                DnsMode.set(this, opts[i])
                Diag.log("DNS alterado para ${opts[i].label}")
                toast("DNS: ${opts[i].label}")
                Http.warm(Store.playlistUrl(this))
                d.dismiss()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun openBufferSettings() {
        val opts = Buffer.values()
        val cur = Buffer.current(this).ordinal
        AlertDialog.Builder(this)
            .setTitle("Buffer (reserva de vídeo)")
            .setSingleChoiceItems(opts.map { "${it.label}\n${it.desc}" }.toTypedArray(), cur) { d, i ->
                Buffer.set(this, opts[i])
                Diag.log("Buffer alterado para ${opts[i].label}")
                toast("Buffer: ${opts[i].label}")
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
                setOnKeyListener { _, code, ev ->
                    if (ev.action == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                        focusContent(); true
                    } else false
                }
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
                current = item
                openFullscreen(channelAdapter.items, pos)
            },
            onLongClick = { item, _ ->
                val added = Store.toggleFavorite(this, item.url)
                channelAdapter.favorites = Store.favorites(this)
                channelAdapter.refresh()
                toast(if (added) "Adicionado aos favoritos" else "Removido dos favoritos")
            },
        )
        val vodTitle = findViewById<TextView>(R.id.vodTitle)
        val vodMeta = findViewById<TextView>(R.id.vodMeta)
        cardFan.onPick = { item, pos ->
            if (item.url.startsWith(Xtream.SERIES_PREFIX)) openSeries(item)
            else openFullscreen(cardFan.items, pos)
        }
        val hlTitle = findViewById<TextView>(R.id.hlTitle)
        spiral.onPick = { _, pos -> openFullscreen(spiral.items, pos) }
        spiral.onChange = { item -> hlTitle.text = item?.name ?: "" }
        cardFan.onChange = { item ->
            vodTitle.text = item?.name ?: ""
            vodMeta.text = item?.group ?: ""
        }

        liveCatList = findViewById(R.id.liveCategories)
        vodCatList = findViewById(R.id.vodCategories)
        liveCatList.layoutManager = LinearLayoutManager(this); liveCatList.adapter = liveCatAdapter
        vodCatList.layoutManager = LinearLayoutManager(this); vodCatList.adapter = vodCatAdapter

        // Direções do controle: cada borda leva exatamente para o lugar certo
        val toTabs = { tabViews[tab.ordinal].requestFocus(); Unit }
        liveCatList.onUp = toTabs
        liveCatList.onLeft = {}
        liveCatList.onRight = { channelsList.focusPosition(0) }
        channelsList.onUp = toTabs
        channelsList.onLeft = { liveCatList.focusPosition(liveCatAdapter.selected) }
        channelsList.onRight = {}
        vodCatList.onUp = toTabs
        vodCatList.onLeft = {}
        vodCatList.onRight = { cardFan.requestFocus() }
        cardFan.onUp = toTabs
        spiral.onUp = toTabs
        cardFan.onLeftEdge = { vodCatList.focusPosition(vodCatAdapter.selected) }
        channelsList.setHasFixedSize(true)
        channelsList.layoutManager = LinearLayoutManager(this)
        channelsList.adapter = channelAdapter
        // Sem animação de troca: assim o foco do controle não "pula"
        listOf(liveCatList, vodCatList, channelsList).forEach { it.itemAnimator = null }
        // Botões do topo: para baixo vai para as abas
        listOf(R.id.btnSearch, R.id.btnSend, R.id.btnRefresh, R.id.btnSettings).forEach { id ->
            findViewById<View>(id).setOnKeyListener { _, code, ev ->
                if (ev.action == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    tabViews[tab.ordinal].requestFocus(); true
                } else false
            }
        }
    }

    /** Desce das abas para o conteúdo da aba atual (painel, categorias). */
    private fun focusContent() {
        when {
            sectionPanel.visibility == View.VISIBLE -> showPanel(kindsFor(tab).firstOrNull { !Store.isLoaded(it) })
            tab == Tab.TV -> liveCatList.focusPosition(liveCatAdapter.selected)
            tab == Tab.HIGHLIGHTS -> spiral.requestFocus()
            else -> vodCatList.focusPosition(vodCatAdapter.selected)
        }
    }

    private fun showTab(t: Tab) {
        tab = t
        tabViews.forEachIndexed { i, v -> v.isSelected = i == t.ordinal }
        val missing = kindsFor(t).filterNot { Store.isLoaded(it) }
        if (missing.isNotEmpty()) {
            // Seção ainda não baixada: se já tem cópia salva abre direto, senão mostra o painel
            if (loadingNow) return
            if (missing.all { Store.hasFreshCache(this, it) }) { loadKinds(missing, force = false); return }
            liveView.visibility = View.GONE
            vodView.visibility = View.GONE
            highlightView.visibility = View.GONE
            empty.visibility = View.GONE
            showPanel(missing.first())
            return
        }
        sectionPanel.visibility = View.GONE
        val data = Store.tab(t)
        val list = data.items
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "Sua lista não tem conteúdo para a aba ${t.label}."

        highlightView.visibility = View.GONE
        if (t == Tab.HIGHLIGHTS) {
            liveView.visibility = View.GONE
            vodView.visibility = View.GONE
            highlightView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            spiral.showItems(list)
        } else if (t == Tab.TV) {
            liveView.visibility = View.VISIBLE
            vodView.visibility = View.GONE
            liveCats = Store.liveCategories(this)
            liveCatAdapter.items = liveCats.map { it.first }
            showLiveCategory(0)
        } else {
            liveView.visibility = View.GONE
            vodView.visibility = View.VISIBLE
            vodCats = buildList {
                add("Todos" to list)
                addAll(data.groups)
            }
            vodCatAdapter.items = vodCats.map { it.first }
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
        cardFan.showItems(cat.second)
    }

    // ---------------- Carregar lista (uma seção de cada vez) ----------------
    private fun kindsFor(t: Tab): List<Kind> = when (t) {
        Tab.TV -> listOf(Kind.LIVE)
        Tab.MOVIES -> listOf(Kind.MOVIE)
        Tab.SERIES -> listOf(Kind.SERIES)
        Tab.KIDS, Tab.ANIME -> listOf(Kind.MOVIE, Kind.SERIES)
        Tab.HIGHLIGHTS -> listOf(Kind.MOVIE)
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
                Store.isLoaded(kind) -> { st.text = "✓ Baixado"; b.text = "Atualizar" }
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

    private var loadingNow = false

    private fun loadKinds(kinds: List<Kind>, force: Boolean) {
        if (loadingNow) return
        loadingNow = true
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
            loadingNow = false
            loaded = true
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
            liveCatAdapter.items = liveCats.map { it.first }
            showLiveCategory(0)
            channelsList.post { channelsList.getChildAt(0)?.requestFocus() }
        } else {
            vodCats = listOf(entry) + vodCats.filterNot { it.first.startsWith("Busca:") }
            vodCatAdapter.items = vodCats.map { it.first }
            showVodCategory(0)
            cardFan.post { cardFan.requestFocus() }
        }
    }

    // ---------------- Player ----------------
    private fun openFullscreen(list: List<Item>, index: Int) {
        Store.playQueue = list
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", index))
    }

    override fun onStart() {
        super.onStart()
        if (loaded && tab == Tab.TV && Store.isLoaded(Kind.LIVE)) {
            val sel = liveCatAdapter.selected
            val name = liveCats.getOrNull(sel)?.first
            liveCats = Store.liveCategories(this)
            liveCatAdapter.items = liveCats.map { it.first }
            val i = liveCats.indexOfFirst { it.first == name }.coerceAtLeast(0)
            liveCatAdapter.selected = i
            showLiveCategory(i)
        }
        ui.post(clockTick)
        // Volta a tocar a prévia ao retornar da tela cheia
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
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
