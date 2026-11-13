package com.gs.iptv

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Guarda o motivo se o app fechar sozinho, para mostrar na próxima vez que abrir. */
class GsApp : Application(), coil.ImageLoaderFactory {
    /** Imagens (logos e capas) com cache de memória pequeno, para não pesar a TV. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.10).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("imagens")).maxSizeBytes(150L * 1024 * 1024).build() }
        .allowRgb565(true)
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText(
                    "Versão ${BuildConfig.VERSION_NAME}\n${e.javaClass.simpleName}: ${e.message}\n\n" + sw.toString().take(3000)
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun takeCrash(app: Application): String? {
            val f = File(app.filesDir, "crash.txt")
            if (!f.exists()) return null
            val text = f.readText()
            f.delete()
            return text
        }
    }
}
