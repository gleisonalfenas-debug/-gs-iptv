package com.gs.iptv

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/** Animações das capas: "câmera" lenta (aproxima e desliza), brilho e pulsar. */
object Motion {
    private const val TAG_KB = 0x6b62_0001
    private const val TAG_PULSE = 0x6b62_0002

    /** Movimento de câmera lento sobre a imagem (efeito trailer). */
    fun kenBurns(v: View, slow: Boolean = false) {
        stopKenBurns(v)
        val dur = if (slow) 14_000L else 7_000L
        val dx = v.width * 0.05f
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.14f).apply { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE },
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.14f).apply { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE },
                ObjectAnimator.ofFloat(v, View.TRANSLATION_X, -dx, dx).apply { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE },
            )
            duration = dur
            interpolator = AccelerateDecelerateInterpolator()
        }
        v.setTag(TAG_KB, set)
        set.start()
    }

    fun stopKenBurns(v: View) {
        (v.getTag(TAG_KB) as? Animator)?.cancel()
        v.setTag(TAG_KB, null)
        v.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(250).start()
    }

    /** Brilho que atravessa a capa uma vez (ao ganhar o foco). */
    fun shine(frame: FrameLayout) {
        if (frame.width == 0) return
        val w = (frame.width * 0.35f).toInt()
        val glow = View(frame.context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, Color.argb(110, 255, 255, 255), Color.TRANSPARENT))
            rotation = 18f
        }
        frame.addView(glow, FrameLayout.LayoutParams(w, (frame.height * 1.6f).toInt()).apply { topMargin = -(frame.height * 0.3f).toInt() })
        glow.translationX = -w.toFloat() * 1.5f
        glow.animate().translationX(frame.width + w.toFloat()).setDuration(750).setInterpolator(LinearInterpolator())
            .withEndAction { (glow.parent as? ViewGroup)?.removeView(glow) }.start()
    }

    /** Pulsar suave (escudos dos times, etiqueta AO VIVO). */
    fun pulse(v: View, strong: Boolean = false) {
        stopPulse(v)
        val to = if (strong) 1.18f else 1.1f
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, to).apply { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE },
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, to).apply { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE },
            )
            duration = 650
        }
        v.setTag(TAG_PULSE, set)
        set.start()
    }

    fun blink(v: View) {
        stopPulse(v)
        val a = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.35f).apply {
            duration = 700; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        }
        v.setTag(TAG_PULSE, a)
        a.start()
    }

    fun stopPulse(v: View) {
        (v.getTag(TAG_PULSE) as? Animator)?.cancel()
        v.setTag(TAG_PULSE, null)
        v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
    }
}
