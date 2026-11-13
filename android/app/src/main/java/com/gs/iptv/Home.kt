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
    var onLeftEdge: (() -> Unit)? = null
    var onUp: (() -> Unit)? = null
    var onDown: (() -> Unit)? = null

    override fun focusSearch(focused: View, direction: Int): View? {
        val item = findContainingItemView(focused) ?: return super.focusSearch(focused, direction)
        val pos = getChildAdapterPosition(item)
        val count = adapter?.itemCount ?: 0
        if (direction == View.FOCUS_RIGHT && pos == count - 1) return focused
        if (direction == View.FOCUS_LEFT && pos == 0) { onLeftEdge?.invoke(); return focused }
        if (direction == View.FOCUS_UP) onUp?.let { it(); return focused }
        if (direction == View.FOCUS_DOWN) onDown?.let { it(); return focused }
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

/** Canais em bolinhas (estilo "stories"): logo ou iniciais dentro de um anel colorido. */
class StoryAdapter(
    private val items: List<Item>,
    private val lite: Boolean,
    /** Avisa quando uma bolinha ganha/perde o foco (para a prévia ao vivo dentro dela). */
    private val onFocusItem: (Item, FrameLayout, Boolean) -> Unit = { _, _, _ -> },
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<StoryAdapter.VH>() {

    class VH(val root: LinearLayout, val ring: FrameLayout, val inner: FrameLayout, val logo: ImageView, val initials: TextView, val name: TextView) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val ctx = p.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RecyclerView.LayoutParams((100 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            isFocusable = true
        }
        val ring = FrameLayout(ctx).apply { setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt()) }
        val inner = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2A0A10")) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; val pd = (12 * d).toInt(); setPadding(pd, pd, pd, pd) }
        val initials = TextView(ctx).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
        }
        inner.addView(initials, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inner.addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ring.addView(inner, FrameLayout.LayoutParams((68 * d).toInt(), (68 * d).toInt(), Gravity.CENTER))
        val name = TextView(ctx).apply {
            setTextColor(Color.parseColor("#E6DCDE")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        // anel com tamanho fixo e quadrado: o círculo não deforma
        root.addView(ring, LinearLayout.LayoutParams((76 * d).toInt(), (76 * d).toInt()))
        root.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return VH(root, ring, inner, logo, initials, name)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.name.text = it.name
        h.initials.text = it.name.split(' ').filter { w -> w.isNotBlank() }.take(2).joinToString("") { w -> w.take(1).uppercase() }
        if (!lite && it.logo.isNotEmpty()) { h.logo.visibility = View.VISIBLE; h.logo.load(it.logo) { size(140, 140) } }
        else { h.logo.visibility = View.GONE; h.logo.setImageDrawable(null) }
        // anel colorido nos 3 primeiros (recentes/favoritos), discreto nos outros
        h.ring.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (pos < 3) { gradientType = GradientDrawable.SWEEP_GRADIENT; colors = intArrayOf(Color.parseColor("#F0C45C"), Color.parseColor("#E3162A"), Color.parseColor("#F0C45C")) }
            else setColor(Color.parseColor("#33FFFFFF"))
        }
        h.root.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(120).start()
            h.name.setTextColor(if (has) Color.WHITE else Color.parseColor("#E6DCDE"))
            h.ring.foreground = if (has) GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke((3 * v.resources.displayMetrics.density).toInt(), Color.WHITE) } else null
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onFocusItem(items[p], h.inner, has)
        }
        h.root.setOnClickListener { _ -> val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onClick(p) }
    }
}
