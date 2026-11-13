package com.gs.iptv

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tela da série: capa e sinopse à esquerda; temporadas e episódios à direita. */
class SeriesActivity : AppCompatActivity() {

    private lateinit var seasonAdapter: ChipAdapter
    private lateinit var epAdapter: EpisodeAdapter
    private lateinit var episodes: TvList
    private lateinit var seasonsRow: HRow
    private var seasons: List<Pair<String, List<Item>>> = emptyList()
    private val lite get() = Store.liteOn(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        val name = intent.getStringExtra("name") ?: ""
        val url = intent.getStringExtra("url") ?: ""
        val logo = intent.getStringExtra("logo") ?: ""
        val group = intent.getStringExtra("group") ?: ""

        findViewById<TextView>(R.id.name).text = name
        findViewById<TextView>(R.id.meta).text = group
        findViewById<TextView>(R.id.coverText).text = name
        findViewById<View>(R.id.coverBox).background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(colorOf(name), 0xFF15171D.toInt())).apply { cornerRadius = 12 * resources.displayMetrics.density }
        val cover = findViewById<ImageView>(R.id.cover)
        if (!lite && logo.isNotEmpty()) cover.load(logo) { size(500, 700) }

        seasonsRow = findViewById(R.id.seasons)
        episodes = findViewById(R.id.episodes)
        seasonAdapter = ChipAdapter { i -> showSeason(i) }
        epAdapter = EpisodeAdapter(!lite) { i -> play(epAdapter.items, i) }
        seasonsRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        seasonsRow.adapter = seasonAdapter
        seasonsRow.onDown = { episodes.focusPosition(0) }
        seasonsRow.onLeftEdge = { findViewById<View>(R.id.resume).requestFocus() }
        episodes.layoutManager = LinearLayoutManager(this)
        episodes.adapter = epAdapter
        episodes.itemAnimator = null
        episodes.onLeft = { findViewById<View>(R.id.resume).requestFocus() }
        episodes.onUp = { seasonsRow.findViewHolderForAdapterPosition(seasonAdapter.selected)?.itemView?.requestFocus() }
        episodes.onRight = {}

        val resume = findViewById<TextView>(R.id.resume)
        resume.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { episodes.focusPosition(0); true } else false
        }

        lifecycleScope.launch {
            resume.text = "Carregando episódios…"
            val info = withContext(Dispatchers.IO) {
                try { Xtream.seriesInfo(this@SeriesActivity, url, name, logo) } catch (e: Exception) { null }
            }
            if (info == null || info.seasons.isEmpty()) {
                resume.text = "Não foi possível abrir a série"
                Toast.makeText(this@SeriesActivity, "Nenhum episódio encontrado", Toast.LENGTH_LONG).show()
                return@launch
            }
            findViewById<TextView>(R.id.meta).text = listOf(info.genre, info.year, "${info.seasons.size} temporada${if (info.seasons.size > 1) "s" else ""}")
                .filter { it.isNotBlank() }.joinToString(" · ")
            findViewById<TextView>(R.id.plot).text = info.plot
            if (!lite && info.cover.isNotEmpty()) cover.load(info.cover) { size(500, 700) }
            if (!lite) {
                val box = findViewById<android.widget.FrameLayout>(R.id.coverBox)
                box.clipToOutline = true
                box.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                cover.post { Motion.kenBurns(cover, slow = true) }
            }
            seasons = info.seasons.map { (s, list) -> "Temporada $s" to list }
            seasonAdapter.items = seasons.map { it.first }
            setupResume(resume)
        }
    }

    /** Botão principal: continua de onde parou (ou começa do primeiro episódio). */
    private fun setupResume(resume: TextView) {
        val watched = Watch.list(this).associateBy { it.item.url }
        var target: Pair<Int, Int> = 0 to 0
        loop@ for ((si, s) in seasons.withIndex()) for ((ei, e) in s.second.withIndex()) {
            if (watched.containsKey(e.url)) { target = si to ei; break@loop }
        }
        val (si, ei) = target
        val ep = seasons.getOrNull(si)?.second?.getOrNull(ei)
        resume.text = if (ep != null && watched.containsKey(ep.url)) "▶  Continuar T${si + 1} · Ep ${ei + 1}" else "▶  Assistir do início"
        resume.setOnClickListener { seasons.getOrNull(si)?.let { play(it.second, ei) } }
        seasonAdapter.select(si)
        showSeason(si)
        resume.requestFocus()
    }

    private fun showSeason(i: Int) {
        val s = seasons.getOrNull(i) ?: return
        epAdapter.progress = Watch.list(this).associate { it.item.url to it.fraction }
        epAdapter.items = s.second
        episodes.scrollToPosition(0)
    }

    private fun play(list: List<Item>, i: Int) {
        Store.playQueue = list
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", i))
    }

    override fun onStart() {
        super.onStart()
        if (seasons.isNotEmpty()) { epAdapter.progress = Watch.list(this).associate { it.item.url to it.fraction }; epAdapter.notifyDataSetChanged() }
    }
}

/** Lista de episódios com miniatura e barra do quanto já foi assistido. */
class EpisodeAdapter(
    private val showImages: Boolean,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var progress: Map<String, Float> = emptyMap()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val bar: ProgressBar = v.findViewById(R.id.bar)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val box: View = v.findViewById(R.id.thumbBox)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.title.text = it.name
        val f = progress[it.url]
        h.meta.text = it.group + if (f != null) " · continuar" else ""
        h.bar.visibility = if (f != null) View.VISIBLE else View.GONE
        h.bar.progress = ((f ?: 0f) * 1000).toInt()
        h.box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colorOf(it.group), 0xFF15171D.toInt()))
            .apply { cornerRadius = 8 * h.itemView.resources.displayMetrics.density }
        if (showImages && it.logo.isNotEmpty()) h.thumb.load(it.logo) { size(300, 170) } else h.thumb.setImageDrawable(null)
        h.itemView.setOnClickListener { _ -> val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onClick(p) }
    }
}
