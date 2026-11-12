package com.gs.iptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/**
 * Atualização automática: lê o arquivo update.json publicado no GitHub
 * (gerado pelo build), e se a versão for maior, baixa e instala o APK.
 */
object Updater {

    fun check(): UpdateInfo? {
        val url = BuildConfig.UPDATE_URL
        if (url.isEmpty()) return null
        return try {
            val j = JSONObject(Net.getText(url, 6_000))
            val code = j.getInt("versionCode")
            if (code > BuildConfig.VERSION_CODE)
                UpdateInfo(code, j.optString("versionName", code.toString()), j.getString("apkUrl"))
            else null
        } catch (e: Exception) {
            null
        }
    }

    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? = try {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "gs-iptv-${info.versionCode}.apk")
        val conn = Net.connect(info.apkUrl, 30_000)
        val total = conn.contentLength.toLong()
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        if (file.length() > 0) file else null
    } catch (e: Exception) {
        null
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(act: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                act.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
                )
            } catch (_: Exception) {
            }
        }
    }

    fun install(act: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(i)
    }
}
