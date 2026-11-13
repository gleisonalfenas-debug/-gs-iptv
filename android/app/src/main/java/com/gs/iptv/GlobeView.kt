package com.gs.iptv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Globo terrestre 3D girando, desenhado pelo app: oceano, continentes estilizados,
 * linhas de latitude/longitude, brilho, atmosfera e um anel dourado em órbita.
 * [compact] = versão pequena (canto dos canais), sem anel nem texto.
 */
class GlobeView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var compact = false
        set(v) { field = v; invalidate() }

    private var spin = 0f            // rotação em graus
    private var animator: ValueAnimator? = null

    // Continentes estilizados (latitude, longitude)
    private val lands: List<List<Pair<Float, Float>>> = listOf(
        listOf(70f to -160f, 72f to -100f, 62f to -62f, 47f to -53f, 30f to -80f, 18f to -92f, 20f to -106f, 33f to -118f, 50f to -128f, 60f to -145f),
        listOf(12f to -75f, 6f to -52f, -6f to -35f, -22f to -41f, -38f to -58f, -54f to -68f, -42f to -74f, -18f to -71f, -2f to -81f),
        listOf(71f to 25f, 60f to 32f, 46f to 40f, 35f to 30f, 31f to 33f, 13f to 44f, 11f to 51f, -4f to 40f, -26f to 34f, -34f to 20f, -18f to 12f, 4f to 9f, 5f to -8f, 15f to -17f, 30f to -10f, 37f to -6f, 43f to -9f, 48f to -4f, 54f to 8f, 62f to 6f),
        listOf(76f to 60f, 76f to 140f, 64f to 176f, 50f to 142f, 36f to 126f, 22f to 110f, 9f to 104f, 21f to 92f, 9f to 78f, 24f to 66f, 38f to 52f, 46f to 42f, 62f to 32f),
        listOf(-11f to 131f, -14f to 144f, -27f to 153f, -38f to 146f, -34f to 116f, -21f to 114f),
        listOf(80f to -62f, 82f to -30f, 70f to -21f, 60f to -44f, 70f to -54f),
    )

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLand = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(70, 255, 255, 255) }
    private val pRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pShine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val path = Path()
    private var cx = 0f; private var cy = 0f; private var r = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        r = min(w, h) * if (compact) 0.46f else 0.30f
        cy = if (compact) h / 2f else h * 0.42f
        pFill.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
            intArrayOf(Color.parseColor("#5EC8F2"), Color.parseColor("#1565C0"), Color.parseColor("#06224A")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        pLand.shader = LinearGradient(cx, cy - r, cx, cy + r,
            Color.parseColor("#7BD67F"), Color.parseColor("#2E7D32"), Shader.TileMode.CLAMP)
        pShine.shader = RadialGradient(cx - r * 0.4f, cy - r * 0.45f, r * 0.7f,
            Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        pGlow.shader = RadialGradient(cx, cy, r * 1.35f,
            intArrayOf(Color.argb(90, 120, 200, 255), Color.argb(0, 120, 200, 255)), floatArrayOf(0.7f, 1f), Shader.TileMode.CLAMP)
        pRim.strokeWidth = r * 0.04f
        pRim.color = Color.argb(150, 160, 220, 255)
        pGrid.strokeWidth = maxOf(1f, r * 0.008f)
        pRing.strokeWidth = r * 0.06f
        pText.textSize = r * 0.42f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = if (compact) 12_000 else 20_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    /** Projeta latitude/longitude na esfera girada. Retorna x, y e profundidade (z > 0 = frente). */
    private fun project(lat: Float, lon: Float, out: FloatArray) {
        val la = Math.toRadians(lat.toDouble())
        val lo = Math.toRadians((lon + spin).toDouble())
        val tilt = Math.toRadians(-18.0) // eixo inclinado como a Terra
        val x = cos(la) * sin(lo)
        var y = sin(la)
        var z = cos(la) * cos(lo)
        val y2 = y * cos(tilt) - z * sin(tilt)
        val z2 = y * sin(tilt) + z * cos(tilt)
        y = y2; z = z2
        out[0] = cx + (r * x).toFloat()
        out[1] = cy - (r * y).toFloat()
        out[2] = z.toFloat()
    }

    private val pt = FloatArray(3)

    /** Linha na esfera: só a parte da frente; pontos de trás vão para a borda. */
    private fun addPoint(lat: Float, lon: Float, first: Boolean, clampToEdge: Boolean): Boolean {
        project(lat, lon, pt)
        var x = pt[0]; var y = pt[1]
        if (pt[2] < 0) {
            if (!clampToEdge) return false
            val dx = x - cx; val dy = y - cy
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            x = cx + dx / d * r; y = cy + dy / d * r
        }
        if (first) path.moveTo(x, y) else path.lineTo(x, y)
        return true
    }

    private fun drawRing(c: Canvas, front: Boolean) {
        if (compact) return
        val rect = RectF(cx - r * 1.55f, cy - r * 0.36f, cx + r * 1.55f, cy + r * 0.36f)
        c.save()
        c.rotate(-16f, cx, cy)
        pRing.shader = LinearGradient(rect.left, cy, rect.right, cy,
            Color.parseColor("#F0C45C"), Color.parseColor("#FFF1C4"), Shader.TileMode.MIRROR)
        pRing.alpha = if (front) 255 else 120
        c.drawArc(rect, if (front) 0f else 180f, 180f, false, pRing)
        // brilho correndo no anel
        val a = Math.toRadians((spin * 2 % 360).toDouble())
        val bx = cx + (r * 1.55f * cos(a)).toFloat(); val by = cy + (r * 0.36f * sin(a)).toFloat()
        if ((sin(a) > 0) == front) {
            pRing.shader = null; pRing.color = Color.WHITE; pRing.alpha = 230
            c.drawCircle(bx, by, r * 0.05f, pRing.apply { style = Paint.Style.FILL })
            pRing.style = Paint.Style.STROKE
        }
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        if (r <= 0) return
        c.drawCircle(cx, cy, r * 1.35f, pGlow)
        drawRing(c, front = false)
        c.drawCircle(cx, cy, r, pFill)

        // linhas de longitude e latitude
        for (lon in -180 until 180 step 30) {
            path.reset(); var first = true
            for (lat in -90..90 step 6) {
                if (addPoint(lat.toFloat(), lon.toFloat(), first, false)) first = false else first = true
            }
            c.drawPath(path, pGrid)
        }
        for (lat in -60..60 step 30) {
            path.reset(); var first = true
            for (lon in -180..180 step 6) {
                if (addPoint(lat.toFloat(), lon.toFloat(), first, false)) first = false else first = true
            }
            c.drawPath(path, pGrid)
        }

        // continentes
        for (land in lands) {
            path.reset()
            var anyFront = false
            land.forEachIndexed { i, (la, lo) ->
                project(la, lo, pt); if (pt[2] > 0) anyFront = true
                addPoint(la, lo, i == 0, true)
            }
            if (!anyFront) continue
            path.close()
            c.drawPath(path, pLand)
        }

        c.drawCircle(cx, cy, r, pShine)
        c.drawCircle(cx, cy, r, pRim)
        drawRing(c, front = true)

        if (!compact) {
            pText.shader = null
            pText.color = Color.WHITE
            val ty = cy + r * 1.75f
            val gs = "GS "
            val iptv = "IPTV"
            val wGs = pText.measureText(gs); val wIp = pText.measureText(iptv)
            val start = cx - (wGs + wIp) / 2
            pText.textAlign = Paint.Align.LEFT
            c.drawText(gs, start, ty, pText)
            pText.color = Color.parseColor("#F0C45C")
            c.drawText(iptv, start + wGs, ty, pText)
            pText.textAlign = Paint.Align.CENTER
        }
    }
}
