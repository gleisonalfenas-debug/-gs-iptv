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
    private val prefixCanais = Regex("^\\s*canais?\\s*[:|/•·–-]*\\s*", RegexOption.IGNORE_CASE)

    fun make(name: String, url: String, logo: String, group: String, kind: Kind, number: Int): Item {
        // Tira o "Canais" do começo do nome da categoria (ex.: "CANAIS | ESPORTES" -> "ESPORTES")
        val shown = group.replace(prefixCanais, "").ifEmpty { group }
        val g = groupPool.getOrPut(shown) { shown }
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

    /** Rotas para tocar um item, da mais provável para a alternativa. */
    fun playRoutes(ctx: Context, item: Item): List<String> {
        val main = playUrl(ctx, item)
        val isLive = item.kind == Kind.LIVE && ("/live/" in main || item.url.startsWith("${XT}live/"))
        if (!isLive) return listOf(main)
        val ts = if (main.endsWith(".m3u8")) main.removeSuffix(".m3u8") + ".ts" else main
        val hls = if (main.endsWith(".ts")) main.removeSuffix(".ts") + ".m3u8" else main
        if (ts == hls) return listOf(main)
        return if (prefs(ctx).getString("route", "ts") == "hls") listOf(hls, ts) else listOf(ts, hls)
    }

    fun rememberRoute(ctx: Context, url: String) {
        val r = when { url.endsWith(".m3u8") -> "hls"; url.endsWith(".ts") -> "ts"; else -> return }
        prefs(ctx).edit().putString("route", r).apply()
    }

    /** Mostra aviso quando o app acabou de ser atualizado. Retorna a versão nova ou null. */
    fun justUpdated(ctx: Context): String? {
        val p = prefs(ctx)
        val seen = p.getInt("seen_version", -1)
        p.edit().putInt("seen_version", BuildConfig.VERSION_CODE).apply()
        return if (seen in 0 until BuildConfig.VERSION_CODE) BuildConfig.VERSION_NAME else null
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
        clearAll(ctx)
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

    // ---------- Carregar lista (uma seção de cada vez) ----------
    private fun cacheFile(ctx: Context, kind: Kind) = File(ctx.filesDir, "items_${kind.name.lowercase()}.tsv")

    private val loaded = HashMap<Kind, List<Item>>()

    fun isLoaded(kind: Kind) = synchronized(loaded) { loaded.containsKey(kind) }
    fun count(kind: Kind) = synchronized(loaded) { loaded[kind]?.size ?: 0 }

    /** Já existe cópia salva recente desta seção? */
    fun hasFreshCache(ctx: Context, kind: Kind): Boolean {
        val f = cacheFile(ctx, kind)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_HOURS * 3600_000L
    }

    /** Apaga tudo (usado quando entra uma lista ou login novo). */
    fun clearAll(ctx: Context) {
        Kind.values().forEach { cacheFile(ctx, it).delete() }
        synchronized(loaded) { loaded.clear() }
        items = emptyList()
        tabs = emptyMap()
    }

    /** Baixa (ou abre a cópia salva) só de uma seção: canais, filmes ou séries. */
    suspend fun loadSection(ctx: Context, kind: Kind, force: Boolean = false, progress: (String) -> Unit = {}): List<Item> =
        withContext(Dispatchers.IO) {
            if (playlistUrl(ctx) == null) throw IllegalStateException("Nenhuma lista configurada")
            val started = System.currentTimeMillis()
            val cache = cacheFile(ctx, kind)
            if (force || !hasFreshCache(ctx, kind)) {
                try {
                    if (isXtream(ctx)) {
                        val list = Xtream.loadSection(ctx, kind, progress)
                        progress("Salvando ${list.size} itens…")
                        writeCache(cache, list)
                    } else {
                        downloadM3uSplit(ctx, progress) // a M3U vem inteira: separa direto em arquivos
                    }
                } catch (e: Exception) {
                    if (!cache.exists()) throw e // sem internet e sem cópia salva
                }
            }
            progress("Abrindo ${label(kind)}…")
            val list = if (cache.exists()) readCache(cache) else emptyList()
            groupPool.clear()
            synchronized(loaded) { loaded[kind] = list }
            progress("Organizando ${list.size} itens…")
            rebuild()
            Diag.listLoaded(label(kind) + if (force) " (atualizado)" else "", list.size, System.currentTimeMillis() - started)
            list
        }

    fun label(kind: Kind) = when (kind) { Kind.LIVE -> "canais ao vivo"; Kind.MOVIE -> "filmes"; Kind.SERIES -> "séries" }

    private fun rebuild() {
        val all = synchronized(loaded) { Kind.values().flatMap { loaded[it] ?: emptyList() } }
        buildTabs(all)
        items = all
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

    /** Baixa a M3U e grava canais, filmes e séries em arquivos separados, sem segurar tudo na memória. */
    private fun downloadM3uSplit(ctx: Context, progress: (String) -> Unit) {
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
        val tmps = Kind.values().associateWith { File(ctx.filesDir, "split_${it.name}.tmp") }
        val writers = tmps.mapValues { it.value.bufferedWriter() }
        try {
            counting.bufferedReader().use { r -> parseM3u(r) { item -> writers[item.kind]!!.write(line(item)) } }
        } finally {
            writers.values.forEach { it.close() }
        }
        for (k in Kind.values()) {
            val f = cacheFile(ctx, k)
            f.delete()
            tmps[k]!!.renameTo(f)
        }
        groupPool.clear()
    }

    private fun line(i: Item) =
        "${i.kind.ordinal}\t${i.number}\t${clean(i.name)}\t${clean(i.group)}\t${clean(i.logo)}\t${clean(i.url)}\n"

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun writeCache(file: File, list: List<Item>) {
        val tmp = File(file.parentFile, "items.tmp")
        tmp.bufferedWriter().use { w -> for (i in list) w.write(line(i)) }
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

    fun parseM3u(reader: BufferedReader, onItem: (Item) -> Unit) {
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
                    onItem(make(name.ifEmpty { "Sem nome" }, line, logo, group.ifEmpty { "Outros" }, kind, n))
                    pending = false
                }
            }
        }
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
