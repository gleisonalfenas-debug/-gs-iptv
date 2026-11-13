package com.gs.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Lista de categorias (coluna da esquerda). */
class CategoryAdapter(
    private val onFocus: (Int) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {
    private var sel = 0
    var items: List<String> = emptyList()
        set(v) { field = v; sel = 0; notifyDataSetChanged() }
    var selected: Int
        get() = sel
        set(v) { val old = sel; sel = v; notifyItemChanged(old); notifyItemChanged(v) }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false) as TextView)

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        h.text.text = items[pos]
        h.text.isActivated = pos == selected
        h.text.setOnFocusChangeListener { _, has ->
            val p = h.bindingAdapterPosition
            if (has && p != RecyclerView.NO_POSITION && p != selected) { selected = p; onFocus(p) }
        }
        h.text.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) { selected = p; onFocus(p) }
        }
    }
}

/** Lista de canais ao vivo. */
class ChannelAdapter(
    private val onClick: (Item, Int) -> Unit,
    private val onLongClick: (Item, Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var playingUrl: String? = null
        set(v) { field = v; refresh() }

    /** Atualiza os itens sem perder o foco do controle remoto. */
    fun refresh() = notifyItemRangeChanged(0, itemCount)
    var favorites: Set<String> = emptySet()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val fav: ImageView = v.findViewById(R.id.fav)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.name.text = if (it.number > 0) "${it.number}  ${it.name}" else it.name
        h.sub.text = it.group
        h.fav.visibility = if (favorites.contains(it.url)) View.VISIBLE else View.GONE
        h.itemView.isActivated = it.url == playingUrl
        if (it.logo.isNotEmpty()) h.logo.load(it.logo) { size(160, 100) } else h.logo.setImageDrawable(null)
        h.itemView.setOnClickListener { _ ->
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p], p)
        }
        h.itemView.setOnLongClickListener { _ ->
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(items[p], p)
            true
        }
    }
}

/** Grade de capas (filmes, séries, kids, anime). */
class PosterAdapter(
    private val onClick: (Item, Int) -> Unit,
) : RecyclerView.Adapter<PosterAdapter.VH>() {
    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val title: TextView = v.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_poster, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.title.text = it.name
        if (it.logo.isNotEmpty()) h.poster.load(it.logo) { size(300, 450) } else h.poster.setImageDrawable(null)
        h.itemView.setOnClickListener { _ ->
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p], p)
        }
        h.itemView.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
        }
    }
}
