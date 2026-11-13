package com.gs.iptv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer

enum class Kind { LIVE, MOVIE, SERIES }

data class Item(
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val kind: Kind,
    val number: Int,
    /** 0 = normal, 1 = kids, 2 = anime (calculado uma vez ao carregar) */
    val section: Int = 0,
)

/** Uma aba já organizada: todos os itens e as categorias. */
class TabData(val items: List<Item>, val groups: List<Pair<String, List<Item>>>)

/** Guarda a lista configurada, baixa, lê (M3U / Xtream) e mantém favoritos. */
object Store {
    private const val CACHE_HOURS = 12

    @Volatile var items: List<Item> = emptyList()
        private set

    @Volatile private var tabs: Map<Tab, TabData> = emptyMap()

    private val groupPool = HashMap<String, String>()
    private val sectionCache = HashMap<String, Int>()

    /** Cria um item reaproveitando textos repetidos (economiza memória). */
    fun make(name: String, url: String, logo: String, group: String, kind: Kind, number: Int): Item {
        val g = groupPool.getOrPut(group) { group }
        val sec = sectionCache.getOrPut(g) {
            val n = norm(g)
            when {
                "anime" in n -> 2
                "kids" in n || "infantil" in n || "desenho" in n || "crianca" in n -> 1
                else -> 0
            }
        }
        return Item(name, url, logo, g, kind, number, sec)
    }

    /** Links do Xtream são guardados curtos ("xt:live/123.ts") e montados na hora de tocar. */
    const val XT = "xt:"

    fun playUrl(ctx: Context, item: Item): String {
        if (!item.url.startsWith(XT)) return item.url
        val l = xtreamLogin(ctx)
        val path = item.url.substring(XT.length) // ex.: live/123.ts
        val slash = path.indexOf('/')
        return "${l.server}/${path.substring(0, slash)}/${l.user}/${l.pass}${path.substring(slash)}"
    }

    /** Lista usada pelo player em tela cheia para trocar de canal (cima/baixo). */
    var playQueue: List<Item> = emptyList()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("gs", Context.MODE_PRIVATE)

    fun hasList(ctx: Context) = playlistUrl(ctx) != null

    fun listName(ctx: Context): String = prefs(ctx).getString("name", null) ?: "Minha lista"

    /** Servidor gravado no app (modelo revenda). Vazio = cliente informa a lista. */
    val fixedServer: String get() = BuildConfig.XTREAM_SERVER.trim().trimEnd('/')
    val hasFixedServer: Boolean get() = fixedServer.isNotEmpty()

    fun playlistUrl(ctx: Context): String? {
        val p = prefs(ctx)
        return if (isXtream(ctx)) {
            val server = if (hasFixedServer) fixedServer else p.getString("server", "")!!.trim().trimEnd('/')
            val user = p.getString("user", "")!!
            val pass = p.getString("pass", "")!!
            if (server.isEmpty() || user.isEmpty()) null
            else "${withScheme(server)}/get.php?username=${enc(user)}&password=${enc(pass)}&type=m3u_plus&output=ts"
        } else {
            p.getString("url", null)?.trim()?.takeIf { it.isNotEmpty() }?.let { withScheme(it) }
        }
    }

    /**
     * Organiza o que veio do celular: link da lista + usuário + senha.
     * - Link com username/password dentro (get.php?username=...): usa esses dados.
     * - Link + usuário + senha: trata como servidor Xtream.
     * Retorna o formulário pronto ou uma mensagem de erro.
     */
    fun normalizeForm(f: Map<String, String>): Pair<Map<String, String>?, String?> {
        val link = f["url"].orEmpty().trim()
        var user = f["user"].orEmpty().trim()
        var pass = f["pass"].orEmpty().trim()
        val server = if (hasFixedServer) fixedServer else link
        if (server.isEmpty()) return null to "Cole o link da lista."
        val full = withScheme(server)
        val uri = try { java.net.URI(full) } catch (e: Exception) { return null to "Link inválido. Confira e cole de novo." }
        val query = uri.rawQuery.orEmpty().split('&').associate {
            val i = it.indexOf('='); if (i < 0) it to "" else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }
        if (user.isEmpty()) user = query["username"].orEmpty()
        if (pass.isEmpty()) pass = query["password"].orEmpty()
        if (user.isEmpty() || pass.isEmpty()) return null to "Digite o usuário e a senha."
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val base = "${uri.scheme}://${uri.host}$port"
        return mapOf("type" to "xtream", "server" to base, "user" to user, "pass" to pass,
            "name" to f["name"].orEmpty()) to null
    }

    /** Salva o que veio do celular (já organizado por [normalizeForm]). */
    fun saveFromForm(ctx: Context, f: Map<String, String>): Boolean {
        val type = if (hasFixedServer) "xtream" else (f["type"] ?: "m3u")
        val e = prefs(ctx).edit()
        if (type == "xtream") {
            val server = if (hasFixedServer) fixedServer else f["server"].orEmpty().trim()
            val user = f["user"].orEmpty().trim()
            if (server.isEmpty() || user.isEmpty()) return false
            e.putString("type", "xtream").putString("server", server).putString("user", user)
                .putString("pass", f["pass"].orEmpty().trim())
        } else {
            val url = f["url"].orEmpty().trim()
            if (url.isEmpty()) return false
            e.putString("type", "m3u").putString("url", url)
        }
        e.putString("name", f["name"].orEmpty().trim().ifEmpty { f["user"].orEmpty().ifEmpty { "Minha lista" } })
            .putString("epg", f["epg"].orEmpty().trim())
            .commit()
        cacheFile(ctx).delete()
        return true
    }

    // ---------- Favoritos e último canal ----------
    fun favorites(ctx: Context): Set<String> = prefs(ctx).getStringSet("favs", emptySet())!!.toSet()

    fun toggleFavorite(ctx: Context, url: String): Boolean {
        val s = favorites(ctx).toMutableSet()
        val added = if (s.contains(url)) { s.remove(url); false } else { s.add(url); true }
        prefs(ctx).edit().putStringSet("favs", s).apply()
        return added
    }

    fun lastUrl(ctx: Context): String? = prefs(ctx).getString("last", null)
    fun setLastUrl(ctx: Context, url: String) = prefs(ctx).edit().putString("last", url).apply()

    // ---------- Carregar lista ----------
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "items.tsv")

    /**
     * Baixa (se preciso) e organiza a lista. [progress] recebe mensagens para mostrar na tela.
     * Usa uma cópia salva por 12 horas para abrir rápido.
     */
    suspend fun load(ctx: Context, force: Boolean = false, progress: (String) -> Unit = {}): List<Item> =
        withContext(Dispatchers.IO) {
            if (playlistUrl(ctx) == null) throw IllegalStateException("Nenhuma lista configurada")
            val cache = cacheFile(ctx)
            val stale = !cache.exists() || cache.length() == 0L ||
                System.currentTimeMillis() - cache.lastModified() > CACHE_HOURS * 3600_000L
            var list: List<Item>? = null
            if (force || stale) {
                try {
                    list = if (isXtream(ctx)) Xtream.loadAll(ctx, progress) else downloadM3u(ctx, progress)
                    if (list.isEmpty()) throw java.io.IOException("A lista veio vazia. Confira o link ou os dados.")
                    progress("Salvando ${list.size} itens…")
                    writeCache(cache, list)
                } catch (e: Exception) {
                    if (!cache.exists()) throw e // sem internet e sem cópia salva
                    list = null
                }
            }
            if (list == null) {
                progress("Abrindo lista salva…")
                list = readCache(cache)
            }
            progress("Organizando ${list.size} itens…")
            buildTabs(list)
            items = list
            groupPool.clear()
            list
        }

    private fun buildTabs(list: List<Item>) {
        val map = HashMap<Tab, TabData>()
        for (t in Tab.values()) {
            val tabItems = list.filter { i ->
                when (t) {
                    Tab.TV -> i.kind == Kind.LIVE
                    Tab.MOVIES -> i.kind == Kind.MOVIE && i.section == 0
                    Tab.SERIES -> i.kind == Kind.SERIES && i.section == 0
                    Tab.KIDS -> i.kind != Kind.LIVE && i.section == 1
                    Tab.ANIME -> i.kind != Kind.LIVE && i.section == 2
                }
            }
            val groups = LinkedHashMap<String, MutableList<Item>>()
            tabItems.forEach { groups.getOrPut(it.group) { ArrayList() }.add(it) }
            map[t] = TabData(tabItems, groups.map { it.key to it.value as List<Item> })
        }
        tabs = map
    }

    fun tab(t: Tab): TabData = tabs[t] ?: TabData(emptyList(), emptyList())

    fun isXtream(ctx: Context) = hasFixedServer || prefs(ctx).getString("type", "m3u") == "xtream"

    data class XtreamLogin(val server: String, val user: String, val pass: String)

    fun xtreamLogin(ctx: Context): XtreamLogin {
        val p = prefs(ctx)
        return XtreamLogin(
            withScheme(if (hasFixedServer) fixedServer else p.getString("server", "")!!.trim().trimEnd('/')),
            p.getString("user", "")!!, p.getString("pass", "")!!,
        )
    }

    private fun downloadM3u(ctx: Context, progress: (String) -> Unit): List<Item> {
        val url = playlistUrl(ctx)!!
        progress("Conectando ao servidor da lista…")
        val conn = Net.connect(url, 30_000)
        var bytes = 0L
        var lastShown = 0L
        val counting = object : java.io.FilterInputStream(conn.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    bytes += n
                    if (bytes - lastShown > 512 * 1024) {
                        lastShown = bytes
                        progress("Baixando lista… ${bytes / (1024 * 1024)} MB")
                    }
                }
                return n
            }
        }
        return counting.bufferedReader().use { parseM3u(it) }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun writeCache(file: File, list: List<Item>) {
        val tmp = File(file.parentFile, "items.tmp")
        tmp.bufferedWriter().use { w ->
            for (i in list) {
                w.write("${i.kind.ordinal}\t${i.number}\t${clean(i.name)}\t${clean(i.group)}\t${clean(i.logo)}\t${clean(i.url)}\n")
            }
        }
        file.delete()
        tmp.renameTo(file)
    }

    private fun readCache(file: File): List<Item> {
        val out = ArrayList<Item>()
        val kinds = Kind.values()
        file.bufferedReader().useLines { lines ->
            lines.forEach { l ->
                val f = l.split('\t')
                if (f.size >= 6) out.add(make(f[2], f[5], f[4], f[3], kinds.getOrElse(f[0].toIntOrNull() ?: 0) { Kind.LIVE }, f[1].toIntOrNull() ?: 0))
            }
        }
        return out
    }

    // ---------- Leitor de M3U ----------
    private val reLogo = Regex("tvg-logo=\"([^\"]*)\"")
    private val reGroup = Regex("group-title=\"([^\"]*)\"")
    private val reName = Regex("tvg-name=\"([^\"]*)\"")

    fun parseM3u(reader: BufferedReader): List<Item> {
        val out = ArrayList<Item>()
        var name = ""; var logo = ""; var group = ""; var pending = false
        var liveNumber = 0
        reader.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    pending = true
                    logo = reLogo.find(line)?.groupValues?.get(1).orEmpty()
                    group = reGroup.find(line)?.groupValues?.get(1).orEmpty()
                    val comma = line.indexOf(',', maxOf(line.lastIndexOf('"'), 0))
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    if (name.isEmpty()) name = reName.find(line)?.groupValues?.get(1).orEmpty()
                }
                line.startsWith("#EXTGRP:") -> group = line.substring(8).trim()
                line.isNotEmpty() && !line.startsWith("#") && pending -> {
                    val kind = kindOf(line, group)
                    val n = if (kind == Kind.LIVE) ++liveNumber else 0
                    out.add(make(name.ifEmpty { "Sem nome" }, line, logo, group.ifEmpty { "Outros" }, kind, n))
                    pending = false
                }
            }
        }
        return out
    }

    private fun kindOf(url: String, group: String): Kind {
        val u = url.lowercase()
        val g = norm(group)
        return when {
            "/movie/" in u -> Kind.MOVIE
            "/series/" in u -> Kind.SERIES
            u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".avi") ->
                if ("serie" in g) Kind.SERIES else Kind.MOVIE
            "filme" in g || "movie" in g || "vod" in g -> Kind.MOVIE
            "serie" in g -> Kind.SERIES
            else -> Kind.LIVE
        }
    }

    // ---------- Abas ----------
    fun forTab(tab: Tab): List<Item> = tab(tab).items

    fun search(query: String, tab: Tab): List<Item> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        return forTab(tab).filter { norm(it.name).contains(q) }
    }

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun withScheme(s: String) = if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
}

enum class Tab(val label: String) { TV("TV"), MOVIES("FILMES"), SERIES("SÉRIES"), KIDS("KIDS"), ANIME("ANIME") }
