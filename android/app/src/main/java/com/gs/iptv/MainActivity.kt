package com.gs.iptv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Páginas da barra lateral. */
enum class Page(val label: String, val icon: Int) {
    HOME("Início", R.drawable.ic_tab_home),
    TV("TV", R.drawable.ic_tab_tv),
    GAMES("Jogos", R.drawable.ic_tab_games),
    MOVIES("Filmes", R.drawable.ic_tab_film),
    SERIES("Séries", R.drawable.ic_tab_series),
    KIDS("Kids", R.drawable.ic_tab_kids),
    SEARCH("Buscar", R.drawable.ic_search),
    ACCOUNT("Conta", R.drawable.ic_user),
}

class MainActivity : AppCompatActivity() {

    private var page = Page.HOME
    private val railItems = HashMap<Page, View>()
    private var lite = true
    private var loaded = false
    private var loadingNow = false
    private var current: Item? = null
    private var unlocked = false
    private fun locked() = Store.kidsSafeOn(this) && !unlocked

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var clock: TextView
    private lateinit var empty: TextView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var sectionPanel: View
    private lateinit var pages: Map<Page, View>

    // TV
    private lateinit var tvCats: TvList
    private lateinit var tvList: TvList
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var liveAdapter: LiveRowAdapter
    private var liveCats: List<Pair<String, List<Item>>> = emptyList()

    // Filmes / séries / busca
    private lateinit var chips: HRow
    private lateinit var vodGrid: TvList
    private lateinit var chipAdapter: ChipAdapter
    private lateinit var gridAdapter: GridAdapter
    private var vodCats: List<Pair<String, List<Item>>> = emptyList()

    // Jogos
    private lateinit var dayChips: HRow
    private lateinit var dayAdapter: ChipAdapter
    private lateinit var gamesList: TvList
    private lateinit var gameAdapter: GameAdapter
    private val dayOffsets = listOf(-1, 0, 1, 2, 3)

    // Kids
    private lateinit var kidsGrid: TvList

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
        window.setBackgroundDrawable(null) // a tela já tem fundo próprio: evita pintar duas vezes
        lite = Store.liteOn(this)

        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        clock = findViewById(R.id.clock)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        sectionPanel = findViewById(R.id.sectionPanel)
        findViewById<TextView>(R.id.version).text = "v${BuildConfig.VERSION_NAME}"
        pages = mapOf(
            Page.HOME to findViewById(R.id.pageHome), Page.TV to findViewById(R.id.pageTv),
            Page.MOVIES to findViewById(R.id.pageVod), Page.SERIES to findViewById(R.id.pageVod),
            Page.SEARCH to findViewById(R.id.pageVod), Page.GAMES to findViewById(R.id.pageGames),
            Page.KIDS to findViewById(R.id.pageKids), Page.ACCOUNT to findViewById(R.id.pageAccount),
        )

        buildRail()
        setupTv()
        setupVod()
        setupGames()
        setupKids()
        setupHome()
        setupPanel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (railItems.values.none { it.hasFocus() }) focusRail() else confirmExit()
            }
        })

        ListServer.start(this)
        GsApp.takeCrash(application)?.let { crash ->
            AlertDialog.Builder(this).setTitle("O app fechou da última vez")
                .setMessage("Tire uma foto desta tela e envie para o suporte:\n\n" + crash.lines().take(14).joinToString("\n"))
                .setPositiveButton("OK", null).show()
        }
        Store.justUpdated(this) // só registra a versão; sem janela depois de atualizar
        Http.warm(Store.playlistUrl(this))
        if (Store.kidsSafeOn(this)) page = Page.KIDS
        if (!Store.hasList(this)) openSendList() else startup()
    }

    // ================= Barra lateral =================
    private fun buildRail() {
        val rail = findViewById<LinearLayout>(R.id.rail)
        Page.values().forEach { p ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundResource(R.drawable.bg_rail_item)
                setPadding(0, dp(9), 0, dp(9))
                isFocusable = true
            }
            val icon = ImageView(this).apply { setImageResource(p.icon) }
            val label = TextView(this).apply {
                text = p.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            item.addView(label)
            fun tint() {
                val c = when { item.isFocused -> Color.parseColor("#111318"); item.isActivated -> Color.WHITE; else -> Color.parseColor("#8B8F9C") }
                icon.setColorFilter(c); label.setTextColor(c)
            }
            item.tag = Runnable { tint() }
            item.setOnFocusChangeListener { _, has ->
                tint()
                if (has && p != page && p != Page.SEARCH && !locked()) showPage(p)
            }
            item.setOnClickListener {
                when {
                    locked() && p != Page.KIDS -> askPin { showPage(p); focusContent() }
                    p == Page.SEARCH -> openSearch()
                    else -> { showPage(p); focusContent() }
                }
            }
            item.setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { focusContent(); true } else false
            }
            rail.addView(item, LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            railItems[p] = item
            tint()
        }
    }

    private fun markRail() {
        railItems.forEach { (p, v) -> v.isActivated = p == page; (v.tag as? Runnable)?.run() }
    }

    private fun focusRail() { railItems[page]?.requestFocus() }
    private val toRail: () -> Unit = { focusRail() }

    // ================= Páginas =================
    private fun kindsFor(p: Page): List<Kind> = when (p) {
        Page.TV -> listOf(Kind.LIVE)
        Page.MOVIES -> listOf(Kind.MOVIE)
        Page.SERIES -> listOf(Kind.SERIES)
        Page.KIDS -> listOf(Kind.MOVIE, Kind.SERIES)
        else -> emptyList()
    }

    private fun showPage(p: Page) {
        if (locked() && p != Page.KIDS) { if (page != Page.KIDS) showPage(Page.KIDS); return }
        page = p
        markRail()
        pages.values.forEach { it.visibility = View.GONE }
        empty.visibility = View.GONE
        sectionPanel.visibility = View.GONE

        val missing = kindsFor(p).filterNot { Store.isLoaded(it) }
        if (missing.isNotEmpty()) {
            setHeader(p.label, "")
            if (loadingNow) return
            if (missing.all { Store.hasCache(this, it) }) { loadKinds(missing, force = false, silent = true); return }
            showPanel(missing.first())
            return
        }
        pages[p]?.visibility = View.VISIBLE
        when (p) {
            Page.HOME -> showHome()
            Page.TV -> showTv()
            Page.MOVIES -> showVod(Tab.MOVIES, "Filmes")
            Page.SERIES -> showVod(Tab.SERIES, "Séries")
            Page.GAMES -> showGames()
            Page.KIDS -> showKids()
            Page.ACCOUNT -> showAccount()
            Page.SEARCH -> {}
        }
    }

    private fun setHeader(t: String, s: String) { title.text = t; subtitle.text = s }

    /** Da barra lateral para dentro da página (seta direita). */
    private fun focusContent() {
        when {
            sectionPanel.visibility == View.VISIBLE -> showPanel(kindsFor(page).firstOrNull { !Store.isLoaded(it) })
            page == Page.HOME -> findViewById<View>(R.id.hero).requestFocus()
            page == Page.TV -> tvList.focusPosition(0)
            page == Page.MOVIES || page == Page.SERIES || page == Page.SEARCH -> vodGrid.focusPosition(0)
            page == Page.GAMES -> gamesList.focusPosition(0)
            page == Page.KIDS -> kidsGrid.focusPosition(0)
            page == Page.ACCOUNT -> findViewById<LinearLayout>(R.id.accPrefs).getChildAt(0)?.requestFocus()
        }
    }

    // ================= Início =================
    private var heroItem: Item? = null
    private var heroList: List<Item> = emptyList()
    private var heroIndex = 0
    private val heroTick = object : Runnable {
        override fun run() {
            if (page != Page.HOME || heroList.size < 2) return
            heroIndex = (heroIndex + 1) % heroList.size
            showHero(animated = true)
            ui.postDelayed(this, 8_000)
        }
    }

    /** Mostra o lançamento atual no destaque do topo (com troca suave). */
    private fun showHero(animated: Boolean = false) {
        val featured = heroList.getOrNull(heroIndex) ?: return
        heroItem = featured
        val box = findViewById<android.widget.FrameLayout>(R.id.hero)
        val apply = {
            findViewById<TextView>(R.id.heroKind).text = when (featured.kind) {
                Kind.MOVIE -> if (Store.isKids(featured)) "KIDS · LANÇAMENTO" else "FILME · LANÇAMENTO"
                Kind.SERIES -> if (Store.isKids(featured)) "KIDS · LANÇAMENTO" else "SÉRIE · LANÇAMENTO"
                else -> "AO VIVO"
            }
            findViewById<TextView>(R.id.heroTitle).text = featured.name
            findViewById<TextView>(R.id.heroMeta).text = "${featured.group}${if (heroList.size > 1) "   ·   ${heroIndex + 1}/${heroList.size}" else ""}"
            val heroImg = findViewById<ImageView>(R.id.heroImg)
            box.clipToOutline = true
            box.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            if (!lite && featured.logo.isNotEmpty()) {
                heroImg.load(featured.logo) { size(900, 500) }
                heroImg.post { Motion.kenBurns(heroImg, slow = true) }
            } else { heroImg.setImageDrawable(null); Motion.stopKenBurns(heroImg) }
        }
        if (animated) box.animate().alpha(0.2f).setDuration(220).withEndAction { apply(); box.animate().alpha(1f).setDuration(320).start() }.start()
        else apply()
    }

    private fun setupHome() {
        val hero = findViewById<View>(R.id.hero)
        hero.setOnClickListener { heroItem?.let { openItem(listOf(it), 0) } }
        hero.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { focusRail(); true } else false
        }
        listOf(R.id.contRow, R.id.favRow, R.id.newRow, R.id.seriesRow, R.id.kidsRow).forEach { id ->
            findViewById<HRow>(id).apply {
                layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
                itemAnimator = null
                onLeftEdge = toRail
            }
        }
    }

    private fun showHome() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        setHeader(when (hour) { in 5..11 -> "Bom dia!"; in 12..17 -> "Boa tarde!"; else -> "Boa noite!" }, "O que vamos assistir?")
        val live = Store.tab(Tab.TV).items
        val movies = Store.tab(Tab.HIGHLIGHTS).items
        if (live.isEmpty() && movies.isEmpty() && Store.tab(Tab.SERIES).items.isEmpty()) {
            pages[Page.HOME]?.visibility = View.GONE
            showPanel(Kind.LIVE); return
        }
        // lançamentos de filmes, séries e kids
        val newMovies = Store.releasesOf(Tab.MOVIES)
        val newSeries = Store.releasesOf(Tab.SERIES)
        val newKids = Store.releasesOf(Tab.KIDS)
        // destaque: passa sozinho pelos lançamentos (a cada 8 s)
        heroList = (newMovies.take(5) + newSeries.take(4) + newKids.take(3)).ifEmpty {
            listOfNotNull(live.firstOrNull { it.url == Store.lastUrl(this) } ?: live.firstOrNull())
        }
        heroIndex = 0
        showHero()
        ui.removeCallbacks(heroTick)
        if (heroList.size > 1) ui.postDelayed(heroTick, 8_000)

        // continuar assistindo
        val cont = Watch.list(this)
        findViewById<View>(R.id.contTitle).visibility = if (cont.isEmpty()) View.GONE else View.VISIBLE
        findViewById<HRow>(R.id.contRow).apply {
            visibility = if (cont.isEmpty()) View.GONE else View.VISIBLE
            adapter = ContinueAdapter(cont, !lite) { i -> openItem(cont.map { it.item }, i) }
        }
        // assistidos recentemente (filmes, séries e kids) no lugar dos canais
        val recent = Watch.recent(this).map { it.item }
        findViewById<TextView>(R.id.favTitle).text = "Assistidos recentemente"
        findViewById<View>(R.id.favTitle).visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        findViewById<HRow>(R.id.favRow).apply {
            visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
            adapter = TileAdapter(HomeRow("", recent, true), lite, {}) { i -> openItem(recent, i) }
        }
        fun row(titleId: Int, rowId: Int, list: List<Item>) {
            findViewById<View>(titleId).visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            findViewById<HRow>(rowId).apply {
                visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                adapter = TileAdapter(HomeRow("", list, true), lite, {}) { i -> openItem(list, i) }
            }
        }
        row(R.id.newTitle, R.id.newRow, newMovies)
        row(R.id.seriesTitle, R.id.seriesRow, newSeries)
        row(R.id.kidsTitle, R.id.kidsRow, newKids)
    }

    // ================= TV ao vivo =================
    private fun setupTv() {
        tvCats = findViewById(R.id.tvCats)
        tvList = findViewById(R.id.tvList)
        catAdapter = CategoryAdapter { i -> showLiveCategory(i) }
        liveAdapter = LiveRowAdapter(this,
            onFocus = { item -> showInfo(item) },
            onClick = { i -> current = liveAdapter.items[i]; openFullscreen(liveAdapter.items, i) },
            onLong = { item ->
                val added = Store.toggleFavorite(this, item.url)
                liveAdapter.favorites = Store.favorites(this)
                liveAdapter.notifyItemRangeChanged(0, liveAdapter.itemCount)
                toast(if (added) "Adicionado aos favoritos" else "Removido dos favoritos")
            })
        tvCats.layoutManager = LinearLayoutManager(this); tvCats.adapter = catAdapter; tvCats.itemAnimator = null
        tvList.layoutManager = LinearLayoutManager(this); tvList.adapter = liveAdapter; tvList.itemAnimator = null
        tvList.setHasFixedSize(true); tvList.setItemViewCacheSize(12); tvCats.setHasFixedSize(true)
        // programação: só busca os canais visíveis quando a lista para de rolar
        tvList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, state: Int) {
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) prefetchEpg()
            }
        })
        tvCats.onLeft = toRail; tvCats.onUp = {}; tvCats.onRight = { tvList.focusPosition(0) }
        tvList.onLeft = { tvCats.focusPosition(catAdapter.selected) }; tvList.onUp = {}
        // seta direita no canal: programação do dia
        tvList.onRight = {
            val pos = tvList.focusedChild?.let { tvList.getChildAdapterPosition(it) } ?: -1
            liveAdapter.items.getOrNull(pos)?.let { ch -> Guide.show(this, ch) { current = ch; openFullscreen(liveAdapter.items, pos) } }
        }
    }

    private fun showTv() {
        liveCats = Store.liveCategories(this)
        catAdapter.items = liveCats.map { it.first }
        val start = liveCats.indexOfFirst { it.first == "Todos" }.coerceAtLeast(0)
        catAdapter.selected = start
        showLiveCategory(start)
    }

    private fun showLiveCategory(i: Int) {
        val cat = liveCats.getOrNull(i) ?: return
        setHeader("TV ao vivo", "${cat.first} · ${cat.second.size} canais")
        liveAdapter.showLogos = !lite
        liveAdapter.favorites = Store.favorites(this)
        liveAdapter.items = cat.second
        tvList.scrollToPosition(0)
        ui.removeCallbacks(epgPrefetch); ui.postDelayed(epgPrefetch, 500)
        cat.second.firstOrNull()?.let { showInfo(it) }
    }

    private val epgPrefetch = Runnable { prefetchEpg() }

    /** Busca a programação só dos canais que estão na tela (no máximo 12). */
    private fun prefetchEpg() {
        val lm = tvList.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition(); val last = lm.findLastVisibleItemPosition()
        if (first < 0) return
        for (i in first..minOf(last, first + 11)) {
            val ch = liveAdapter.items.getOrNull(i) ?: continue
            if (Epg.cached(ch) == null) Epg.request(this, ch) { _ -> if (liveAdapter.items.getOrNull(i)?.url == ch.url) liveAdapter.notifyItemChanged(i) }
        }
    }

    private fun showInfo(item: Item) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        findViewById<TextView>(R.id.infoName).text = item.name
        findViewById<TextView>(R.id.infoInitials).apply {
            text = initialsOf(item.name)
            (parent as View).background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(colorOf(item.name)) }
        }
        val logo = findViewById<ImageView>(R.id.infoLogo)
        if (!lite && item.logo.isNotEmpty()) { logo.visibility = View.VISIBLE; logo.load(item.logo) { size(200, 200) } } else { logo.visibility = View.GONE }
        fun fill(progs: List<Prog>?) {
            val cur = Epg.current(progs); val nxt = Epg.next(progs)
            findViewById<TextView>(R.id.infoProg).text = cur?.title ?: item.group
            findViewById<TextView>(R.id.infoTime).text = cur?.let {
                val left = ((it.end - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                "${fmt.format(Date(it.start))} – ${fmt.format(Date(it.end))} · termina em $left min"
            } ?: if (Store.isXtream(this)) "Sem programação disponível" else ""
            findViewById<TextView>(R.id.infoNext).text = nxt?.let { "Depois: ${fmt.format(Date(it.start))}  ${it.title}" } ?: ""
        }
        fill(Epg.cached(item))
        Epg.request(this, item) { progs -> if (liveAdapter.items.contains(item)) fill(progs) }
    }

    // ================= Filmes / Séries / Busca =================
    private fun setupVod() {
        chips = findViewById(R.id.chips)
        vodGrid = findViewById(R.id.vodGrid)
        chipAdapter = ChipAdapter { i -> showVodCategory(i) }
        gridAdapter = GridAdapter({ !lite }, onLong = { item -> rate(item) }) { i -> openItem(gridAdapter.items, i) }
        chips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        chips.adapter = chipAdapter; chips.itemAnimator = null
        chips.onLeftEdge = toRail
        chips.onDown = { vodGrid.focusPosition(0) }
        vodGrid.layoutManager = GridLayoutManager(this, 5)
        vodGrid.adapter = gridAdapter; vodGrid.itemAnimator = null
        vodGrid.setHasFixedSize(true); vodGrid.setItemViewCacheSize(15)
        vodGrid.onLeft = toRail
        vodGrid.onUp = { chips.findViewHolderForAdapterPosition(chipAdapter.selected)?.itemView?.requestFocus() ?: chips.requestFocus() }
        vodGrid.onRight = {}
    }

    private fun showVod(t: Tab, name: String) {
        val data = Store.tab(t)
        vodCats = listOf("Todos" to data.items) + data.groups
        // "Lançamentos" primeiro nos filmes
        if (t == Tab.MOVIES) Store.tab(Tab.HIGHLIGHTS).items.takeIf { it.isNotEmpty() }?.let { vodCats = listOf("Lançamentos" to it) + vodCats }
        chipAdapter.items = vodCats.map { it.first }
        setHeader(name, vodCats.firstOrNull()?.first ?: "")
        showVodCategory(0)
        empty.visibility = if (data.items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "Sua lista não tem conteúdo em $name."
    }

    private fun showVodCategory(i: Int) {
        val c = vodCats.getOrNull(i) ?: return
        subtitle.text = "${c.first} · ${c.second.size}"
        gridAdapter.items = c.second
        vodGrid.scrollToPosition(0)
    }

    private fun openSearch() {
        val input = EditText(this).apply { hint = "Canal, filme ou série"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH; setTextColor(Color.BLACK) }
        val d = AlertDialog.Builder(this).setTitle("Buscar").setView(input)
            .setPositiveButton("Buscar") { _, _ -> runSearch(input.text.toString()) }
            .setNegativeButton("Cancelar", null).create()
        input.setOnEditorActionListener { _, _, _ -> runSearch(input.text.toString()); d.dismiss(); true }
        d.show()
    }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) {
                val n = Store.norm(q)
                Store.items.filter { Store.norm(it.name).contains(n) }.take(300)
            }
            if (results.isEmpty()) { toast("Nada encontrado para \"$q\""); return@launch }
            page = Page.SEARCH; markRail()
            pages.values.forEach { it.visibility = View.GONE }
            pages[Page.SEARCH]?.visibility = View.VISIBLE
            vodCats = listOf("Resultados" to results)
            chipAdapter.items = listOf("Resultados")
            setHeader("Busca: $q", "")
            showVodCategory(0)
            vodGrid.post { vodGrid.focusPosition(0) }
        }
    }

    // ================= Jogos =================
    private fun setupGames() {
        dayChips = findViewById(R.id.dayChips)
        gamesList = findViewById(R.id.gamesList)
        dayAdapter = ChipAdapter { i -> loadGames(dayOffsets[i]) }
        gameAdapter = GameAdapter { g -> onGameClick(g) }
        dayChips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dayChips.adapter = dayAdapter; dayChips.itemAnimator = null
        dayChips.onLeftEdge = toRail
        dayChips.onDown = { gamesList.focusPosition(0) }
        gamesList.layoutManager = LinearLayoutManager(this); gamesList.adapter = gameAdapter; gamesList.itemAnimator = null
        gamesList.onLeft = toRail; gamesList.onRight = {}
        gamesList.onUp = { dayChips.findViewHolderForAdapterPosition(dayAdapter.selected)?.itemView?.requestFocus() }
        val wd = SimpleDateFormat("EEEE", Locale("pt", "BR"))
        dayAdapter.items = dayOffsets.map { o ->
            when (o) { -1 -> "Ontem"; 0 -> "Hoje"; 1 -> "Amanhã"
                else -> wd.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, o) }.time).replaceFirstChar { it.uppercase() } }
        }
        dayAdapter.select(1)
    }

    private var gamesDay = 0

    private fun showGames() {
        setHeader("Jogos", "Futebol")
        loadGames(gamesDay)
    }

    private fun loadGames(offset: Int, force: Boolean = false) {
        gamesDay = offset
        gameAdapter.scheduled = Scheduler.all(this).map { it.id }.toSet()
        lifecycleScope.launch {
            if (gameAdapter.items.isEmpty()) { loading.visibility = View.VISIBLE; loadingText.text = "Buscando jogos…" }
            val games = try { Games.day(offset, force) } catch (_: Exception) { emptyList() }
            loading.visibility = View.GONE
            if (page != Page.GAMES) return@launch
            gameAdapter.items = games
            empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
            empty.text = "Nenhum jogo encontrado para este dia."
        }
    }

    private fun onGameClick(g: Game) {
        val sched = Scheduler.all(this).firstOrNull { it.id == g.id }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val upcoming = g.startMs > System.currentTimeMillis()
        val options = ArrayList<Pair<String, () -> Unit>>()
        options.add("Assistir agora" to { pickChannel(g) { ch -> current = ch; openFullscreen(listOf(ch), 0) } })
        if (upcoming && sched == null) options.add("Agendar (abre às ${fmt.format(Date(g.startMs - Scheduler.EARLY_MS))})" to { pickChannel(g) { ch -> scheduleGame(g, ch) } })
        if (sched != null) options.add("Cancelar agendamento (${sched.channelName})" to { Scheduler.remove(this, g.id); toast("Agendamento cancelado"); loadGames(gamesDay) })
        AlertDialog.Builder(this).setTitle("${g.title}\n${g.league}${if (g.startMs > 0) " · " + fmt.format(Date(g.startMs)) else ""}")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .setNegativeButton("Voltar", null).show()
    }

    private fun pickChannel(g: Game, then: (Item) -> Unit) {
        if (!Store.isLoaded(Kind.LIVE)) { toast("Baixe os canais ao vivo primeiro"); showPage(Page.TV); return }
        val chans = Games.sportsChannels()
        if (chans.isEmpty()) { toast("Nenhum canal encontrado na sua lista"); return }
        AlertDialog.Builder(this).setTitle("Em qual canal passa ${g.title}?")
            .setItems(chans.map { if (it.number > 0) "${it.number}  ${it.name}" else it.name }.toTypedArray()) { _, i -> then(chans[i]) }
            .setNegativeButton("Voltar", null).show()
    }

    private fun scheduleGame(g: Game, ch: Item) {
        Scheduler.add(this, Schedule(g.id, g.title, ch.name, ch.url, g.startMs))
        toast("Agendado! ${ch.name} abre às ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(g.startMs - Scheduler.EARLY_MS))}")
        loadGames(gamesDay)
        askOverlayPermission()
    }

    private fun askOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) return
        AlertDialog.Builder(this).setTitle("Abrir sozinho na hora do jogo")
            .setMessage("Com o GS IPTV aberto, o canal abre na hora certa.\n\nPara abrir mesmo com o app fechado, permita \"Sobrepor a outros apps\".")
            .setPositiveButton("Permitir") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                catch (e: Exception) { toast("Esta TV não tem essa opção. Deixe o app aberto na hora do jogo.") }
            }
            .setNegativeButton("Agora não", null).show()
    }

    private val scheduleCheck = object : Runnable {
        override fun run() {
            Scheduler.due(this@MainActivity)?.let { s -> toast("Abrindo ${s.channelName} para ${s.title}"); Scheduler.open(this@MainActivity, s) }
            ui.postDelayed(this, 20_000)
        }
    }

    // ================= Kids =================
    private fun setupKids() {
        kidsGrid = findViewById(R.id.kidsGrid)
        kidsGrid.layoutManager = GridLayoutManager(this, 3)
        kidsGrid.itemAnimator = null
        kidsGrid.onLeft = toRail; kidsGrid.onUp = {}; kidsGrid.onRight = {}
    }

    private fun showKids() {
        setHeader("Kids", "Diversão para os pequenos")
        findViewById<View>(R.id.kidsBadge).visibility = if (Store.kidsSafeOn(this)) View.VISIBLE else View.GONE
        val liveKids = Store.tab(Tab.TV).items.filter { Store.isKids(it) }
        val list = (liveKids + Store.tab(Tab.KIDS).items).take(300)
        kidsGrid.adapter = BigTileAdapter(list, !lite, true) { i -> openItem(list, i) }
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "Sua lista não tem conteúdo infantil."
    }

    // ================= Conta =================
    private fun showAccount() {
        setHeader("Minha conta", "")
        val info = findViewById<LinearLayout>(R.id.accInfo)
        val prefs = findViewById<LinearLayout>(R.id.accPrefs)
        info.removeAllViews(); prefs.removeAllViews()

        // dados da conta
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val avatar = TextView(this).apply {
            text = Store.listName(this@MainActivity).take(1).uppercase(); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E3162A")) }
        }
        val who = TextView(this).apply { text = Store.listName(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(16), 0, 0, 0) }
        header.addView(avatar, LinearLayout.LayoutParams(dp(64), dp(64))); header.addView(who)
        info.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(rowsBox)
        fun infoRow(k: String, v: String) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(12)) }
            r.addView(TextView(this).apply { text = k; setTextColor(Color.parseColor("#8B8F9C")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(TextView(this).apply { text = v; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTypeface(null, android.graphics.Typeface.BOLD) })
            rowsBox.addView(r)
            rowsBox.addView(View(this).apply { setBackgroundColor(Color.parseColor("#262B36")) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
        infoRow("Versão do app", BuildConfig.VERSION_NAME)
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                try { if (Store.isXtream(this@MainActivity)) Xtream.accountInfo(this@MainActivity) else listOf("Tipo de lista" to "Link M3U") }
                catch (e: Exception) { listOf("Conta" to "Não consegui falar com o servidor") }
            }
            if (page != Page.ACCOUNT) return@launch
            rows.forEach { (k, v) -> infoRow(k, v) }
        }

        // preferências (liga/desliga) e ações
        prefs.addView(sectionLabel("PREFERÊNCIAS"))
        prefs.addView(toggleRow("Modo simples (mais rápido)", Store.liteOn(this)) { on -> Store.setLite(this, on); lite = on; recreate() })
        prefs.addView(toggleRow("Relógio fixo nos canais", Store.clockOn(this)) { on -> Store.setClock(this, on) })
        prefs.addView(toggleRow("Logo no canto dos canais", Store.cornerLogoOn(this)) { on -> Store.setCornerLogo(this, on) })
        prefs.addView(toggleRow("Abrir no último canal", Store.autoLastOn(this)) { on -> Store.setAutoLast(this, on) })
        prefs.addView(toggleRow("Modo seguro do Kids (pede senha para sair)", Store.kidsSafeOn(this)) { on ->
            if (on) createPin { pin -> Store.setKidsSafe(this, true, pin); unlocked = false; toast("Modo seguro ligado. O app abre no Kids."); showPage(Page.KIDS) }
            else { Store.setKidsSafe(this, false); toast("Modo seguro desligado") }
        })
        prefs.addView(sectionLabel("AJUSTES"))
        prefs.addView(actionRow("Buffer: ${Buffer.current(this).label}") { openBufferSettings() })
        prefs.addView(actionRow("DNS: ${DnsMode.current(this).label}") { openDnsSettings() })
        prefs.addView(actionRow("Atualizar a lista agora") { refreshAll() })
        prefs.addView(actionRow("Trocar login") { openSendList() })
        prefs.addView(actionRow("Ver diagnóstico") {
            AlertDialog.Builder(this).setTitle("Diagnóstico").setMessage(Diag.report(this)).setPositiveButton("Fechar", null).show()
        })
        val agenda = Scheduler.all(this)
        if (agenda.isNotEmpty()) {
            prefs.addView(sectionLabel("AGENDAMENTOS"))
            val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            agenda.forEach { a ->
                prefs.addView(actionRow("${a.title} — ${a.channelName} (abre ${fmt.format(Date(a.openAt))})") {
                    AlertDialog.Builder(this).setTitle(a.title).setMessage("Cancelar este agendamento?")
                        .setPositiveButton("Cancelar agendamento") { _, _ -> Scheduler.remove(this, a.id); showAccount() }
                        .setNegativeButton("Manter", null).show()
                })
            }
        }
    }

    // ---------- Senha do Modo seguro ----------
    private fun pinInput() = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        hint = "4 números"; setTextColor(Color.BLACK); gravity = Gravity.CENTER
    }

    private fun createPin(done: (String) -> Unit) {
        val input = pinInput()
        AlertDialog.Builder(this).setTitle("Crie uma senha de 4 números").setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val pin = input.text.toString()
                if (pin.length == 4) done(pin) else { toast("A senha precisa ter 4 números"); showAccount() }
            }
            .setNegativeButton("Cancelar") { _, _ -> showAccount() }.show()
    }

    private fun askPin(onOk: () -> Unit) {
        val input = pinInput()
        AlertDialog.Builder(this).setTitle("Modo seguro: digite a senha para sair do Kids").setView(input)
            .setPositiveButton("Entrar") { _, _ ->
                if (input.text.toString() == Store.kidsPin(this)) { unlocked = true; onOk() }
                else { toast("Senha errada"); focusRail() }
            }
            .setNegativeButton("Cancelar") { _, _ -> focusRail() }.show()
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; letterSpacing = 0.15f; setTextColor(Color.parseColor("#8B8F9C"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(14), 0, dp(8))
    }

    private fun prefRowBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_item); isFocusable = true
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        setOnKeyListener { _, code, ev -> if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { focusRail(); true } else false }
    }

    private fun toggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        var on = initial
        val row = prefRowBase()
        row.addView(TextView(this).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val track = android.widget.FrameLayout(this)
        val knob = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        track.addView(knob, android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL))
        fun paint() {
            track.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor(if (on) "#E3162A" else "#2F3441")) }
            knob.translationX = if (on) dp(25).toFloat() else dp(3).toFloat()
        }
        paint()
        row.addView(track, LinearLayout.LayoutParams(dp(50), dp(28)))
        row.setOnClickListener { on = !on; paint(); onChange(on) }
        return row
    }

    private fun actionRow(label: String, action: () -> Unit): View {
        val row = prefRowBase()
        row.addView(TextView(this).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = "›"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Color.parseColor("#8B8F9C")) })
        row.setOnClickListener { action() }
        return row
    }

    private fun openDnsSettings() {
        val opts = DnsMode.values()
        AlertDialog.Builder(this).setTitle("DNS (endereço do servidor)")
            .setSingleChoiceItems(opts.map { it.label }.toTypedArray(), DnsMode.current(this).ordinal) { d, i ->
                DnsMode.set(this, opts[i]); Http.warm(Store.playlistUrl(this)); toast("DNS: ${opts[i].label}"); d.dismiss(); showAccount()
            }.setNegativeButton("Fechar", null).show()
    }

    private fun openBufferSettings() {
        val opts = Buffer.values()
        AlertDialog.Builder(this).setTitle("Buffer (reserva de vídeo)")
            .setSingleChoiceItems(opts.map { "${it.label}\n${it.desc}" }.toTypedArray(), Buffer.current(this).ordinal) { d, i ->
                Buffer.set(this, opts[i]); toast("Buffer: ${opts[i].label}"); d.dismiss(); showAccount()
            }.setNegativeButton("Fechar", null).show()
    }

    // ================= Estrelas =================
    /** Dar estrelas a um filme ou série (segurar OK na capa). */
    private fun rate(item: Item) {
        if (item.kind == Kind.LIVE) return
        val cur = Store.userStars(this, item.url)
        val server = if (item.rating > 0f) "Nota do servidor: ${starText(item.rating)} ${String.format(Locale("pt", "BR"), "%.1f", item.rating)}\n\n" else ""
        val opts = (1..5).map { n -> "${starText(n.toFloat())}  $n estrela${if (n > 1) "s" else ""}" } + listOf("Remover minha nota")
        AlertDialog.Builder(this)
            .setTitle("Dar estrelas: ${item.name}")
            .setSingleChoiceItems(opts.toTypedArray(), if (cur in 1..5) cur - 1 else -1) { d, i ->
                Store.setUserStars(this, item.url, if (i < 5) i + 1 else 0)
                toast(if (i < 5) "Você deu ${i + 1} estrela${if (i > 0) "s" else ""}" else "Nota removida")
                gridAdapter.notifyItemRangeChanged(0, gridAdapter.itemCount)
                d.dismiss()
            }
            .setMessage(null)
            .setNegativeButton("Fechar", null)
            .also { if (server.isNotEmpty()) toast(server.trim()) }
            .show()
    }

    // ================= Abrir conteúdo =================
    private fun openItem(list: List<Item>, i: Int) {
        val item = list.getOrNull(i) ?: return
        when {
            item.url.startsWith(Xtream.SERIES_PREFIX) -> openSeries(item)
            else -> { if (item.kind == Kind.LIVE) current = item; openFullscreen(list, i) }
        }
    }

    private fun openSeries(series: Item) {
        startActivity(Intent(this, SeriesActivity::class.java)
            .putExtra("name", series.name).putExtra("url", series.url).putExtra("logo", series.logo).putExtra("group", series.group))
    }

    private fun openFullscreen(list: List<Item>, index: Int) {
        Store.playQueue = list
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", index))
    }

    // ================= Carregar lista =================
    private fun setupPanel() {
        findViewById<View>(R.id.btnLive).setOnClickListener { loadKinds(listOf(Kind.LIVE), Store.isLoaded(Kind.LIVE)) }
        findViewById<View>(R.id.btnMovie).setOnClickListener { loadKinds(listOf(Kind.MOVIE), Store.isLoaded(Kind.MOVIE)) }
        findViewById<View>(R.id.btnSeries).setOnClickListener { loadKinds(listOf(Kind.SERIES), Store.isLoaded(Kind.SERIES)) }
        listOf(R.id.btnLive, R.id.btnMovie, R.id.btnSeries).forEach { id ->
            findViewById<View>(id).setOnKeyListener { _, code, ev -> if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { focusRail(); true } else false }
        }
    }

    private fun updatePanel() {
        fun set(kind: Kind, status: Int, btn: Int) {
            val st = findViewById<TextView>(status); val b = findViewById<TextView>(btn)
            when {
                Store.isLoaded(kind) -> { st.text = "✓ Baixado"; b.text = "Atualizar" }
                Store.hasCache(this, kind) -> { st.text = "Salvo na TV"; b.text = "Abrir" }
                else -> { st.text = "Não baixado"; b.text = "Baixar" }
            }
        }
        set(Kind.LIVE, R.id.statusLive, R.id.btnLive)
        set(Kind.MOVIE, R.id.statusMovie, R.id.btnMovie)
        set(Kind.SERIES, R.id.statusSeries, R.id.btnSeries)
    }

    private fun showPanel(focus: Kind?) {
        updatePanel()
        pages.values.forEach { it.visibility = View.GONE }
        sectionPanel.visibility = View.VISIBLE
        val id = when (focus) { Kind.MOVIE -> R.id.btnMovie; Kind.SERIES -> R.id.btnSeries; else -> R.id.btnLive }
        if (railItems.values.none { it.hasFocus() }) sectionPanel.post { findViewById<View>(id).requestFocus() }
    }

    /** Ao abrir: mostra na hora o que já está salvo e atualiza os canais por trás. */
    private fun startup() {
        val cached = Kind.values().filter { Store.hasCache(this, it) && !Store.isLoaded(it) }
        if (cached.isEmpty()) { showPage(page); focusRail(); return }
        loadKinds(cached, force = false, silent = true) {
            if (Store.autoLastOn(this)) openLastChannel()
            refreshQuietly(cached.filter { it == Kind.LIVE || Store.cacheAgeMin(this, it) > 12 * 60 })
        }
    }

    private fun openLastChannel() {
        val last = Store.lastUrl(this) ?: return
        val live = Store.tab(Tab.TV).items
        val i = live.indexOfFirst { it.url == last }
        if (i >= 0) { current = live[i]; openFullscreen(live, i) }
    }

    private fun loadKinds(kinds: List<Kind>, force: Boolean, silent: Boolean = false, after: () -> Unit = {}) {
        if (loadingNow) return
        loadingNow = true
        lifecycleScope.launch {
            sectionPanel.visibility = View.GONE
            if (!silent) loading.visibility = View.VISIBLE
            var failed = false
            // uma lista por vez (canais → filmes → séries); cada uma aparece assim que termina
            val ordered = kinds.sortedBy { it.ordinal }
            LoadStatus.queue(ordered)
            startLoadBar()
            for (k in ordered) {
                try {
                    Store.loadSection(this@MainActivity, k, force, { msg -> if (!silent) runOnUiThread { loadingText.text = msg } }, allowStale = silent)
                    if (k in kindsFor(page) || page == Page.HOME) { if (railItems.values.any { it.hasFocus() } || currentFocus == null) showPage(page) }
                } catch (e: OutOfMemoryError) {
                    LoadStatus.set(k, 0, LoadStatus.State.ERROR)
                    failed = true; toast("Os ${Store.label(k)} são muitos para a memória desta TV.")
                } catch (e: Exception) {
                    LoadStatus.set(k, 0, LoadStatus.State.ERROR)
                    failed = true; toast("Não foi possível baixar ${Store.label(k)}: ${e.message ?: "erro de conexão"}")
                }
            }
            loading.visibility = View.GONE
            loadingNow = false
            loaded = true
            if (failed) showPanel(kinds.first()) else showPage(page)
            if (sectionPanel.visibility != View.VISIBLE && currentFocus == null) focusRail()
            after()
        }
    }

    /** Mostra embaixo: "Canais 100% ✓ · Filmes 42% · Séries na fila" + barra do total. */
    private val loadBarTick = object : Runnable {
        override fun run() {
            val bar = findViewById<View>(R.id.loadBar)
            val kinds = Kind.values().filter { LoadStatus.state.containsKey(it) }
            if (kinds.isEmpty()) { bar.visibility = View.GONE; return }
            val parts = kinds.map { k ->
                val name = when (k) { Kind.LIVE -> "Canais"; Kind.MOVIE -> "Filmes"; Kind.SERIES -> "Séries" }
                when (LoadStatus.state[k]) {
                    LoadStatus.State.DONE -> "$name 100% ✓"
                    LoadStatus.State.ERROR -> "$name erro"
                    LoadStatus.State.WAITING -> "$name na fila"
                    else -> "$name ${LoadStatus.pct[k] ?: 0}%"
                }
            }
            val total = kinds.sumOf { LoadStatus.pct[it] ?: 0 } / kinds.size
            bar.visibility = View.VISIBLE
            findViewById<android.widget.ProgressBar>(R.id.loadBarProgress).progress = total
            if (LoadStatus.active()) {
                findViewById<TextView>(R.id.loadBarText).text = "Carregando listas ($total%):  ${parts.joinToString("  ·  ")}"
                ui.postDelayed(this, 300)
            } else {
                findViewById<TextView>(R.id.loadBarText).text = "Listas prontas ✓   ${parts.joinToString("  ·  ")}"
                ui.postDelayed({ bar.visibility = View.GONE; LoadStatus.clear() }, 4_000)
            }
        }
    }

    private fun startLoadBar() { ui.removeCallbacks(loadBarTick); ui.post(loadBarTick) }

    private fun refreshQuietly(kinds: List<Kind>) {
        if (kinds.isEmpty()) return
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            var changed = false
            val ordered = kinds.sortedBy { it.ordinal }
            LoadStatus.queue(ordered)
            startLoadBar()
            for (k in ordered) {
                try { Store.loadSection(this@MainActivity, k, force = true); changed = true }
                catch (_: Throwable) { LoadStatus.set(k, 0, LoadStatus.State.ERROR) }
            }
            Diag.log("Atualização em segundo plano: ${kinds.joinToString { Store.label(it) }} em ${System.currentTimeMillis() - t0}ms")
            if (changed && railItems.values.any { it.hasFocus() }) showPage(page)
        }
    }

    private fun refreshAll() {
        if (page == Page.GAMES) { loadGames(gamesDay, force = true); return }
        val open = Kind.values().filter { Store.isLoaded(it) }
        if (open.isEmpty()) showPanel(null) else { toast("Atualizando a lista…"); refreshQuietly(open) }
    }

    private fun onNewList() {
        toast("Login confirmado!")
        current = null
        page = Page.TV
        showPage(Page.TV)
    }

    private fun openSendList() = sendList.launch(Intent(this, SendListActivity::class.java))

    private fun confirmExit() {
        AlertDialog.Builder(this).setTitle("Sair do GS IPTV?").setMessage("Deseja realmente sair do app?")
            .setPositiveButton("Sair") { _, _ -> finish() }
            .setNegativeButton("Continuar", null).show()
            .getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        ui.post(clockTick)
        ui.post(scheduleCheck)
        // voltou do player: atualiza o que pode ter mudado (favoritos, continuar assistindo)
        if (loaded && page == Page.HOME) showHome()
        if (loaded && page == Page.TV) liveAdapter.notifyItemRangeChanged(0, liveAdapter.itemCount)
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
        ui.removeCallbacks(scheduleCheck)
        ui.removeCallbacks(heroTick)
        ui.removeCallbacks(epgPrefetch)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
