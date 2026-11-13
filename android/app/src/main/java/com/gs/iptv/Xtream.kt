package com.gs.iptv

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Leitura de listas Xtream Codes pela API (player_api.php), que é bem mais leve
 * que baixar a lista M3U completa. Séries carregam os episódios só ao abrir.
 */
object Xtream {
    const val SERIES_PREFIX = "xtream-series:"

    private fun api(l: Store.XtreamLogin, action: String, extra: String = "") =
        "${l.server}/player_api.php?username=${enc(l.user)}&password=${enc(l.pass)}&action=$action$extra"

    /** Baixa só uma seção: canais ao vivo, filmes ou séries. Categorias e itens vêm ao mesmo tempo. */
    fun loadSection(ctx: Context, kind: Kind, progress: (String) -> Unit): List<Item> {
        val l = Store.xtreamLogin(ctx)
        val (catAction, action, fallback) = when (kind) {
            Kind.LIVE -> Triple("get_live_categories", "get_live_streams", "Outros")
            Kind.MOVIE -> Triple("get_vod_categories", "get_vod_streams", "Filmes")
            Kind.SERIES -> Triple("get_series_categories", "get_series", "Séries")
        }
        // categorias em paralelo com a lista de itens
        var cats: Map<String, String> = emptyMap()
        val catThread = kotlin.concurrent.thread(isDaemon = true) { cats = categories(l, catAction) }
        progress("Baixando ${Store.label(kind)}…")
        val raw = ArrayList<Array<String>>()
        readArray(api(l, action)) { o ->
            val id = (if (kind == Kind.SERIES) o["series_id"] else o["stream_id"]) ?: return@readArray
            val icon = (if (kind == Kind.SERIES) o["cover"] else o["stream_icon"]).orEmpty()
            val stars = o["rating_5based"]?.toFloatOrNull() ?: o["rating"]?.toFloatOrNull()?.div(2f) ?: 0f
            raw.add(arrayOf(o["name"].orEmpty(), id, icon, o["category_id"].orEmpty(), o["container_extension"].orEmpty(), stars.toString()))
            if (raw.size % 1000 == 0) progress("Baixando ${Store.label(kind)}… ${raw.size}")
        }
        catThread.join(15_000)
        val out = ArrayList<Item>(raw.size)
        var n = 0
        for (r in raw) {
            val group = cats[r[3]] ?: fallback
            when (kind) {
                Kind.LIVE -> out.add(Store.make(r[0], "${Store.XT}live/${r[1]}.ts", r[2], group, Kind.LIVE, ++n))
                Kind.MOVIE -> out.add(Store.make(r[0], "${Store.XT}movie/${r[1]}.${r[4].ifEmpty { "mp4" }}", r[2], group, Kind.MOVIE, 0, r[5].toFloatOrNull() ?: 0f))
                Kind.SERIES -> out.add(Store.make(r[0], "$SERIES_PREFIX${r[1]}", r[2], group, Kind.SERIES, 0, r[5].toFloatOrNull() ?: 0f))
            }
        }
        return out
    }

    /** Dados da conta (Minha conta). Retorna linhas "rótulo" to "valor". */
    fun accountInfo(ctx: Context): List<Pair<String, String>> {
        val l = Store.xtreamLogin(ctx)
        val j = JSONObject(Net.getText("${l.server}/player_api.php?username=${enc(l.user)}&password=${enc(l.pass)}", 15_000))
        val u = j.optJSONObject("user_info") ?: return listOf("Conta" to "Não foi possível ler os dados")
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR"))
        fun date(v: String): String = v.toLongOrNull()?.let { fmt.format(java.util.Date(it * 1000)) } ?: "Sem vencimento"
        val exp = u.optString("exp_date")
        val days = exp.toLongOrNull()?.let { ((it * 1000 - System.currentTimeMillis()) / 86_400_000L) }
        val status = when (u.optString("status").lowercase()) {
            "active" -> "Ativa"; "expired" -> "Vencida"; "banned" -> "Bloqueada"; "disabled" -> "Desativada"
            else -> u.optString("status")
        }
        return listOf(
            "Usuário" to u.optString("username"),
            "Situação" to status,
            "Vencimento" to date(exp) + (days?.let { if (it >= 0) "  (faltam $it dias)" else "" } ?: ""),
            "Telas em uso" to "${u.optString("active_cons", "0")} de ${u.optString("max_connections", "?")}",
            "Teste grátis" to if (u.optString("is_trial") == "1") "Sim" else "Não",
            "Conta criada em" to date(u.optString("created_at")),
            "Servidor" to java.net.URL(l.server).host,
        )
    }

    /** Confere usuário e senha no servidor. Retorna null se estiver tudo certo, ou a mensagem de erro. */
    fun checkLogin(server: String, user: String, pass: String): String? {
        val base = (if (server.startsWith("http")) server else "http://$server").trimEnd('/')
        return try {
            val j = JSONObject(Net.getText("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}", 15_000))
            val info = j.optJSONObject("user_info") ?: return "Usuário ou senha incorretos."
            when {
                info.optString("auth") != "1" && info.optInt("auth", 0) != 1 -> "Usuário ou senha incorretos."
                info.optString("status").equals("Expired", true) -> "Seu plano venceu. Fale com seu revendedor."
                info.optString("status").equals("Banned", true) || info.optString("status").equals("Disabled", true) ->
                    "Este usuário está bloqueado. Fale com seu revendedor."
                else -> null
            }
        } catch (e: Exception) {
            "Não consegui falar com o servidor. Tente de novo em instantes."
        }
    }

    /** Dados completos da série: sinopse, gênero, ano e episódios por temporada. */
    data class SeriesInfo(val plot: String, val genre: String, val year: String, val cover: String, val seasons: LinkedHashMap<String, List<Item>>, val rating: Float = 0f)

    fun seriesInfo(ctx: Context, seriesUrl: String, seriesName: String, logo: String): SeriesInfo {
        val l = Store.xtreamLogin(ctx)
        val id = seriesUrl.removePrefix(SERIES_PREFIX)
        val json = JSONObject(Net.getText(api(l, "get_series_info", "&series_id=$id"), 20_000))
        val info = json.optJSONObject("info")
        val eps = json.optJSONObject("episodes")
        val seasons = LinkedHashMap<String, List<Item>>()
        if (eps != null) {
            for (season in eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }) {
                val arr = eps.optJSONArray(season) ?: continue
                val list = ArrayList<Item>()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val num = e.optString("episode_num", "${i + 1}")
                    val title = e.optString("title", "").ifEmpty { "Episódio $num" }
                    val ext = e.optString("container_extension", "mp4")
                    val einfo = e.optJSONObject("info")
                    val img = einfo?.optString("movie_image").orEmpty().ifEmpty { logo }
                    val dur = einfo?.optString("duration").orEmpty()
                    list.add(Item("$num. ${title.substringAfterLast(" - ").ifBlank { title }}", "${l.server}/series/${l.user}/${l.pass}/${e.optString("id")}.$ext",
                        img, if (dur.isNotEmpty()) "T$season · $dur" else "Temporada $season", Kind.SERIES, 0))
                }
                seasons[season] = list
            }
        }
        fun s(k: String) = info?.optString(k).orEmpty().let { if (it == "null") "" else it }
        val rating = s("rating_5based").toFloatOrNull() ?: s("rating").toFloatOrNull()?.div(2f) ?: 0f
        return SeriesInfo(s("plot"), s("genre"), (s("releaseDate").ifEmpty { s("release_date") }).take(4), s("cover").ifEmpty { logo }, seasons, rating)
    }

    /** Episódios de uma série, já com o link para tocar. */
    fun episodes(ctx: Context, seriesUrl: String, seriesName: String, logo: String): List<Item> {
        val l = Store.xtreamLogin(ctx)
        val id = seriesUrl.removePrefix(SERIES_PREFIX)
        val json = JSONObject(Net.getText(api(l, "get_series_info", "&series_id=$id"), 20_000))
        val eps = json.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Item>()
        val seasons = eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.toList()
        for (season in seasons) {
            val arr = eps.optJSONArray(season) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val num = e.optString("episode_num", "${i + 1}")
                val title = e.optString("title", "").ifEmpty { "Episódio $num" }
                val ext = e.optString("container_extension", "mp4")
                out.add(Item("T$season E$num · $title", "${l.server}/series/${l.user}/${l.pass}/${e.optString("id")}.$ext",
                    logo, seriesName, Kind.SERIES, 0))
            }
        }
        return out
    }

    private fun categories(l: Store.XtreamLogin, action: String): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            readArray(api(l, action)) { o ->
                val id = o["category_id"]; val name = o["category_name"]
                if (id != null && name != null) map[id] = name
            }
        } catch (_: Exception) { }
        return map
    }

    /** Lê um array JSON grande item por item, sem carregar tudo na memória. */
    private fun readArray(url: String, onItem: (Map<String, String>) -> Unit) {
        Net.open(url, 30_000).use { input ->
            JsonReader(input.bufferedReader()).use { r ->
                if (r.peek() != JsonToken.BEGIN_ARRAY) {
                    // Login inválido ou servidor sem esse conteúdo
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        val o = readObject(r)
                        if (o["auth"] == "0" || o["status"] == "Disabled" || o["status"] == "Expired")
                            throw java.io.IOException("Usuário ou senha do Xtream inválidos ou plano vencido.")
                    }
                    return
                }
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) onItem(readObject(r)) else r.skipValue()
                }
                r.endArray()
            }
        }
    }

    private val KEYS = setOf("stream_id", "series_id", "name", "stream_icon", "cover", "category_id",
        "category_name", "container_extension", "auth", "status", "rating", "rating_5based")

    /** Lê só os campos usados (economiza muita memória em listas grandes). */
    private fun readObject(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>(8)
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            if (k != "user_info" && k !in KEYS) { r.skipValue(); continue }
            when (r.peek()) {
                JsonToken.STRING -> m[k] = r.nextString()
                JsonToken.NUMBER -> m[k] = r.nextString()
                JsonToken.BOOLEAN -> m[k] = if (r.nextBoolean()) "1" else "0"
                JsonToken.NULL -> r.nextNull()
                JsonToken.BEGIN_OBJECT -> {
                    // user_info vem dentro de um objeto no login
                    if (k == "user_info") m.putAll(readObject(r)) else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
