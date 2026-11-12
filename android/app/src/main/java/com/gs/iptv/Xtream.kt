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

    fun loadAll(ctx: Context, progress: (String) -> Unit): List<Item> {
        val l = Store.xtreamLogin(ctx)
        val out = ArrayList<Item>()

        progress("Entrando no servidor…")
        val liveCats = categories(l, "get_live_categories")
        progress("Baixando canais ao vivo…")
        var n = 0
        readArray(api(l, "get_live_streams")) { o ->
            val id = o["stream_id"] ?: return@readArray
            out.add(Item(o["name"].orEmpty(), "${l.server}/live/${l.user}/${l.pass}/$id.ts",
                o["stream_icon"].orEmpty(), liveCats[o["category_id"]] ?: "Outros", Kind.LIVE, ++n))
            if (n % 500 == 0) progress("Baixando canais ao vivo… $n")
        }

        try {
            val vodCats = categories(l, "get_vod_categories")
            progress("Baixando filmes…")
            var m = 0
            readArray(api(l, "get_vod_streams")) { o ->
                val id = o["stream_id"] ?: return@readArray
                val ext = o["container_extension"].orEmpty().ifEmpty { "mp4" }
                out.add(Item(o["name"].orEmpty(), "${l.server}/movie/${l.user}/${l.pass}/$id.$ext",
                    o["stream_icon"].orEmpty(), vodCats[o["category_id"]] ?: "Filmes", Kind.MOVIE, 0))
                if (++m % 1000 == 0) progress("Baixando filmes… $m")
            }
        } catch (_: Exception) { /* sem filmes no plano: segue */ }

        try {
            val serCats = categories(l, "get_series_categories")
            progress("Baixando séries…")
            var s = 0
            readArray(api(l, "get_series")) { o ->
                val id = o["series_id"] ?: return@readArray
                out.add(Item(o["name"].orEmpty(), "$SERIES_PREFIX$id",
                    o["cover"].orEmpty(), serCats[o["category_id"]] ?: "Séries", Kind.SERIES, 0))
                if (++s % 500 == 0) progress("Baixando séries… $s")
            }
        } catch (_: Exception) { }

        return out
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

    private fun readObject(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
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
