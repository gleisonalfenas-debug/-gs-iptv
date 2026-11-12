package com.gs.iptv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

object Net {
    const val USER_AGENT = "GS-IPTV/1.0 (Android TV)"

    /** Abre uma URL seguindo redirecionamentos (inclusive http -> https). */
    fun open(url: String, timeout: Int = 15_000): InputStream = connect(url, timeout).inputStream

    fun connect(url: String, timeout: Int = 15_000): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw java.io.IOException("Redirecionamento inválido")
                current = URL(URL(current), loc).toString()
                c.disconnect()
            } else if (code >= 400) {
                c.disconnect()
                throw java.io.IOException("Erro HTTP $code")
            } else {
                return c
            }
        }
        throw java.io.IOException("Muitos redirecionamentos")
    }

    fun getText(url: String, timeout: Int = 8_000): String =
        open(url, timeout).bufferedReader().use { it.readText() }

    /** IP da TV na rede Wi-Fi/cabo (ex.: 192.168.0.25). */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { null }

    fun qrCode(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
