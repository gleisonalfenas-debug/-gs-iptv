package com.gs.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
private val hhmm get() = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Iniciais do nome (para quando não há logo). */
fun initialsOf(name: String) = name.split(' ', '-', '|').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
    .take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "TV" }

/** Cor fixa e bonita para cada nome (fundo das iniciais). */
fun colorOf(name: String): Int {
    val palette = intArrayOf(0xFF1F6FD1.toInt(), 0xFF0F8A5F.toInt(), 0xFFC8161F.toInt(), 0xFF7A2BC8.toInt(),
        0xFFE0A515.toInt(), 0xFF0AA6A6.toInt(), 0xFFD94F9B.toInt(), 0xFF3A5A8A.toInt())
    return palette[(name.hashCode() and 0x7fffffff) % palette.size]
}

/** Lista de canais ao vivo com o programa atual, barra de progresso e o próximo. */
class LiveRowAdapter(
    private val ctx: Context,
    private val onFocus: (Item) -> Unit,
    private val onClick: (Int) -> Unit,
    private val onLong: (Item) -> Unit,
) : RecyclerView.Adapter<LiveRowAdapter.VH>() {
    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var showLogos = true
    var favorites: Set<String> = emptySet()
    private var rv: RecyclerView? = null
    override fun onAttachedToRecyclerView(r: RecyclerView) { rv = r }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val logo: ImageView = v.findViewById(R.id.logo)
        val initials: TextView = v.findViewById(R.id.initials)
        val name: TextView = v.findViewById(R.id.name)
        val time: TextView = v.findViewById(R.id.time)
        val prog: TextView = v.findViewById(R.id.prog)
        val bar: ProgressBar = v.findViewById(R.id.bar)
        val next: TextView = v.findViewById(R.id.next)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_live_row, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.num.text = if (it.number > 0) it.number.toString() else ""
        h.name.text = (if (favorites.contains(it.url)) "★ " else "") + it.name
        h.initials.text = initialsOf(it.name)
        (h.initials.parent as View).background = GradientDrawable().apply { cornerRadius = ctx.dp(10).toFloat(); setColor(colorOf(it.name)) }
        if (showLogos && it.logo.isNotEmpty()) { h.logo.visibility = View.VISIBLE; h.logo.load(it.logo) { size(120, 120) } }
        else { h.logo.visibility = View.GONE; h.logo.setImageDrawable(null) }
        bindEpg(h, Epg.cached(it), it)
        h.itemView.setOnFocusChangeListener { _, has -> if (has) { val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onFocus(items[p]) } }
        h.itemView.setOnClickListener { _ -> val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onClick(p) }
        h.itemView.setOnLongClickListener { _ -> val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onLong(items[p]); true }
    }

    private fun bindEpg(h: VH, progs: List<Prog>?, item: Item) {
        val cur = Epg.current(progs)
        val nxt = Epg.next(progs)
        if (cur != null) {
            h.prog.text = cur.title
            h.time.text = "${hhmm.format(Date(cur.start))}–${hhmm.format(Date(cur.end))}"
            h.bar.visibility = View.VISIBLE
            h.bar.progress = (((System.currentTimeMillis() - cur.start) * 1000) / (cur.end - cur.start).coerceAtLeast(1)).toInt()
        } else {
            h.prog.text = item.group
            h.time.text = ""
            h.bar.visibility = View.GONE
        }
        h.next.visibility = if (nxt != null) View.VISIBLE else View.GONE
        h.next.text = nxt?.let { "Depois: ${hhmm.format(Date(it.start))}  ${it.title}" } ?: ""
    }
}

/** Estrelas em texto: ★★★★☆ (meia estrela arredonda para cima a partir de 0,5). */
fun starText(stars: Float): String {
    val full = (stars + 0.25f).toInt().coerceIn(0, 5)
    return "★".repeat(full) + "☆".repeat(5 - full)
}

/** Linha de estrelas do item: nota do servidor e, se houver, a sua nota. */
fun starLine(ctx: Context, it: Item): String {
    val mine = Store.userStars(ctx, it.url)
    return when {
        mine > 0 -> "${starText(mine.toFloat())}  sua nota"
        it.rating > 0f -> "${starText(it.rating)}  ${String.format(Locale("pt", "BR"), "%.1f", it.rating)}"
        else -> it.group
    }
}

/** Grade de capas (filmes, séries) com estrelas; segurar OK = dar estrelas. */
class GridAdapter(
    private val showImages: () -> Boolean,
    private val onLong: (Item) -> Unit = {},
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<GridAdapter.VH>() {
    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val placeholder: TextView = v.findViewById(R.id.placeholder)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val frame: View = v.findViewById(R.id.frame)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_grid_poster, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.title.text = it.name
        h.meta.text = if (it.kind == Kind.LIVE) it.group else starLine(h.itemView.context, it)
        h.meta.setTextColor(if (Store.userStars(h.itemView.context, it.url) > 0 || it.rating > 0f) Color.parseColor("#F0C45C") else Color.parseColor("#8B8F9C"))
        h.placeholder.text = it.name
        h.frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(colorOf(it.name), Color.parseColor("#15171D"))).apply { cornerRadius = 12 * h.itemView.resources.displayMetrics.density }
        if (showImages() && it.logo.isNotEmpty()) { h.poster.visibility = View.VISIBLE; h.poster.load(it.logo) { size(300, 450) } }
        else { h.poster.visibility = View.GONE; h.poster.setImageDrawable(null) }
        h.frame.clipToOutline = true
        h.frame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        h.itemView.setOnFocusChangeListener { v, has ->
            h.frame.foreground?.state = if (has) intArrayOf(android.R.attr.state_focused) else intArrayOf()
            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(110).start()
            // capa animada: câmera lenta + brilho
            if (has && h.poster.visibility == View.VISIBLE && h.poster.drawable != null) { Motion.kenBurns(h.poster); Motion.shine(h.frame as FrameLayout) }
            else Motion.stopKenBurns(h.poster)
        }
        h.itemView.setOnClickListener { _ -> val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onClick(p) }
        h.itemView.setOnLongClickListener { _ -> val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onLong(items[p]); true }
    }
}

/** Filtros em pílula (categorias, temporadas, dias). Ao passar o foco, escolhe. */
class ChipAdapter(
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ChipAdapter.VH>() {
    var items: List<String> = emptyList()
        set(v) { field = v; selected = 0; notifyDataSetChanged() }
    var selected = 0
        private set
    private var rv: RecyclerView? = null
    override fun onAttachedToRecyclerView(r: RecyclerView) { rv = r }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val c = p.context
        val tv = TextView(c).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = c.dp(10) }
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(c.dp(18), c.dp(9), c.dp(18), c.dp(9))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isFocusable = true
            maxLines = 1
        }
        return VH(tv)
    }

    override fun getItemCount() = items.size

    fun select(i: Int) {
        val old = selected; selected = i
        rv?.findViewHolderForAdapterPosition(old)?.let { style(it as VH, old) }
        rv?.findViewHolderForAdapterPosition(i)?.let { style(it as VH, i) }
    }

    private fun style(h: VH, pos: Int) {
        val on = pos == selected
        h.text.isActivated = on
        h.text.setTextColor(if (on || h.text.isFocused) Color.parseColor("#111318") else Color.parseColor("#D0D3DB"))
        h.text.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        h.text.text = items[pos]
        style(h, pos)
        h.text.setOnFocusChangeListener { _, has ->
            val p = h.bindingAdapterPosition
            if (has && p != RecyclerView.NO_POSITION && p != selected) { select(p); onPick(p) }
            if (p != RecyclerView.NO_POSITION) style(h, p)
        }
    }
}

/** Cartões do "Continuar assistindo": capa larga, barra de progresso e tempo que falta. */
class ContinueAdapter(
    private val entries: List<Watch.Entry>,
    private val showImages: Boolean,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<ContinueAdapter.VH>() {
    class VH(val root: LinearLayout, val img: ImageView, val bar: ProgressBar, val title: TextView, val sub: TextView, val frame: FrameLayout) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val c = p.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(c.dp(104), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = c.dp(10) }
            isFocusable = true
        }
        val frame = FrameLayout(c).apply { foreground = c.getDrawable(R.drawable.bg_focus_ring) }
        val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressDrawable = c.getDrawable(R.drawable.progress_red)
        }
        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(3), Gravity.BOTTOM))
        val title = TextView(c).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTypeface(null, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, c.dp(5), 0, 0) }
        val sub = TextView(c).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(Color.parseColor("#8B8F9C")) }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(58)))
        root.addView(title); root.addView(sub)
        return VH(root, img, bar, title, sub, frame)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val e = entries[pos]
        h.title.text = e.item.name
        h.sub.text = "Faltam ${e.leftMin} min"
        h.bar.progress = (e.fraction * 1000).toInt()
        h.frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colorOf(e.item.name), Color.parseColor("#15171D")))
            .apply { cornerRadius = 12 * h.root.resources.displayMetrics.density }
        if (showImages && e.item.logo.isNotEmpty()) h.img.load(e.item.logo) { size(500, 280) } else h.img.setImageDrawable(null)
        h.frame.clipToOutline = true
        h.frame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        h.root.setOnFocusChangeListener { _, has ->
            h.frame.foreground?.state = if (has) intArrayOf(android.R.attr.state_focused) else intArrayOf()
            if (has && showImages) { Motion.kenBurns(h.img); Motion.shine(h.frame) } else Motion.stopKenBurns(h.img)
        }
        h.root.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}

/** Canais favoritos em ícones grandes. Também serve para os cartões coloridos do Kids. */
class BigTileAdapter(
    private val items: List<Item>,
    private val showImages: Boolean,
    private val kids: Boolean,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<BigTileAdapter.VH>() {
    class VH(val root: LinearLayout, val box: FrameLayout, val img: ImageView, val ini: TextView, val name: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)

    private val kidColors = listOf(
        intArrayOf(0xFFFF9A3D.toInt(), 0xFFFF5A6E.toInt()), intArrayOf(0xFF3A8DFF.toInt(), 0xFF2BC27A.toInt()),
        intArrayOf(0xFF9B5BFF.toInt(), 0xFFFF6FC1.toInt()), intArrayOf(0xFF2BC27A.toInt(), 0xFFFFC94A.toInt()),
        intArrayOf(0xFFFFC94A.toInt(), 0xFFFF9A3D.toInt()), intArrayOf(0xFF0AA6A6.toInt(), 0xFF3A8DFF.toInt()),
    )

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val c = p.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (kids) Gravity.START else Gravity.CENTER_HORIZONTAL
            layoutParams = if (kids) RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(c.dp(10), c.dp(10), c.dp(10), c.dp(10)) }
            else RecyclerView.LayoutParams(c.dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
            isFocusable = true
        }
        val box = FrameLayout(c).apply { foreground = c.getDrawable(R.drawable.bg_focus_ring) }
        val img = ImageView(c).apply { scaleType = if (kids) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER; if (!kids) setPadding(c.dp(10), c.dp(10), c.dp(10), c.dp(10)) }
        val ini = TextView(c).apply { gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (kids) 22f else 20f); setTypeface(null, android.graphics.Typeface.BOLD) }
        box.addView(ini, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val name = TextView(c).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, if (kids) 18f else 13f); maxLines = 1; setPadding(0, c.dp(8), 0, 0); if (kids) setTypeface(null, android.graphics.Typeface.BOLD) else gravity = Gravity.CENTER }
        val sub = TextView(c).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.parseColor("#C9C2E6")) }
        root.addView(box, if (kids) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(170)) else LinearLayout.LayoutParams(c.dp(76), c.dp(76)))
        root.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (kids) root.addView(sub)
        return VH(root, box, img, ini, name, sub)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        val d = h.root.resources.displayMetrics.density
        h.name.text = it.name
        h.sub.text = when (it.kind) { Kind.LIVE -> "Canal ao vivo"; Kind.MOVIE -> "Filme"; Kind.SERIES -> "Série" }
        h.ini.text = initialsOf(it.name)
        h.box.background = if (kids) GradientDrawable(GradientDrawable.Orientation.TL_BR, kidColors[pos % kidColors.size]).apply { cornerRadius = 24 * d }
        else GradientDrawable().apply { cornerRadius = 18 * d; setColor(colorOf(it.name)) }
        h.box.clipToOutline = true
        h.box.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        if (showImages && it.logo.isNotEmpty()) { h.img.visibility = View.VISIBLE; h.img.load(it.logo) { size(300, 300) } }
        else { h.img.visibility = View.GONE; h.img.setImageDrawable(null) }
        h.root.setOnFocusChangeListener { v, has ->
            h.box.foreground?.state = if (has) intArrayOf(android.R.attr.state_focused) else intArrayOf()
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(110).start()
            if (has && kids) {
                if (h.img.visibility == View.VISIBLE) Motion.kenBurns(h.img) else Motion.pulse(h.ini)
                Motion.shine(h.box)
            } else { Motion.stopKenBurns(h.img); Motion.stopPulse(h.ini) }
        }
        h.root.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}
