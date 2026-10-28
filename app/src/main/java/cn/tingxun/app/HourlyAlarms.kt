package cn.tingxun.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class HourlyAlarms(private val g: Graph) {
    private val manager = g.context.getSystemService(AlarmManager::class.java)
    private val prefs = g.context.getSharedPreferences("hourly_clock", Context.MODE_PRIVATE)
    val tracker = HourlyTracker({ prefs.getString("lastSlot", "").orEmpty() }, { prefs.edit().putString("lastSlot", it).apply() })
    fun exactAvailable() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    private fun operation() = PendingIntent.getBroadcast(g.context, 1800, Intent(g.context, HourlyAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule() {
        if (!g.engine.running.value || g.engine.paused.value || !g.settings.config.value.hourly) { cancel(); return }
        val at = HourlyPolicy.next(g.clock()).toInstant().toEpochMilli()
        // Permission revocation may race the check. Fall back without crashing or bypassing the user's choice.
        runCatching {
            if (exactAvailable()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation())
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation())
        }.onFailure { runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation()) } }
    }
    fun cancel() { manager.cancel(operation()) }
    fun reset() { cancel(); prefs.edit().clear().apply() }
}

class HourlyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val g = context.graph
        g.diagnostics.record(DiagnosticStage.HOURLY_ALARM)
        if (!g.engine.running.value || !g.settings.config.value.hourly) return
        val lease = g.wake.acquire(10_000)
        try { g.hourly.schedule(); g.engine.pollHourly() } finally { lease.close() }
    }
}
