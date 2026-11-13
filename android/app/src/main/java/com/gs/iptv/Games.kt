package com.gs.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Game(
    val id: String,
    val league: String,
    val home: String,
    val away: String,
    val homeBadge: String,
    val awayBadge: String,
    val startMs: Long,      // horário de início (0 = sem horário)
    val status: String,     // texto do status vindo da fonte (ex.: "Match Finished")
    val homeScore: String,
    val awayScore: String,
) {
    val title get() = "$home x $away"
    fun started(now: Long = System.currentTimeMillis()) = startMs in 1..now
    fun finished() = status.contains("finish", true) || status.equals("FT", true) || status.contains("encerr", true)
}

/**
 * Jogos do dia (futebol) com escudos, da TheSportsDB (chave gratuita pública "123").
 * A chave gratuita traz resultados limitados; por isso o app busca liga por liga.
 */
object Games {
    private const val KEY = "123"
    private val LEAGUES = listOf(
        "Brazilian Serie A", "Brazilian Serie B", "Copa Libertadores", "Copa Sudamericana",
        "Brazilian Cup", "UEFA Champions League", "UEFA Europa League", "English Premier League",
        "Spanish La Liga", "Italian Serie A", "German Bundesliga", "French Ligue 1",
    )
    private var cache: Pair<Long, List<Game>>? = null

    suspend fun today(force: Boolean = false): List<Game> = withContext(Dispatchers.IO) {
        cache?.let { (t, g) -> if (!force && System.currentTimeMillis() - t < 20 * 60_000) return@withContext g }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val out = LinkedHashMap<String, Game>()
        val tasks = mutableListOf<String>()
        tasks.add("https://www.thesportsdb.com/api/v1/json/$KEY/eventsday.php?d=$day&s=Soccer")
        LEAGUES.forEach { tasks.add("https://www.thesportsdb.com/api/v1/json/$KEY/eventsday.php?d=$day&l=${URLEncoder.encode(it, "UTF-8")}") }
        for (url in tasks) {
            try {
                parse(Net.getText(url, 10_000)).forEach { out.putIfAbsent(it.id, it) }
            } catch (_: Exception) { }
        }
        // só jogos de hoje no horário local (a fonte usa horário UTC)
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val list = out.values
            .filter { it.startMs == 0L || it.startMs in startOfDay until startOfDay + 24 * 3600_000L + 3 * 3600_000L }
            .sortedBy { if (it.startMs == 0L) Long.MAX_VALUE else it.startMs }
        cache = System.currentTimeMillis() to list
        Diag.log("Jogos do dia: ${list.size} jogos")
        list
    }

    private fun parse(json: String): List<Game> {
        val arr = JSONObject(json).optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Game>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (!e.optString("strSport", "Soccer").equals("Soccer", true)) continue
            out.add(
                Game(
                    id = e.optString("idEvent"),
                    league = e.optString("strLeague"),
                    home = e.optString("strHomeTeam"),
                    away = e.optString("strAwayTeam"),
                    homeBadge = clean(e.optString("strHomeTeamBadge")),
                    awayBadge = clean(e.optString("strAwayTeamBadge")),
                    startMs = time(e.optString("strTimestamp"), e.optString("dateEvent"), e.optString("strTime")),
                    status = clean(e.optString("strStatus")),
                    homeScore = clean(e.optString("intHomeScore")),
                    awayScore = clean(e.optString("intAwayScore")),
                )
            )
        }
        return out
    }

    private fun clean(s: String) = if (s == "null") "" else s

    private fun time(ts: String, date: String, t: String): Long {
        val utc = TimeZone.getTimeZone("UTC")
        val candidates = listOf(
            ts.replace(' ', 'T').take(19) to "yyyy-MM-dd'T'HH:mm:ss",
            "${date}T${t.take(8)}" to "yyyy-MM-dd'T'HH:mm:ss",
        )
        for ((value, pattern) in candidates) {
            if (value.length < 19 || value.contains("null")) continue
            try {
                return SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.parse(value)!!.time
            } catch (_: Exception) { }
        }
        return 0L
    }

    /** Canais de esporte da lista do usuário (para escolher onde assistir o jogo). */
    fun sportsChannels(): List<Item> {
        val keys = listOf("esport", "sport", "futebol", "premiere", "espn", "combate", "tnt", "fox", "band", "dazn", "ppv", "jogo")
        val live = Store.tab(Tab.TV).items
        val picked = live.filter { i -> val g = Store.norm(i.group + " " + i.name); keys.any { it in g } }
        return if (picked.isNotEmpty()) picked else live
    }
}
