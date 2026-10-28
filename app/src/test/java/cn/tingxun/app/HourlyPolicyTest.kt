package cn.tingxun.app

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class HourlyPolicyTest {
    private val base = ZonedDateTime.parse("2026-10-19T14:59:59+08:00[Asia/Shanghai]")
    @Test fun nextHourUsesLocalCalendarIncludingMidnight() {
        assertEquals(15, HourlyPolicy.next(base).hour)
        assertEquals("2026-10-20", HourlyPolicy.next(base.withHour(23)).toLocalDate().toString())
        assertEquals("现在是零点整。", HourlyPolicy.text(0))
        assertEquals("现在是十五点整。", HourlyPolicy.text(15))
        assertEquals("现在是二十三点整。", HourlyPolicy.text(23))
    }
    @Test fun startupDoesNotReportImmediatelyAndAnHourIsConsumedOnlyOnce() {
        var stored = ""
        val tracker = HourlyTracker({ stored }, { stored = it })
        tracker.baseline(base.withMinute(0))
        assertNull(tracker.take(base.withMinute(0).withSecond(40)))
        val next = HourlyPolicy.next(base)
        assertNotNull(tracker.take(next))
        assertNull(tracker.take(next.plusSeconds(10)))
        assertNull(HourlyTracker({ stored }, { stored = it }).take(next.plusSeconds(15)))
    }
    @Test fun staleHoursAreNotReplayedAfterSuspensionOrClockChanges() {
        val now = HourlyPolicy.next(base)
        val item = HourAnnouncement(HourlyPolicy.slot(now), now.hour, HourlyPolicy.text(now.hour))
        assertTrue(HourlyPolicy.fresh(item, now.plusSeconds(59)))
        assertFalse(HourlyPolicy.fresh(item, now.plusMinutes(1)))
        assertFalse(HourlyPolicy.fresh(item, now.plusHours(1)))
        var stored = ""
        val tracker = HourlyTracker({ stored }, { stored = it })
        assertNull(tracker.take(now.plusMinutes(3)))
    }
    @Test fun newOptionsDefaultOffAndDoNotDependOnModeOrVoice() {
        for (mode in ReadMode.entries) for (voice in VoiceEngine.entries) {
            val c = Config(mode = mode, voice = voice)
            assertFalse(c.hourly); assertFalse(c.onlyScreenOff)
        }
    }
}
