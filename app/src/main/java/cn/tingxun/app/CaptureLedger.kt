package cn.tingxun.app

/** In-memory hints only. Room remains the durable source of deduplication and playback state. */
class CaptureLedger(private val limit: Int = 256) {
    private val seen = LinkedHashMap<String, String>()
    fun observe(key: String, stamp: String): Boolean {
        val changed = seen[key] != stamp
        seen.remove(key); seen[key] = stamp
        while (seen.size > limit) seen.remove(seen.keys.first())
        return changed
    }
    fun clear() { seen.clear() }
}

enum class CaptureSignal {
    CALLBACK, SELECTED_CALLBACK, NOT_SELECTED, SESSION_OFF, BEFORE_SESSION, ONGOING, GROUP_SUMMARY,
    EMPTY_CONTENT, OLD_MESSAGES, SNAPSHOT_OK, SNAPSHOT_FAILED, RECOVERED
}
