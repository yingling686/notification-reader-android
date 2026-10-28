package cn.tingxun.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class PolicyTest {
    private fun row(id: Long, body: String = "请确认明天开会", app: String = "wechat", title: String = "工作群") =
        NoticeRow(id = id, fingerprint = "$id", sourceKey = "key", app = app, label = "微信", title = title, body = body, receivedAt = 0, instant = false)
    @Test fun overnightQuietHoursHaveCorrectBoundaries() {
        val c = Config(quiet = true)
        assertTrue(Policy.quiet(c, LocalTime.of(22, 0))); assertTrue(Policy.quiet(c, LocalTime.of(7, 59)))
        assertFalse(Policy.quiet(c, LocalTime.of(8, 0))); assertFalse(Policy.quiet(c, LocalTime.of(21, 59)))
    }
    @Test fun daytimeQuietHoursAndDisabledSetting() {
        val c = Config(quiet = true, quietStart = 600, quietEnd = 660)
        assertTrue(Policy.quiet(c, LocalTime.of(10, 15))); assertFalse(Policy.quiet(c, LocalTime.of(11, 0)))
        assertFalse(Policy.quiet(c.copy(quiet = false), LocalTime.of(10, 15)))
    }
    @Test fun matchingIsScopedToApplication() {
        val c = Config(mode = ReadMode.MIXED, rules = listOf(PriorityRule("wechat", "张三")))
        assertTrue(Policy.instant(c, "wechat", "张三", "你好"))
        assertFalse(Policy.instant(c, "sms", "张三", "你好"))
        assertFalse(Policy.instant(c, "wechat", "李四", "你好"))
    }
    @Test fun modesAndVoicesAreIndependent() {
        for (mode in ReadMode.entries) for (voice in VoiceEngine.entries) {
            val c = Config(mode = mode, voice = voice)
            assertEquals(mode == ReadMode.INSTANT, Policy.instant(c, "app", "", ""))
            assertEquals(mode == ReadMode.SUMMARY || mode == ReadMode.MIXED, Policy.needsSummary(c))
        }
    }
    @Test fun plainNotificationUpdatesDeduplicateButChangedBodyDoesNot() {
        assertEquals(Policy.fingerprint("same", "张三", "你好", null), Policy.fingerprint("same", "张三", "你好", null))
        assertNotEquals(Policy.fingerprint("same", "张三", "你好", null), Policy.fingerprint("same", "张三", "你好啊", null))
    }
    @Test fun messagingIdentityIncludesTimeAndSender() {
        assertNotEquals(Policy.fingerprint("k", "张三", "收到", 1), Policy.fingerprint("k", "张三", "收到", 2))
        assertNotEquals(Policy.fingerprint("k", "张三", "收到", 1), Policy.fingerprint("k", "李四", "收到", 1))
    }
    @Test fun completedAndUnterminatedThinkingAreNeverSpoken() {
        assertEquals("结果", Policy.cleanAnswer("<think>不该读出的内容</think>结果"))
        assertEquals("", Policy.cleanAnswer("<think>未结束的思考"))
        assertEquals("结果", Policy.cleanAnswer("<THINK>秘密\n内容</THINK>结果"))
    }
    @Test fun speechSegmentsPreserveTextAndSurrogatePairs() {
        val text = "这是一个完整句子。".repeat(400) + "😀".repeat(700)
        val parts = Policy.segments(text)
        assertEquals(text, parts.joinToString("")); assertTrue(parts.all { it.length <= 1000 })
        assertTrue(parts.none { Character.isHighSurrogate(it.last()) })
    }
    @Test fun inputsRespectBothBatchLimitsWithoutDroppingNextRow() {
        assertEquals(50, Policy.batch((1L..60L).map { row(it) }).size)
        val source = (1L..10L).map { row(it, "字".repeat(2800)) }
        val first = Policy.batch(source)
        assertTrue(first.sumOf { Policy.wireText(it).length } <= 12000)
        assertEquals(4, first.size); assertEquals(6, source.drop(first.size).size)
    }
    @Test fun localReadingGroupsConversationsAndTruncatesOnlySpokenText() {
        val rows = listOf(row(1, "甲"), row(2, "乙"), row(3, "长".repeat(4000), title = "另一群"))
        val text = Policy.localReading(rows)
        assertEquals(1, Regex("工作群").findAll(text).count())
        assertTrue(text.contains("甲。乙")); assertTrue(text.contains("请在应用中查看全文"))
        assertEquals(4000, rows.last().body.length)
    }
}
