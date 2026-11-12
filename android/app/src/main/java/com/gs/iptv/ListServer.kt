package com.gs.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Pequeno servidor web dentro da TV. O celular abre http://IP-DA-TV:8080,
 * preenche a lista e envia. Funciona só dentro da rede da casa.
 */
object ListServer {
    const val PORT = 8080

    /** Chamado (na thread principal) quando uma lista nova chega. */
    @Volatile var listener: (() -> Unit)? = null

    @Volatile private var running = false
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    fun address(): String? = Net.localIp()?.let { "http://$it:$PORT" }

    fun start(ctx: Context) {
        if (running) return
        running = true
        app = ctx.applicationContext
        thread(isDaemon = true, name = "gs-list-server") {
            try {
                ServerSocket(PORT).use { server ->
                    while (true) {
                        val s = server.accept()
                        thread(isDaemon = true) { handle(s) }
                    }
                }
            } catch (e: Exception) {
                running = false
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 15_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "" }
                val path = parts.getOrElse(1) { "/" }
                val out = s.getOutputStream()

                if (method == "POST" && path.startsWith("/send")) {
                    val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 200_000)
                    val body = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(body, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    val (form, formError) = Store.normalizeForm(parseForm(String(body, 0, read, Charsets.UTF_8)))
                    val error = formError ?: Xtream.checkLogin(form!!["server"]!!, form["user"]!!, form["pass"]!!)
                    val ok = error == null && Store.saveFromForm(app, form!!)
                    val msg = error ?: "Não foi possível salvar. Tente de novo."
                    val json = if (ok) "{\"ok\":true}"
                    else "{\"ok\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}"
                    respond(out, 200, "application/json; charset=utf-8", json)
                    if (ok) main.post { listener?.invoke() }
                } else if (path.startsWith("/favicon")) {
                    respond(out, 404, "text/plain", "")
                } else {
                    val html = app.resources.openRawResource(R.raw.phone).bufferedReader().use { it.readText() }
                        .replace("{{LIST_NAME}}", Store.listName(app))
                        .replace("{{FIXED}}", if (Store.hasFixedServer) "1" else "0")
                    respond(out, 200, "text/html; charset=utf-8", html)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) break
        }
        return buf.toString("ISO-8859-1")
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "Not Found"
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            if (i < 0) URLDecoder.decode(it, "UTF-8") to ""
            else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }
}
