package com.gs.iptv

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guia de programação do dia de um canal: passado, AGORA e próximos (com agendamento). */
object Guide {
    fun show(act: Activity, item: Item, onWatch: () -> Unit) {
        if (!Store.isXtream(act) || Epg.streamId(item) == null) {
            Toast.makeText(act, "Esta lista não tem guia de programação", Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(act, "Carregando a programação de hoje…", Toast.LENGTH_SHORT).show()
        Epg.dayGuide(act, item) { list ->
            if (act.isFinishing) return@dayGuide
            if (list.isEmpty()) { Toast.makeText(act, "Sem programação para ${item.name} hoje", Toast.LENGTH_LONG).show(); return@dayGuide }
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val now = System.currentTimeMillis()
            val labels = list.map { (p, _) ->
                val tag = when { now in p.start until p.end -> "  ● AGORA"; p.end <= now -> "  (já passou)"; else -> "" }
                "${fmt.format(Date(p.start))}–${fmt.format(Date(p.end))}   ${p.title}$tag"
            }
            val curIdx = list.indexOfFirst { now in it.first.start until it.first.end }.coerceAtLeast(0)
            val dlg = AlertDialog.Builder(act)
                .setTitle("${item.name} · programação de hoje")
                .setItems(labels.toTypedArray()) { _, i -> onPick(act, item, list[i].first, list[i].second, onWatch) }
                .setNegativeButton("Fechar", null)
                .show()
            dlg.listView?.post { dlg.listView.setSelection(curIdx); dlg.listView.requestFocus() }
        }
    }

    private fun onPick(act: Activity, item: Item, p: Prog, desc: String, onWatch: () -> Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()
        val b = AlertDialog.Builder(act)
            .setTitle(p.title)
            .setMessage("${fmt.format(Date(p.start))} – ${fmt.format(Date(p.end))}\n\n${desc.ifBlank { "Sem descrição." }}")
            .setNegativeButton("Voltar", null)
        when {
            now in p.start until p.end -> b.setPositiveButton("Assistir agora") { _, _ -> onWatch() }
            p.start > now -> b.setPositiveButton("Agendar (abre 5 min antes)") { _, _ ->
                val id = "epg_${item.url.hashCode()}_${p.start}"
                Scheduler.add(act, Schedule(id, p.title, item.name, item.url, p.start))
                Toast.makeText(act, "Agendado! ${item.name} abre às ${fmt.format(Date(p.start - Scheduler.EARLY_MS))}", Toast.LENGTH_LONG).show()
            }
        }
        b.show()
    }
}
