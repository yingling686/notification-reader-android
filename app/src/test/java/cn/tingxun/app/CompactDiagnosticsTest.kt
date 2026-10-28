package cn.tingxun.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactDiagnosticsTest {
    @Test fun recentFailureAndNewestEventsSurviveUnderTransferLimitWithoutBodyFields() {
        val events = JSONArray()
        repeat(200) { i -> events.put(JSONObject().put("stage", "BLOCKED").put("time", i).put("value", i)
            .put("reason", if (i == 199) "TTS_FAILED" else "HEADPHONES_MISSING")
            .put("body", "private-body").put("key", "private-key")) }
        val source = JSONObject().put("version", "test").put("events", events).put("secret", "private-root")
        val output = CompactDiagnostics.format(source.toString())
        assertTrue(output.length <= 6000)
        val parsed = JSONObject(output)
        assertEquals("TTS_FAILED", parsed.getJSONObject("recentBlockingEvent").getString("reason"))
        assertEquals(199, parsed.getJSONArray("events").getJSONObject(0).getInt("time"))
        assertEquals(200, parsed.getInt("storedEventCount"))
        assertFalse(output.contains("private-body")); assertFalse(output.contains("private-key")); assertFalse(output.contains("private-root"))
    }
    @Test fun largeVoiceSamplesAreRemovedBeforeReportCanExceedLimit() {
        val source = JSONObject().put("version", "test").put("events", JSONArray())
            .put("systemSpeech", JSONObject().put("offlineChineseCount", 0).put("voiceSample", JSONArray().put("字".repeat(8000))))
        val output = CompactDiagnostics.format(source.toString())
        assertTrue(output.length <= 6000)
        assertEquals(0, JSONObject(output).getJSONObject("systemSpeech").getInt("offlineChineseCount"))
    }
}
