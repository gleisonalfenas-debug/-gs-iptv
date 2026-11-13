package com.gs.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Uma fileira da tela Início. */
class HomeRow(val title: String, val items: List<Item>, val posters: Boolean)

/** Fileira horizontal: nas pontas o foco para (não pula para outra fileira). */
class HRow @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : RecyclerView(ctx, attrs) {
    override fun focusSearch(focused: View, direction: Int): View? {
        val item = findContainingItemView(focused) ?: return super.focusSearch(focused, direction)
        val pos = getChildAdapterPosition(item)
        val count = adapter?.itemCount ?: 0
        if (direction == View.FOCUS_RIGHT && pos == count - 1) return focused
        if (direction == View.FOCUS_LEFT && pos == 0) return focused
        return super.focusSearch(focused, direction)
    }
}

/** Fileiras empilhadas (vertical), cada uma com seus itens na horizontal. */
class HomeAdapter(
    private val lite: () -> Boolean,
    private val onFocus: (Item) -> Unit,
    private val onClick: (HomeRow, Int) -> Unit,
) : RecyclerView.Adapter<HomeAdapter.VH>() {
    var rows: List<HomeRow> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val root: LinearLayout, val title: TextView, val list: HRow) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val ctx = p.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt())
            clipChildren = false; clipToPadding = false
        }
        val title = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((4 * d).toInt(), 0, 0, (8 * d).toInt())
        }
        val list = HRow(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            clipChildren = false; clipToPadding = false
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
        }
        root.addView(title)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return VH(root, title, list)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val row = rows[pos]
        h.title.text = row.title
        h.list.adapter = TileAdapter(row, lite(), onFocus) { i -> onClick(row, i) }
    }
}

/** Itens de uma fileira: canal (logo + nome) ou capa (filme/série). */
class TileAdapter(
    private val row: HomeRow,
    private val lite: Boolean,
    private val onFocus: (Item) -> Unit,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<TileAdapter.VH>() {

    class VH(val root: FrameLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = row.items.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val ctx = p.context
        val d = ctx.resources.displayMetrics.density
        val poster = row.posters && !lite
        val w = ((if (poster) 128 else 180) * d).toInt()
        val hgt = ((if (poster) 192 else 100) * d).toInt()
        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(w, hgt).apply { marginEnd = (14 * d).toInt() }
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(Color.parseColor("#2A2F3A"))
            }
            foreground = GradientDrawable().apply { cornerRadius = 10 * d; setColor(Color.TRANSPARENT) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val image = ImageView(ctx).apply {
            scaleType = if (poster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            val pad = if (poster) 0 else (18 * d).toInt()
            setPadding(pad, pad, pad, if (poster) 0 else (34 * d).toInt())
        }
        val label = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (poster) 13f else 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            gravity = if (poster) Gravity.START else Gravity.CENTER
            setPadding((8 * d).toInt(), (18 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            if (poster) setBackgroundResource(R.drawable.bg_poster_label)
        }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            if (poster || lite) Gravity.BOTTOM else Gravity.BOTTOM))
        return VH(root, image, label)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = row.items[pos]
        h.label.text = if (it.number > 0 && !row.posters) "${it.number}  ${it.name}" else it.name
        if (!lite && it.logo.isNotEmpty()) h.image.load(it.logo) { size(300, 450) } else h.image.setImageDrawable(null)
        if (lite) h.label.gravity = Gravity.CENTER
        h.root.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
            v.elevation = if (has) 12f else 0f
            (v.foreground as? GradientDrawable)?.setStroke(if (has) (4 * v.resources.displayMetrics.density).toInt() else 0, Color.WHITE)
            if (has) { val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onFocus(row.items[p]) }
        }
        h.root.setOnClickListener { _ ->
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
    }
}
