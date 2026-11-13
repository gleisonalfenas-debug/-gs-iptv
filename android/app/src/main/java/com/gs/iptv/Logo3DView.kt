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
 * Logo GS IPTV em 3D: uma "moeda" grossa girando no próprio eixo (frente vermelha/branca,
 * verso dourado), anéis inclinados em órbita, sombra e o texto IPTV.
 * Tudo se ajusta ao tamanho da view. [compact] = versão pequena para o canto do canal.
 */
class Logo3DView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    var compact = false
        set(v) { field = v; applyMode() }

    private val ringOuter = ImageView(ctx).apply { setImageResource(R.drawable.ic_ring) }
    private val ringInner = ImageView(ctx).apply { setImageResource(R.drawable.ic_ring); alpha = 0.6f }
    private val shadow = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#66000000")) }
    }
    private val edges = List(10) { View(ctx) }
    private val front = FrameLayout(ctx)
    private val back = FrameLayout(ctx)
    private val frontText = TextView(ctx)
    private val backText = TextView(ctx)
    private val frontPlay = ImageView(ctx).apply { setImageResource(R.drawable.ic_play_gold) }
    private val backPlay = ImageView(ctx).apply { setImageResource(R.drawable.ic_play_gold) }
    private val label = TextView(ctx).apply {
        text = "IPTV"
        setTextColor(Color.parseColor("#F0C45C"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.45f
        gravity = Gravity.CENTER
    }
    private var animator: ValueAnimator? = null
    private var thick = 2f

    init {
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(ringOuter); addView(ringInner); addView(shadow)
        edges.forEach { addView(it) }
        listOf(front to (frontText to frontPlay), back to (backText to backPlay)).forEach { (face, parts) ->
            face.addView(parts.first); face.addView(parts.second); addView(face)
        }
        frontText.text = "GS"; backText.text = "GS"
        frontText.setTextColor(Color.parseColor("#C8161F")); backText.setTextColor(Color.WHITE)
        listOf(frontText, backText).forEach { it.setTypeface(it.typeface, android.graphics.Typeface.BOLD); it.letterSpacing = -0.06f }
        addView(label)
        (edges + front + back + ringOuter + ringInner).forEach { it.setLayerType(LAYER_TYPE_HARDWARE, null) }
        ringOuter.rotationX = 74f
        ringInner.rotationX = 74f
    }

    private fun applyMode() {
        val v = if (compact) View.GONE else View.VISIBLE
        ringOuter.visibility = v; ringInner.visibility = v; shadow.visibility = v; label.visibility = v
        requestLayout()
    }

    private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Calcula todos os tamanhos a partir da largura da view. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0) return
        val u = minOf(w, h) / 600f                       // unidade: 1/600 do tamanho
        val coin = if (compact) (minOf(w, h) * 0.86f).toInt() else (210 * u).toInt()
        val cu = coin / 210f
        val coinTop = if (compact) (h - coin) / 2 else ((h - coin) / 2 - 30 * u).toInt()
        fun place(v: View, width: Int, height: Int, top: Int) {
            v.layoutParams = LayoutParams(width, height, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = top }
        }
        place(ringOuter, (540 * u).toInt(), (540 * u).toInt(), ((h - 540 * u) / 2 - 30 * u).toInt())
        place(ringInner, (430 * u).toInt(), (430 * u).toInt(), ((h - 430 * u) / 2 - 30 * u).toInt())
        place(shadow, (220 * u).toInt(), (36 * u).toInt(), coinTop + coin + (30 * u).toInt())
        edges.forEach { e -> place(e, coin, coin, coinTop); e.background = rounded(Color.parseColor("#7A0D16"), 52 * cu) }
        listOf(front, back).forEach { place(it, coin, coin, coinTop) }
        front.background = rounded(Color.WHITE, 52 * cu, (6 * cu).toInt().coerceAtLeast(1), Color.parseColor("#F0C45C"))
        back.background = rounded(Color.parseColor("#E0A515"), 52 * cu, (6 * cu).toInt().coerceAtLeast(1), Color.WHITE)
        listOf(frontText, backText).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, 120 * cu)
            it.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { marginEnd = (26 * cu).toInt() }
        }
        listOf(frontPlay, backPlay).forEach {
            it.layoutParams = LayoutParams((40 * cu).toInt(), (48 * cu).toInt(), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = (16 * cu).toInt() }
        }
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 64 * u)
        place(label, w, LayoutParams.WRAP_CONTENT, coinTop + coin + (70 * u).toInt())
        val camera = (if (compact) 4000 else 9000) * resources.displayMetrics.density
        (edges + front + back + ringOuter + ringInner).forEach { it.cameraDistance = camera }
        thick = 2f * cu * resources.displayMetrics.density
        post { requestLayout() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = if (compact) 6_000 else 9_000
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
        edges.forEachIndexed { i, e -> e.rotationY = angle; e.translationX = -(i + 1) * thick * s }
        front.rotationY = angle
        back.rotationY = angle + 180f
        front.visibility = if (c >= 0) View.VISIBLE else View.INVISIBLE
        back.visibility = if (c < 0) View.VISIBLE else View.INVISIBLE
        back.translationX = -edges.size * thick * s
        if (!compact) {
            ringOuter.rotation = angle * 0.5f
            ringInner.rotation = -angle * 0.8f
            shadow.scaleX = 0.35f + 0.65f * abs(c)
            shadow.alpha = 0.5f + 0.5f * abs(c)
        }
    }
}
