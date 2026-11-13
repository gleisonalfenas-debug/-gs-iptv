package com.gs.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Um programa da grade (guia de programação). */
data class Prog(val title: String, val start: Long, val end: Long)

/**
 * Guia de programação (EPG) do servidor Xtream: programa atual e próximo de cada canal.
 * Busca um canal por vez, guarda por 15 minutos e nunca trava a tela.
 */
object Epg {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Prog>>>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun streamId(item: Item): String? = when {
        item.kind != Kind.LIVE -> null
        item.url.startsWith("${Store.XT}live/") -> item.url.removePrefix("${Store.XT}live/").substringBefore('.')
        else -> Regex("/live/[^/]+/[^/]+/(\\d+)").find(item.url)?.groupValues?.get(1)
    }

    fun cached(item: Item): List<Prog>? {
        val id = streamId(item) ?: return null
        val c = cache[id] ?: return null
        return if (System.currentTimeMillis() - c.first < 15 * 60_000) c.second else null
    }

    fun current(progs: List<Prog>?, now: Long = System.currentTimeMillis()) = progs?.firstOrNull { now in it.start until it.end }
    fun next(progs: List<Prog>?, now: Long = System.currentTimeMillis()): Prog? {
        val cur = current(progs, now)
        return progs?.firstOrNull { it.start >= (cur?.end ?: now) }
    }

    /** Pede a grade do canal; [done] roda na tela principal quando chegar. */
    fun request(ctx: Context, item: Item, done: (List<Prog>) -> Unit) {
        if (!Store.isXtream(ctx)) return
        val id = streamId(item) ?: return
        cached(item)?.let { done(it); return }
        if (!pending.add(id)) return
        val app = ctx.applicationContext
        exec.execute {
            val list = try { fetch(app, id) } catch (_: Exception) { emptyList() }
            cache[id] = System.currentTimeMillis() to list
            pending.remove(id)
            main.post { done(list) }
        }
    }

    private fun fetch(ctx: Context, id: String): List<Prog> {
        val l = Store.xtreamLogin(ctx)
        val url = "${l.server}/player_api.php?username=${enc(l.user)}&password=${enc(l.pass)}&action=get_short_epg&stream_id=$id&limit=4"
        val arr = JSONObject(Net.getText(url, 8_000)).optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Prog>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = decode(o.optString("title"))
            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val end = o.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: continue
            out.add(Prog(title.ifBlank { "Programação" }, start, end))
        }
        return out.sortedBy { it.start }
    }

    private fun decode(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (_: Exception) { s }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** Continuar assistindo: guarda onde você parou em filmes e episódios. */
object Watch {
    data class Entry(val item: Item, val pos: Long, val dur: Long, val at: Long) {
        val fraction get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val leftMin get() = ((dur - pos) / 60_000).coerceAtLeast(1)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gs_watch", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Entry> {
        val arr = org.json.JSONArray(prefs(ctx).getString("list", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = Kind.values().getOrElse(o.optInt("k")) { Kind.MOVIE }
            Entry(Item(o.optString("n"), o.optString("u"), o.optString("l"), o.optString("g"), kind, 0), o.optLong("p"), o.optLong("d"), o.optLong("t"))
        }
    }

    fun position(ctx: Context, url: String): Long = list(ctx).firstOrNull { it.item.url == url }?.pos ?: 0L

    fun save(ctx: Context, item: Item, pos: Long, dur: Long) {
        if (item.kind == Kind.LIVE || dur <= 0) return
        val rest = list(ctx).filterNot { it.item.url == item.url }
        val keep = pos > 30_000 && pos < dur - 90_000 // terminou ou mal começou: sai da lista
        val newList = (if (keep) listOf(Entry(item, pos, dur, System.currentTimeMillis())) else emptyList()) + rest
        val arr = org.json.JSONArray()
        newList.take(20).forEach { e ->
            arr.put(JSONObject().put("n", e.item.name).put("u", e.item.url).put("l", e.item.logo).put("g", e.item.group)
                .put("k", e.item.kind.ordinal).put("p", e.pos).put("d", e.dur).put("t", e.at))
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }
}
