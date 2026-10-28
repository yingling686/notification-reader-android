package cn.tingxun.app

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class HourAnnouncement(val slot: String, val hour: Int, val text: String)

object HourlyPolicy {
    fun slot(now: ZonedDateTime): String = now.truncatedTo(ChronoUnit.HOURS).toOffsetDateTime().toString()
    fun next(now: ZonedDateTime): ZonedDateTime = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
    fun fresh(item: HourAnnouncement, now: ZonedDateTime) = now.minute == 0 && slot(now) == item.slot
    fun text(hour: Int): String {
        require(hour in 0..23)
        val digits = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        val value = when { hour < 10 -> digits[hour]; hour == 10 -> "十"; hour < 20 -> "十" + digits[hour % 10]; hour == 20 -> "二十"; else -> "二十" + digits[hour % 10] }
        return "现在是${value}点整。"
    }
}

/** Mark before dispatch, so competing timer/alarm callbacks cannot report the same hour twice. */
class HourlyTracker(private val read: () -> String, private val write: (String) -> Unit) {
    fun baseline(now: ZonedDateTime) { write(HourlyPolicy.slot(now)) }
    fun take(now: ZonedDateTime): HourAnnouncement? {
        val slot = HourlyPolicy.slot(now)
        if (now.minute != 0 || read() == slot) return null
        write(slot)
        return HourAnnouncement(slot, now.hour, HourlyPolicy.text(now.hour))
    }
}
