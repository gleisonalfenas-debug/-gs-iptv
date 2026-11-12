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
)

/** Guarda a lista configurada, baixa, lê (M3U / Xtream) e mantém favoritos. */
object Store {
    private const val CACHE_HOURS = 12

    @Volatile var items: List<Item> = emptyList()
        private set

    /** Lista usada pelo player em tela cheia para trocar de canal (cima/baixo). */
    var playQueue: List<Item> = emptyList()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("gs", Context.MODE_PRIVATE)

    fun hasList(ctx: Context) = playlistUrl(ctx) != null

    fun listName(ctx: Context): String = prefs(ctx).getString("name", null) ?: "Minha lista"

    fun playlistUrl(ctx: Context): String? {
        val p = prefs(ctx)
        return if (p.getString("type", "m3u") == "xtream") {
            val server = p.getString("server", "")!!.trim().trimEnd('/')
            val user = p.getString("user", "")!!
            val pass = p.getString("pass", "")!!
            if (server.isEmpty() || user.isEmpty()) null
            else "${withScheme(server)}/get.php?username=${enc(user)}&password=${enc(pass)}&type=m3u_plus&output=ts"
        } else {
            p.getString("url", null)?.trim()?.takeIf { it.isNotEmpty() }?.let { withScheme(it) }
        }
    }

    /** Salva o que veio do celular. Retorna false se faltou algum dado. */
    fun saveFromForm(ctx: Context, f: Map<String, String>): Boolean {
        val type = f["type"] ?: "m3u"
        val e = prefs(ctx).edit()
        if (type == "xtream") {
            val server = f["server"].orEmpty().trim()
            val user = f["user"].orEmpty().trim()
            if (server.isEmpty() || user.isEmpty()) return false
            e.putString("type", "xtream").putString("server", server).putString("user", user)
                .putString("pass", f["pass"].orEmpty().trim())
        } else {
            val url = f["url"].orEmpty().trim()
            if (url.isEmpty()) return false
            e.putString("type", "m3u").putString("url", url)
        }
        e.putString("name", f["name"].orEmpty().trim().ifEmpty { "Minha lista" })
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
            items = list
            list
        }

    fun isXtream(ctx: Context) = prefs(ctx).getString("type", "m3u") == "xtream"

    data class XtreamLogin(val server: String, val user: String, val pass: String)

    fun xtreamLogin(ctx: Context): XtreamLogin {
        val p = prefs(ctx)
        return XtreamLogin(
            withScheme(p.getString("server", "")!!.trim().trimEnd('/')),
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
                if (f.size >= 6) out.add(Item(f[2], f[5], f[4], f[3], kinds.getOrElse(f[0].toIntOrNull() ?: 0) { Kind.LIVE }, f[1].toIntOrNull() ?: 0))
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
                    out.add(Item(name.ifEmpty { "Sem nome" }, line, logo, group.ifEmpty { "Outros" }, kind, n))
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
    fun isKids(i: Item): Boolean { val g = norm(i.group); return "kids" in g || "infantil" in g || "desenho" in g || "crianca" in g }
    fun isAnime(i: Item): Boolean = "anime" in norm(i.group)

    fun forTab(tab: Tab): List<Item> = items.filter { i ->
        when (tab) {
            Tab.TV -> i.kind == Kind.LIVE
            Tab.MOVIES -> i.kind == Kind.MOVIE && !isKids(i) && !isAnime(i)
            Tab.SERIES -> i.kind == Kind.SERIES && !isKids(i) && !isAnime(i)
            Tab.KIDS -> i.kind != Kind.LIVE && isKids(i)
            Tab.ANIME -> i.kind != Kind.LIVE && isAnime(i)
        }
    }

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
