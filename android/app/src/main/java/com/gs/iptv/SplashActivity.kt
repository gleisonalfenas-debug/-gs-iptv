package com.gs.iptv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Abertura com o logo animado. Também procura atualização do app. */
class SplashActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var pendingApk: File? = null
    private var installerOpened = false
    private var waitingPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        findViewById<TextView>(R.id.version).text = "versão ${BuildConfig.VERSION_NAME}"

        val word = findViewById<TextView>(R.id.word)
        word.text = SpannableString("GS IPTV").apply {
            setSpan(ForegroundColorSpan(Color.parseColor("#F0C45C")), 3, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        animateLogo(word)
        ListServer.start(this)

        lifecycleScope.launch {
            val fill = ValueAnimator.ofInt(0, 70).setDuration(1600)
            fill.addUpdateListener { progress.progress = it.animatedValue as Int }
            fill.start()
            delay(1700)

            status.text = "Procurando atualizações…"
            val update = withContext(Dispatchers.IO) { Updater.check() }
            if (update != null) {
                status.text = "Baixando nova versão ${update.versionName}…"
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(this@SplashActivity, update) { p -> runOnUiThread { progress.progress = p } }
                }
                if (apk != null) {
                    pendingApk = apk
                    tryInstall()
                    return@launch
                }
            }
            progress.progress = 100
            goNext()
        }
    }

    private fun tryInstall() {
        val apk = pendingApk ?: return
        if (!Updater.canInstall(this)) {
            waitingPermission = true
            status.text = "Permita \"Instalar apps desconhecidos\" para o GS IPTV e volte"
            Updater.openInstallPermission(this)
            return
        }
        status.text = "Instalando atualização…"
        installerOpened = true
        Updater.install(this, apk)
    }

    override fun onResume() {
        super.onResume()
        if (waitingPermission) {
            waitingPermission = false
            if (Updater.canInstall(this)) tryInstall() else goNext()
        } else if (installerOpened) {
            // Voltou do instalador sem atualizar: segue com a versão atual.
            goNext()
        }
    }

    private fun goNext() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun animateLogo(word: View) {
        val ring = findViewById<View>(R.id.ring)
        val badge = findViewById<View>(R.id.badge)
        val play = findViewById<View>(R.id.play)

        ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
            duration = 3200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
        badge.scaleX = 0.2f; badge.scaleY = 0.2f; badge.rotation = -25f; badge.alpha = 0f
        badge.animate().scaleX(1f).scaleY(1f).rotation(0f).alpha(1f)
            .setDuration(900).setInterpolator(OvershootInterpolator(1.6f)).start()

        play.translationX = -40f; play.scaleX = 0f; play.scaleY = 0f
        play.animate().translationX(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(600).setDuration(600).setInterpolator(OvershootInterpolator(2f)).start()

        word.alpha = 0f; word.translationY = 30f
        word.animate().alpha(1f).translationY(0f).setStartDelay(800).setDuration(800).start()
    }
}
