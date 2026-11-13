package com.gs.iptv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico: registra o que acontece no app (tempo para abrir canais, travadas,
 * quadros perdidos, downloads das listas, memória). O relatório é aberto pelo
 * celular em http://IP-DA-TV:8080/diagnostico para copiar e enviar ao suporte.
 */
object Diag {
    private val events = ArrayDeque<String>()
    private val openTimes = ArrayList<Long>()
    private var rebuffers = 0
    private var droppedFrames = 0L
    private var failures = 0
    private var lastVideo = "-"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(msg: String) = synchronized(events) {
        events.addLast("${fmt.format(Date())}  $msg")
        while (events.size > 80) events.removeFirst()
    }

    fun listLoaded(what: String, count: Int, ms: Long) = log("Lista: $what · $count itens em ${sec(ms)}")

    fun channelOpened(name: String, ms: Long, route: String?) {
        synchronized(openTimes) { openTimes.add(ms); if (openTimes.size > 50) openTimes.removeAt(0) }
        val r = when { route == null -> ""; route.endsWith(".m3u8") -> " (HLS)"; route.endsWith(".ts") -> " (TS)"; else -> "" }
        log("Abriu \"$name\" em ${sec(ms)}$r")
    }

    fun rebuffer(name: String) { rebuffers++; log("Travou para carregar: \"$name\"") }
    fun failed(name: String) { failures++; log("Falhou ao abrir: \"$name\"") }
    fun dropped(n: Int) { droppedFrames += n }
    fun video(w: Int, h: Int, bitrate: Int, codec: String?) {
        lastVideo = "${w}x$h · ${if (bitrate > 0) "${bitrate / 1000} kbps" else "?"} · ${codec ?: "?"}"
    }

    fun report(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        val times = synchronized(openTimes) { openTimes.toList() }
        val avg = if (times.isEmpty()) "-" else sec(times.average().toLong())
        val worst = if (times.isEmpty()) "-" else sec(times.max())
        val sb = StringBuilder()
        sb.appendLine("=== GS IPTV · Diagnóstico ===")
        sb.appendLine("Versão do app: ${BuildConfig.VERSION_NAME}")
        sb.appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Memória do app: usando $usedMb MB de $maxMb MB")
        sb.appendLine("Memória da TV: livre ${mi.availMem / 1048576} MB de ${mi.totalMem / 1048576} MB${if (mi.lowMemory) " · POUCA MEMÓRIA" else ""}")
        sb.appendLine("TV com pouca RAM: ${if (am.isLowRamDevice) "sim" else "não"}")
        sb.appendLine("Tipo de lista: ${if (Store.isXtream(ctx)) "Xtream" else "M3U"}")
        sb.appendLine("Itens abertos: canais ${Store.count(Kind.LIVE)} · filmes ${Store.count(Kind.MOVIE)} · séries ${Store.count(Kind.SERIES)}")
        sb.appendLine("Canais: tempo médio para abrir $avg · pior $worst · ${times.size} aberturas")
        sb.appendLine("Travadas: $rebuffers · Falhas: $failures · Quadros perdidos: $droppedFrames")
        sb.appendLine("Último vídeo: $lastVideo")
        sb.appendLine()
        sb.appendLine("--- Últimos eventos ---")
        synchronized(events) { events.reversed().forEach { sb.appendLine(it) } }
        return sb.toString()
    }

    private fun sec(ms: Long) = String.format(Locale("pt", "BR"), "%.1fs", ms / 1000.0)
}
