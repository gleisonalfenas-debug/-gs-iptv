package com.gs.iptv

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista feita para o controle remoto:
 * - no fim da lista o foco PARA no último item (não pula para outro lugar)
 * - nas bordas o foco vai exatamente para onde definimos (onUp / onLeft / onRight)
 */
class TvList @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : RecyclerView(ctx, attrs) {
    var onUp: (() -> Unit)? = null
    var onLeft: (() -> Unit)? = null
    var onRight: (() -> Unit)? = null

    override fun focusSearch(focused: View, direction: Int): View? {
        val item = findContainingItemView(focused) ?: return super.focusSearch(focused, direction)
        val pos = getChildAdapterPosition(item)
        val count = adapter?.itemCount ?: 0
        if (pos == NO_POSITION || count == 0) return super.focusSearch(focused, direction)
        val span = (layoutManager as? GridLayoutManager)?.spanCount ?: 1
        val lastRow = (count - 1) / span
        when (direction) {
            View.FOCUS_DOWN -> if (pos / span == lastRow) return focused
            View.FOCUS_UP -> if (pos < span) { onUp?.let { it(); return focused } }
            View.FOCUS_LEFT -> if (pos % span == 0) { onLeft?.let { it() }; return focused }
            View.FOCUS_RIGHT -> if (span == 1 || pos % span == span - 1 || pos == count - 1) { onRight?.let { it() }; return focused }
        }
        return super.focusSearch(focused, direction)
    }

    /** Coloca o foco no item da posição, rolando a lista se precisar. */
    fun focusPosition(pos: Int) {
        val count = adapter?.itemCount ?: 0
        if (count == 0) { requestFocus(); return }
        val p = pos.coerceIn(0, count - 1)
        val holder = findViewHolderForAdapterPosition(p)
        if (holder != null && holder.itemView.isShown && holder.itemView.requestFocus()) return
        (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 120)
        post { findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
    }
}
