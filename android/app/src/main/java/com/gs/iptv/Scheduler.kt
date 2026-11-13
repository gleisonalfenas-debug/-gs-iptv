package com.gs.iptv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Um agendamento: abrir [channelName] 5 minutos antes de [startMs]. */
data class Schedule(
    val id: String,
    val title: String,
    val channelName: String,
    val channelUrl: String,
    val startMs: Long,
) {
    val openAt get() = startMs - Scheduler.EARLY_MS
}

/**
 * Agendamentos: guarda na TV, cria um alarme e abre o canal 5 minutos antes do jogo.
 * Com o app aberto o canal abre na hora; com o app fechado depende da permissão
 * "Sobrepor a outros apps" (algumas TVs bloqueiam abrir apps sozinhos).
 */
object Scheduler {
    const val EARLY_MS = 5 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gs_agenda", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Schedule> {
        val arr = JSONArray(prefs(ctx).getString("list", "[]"))
        val now = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Schedule(o.getString("id"), o.getString("title"), o.getString("name"), o.getString("url"), o.getLong("start"))
        }.filter { it.startMs + 3 * 3600_000L > now } // remove os que já passaram há mais de 3h
    }

    private fun save(ctx: Context, list: List<Schedule>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("name", it.channelName).put("url", it.channelUrl).put("start", it.startMs))
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun find(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun add(ctx: Context, s: Schedule) {
        save(ctx, all(ctx).filterNot { it.id == s.id } + s)
        arm(ctx, s)
        Diag.log("Agendado: ${s.title} em ${s.channelName}")
    }

    fun remove(ctx: Context, id: String) {
        all(ctx).firstOrNull { it.id == id }?.let { cancelAlarm(ctx, it) }
        save(ctx, all(ctx).filterNot { it.id == id })
    }

    private fun markFired(ctx: Context, id: String) = prefs(ctx).edit().putBoolean("fired_$id", true).apply()
    private fun fired(ctx: Context, id: String) = prefs(ctx).getBoolean("fired_$id", false)

    /** Agendamento que já deve abrir agora (usado com o app aberto). */
    fun due(ctx: Context): Schedule? {
        val now = System.currentTimeMillis()
        return all(ctx).firstOrNull { now >= it.openAt && now < it.startMs + 2 * 3600_000L && !fired(ctx, it.id) }
    }

    fun open(ctx: Context, s: Schedule) {
        markFired(ctx, s.id)
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("url", s.channelUrl)
                .putExtra("name", s.channelName)
                .putExtra("title", s.title)
        )
    }

    private fun pending(ctx: Context, s: Schedule) = PendingIntent.getBroadcast(
        ctx, s.id.hashCode(),
        Intent(ctx, AlarmReceiver::class.java).putExtra("id", s.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun arm(ctx: Context, s: Schedule) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = maxOf(s.openAt, System.currentTimeMillis() + 5_000)
        val pi = pending(ctx, s)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(ctx: Context, s: Schedule) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, s))
    }

    fun rearmAll(ctx: Context) = all(ctx).forEach { arm(ctx, it) }
}

/** Alarme do agendamento: abre o canal. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val s = Scheduler.find(ctx, intent.getStringExtra("id") ?: return) ?: return
        try { Scheduler.open(ctx, s) } catch (e: Exception) { Diag.log("Não consegui abrir o agendamento: ${e.message}") }
    }
}

/** Depois que a TV liga, recria os alarmes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rearmAll(ctx)
    }
}
