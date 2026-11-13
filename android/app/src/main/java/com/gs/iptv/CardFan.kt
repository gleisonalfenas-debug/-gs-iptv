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

/**
 * Cartas em leque: o item do meio fica grande e de frente; os vizinhos ficam
 * inclinados, menores e escurecidos. Usa só 7 cartas na tela, então é leve
 * mesmo com listas de milhares de filmes.
 */
class CardFan @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    var onPick: ((Item, Int) -> Unit)? = null
    var onChange: ((Item?) -> Unit)? = null
    var onUp: (() -> Unit)? = null
    var onLeftEdge: (() -> Unit)? = null

    var items: List<Item> = emptyList()
        private set
    var index = 0
        private set

    private val side = 3
    private val slots = ArrayList<Card>()
    private val cardW = dp(230)
    private val cardH = dp(345)
    private val spacing = dp(175).toFloat()

    private inner class Card : FrameLayout(context) {
        val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 3
            setPadding(dp(14), dp(30), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_poster_label)
        }
        val ring = View(context)
        var bound: Item? = null

        init {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                colors = intArrayOf(Color.parseColor("#3A3A44"), Color.parseColor("#15151A"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(poster, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            ring.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(4), Color.WHITE)
                setColor(Color.TRANSPARENT)
            }
            ring.visibility = View.GONE
            addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun bind(item: Item?) {
            if (item === bound) return
            bound = item
            if (item == null) { visibility = View.INVISIBLE; return }
            visibility = View.VISIBLE
            title.text = item.name
            if (item.logo.isNotEmpty()) poster.load(item.logo) { size(300, 450) } else poster.setImageDrawable(null)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        clipChildren = false
        clipToPadding = false
        for (i in 0 until side * 2 + 1) {
            val c = Card()
            addView(c, LayoutParams(cardW, cardH, Gravity.CENTER))
            slots.add(c)
        }
        setOnFocusChangeListener { _, _ -> updateRing() }
    }

    fun setItems(list: List<Item>) {
        items = list
        index = 0
        layoutCards(0)
        onChange?.invoke(items.getOrNull(0))
    }

    fun current(): Item? = items.getOrNull(index)

    private fun layoutCards(dir: Int) {
        for (off in -side..side) {
            val card = slots[off + side]
            card.bind(items.getOrNull(index + off).takeIf { index + off >= 0 })
            val a = abs(off)
            val targetX = off * spacing
            val scale = if (off == 0) 1f else 0.82f - a * 0.05f
            card.translationZ = (10 - a).toFloat() * dp(2)
            card.animate().cancel()
            if (dir != 0) card.translationX = targetX + dir * spacing
            card.animate()
                .translationX(targetX)
                .translationY(a * dp(16).toFloat())
                .rotation(off * 7f)
                .scaleX(scale).scaleY(scale)
                .alpha(if (off == 0) 1f else maxOf(0.2f, 0.65f - a * 0.15f))
                .setDuration(if (dir == 0) 0 else 220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        updateRing()
    }

    private fun updateRing() {
        slots.forEachIndexed { i, c -> c.ring.visibility = if (i == side && hasFocus()) View.VISIBLE else View.GONE }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (index > 0) { index--; layoutCards(-1); onChange?.invoke(current()) } else onLeftEdge?.invoke()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (index < items.size - 1) { index++; layoutCards(1); onChange?.invoke(current()) }
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
