package cn.tingxun.app

import org.json.JSONArray
import org.json.JSONObject

/** Fits the observed 7,000-character transfer limit; never alters the full local history. */
object CompactDiagnostics {
    const val MAX_CHARS = 6000
    fun format(full: String): String {
        val source = JSONObject(full)
        val result = JSONObject().put("format", "compact-v1").put("eventsOrder", "newest_first")
        val all = source.getJSONArray("events")
        val fields = listOf("stage", "time", "id", "value", "reason", "sessionState")
        fun event(row: JSONObject) = JSONObject().apply { fields.forEach { if (row.has(it)) put(it, row.get(it)) } }
        for (i in all.length() - 1 downTo 0) {
            val row = all.getJSONObject(i)
            if (row.has("reason")) { result.put("recentBlockingEvent", event(row)); break }
        }
        listOf("version", "exportedAt", "session", "systemSpeech", "runtime", "environment", "capture", "captureCounterScope",
            "processId", "processInstance", "processExits", "sdk", "android", "manufacturer", "model", "ttsEngine").forEach {
            if (source.has(it)) result.put(it, source.get(it))
        }
        val recent = JSONArray()
        for (i in all.length() - 1 downTo (all.length() - 24).coerceAtLeast(0)) recent.put(event(all.getJSONObject(i)))
        result.put("storedEventCount", all.length()).put("events", recent)
        while (result.toString().length > MAX_CHARS && recent.length() > 0) recent.remove(recent.length() - 1)
        if (result.toString().length > MAX_CHARS) result.optJSONObject("systemSpeech")?.remove("voiceSample")
        if (result.toString().length > MAX_CHARS) result.remove("processExits")
        check(result.toString().length <= MAX_CHARS) { "精简诊断超出大小限制" }
        return result.toString()
    }
}
