package com.gs.iptv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
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
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var tab = Tab.HOME
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
    private lateinit var vodSimple: TvList
    private lateinit var vodSimpleAdapter: ChannelAdapter
    private var lite = true
    private lateinit var spiral: SpiralView
    private lateinit var gamesView: View
    private lateinit var homeView: View
    private lateinit var homeRows: RecyclerView
    private lateinit var homeAdapter: HomeAdapter
    private lateinit var gamesList: TvList
    private lateinit var gameAdapter: GameAdapter
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
        lite = Store.liteOn(this)
        vodSimple = findViewById(R.id.vodSimple)
        vodSimpleAdapter = ChannelAdapter(
            onClick = { item, pos ->
                if (item.url.startsWith(Xtream.SERIES_PREFIX)) openSeries(item) else openFullscreen(vodSimpleAdapter.items, pos)
            },
            onLongClick = { _, _ -> },
        ).apply { showLogos = false }
        vodSimple.layoutManager = LinearLayoutManager(this)
        vodSimple.adapter = vodSimpleAdapter
        vodSimple.itemAnimator = null
        vodSimple.setHasFixedSize(true)
        spiral = findViewById(R.id.spiral)
        gamesView = findViewById(R.id.gamesView)
        homeView = findViewById(R.id.homeView)
        homeRows = findViewById(R.id.homeRows)
        homeAdapter = HomeAdapter(
            lite = { lite },
            onFocus = { item -> showHero(item) },
            onClick = { row, i ->
                val item = row.items[i]
                when {
                    item.url.startsWith(Xtream.SERIES_PREFIX) -> openSeries(item)
                    else -> { if (item.kind == Kind.LIVE) current = item; openFullscreen(row.items, i) }
                }
            },
        )
        homeRows.layoutManager = LinearLayoutManager(this)
        homeRows.adapter = homeAdapter
        homeRows.itemAnimator = null
        gamesList = findViewById(R.id.gamesList)
        gameAdapter = GameAdapter { g -> onGameClick(g) }
        gamesList.layoutManager = LinearLayoutManager(this)
        gamesList.adapter = gameAdapter
        gamesList.itemAnimator = null
        gamesList.onLeft = {}
        gamesList.onRight = {}
        highlightView = findViewById(R.id.highlightView)
        findViewById<TextView>(R.id.brand).text = Store.listName(this).let { "GS IPTV  ·  $it" }

        buildTabs()
        setupLists()

        findViewById<View>(R.id.btnSearch).setOnClickListener { openSearch() }
        findViewById<View>(R.id.btnSend).setOnClickListener { openSendList() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refreshAll() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<View>(R.id.btnAccount).setOnClickListener { openAccount() }

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
        applyLite()
        Http.warm(Store.playlistUrl(this)) // já resolve o endereço do servidor
        if (!Store.hasList(this)) openSendList() else startup()
    }

    // ---------------- Início em fileiras ----------------
    private fun buildHomeRows(): List<HomeRow> {
        val rows = ArrayList<HomeRow>()
        val live = Store.tab(Tab.TV).items
        val favs = Store.favorites(this)
        val last = Store.lastUrl(this)
        if (live.isNotEmpty()) {
            val recent = live.filter { it.url == last }
            val favList = live.filter { favs.contains(it.url) }
            if (recent.isNotEmpty() || favList.isNotEmpty())
                rows.add(HomeRow("Continuar assistindo e favoritos", (recent + favList).distinctBy { it.url }.take(30), false))
            rows.add(HomeRow("Canais ao vivo", live.take(30), false))
            Games.sportsChannels().take(30).takeIf { it.isNotEmpty() && it.size < live.size }?.let { rows.add(HomeRow("Esportes", it, false)) }
        }
        Store.tab(Tab.HIGHLIGHTS).items.take(30).takeIf { it.isNotEmpty() }?.let { rows.add(HomeRow("Lançamentos", it, true)) }
        Store.tab(Tab.SERIES).items.take(30).takeIf { it.isNotEmpty() }?.let { rows.add(HomeRow("Séries", it, true)) }
        Store.tab(Tab.KIDS).items.take(30).takeIf { it.isNotEmpty() }?.let { rows.add(HomeRow("Kids", it, true)) }
        return rows
    }

    private fun showHome() {
        val rows = buildHomeRows()
        if (rows.isEmpty()) {
            homeView.visibility = View.GONE
            showPanel(Kind.LIVE)
            return
        }
        sectionPanel.visibility = View.GONE
        empty.visibility = View.GONE
        homeView.visibility = View.VISIBLE
        findViewById<View>(R.id.heroImage).visibility = if (lite) View.GONE else View.VISIBLE
        homeAdapter.rows = rows
        rows.firstOrNull()?.items?.firstOrNull()?.let { showHero(it) }
    }

    private fun showHero(item: Item) {
        findViewById<TextView>(R.id.heroKind).text = when (item.kind) { Kind.LIVE -> "AO VIVO"; Kind.MOVIE -> "FILME"; Kind.SERIES -> "SÉRIE" }
        findViewById<TextView>(R.id.heroTitle).text = item.name
        findViewById<TextView>(R.id.heroMeta).text = item.group
        val img = findViewById<ImageView>(R.id.heroImage)
        if (!lite && item.logo.isNotEmpty()) img.load(item.logo) { size(600, 400) } else img.setImageDrawable(null)
    }

    private fun focusFirstTile() {
        homeRows.scrollToPosition(0)
        homeRows.post {
            val row = homeRows.findViewHolderForAdapterPosition(0) as? HomeAdapter.VH ?: return@post
            row.list.post { row.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    // ---------------- Jogos do dia ----------------
    private fun loadGames(force: Boolean = false) {
        findViewById<TextView>(R.id.gamesDate).text =
            SimpleDateFormat("EEEE, dd/MM", Locale("pt", "BR")).format(Date())
        gameAdapter.scheduled = Scheduler.all(this).map { it.id }.toSet()
        lifecycleScope.launch {
            if (gameAdapter.items.isEmpty()) { loading.visibility = View.VISIBLE; loadingText.text = "Buscando jogos de hoje…" }
            val games = try { Games.today(force) } catch (e: Exception) { emptyList() }
            loading.visibility = View.GONE
            if (tab != Tab.GAMES) return@launch
            gameAdapter.items = games
            empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
            empty.text = "Nenhum jogo encontrado para hoje.\nVerifique a internet e tente o botão de atualizar."
        }
    }

    private fun onGameClick(g: Game) {
        val sched = Scheduler.all(this).firstOrNull { it.id == g.id }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val upcoming = g.startMs > System.currentTimeMillis()
        val options = ArrayList<Pair<String, () -> Unit>>()
        options.add("▶ Assistir agora" to { pickChannel(g) { ch -> current = ch; openFullscreen(listOf(ch), 0) } })
        if (upcoming && sched == null) {
            options.add("⏰ Agendar (abre 5 min antes, às ${fmt.format(Date(g.startMs - Scheduler.EARLY_MS))})" to {
                pickChannel(g) { ch -> scheduleGame(g, ch) }
            })
        }
        if (sched != null) {
            options.add("✖ Cancelar agendamento (${sched.channelName})" to {
                Scheduler.remove(this, g.id); toast("Agendamento cancelado"); loadGames()
            })
        }
        val subtitle = if (g.startMs > 0) "${g.league} · ${fmt.format(Date(g.startMs))}" else g.league
        AlertDialog.Builder(this)
            .setTitle("${g.title}\n$subtitle")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .setNegativeButton("Voltar", null)
            .show()
    }

    /** Escolhe em qual canal assistir o jogo (mostra os canais de esporte da lista). */
    private fun pickChannel(g: Game, then: (Item) -> Unit) {
        if (!Store.isLoaded(Kind.LIVE)) {
            toast("Baixe os canais ao vivo primeiro")
            tab = Tab.TV; showTab(Tab.TV); return
        }
        val chans = Games.sportsChannels()
        if (chans.isEmpty()) { toast("Nenhum canal encontrado na sua lista"); return }
        AlertDialog.Builder(this)
            .setTitle("Em qual canal passa ${g.title}?")
            .setItems(chans.map { if (it.number > 0) "${it.number}  ${it.name}" else it.name }.toTypedArray()) { _, i -> then(chans[i]) }
            .setNegativeButton("Voltar", null)
            .show()
    }

    private fun scheduleGame(g: Game, ch: Item) {
        Scheduler.add(this, Schedule(g.id, g.title, ch.name, ch.url, g.startMs))
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        toast("Agendado! ${ch.name} abre às ${fmt.format(Date(g.startMs - Scheduler.EARLY_MS))}")
        loadGames()
        askOverlayPermission()
    }

    /** Para abrir o canal sozinho com o app fechado, o Android pede "Sobrepor a outros apps". */
    private fun askOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) return
        AlertDialog.Builder(this)
            .setTitle("Abrir sozinho na hora do jogo")
            .setMessage("Com o GS IPTV aberto, o canal abre na hora certa.\n\nPara abrir mesmo com o app fechado, permita \"Sobrepor a outros apps\" para o GS IPTV.")
            .setPositiveButton("Permitir") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) { toast("Esta TV não tem essa opção. Deixe o app aberto na hora do jogo.") }
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private val scheduleCheck = object : Runnable {
        override fun run() {
            Scheduler.due(this@MainActivity)?.let { s ->
                toast("Abrindo ${s.channelName} para ${s.title}")
                Scheduler.open(this@MainActivity, s)
            }
            ui.postDelayed(this, 20_000)
        }
    }

    // ---------------- Minha conta ----------------
    private fun openAccount() {
        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            loadingText.text = "Lendo seus dados…"
            val rows = withContext(Dispatchers.IO) {
                try {
                    if (Store.isXtream(this@MainActivity)) Xtream.accountInfo(this@MainActivity)
                    else listOf("Lista" to Store.listName(this@MainActivity), "Tipo" to "Link M3U")
                } catch (e: Exception) { listOf("Conta" to "Não consegui falar com o servidor") }
            }
            loading.visibility = View.GONE
            showAccount(rows)
        }
    }

    private fun showAccount(rows: List<Pair<String, String>>) {
        val agenda = Scheduler.all(this)
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val text = buildString {
            rows.forEach { (k, v) -> append("$k:  $v\n") }
            append("\nVersão do app:  ${BuildConfig.VERSION_NAME}\n")
            append("Relógio na tela:  ${if (Store.clockOn(this@MainActivity)) "Ligado" else "Desligado"}\n")
            append("Logo no canal:  ${if (Store.cornerLogoOn(this@MainActivity)) "Ligado" else "Desligado"}\n")
            if (agenda.isNotEmpty()) {
                append("\nAgendamentos:\n")
                agenda.forEach { append("• ${it.title} — ${it.channelName} (${fmt.format(Date(it.openAt))})\n") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Minha conta")
            .setMessage(text)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Preferências") { _, _ -> openPreferences(rows) }
            .setNegativeButton("Trocar login") { _, _ -> openSendList() }
            .show()
    }

    /** Liga/desliga relógio fixo, logo no canto dos canais e logo animado do fundo. */
    private fun openPreferences(rows: List<Pair<String, String>>) {
        val labels = arrayOf("Modo simples (mais rápido)", "Relógio fixo na tela dos canais", "Logo no canto dos canais", "Logo animado no fundo do menu")
        val checked = booleanArrayOf(Store.liteOn(this), Store.clockOn(this), Store.cornerLogoOn(this), Store.bgLogoOn(this))
        AlertDialog.Builder(this)
            .setTitle("Preferências")
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                when (i) {
                    0 -> { Store.setLite(this, on); lite = on; applyLite(); showTab(tab) }
                    1 -> Store.setClock(this, on)
                    2 -> Store.setCornerLogo(this, on)
                    3 -> { Store.setBgLogo(this, on); applyBgLogo() }
                }
            }
            .setPositiveButton("OK") { _, _ -> showAccount(rows) }
            .show()
    }

    private fun applyBgLogo() {
        findViewById<View>(R.id.bgLogo).visibility = if (Store.bgLogoOn(this) && !lite) View.VISIBLE else View.GONE
    }

    /** Modo simples: fundo liso, listas só com texto, sem cartas animadas. */
    private fun applyLite() {
        val root = (findViewById<View>(R.id.bgLogo).parent as View)
        if (lite) root.setBackgroundColor(android.graphics.Color.parseColor("#240407")) else root.setBackgroundResource(R.drawable.bg_main)
        vodSimple.visibility = if (lite) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fanBox).visibility = if (lite) View.GONE else View.VISIBLE
        channelAdapter.showLogos = !lite
        applyBgLogo()
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
        vodCatList.onRight = { if (lite) vodSimple.focusPosition(0) else cardFan.requestFocus() }
        vodSimple.onLeft = { vodCatList.focusPosition(vodCatAdapter.selected) }
        vodSimple.onUp = toTabs
        vodSimple.onRight = {}
        cardFan.onUp = toTabs
        gamesList.onUp = toTabs
        spiral.onUp = toTabs
        cardFan.onLeftEdge = { vodCatList.focusPosition(vodCatAdapter.selected) }
        channelsList.setHasFixedSize(true)
        channelsList.layoutManager = LinearLayoutManager(this)
        channelsList.adapter = channelAdapter
        // Sem animação de troca: assim o foco do controle não "pula"
        listOf(liveCatList, vodCatList, channelsList).forEach { it.itemAnimator = null }
        // Botões do topo: para baixo vai para as abas
        listOf(R.id.btnSearch, R.id.btnSend, R.id.btnRefresh, R.id.btnAccount, R.id.btnSettings).forEach { id ->
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
            tab == Tab.HIGHLIGHTS && !lite -> spiral.requestFocus()
            tab == Tab.GAMES -> gamesList.focusPosition(0)
            tab == Tab.HOME -> focusFirstTile()
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
            if (missing.all { Store.hasCache(this, it) }) { loadKinds(missing, force = false, silent = true); return }
            liveView.visibility = View.GONE
            vodView.visibility = View.GONE
            highlightView.visibility = View.GONE
            gamesView.visibility = View.GONE
            homeView.visibility = View.GONE
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
        gamesView.visibility = View.GONE
        homeView.visibility = View.GONE
        if (t == Tab.HOME) {
            liveView.visibility = View.GONE
            vodView.visibility = View.GONE
            showHome()
        } else if (t == Tab.GAMES) {
            liveView.visibility = View.GONE
            vodView.visibility = View.GONE
            empty.visibility = View.GONE
            gamesView.visibility = View.VISIBLE
            loadGames()
        } else if (t == Tab.HIGHLIGHTS && lite) {
            liveView.visibility = View.GONE
            vodView.visibility = View.VISIBLE
            vodCats = listOf("Destaques" to list)
            vodCatAdapter.items = vodCats.map { it.first }
            showVodCategory(0)
        } else if (t == Tab.HIGHLIGHTS) {
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
        if (lite) { vodSimpleAdapter.items = cat.second; vodSimple.scrollToPosition(0) }
        else cardFan.showItems(cat.second)
    }

    // ---------------- Carregar lista (uma seção de cada vez) ----------------
    private fun kindsFor(t: Tab): List<Kind> = when (t) {
        Tab.TV -> listOf(Kind.LIVE)
        Tab.MOVIES -> listOf(Kind.MOVIE)
        Tab.SERIES -> listOf(Kind.SERIES)
        Tab.KIDS, Tab.ANIME -> listOf(Kind.MOVIE, Kind.SERIES)
        Tab.HIGHLIGHTS -> listOf(Kind.MOVIE)
        Tab.GAMES -> emptyList()
        Tab.HOME -> emptyList()
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
    /**
     * Ao abrir: mostra na hora o que já está salvo na TV (sem tela de carregamento)
     * e atualiza os canais por trás, em silêncio.
     */
    private fun startup() {
        findViewById<TextView>(R.id.brand).text = "GS IPTV  ·  ${Store.listName(this)}"
        val cached = Kind.values().filter { Store.hasCache(this, it) && !Store.isLoaded(it) }
        if (cached.isEmpty()) { showTab(tab); tabViews[tab.ordinal].requestFocus(); return }
        loadKinds(cached, force = false, silent = true) {
            // depois de abrir a cópia salva, atualiza em segundo plano
            val toRefresh = cached.filter { it == Kind.LIVE || Store.cacheAgeMin(this, it) > 12 * 60 }
            refreshQuietly(toRefresh)
        }
    }

    /** Atualiza as seções por trás, sem aparecer nada na tela; troca os dados no lugar. */
    private fun refreshQuietly(kinds: List<Kind>) {
        if (kinds.isEmpty()) return
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            var changed = false
            for (k in kinds) {
                try { Store.loadSection(this@MainActivity, k, force = true); changed = true } catch (_: Throwable) { }
            }
            Diag.log("Atualização em segundo plano: ${kinds.joinToString { Store.label(it) }} em ${System.currentTimeMillis() - t0}ms")
            if (changed) applyFreshData()
        }
    }

    /** Coloca os dados novos na tela sem tirar o usuário de onde ele está. */
    private fun applyFreshData() {
        if (sectionPanel.visibility == View.VISIBLE) { updatePanel(); return }
        when {
            tab == Tab.TV -> refreshLiveInPlace()
            tabViews.any { it.hasFocus() } -> showTab(tab)   // está nas abas: pode redesenhar
            // dentro de filmes/séries: os dados novos entram na próxima troca de aba
        }
    }

    private fun refreshLiveInPlace() {
        if (!Store.isLoaded(Kind.LIVE)) return
        val sel = liveCatAdapter.selected
        val name = liveCats.getOrNull(sel)?.first
        val focusedRow = (channelsList.focusedChild?.let { channelsList.getChildAdapterPosition(it) } ?: -1)
        liveCats = Store.liveCategories(this)
        liveCatAdapter.items = liveCats.map { it.first }
        val i = liveCats.indexOfFirst { it.first == name }.coerceAtLeast(0)
        liveCatAdapter.selected = i
        channelAdapter.favorites = Store.favorites(this)
        channelAdapter.items = liveCats.getOrNull(i)?.second ?: emptyList()
        if (focusedRow >= 0) channelsList.focusPosition(focusedRow)
    }

    private var loadingNow = false

    private fun loadKinds(kinds: List<Kind>, force: Boolean, silent: Boolean = false, after: () -> Unit = {}) {
        if (loadingNow) return
        loadingNow = true
        lifecycleScope.launch {
            sectionPanel.visibility = View.GONE
            if (!silent) loading.visibility = View.VISIBLE
            empty.visibility = View.GONE
            var failed = false
            for (k in kinds) {
                loadingText.text = "Preparando ${Store.label(k)}…"
                try {
                    Store.loadSection(this@MainActivity, k, force, { msg -> if (!silent) runOnUiThread { loadingText.text = msg } }, allowStale = silent)
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
            after()
        }
    }

    /** Atualizar: baixa de novo o que já está aberto, ou mostra o painel. */
    private fun refreshAll() {
        if (tab == Tab.GAMES) { loadGames(force = true); return }
        val open = Kind.values().filter { Store.isLoaded(it) }
        if (open.isEmpty()) showPanel(null) else { toast("Atualizando a lista…"); refreshQuietly(open) }
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
        ui.post(scheduleCheck)
        if (loaded && tab == Tab.TV) refreshLiveInPlace()
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
        ui.removeCallbacks(scheduleCheck)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
