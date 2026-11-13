package com.gs.iptv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Logo GS em 3D para o fundo da tela: uma "moeda" grossa girando no próprio eixo
 * (frente vermelha/branca, verso dourado), com dois anéis inclinados em órbita
 * e uma sombra que acompanha o giro.
 */
class Logo3DView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    private val coin = dp(210)
    private val ringOuter = ImageView(ctx).apply { setImageResource(R.drawable.ic_ring) }
    private val ringInner = ImageView(ctx).apply { setImageResource(R.drawable.ic_ring); alpha = 0.6f }
    private val shadow = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#66000000")) }
    }
    private val edges = List(10) { edgeLayer() }   // espessura da moeda
    private val front = face(front = true)
    private val back = face(front = false)
    private var animator: ValueAnimator? = null

    init {
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(ringOuter, LayoutParams(dp(540), dp(540), Gravity.CENTER))
        addView(ringInner, LayoutParams(dp(430), dp(430), Gravity.CENTER))
        addView(shadow, LayoutParams(dp(220), dp(36), Gravity.CENTER).apply { topMargin = dp(300) })
        edges.forEach { addView(it, LayoutParams(coin, coin, Gravity.CENTER)) }
        addView(back, LayoutParams(coin, coin, Gravity.CENTER))
        addView(front, LayoutParams(coin, coin, Gravity.CENTER))

        val camera = 9000 * resources.displayMetrics.density
        (edges + front + back).forEach { it.cameraDistance = camera; it.setLayerType(LAYER_TYPE_HARDWARE, null) }
        ringOuter.rotationX = 74f
        ringInner.rotationX = 74f
        ringOuter.cameraDistance = camera
        ringInner.cameraDistance = camera
        ringOuter.setLayerType(LAYER_TYPE_HARDWARE, null)
        ringInner.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun rounded(color: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(52).toFloat()
        setColor(color)
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun face(front: Boolean): FrameLayout = FrameLayout(context).apply {
        background = if (front) rounded(Color.WHITE, dp(6), Color.parseColor("#F0C45C"))
        else rounded(Color.parseColor("#E0A515"), dp(6), Color.WHITE)
        addView(TextView(context).apply {
            text = "GS"
            setTextColor(if (front) Color.parseColor("#C8161F") else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = -0.06f
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { marginEnd = dp(26) })
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_play_gold) },
            LayoutParams(dp(40), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
    }

    private fun edgeLayer() = View(context).apply { background = rounded(Color.parseColor("#7A0D16")) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 9_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { apply3D(it.animatedValue as Float) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun apply3D(angle: Float) {
        val rad = Math.toRadians(angle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val thick = dp(2).toFloat()
        // Espessura: camadas escuras deslocadas para o lado contrário do giro
        edges.forEachIndexed { i, e ->
            e.rotationY = angle
            e.translationX = -(i + 1) * thick * s
        }
        front.rotationY = angle
        back.rotationY = angle + 180f
        // Mostra a frente ou o verso conforme o lado virado para a tela
        front.visibility = if (c >= 0) View.VISIBLE else View.INVISIBLE
        back.visibility = if (c < 0) View.VISIBLE else View.INVISIBLE
        back.translationX = -edges.size * thick * s
        // Anéis em órbita e sombra
        ringOuter.rotation = angle * 0.5f
        ringInner.rotation = -angle * 0.8f
        shadow.scaleX = 0.35f + 0.65f * abs(c)
        shadow.alpha = 0.5f + 0.5f * abs(c)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
