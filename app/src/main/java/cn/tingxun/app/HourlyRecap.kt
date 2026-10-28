package cn.tingxun.app

import java.time.OffsetDateTime

data class HourlySelection(val rows: List<NoticeRow>, val input: String, val truncated: Boolean)

object HourlyRecap {
    /** A half-open elapsed hour ending at the scheduled boundary, never the delayed callback time. */
    fun window(item: HourAnnouncement): Pair<Long, Long> {
        val end = OffsetDateTime.parse(item.slot).toInstant()
        return end.minusSeconds(3600).toEpochMilli() to end.toEpochMilli()
    }
    fun select(newestFirst: List<NoticeRow>): HourlySelection {
        val prefix = "上一小时通知资料：\n"
        val selected = mutableListOf<NoticeRow>()
        var length = prefix.length
        for (row in newestFirst) {
            val size = Policy.wireText(row).length
            if (selected.size == 50 || length + size > 12000) break
            selected += row; length += size
        }
        val rows = selected.asReversed()
        return HourlySelection(rows, prefix + rows.joinToString("") { Policy.wireText(it) }, selected.size < newestFirst.size)
    }
    fun spoken(result: SummaryResult, truncated: Boolean): String {
        val text = Policy.cleanAnswer(result.text).take(500).trim()
        if (!result.important || text.isBlank()) return ""
        return (if (truncated) "上一小时通知较多，以下仅概括最近部分通知。" else "上一小时通知重点：") + text
    }
}
