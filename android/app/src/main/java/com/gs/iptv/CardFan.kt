package com.gs.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Uma capa de filme/série (usada nas cartas em leque e na espiral). */
class PosterCard(context: Context) : FrameLayout(context) {
    private val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 3
        setPadding(dp(12), dp(26), dp(12), dp(12))
        setBackgroundResource(R.drawable.bg_poster_label)
    }
    val ring = View(context)
    private var bound: Item? = null
    private var bindedOnce = false

    init {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            colors = intArrayOf(Color.parseColor("#3A3A44"), Color.parseColor("#15151A"))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        addView(poster, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ring.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(4), Color.WHITE)
            setColor(Color.TRANSPARENT)
        }
        ring.visibility = View.GONE
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(item: Item?) {
        if (bindedOnce && item === bound) return
        bindedOnce = true
        bound = item
        if (item == null) { visibility = View.INVISIBLE; return }
        visibility = View.VISIBLE
        title.text = item.name
        if (item.logo.isNotEmpty()) poster.load(item.logo) { size(300, 450) } else poster.setImageDrawable(null)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Base com a navegação pelo controle (esquerda/direita/OK/cima). */
abstract class PosterStage(ctx: Context, attrs: AttributeSet?) : FrameLayout(ctx, attrs) {
    var onPick: ((Item, Int) -> Unit)? = null
    var onChange: ((Item?) -> Unit)? = null
    var onUp: (() -> Unit)? = null
    var onLeftEdge: (() -> Unit)? = null

    var items: List<Item> = emptyList()
        protected set
    var index = 0
        protected set

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { _, _ -> updateRing() }
    }

    fun showItems(list: List<Item>) {
        items = list
        index = 0
        layoutCards(0, 0)
        onChange?.invoke(items.getOrNull(0))
    }

    fun current(): Item? = items.getOrNull(index)

    protected abstract fun layoutCards(prevIndex: Int, dir: Int)
    protected abstract fun updateRing()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (index > 0) { val p = index; index--; layoutCards(p, -1); onChange?.invoke(current()) } else onLeftEdge?.invoke()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (index < items.size - 1) { val p = index; index++; layoutCards(p, 1); onChange?.invoke(current()) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { onUp?.invoke(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                current()?.let { onPick?.invoke(it, index) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/**
 * Cartas em leque, encostadas no menu lateral: a carta escolhida fica grande;
 * as próximas vão para a direita inclinadas. No começo da lista não sobra espaço vazio.
 */
class CardFan @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : PosterStage(ctx, attrs) {
    private val before = 2           // quantas cartas anteriores aparecem à esquerda
    private val after = 5            // quantas próximas aparecem à direita
    private val slots = ArrayList<PosterCard>()
    private val cardW = dp(175)
    private val cardH = dp(262)
    private val spacing = dp(135).toFloat()
    private val pad = dp(8).toFloat()

    init {
        clipChildren = true
        clipToPadding = true
        for (i in 0..(before + after)) {
            val c = PosterCard(context)
            addView(c, LayoutParams(cardW, cardH, Gravity.START or Gravity.CENTER_VERTICAL))
            slots.add(c)
        }
    }

    /** Onde fica, na tela, o item [itemPos] quando o escolhido é [at]. */
    private fun xFor(itemPos: Int, at: Int): Float {
        val anchor = pad + min(at, before) * spacing
        val extra = if (itemPos > at) spacing * 0.25f else 0f  // folga para a carta grande
        return anchor + (itemPos - at) * spacing + extra
    }

    override fun layoutCards(prevIndex: Int, dir: Int) {
        for (off in -before..after) {
            val card = slots[off + before]
            val pos = index + off
            card.bind(if (pos >= 0) items.getOrNull(pos) else null)
            val a = abs(off)
            val scale = if (off == 0) 1f else 0.84f - a * 0.05f
            card.translationZ = (20 - a).toFloat() * dp(2)
            card.animate().cancel()
            if (dir != 0) card.translationX = xFor(pos, prevIndex)
            card.animate()
                .translationX(xFor(pos, index))
                .translationY(a * dp(10).toFloat())
                .rotation(off * 6f)
                .scaleX(scale).scaleY(scale)
                .alpha(if (off == 0) 1f else maxOf(0.2f, 0.7f - a * 0.12f))
                .setDuration(if (dir == 0) 0 else 220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        updateRing()
    }

    override fun updateRing() {
        slots.forEachIndexed { i, c -> c.ring.visibility = if (i == before && hasFocus()) View.VISIBLE else View.GONE }
    }
}

/**
 * Espiral de destaques: o filme escolhido fica grande no centro e os outros
 * giram em volta numa espiral, ficando menores e mais distantes.
 */
class SpiralView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : PosterStage(ctx, attrs) {
    private val side = 6
    private val slots = ArrayList<PosterCard>()
    private val cardW = dp(150)
    private val cardH = dp(225)

    init {
        clipChildren = true
        for (i in 0..side * 2) {
            val c = PosterCard(context)
            addView(c, LayoutParams(cardW, cardH, Gravity.CENTER))
            slots.add(c)
        }
    }

    /** Posição na espiral para a distância k do centro: x, y, escala, transparência, inclinação. */
    private fun place(k: Int): FloatArray {
        if (k == 0) return floatArrayOf(0f, 0f, 1.3f, 1f, 0f)
        val a = abs(k)
        val ang = Math.toRadians(90.0 + k * 46.0)         // gira em volta do centro
        val r = dp(150) + (a - 1) * dp(34)                 // e vai se afastando
        val x = (r * cos(ang)).toFloat() * 1.55f            // espiral achatada (perspectiva)
        val y = (r * sin(ang)).toFloat() * 0.62f - dp(10)
        val scale = maxOf(0.35f, 0.78f - (a - 1) * 0.08f)
        val alpha = maxOf(0.12f, 0.8f - (a - 1) * 0.13f)
        return floatArrayOf(x, y, scale, alpha, (k * 4).toFloat())
    }

    override fun layoutCards(prevIndex: Int, dir: Int) {
        for (k in -side..side) {
            val card = slots[k + side]
            val pos = index + k
            card.bind(if (pos >= 0) items.getOrNull(pos) else null)
            val p = place(k)
            card.translationZ = (30 - abs(k) * 4).toFloat()
            card.animate().cancel()
            if (dir != 0) {
                // começa de onde estava na espiral e "gira" até o lugar novo
                val from = place((k + dir).coerceIn(-side, side))
                card.translationX = from[0]; card.translationY = from[1]
                card.scaleX = from[2]; card.scaleY = from[2]; card.alpha = from[3]; card.rotation = from[4]
            }
            card.animate()
                .translationX(p[0]).translationY(p[1])
                .scaleX(p[2]).scaleY(p[2]).alpha(p[3]).rotation(p[4])
                .setDuration(if (dir == 0) 0 else 260)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        updateRing()
    }

    override fun updateRing() {
        slots.forEachIndexed { i, c -> c.ring.visibility = if (i == side && hasFocus()) View.VISIBLE else View.GONE }
    }
}
