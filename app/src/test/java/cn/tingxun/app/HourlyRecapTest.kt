package cn.tingxun.app

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class HourlyRecapTest {
    private fun row(id: Long, body: String = "通知") = NoticeRow(id, "$id", "key", "app", "应用", "标题", body, id, false)
    @Test fun previousNaturalHourCrossesMidnightWithoutIncludingCurrentHour() {
        val now = ZonedDateTime.parse("2026-10-20T00:00:00+08:00")
        val item = HourAnnouncement(HourlyPolicy.slot(now), 0, "")
        val (start, end) = HourlyRecap.window(item)
        assertEquals(now.minusHours(1).toInstant().toEpochMilli(), start)
        assertEquals(now.toInstant().toEpochMilli(), end)
        assertEquals(3600000, end - start)
    }
    @Test fun limitIncludesNewestButPresentsChronologicallyAndDisclosesTruncation() {
        val s = HourlyRecap.select((51L downTo 1L).map { row(it) })
        assertTrue(s.truncated); assertEquals(50, s.rows.size)
        assertEquals(2L, s.rows.first().id); assertEquals(51L, s.rows.last().id)
        val large = HourlyRecap.select(listOf(row(2, "字".repeat(9000)), row(1, "字".repeat(9000))))
        assertTrue(large.truncated); assertTrue(large.input.length <= 12000); assertEquals(1, large.rows.size)
    }
    @Test fun onlyFinalImportantTextIsSpokenAndDefaultsRemainOff() {
        assertEquals("", HourlyRecap.spoken(SummaryResult(false, "内容"), false))
        assertEquals("", HourlyRecap.spoken(SummaryResult(true, "<think>隐私</think>"), false))
        assertEquals("上一小时通知重点：请回复会议", HourlyRecap.spoken(SummaryResult(true, "<think>不要读</think>请回复会议"), false))
        assertTrue(HourlyRecap.spoken(SummaryResult(true, "摘要"), true).contains("仅概括最近部分"))
        assertFalse(Config().hourlySummary)
    }
}
