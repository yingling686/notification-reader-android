package cn.tingxun.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class DiagnosticStage(val title: String) {
    PROCESS_STARTED("应用进程创建"), LISTENER_CREATED("监听服务已创建"), LISTENER_DESTROYED("监听服务已销毁"),
    LISTENER_REBIND_RETURNED("重连调用已返回，等待系统连接"), LISTENER_REBIND_FAILED("通知监听重连调用失败"),
    LISTENER_CONNECT_TIMEOUT("通知已授权但监听连接超时"), LISTENER_ACCESS_MISSING("通知访问未授权"), LISTENER_STATUS_FAILED("系统监听状态查询失败"),
    LISTENER_PROBE_POSTED("已发送固定自检通知"), LISTENER_PROBE_RECEIVED("已收到固定自检通知回调"),
    LISTENER_PROBE_TIMEOUT("固定自检通知回调超时"), LISTENER_PROBE_FAILED("通知自检发生系统错误"),
    SESSION_PERMISSION_LOST("通知授权撤销，听读会话停止"),
    START_REQUEST("请求启动自动听读"), START_ACK("自动听读启动已确认"), AUTO_DISPATCH("新通知自动调度"),
    MEDIA_PLAY_COMMAND("收到系统播放指令"), MEDIA_PAUSE_COMMAND("收到系统暂停指令"),
    SESSION_RESUMED("自动听读恢复"), STATE_CHANGED("自动听读状态变化"),
    UI_VISIBLE("界面进入前台"), UI_HIDDEN("界面退到后台"),
    SERVICE_FOREGROUND("音频前台服务已注册"), SERVICE_FAILED("前台注册失败"), SERVICE_STOPPED("服务已停止"),
    SERVICE_HEARTBEAT("后台会话存活"), SCREEN_CHANGED("屏幕状态变化"),
    HOURLY_DUE("整点报时到期"), HOURLY_DONE("整点报时完成"), HOURLY_SKIPPED("本次整点报时已跳过"), HOURLY_ALARM("收到系统报时提醒"),
    LISTENER_CONNECTED("通知监听已连接"), LISTENER_DISCONNECTED("通知监听已断开"), LISTENER_REBIND("请求重连通知监听"),
    NOTICE_RECEIVED("收到通知回调"), NOTICE_STORED("通知已入库"), NOTICE_DUPLICATE("重复通知已跳过"), NOTICE_FAILED("通知解析失败"),
    NOTICE_RECOVERED("活动通知补偿接收"), NOTICE_FILTERED("通知已按规则过滤"), LISTENER_QUERY_FAILED("活动通知核对失败"),
    TTS_INIT("初始化系统语音"), TTS_READY("系统语音已就绪"), TTS_BEGIN("开始系统合成"), TTS_DONE("系统合成已完成"),
    CLOUD_BEGIN("开始云端合成"), CLOUD_DONE("云端合成已完成"),
    FOCUS_REQUEST("申请后台音频焦点"), FOCUS_RESULT("音频焦点申请结果"), FOCUS_CHANGE("音频焦点变化"),
    PLAYER_PREPARE("播放器准备中"), PLAYER_PREPARED("播放器已准备"), ROUTE_CONFIRMED("实际耳机输出已确认"),
    PLAY_DONE("本段播放完成"), RETRY("暂缓后自动重试"), BLOCKED("播报被阻止"), WORK_CANCELLED("任务已取消"), SESSION_PAUSED("用户暂停"), SESSION_STARTED("听读会话启动")
}

/** Restricted event schema: callers cannot pass notification text, exception messages or secrets. */
class Diagnostics(private val context: Context) {
    private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val power = context.getSystemService(PowerManager::class.java)
    private val events = ArrayDeque<JSONObject>()
    @Volatile var foregroundType = 0
        private set
    val latest = MutableStateFlow("尚无后台诊断记录")
    var snapshot: (() -> DiagnosticSnapshot)? = null
    var startSource = CommandSource.SYSTEM
    internal var runtime: (() -> JSONObject)? = null
    internal var systemSpeech: (() -> JSONObject)? = null
    private val processInstance = java.util.UUID.randomUUID().toString()
    private val captureCounts = mutableMapOf<CaptureSignal, Long>()
    @Synchronized fun capture(signal: CaptureSignal) { captureCounts[signal] = (captureCounts[signal] ?: 0) + 1 }
    init {
        runCatching {
            val saved = JSONArray(prefs.getString("events", "[]"))
            for (i in 0 until saved.length()) {
                val row = saved.getJSONObject(i)
                // Reconstruct only the fixed schema, even if an older preferences file contains extra fields.
                val stage = DiagnosticStage.valueOf(row.getString("stage"))
                val clean = JSONObject().put("stage", stage.name)
                listOf("time", "elapsed", "id", "value", "importance", "fgs").forEach { clean.put(it, row.optLong(it)) }
                listOf("screenOn", "idle", "batteryExempt").forEach { clean.put(it, row.optBoolean(it)) }
                row.optString("reason").takeIf { it.isNotEmpty() }?.let { clean.put("reason", PlaybackBlockReason.valueOf(it).name) }
                row.optString("sessionState").takeIf { it.isNotEmpty() }?.let { clean.put("sessionState", ListeningState.valueOf(it).name) }
                listOf("source", "startSource").forEach { key -> row.optString(key).takeIf { it.isNotEmpty() }?.let { clean.put(key, CommandSource.valueOf(it).name) } }
                listOf("listening", "paused", "quietActive").forEach { key -> if (row.has(key)) clean.put(key, row.optBoolean(key)) }
                val types = row.optJSONArray("routes") ?: JSONArray()
                clean.put("routes", JSONArray((0 until types.length()).map { types.optInt(it) }))
                events.addLast(clean)
            }
        }
        trim(); persist()
    }
    @Synchronized fun foreground(type: Int) { foregroundType = type; record(DiagnosticStage.SERVICE_FOREGROUND, value = type) }
    @Synchronized fun record(stage: DiagnosticStage, id: Long = 0, value: Int = 0, reason: PlaybackBlockReason? = null, routes: List<Int> = emptyList(), source: CommandSource = CommandSource.SYSTEM) {
        val process = ActivityManager.RunningAppProcessInfo()
        runCatching { ActivityManager.getMyMemoryState(process) }
        events.addLast(JSONObject().put("time", System.currentTimeMillis()).put("elapsed", SystemClock.elapsedRealtime())
            .put("stage", stage.name).put("id", id).put("value", value).put("importance", process.importance)
            .put("fgs", foregroundType).put("screenOn", power.isInteractive).put("idle", power.isDeviceIdleMode)
            .put("batteryExempt", power.isIgnoringBatteryOptimizations(context.packageName)).put("routes", JSONArray(routes))
            .put("source", source.name).put("startSource", startSource.name)
            .apply { snapshot?.invoke()?.let { s -> put("sessionState", s.state.name).put("listening", s.listening).put("paused", s.paused).put("quietActive", s.quietActive) } }
            .apply { if (reason != null) put("reason", reason.name) })
        trim(); persist()
        latest.value = stage.title + (reason?.let { "：${it.title}" } ?: "")
    }
    @Synchronized fun stopped() { foregroundType = 0; record(DiagnosticStage.SERVICE_STOPPED) }
    private fun trim() {
        val cutoff = System.currentTimeMillis() - 86_400_000L
        events.removeAll { it.optLong("time") < cutoff }
        while (events.size > 200) events.removeFirst()
    }
    private fun persist() { prefs.edit().putString("events", JSONArray(events.toList()).toString()).apply() }
    @Synchronized fun export(): String {
        trim(); persist()
        return JSONObject().put("version", BuildConfig.VERSION_NAME)
            .put("exportedAt", System.currentTimeMillis()).put("processId", android.os.Process.myPid()).put("processInstance", processInstance)
            .put("environment", DeviceEvidence.environment(context)).put("processExits", DeviceEvidence.exits(context)).put("sdk", Build.VERSION.SDK_INT)
            .put("session", snapshot?.invoke()?.let { JSONObject().put("state", it.state.name).put("running", it.listening).put("paused", it.paused).put("quietActive", it.quietActive) } ?: JSONObject())
            .put("android", Build.VERSION.RELEASE).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("ttsEngine", Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH))
            .put("runtime", runtime?.invoke() ?: JSONObject())
            .put("systemSpeech", systemSpeech?.invoke() ?: JSONObject())
            .put("captureCounterScope", "current_process")
            .put("capture", JSONObject(captureCounts.mapKeys { it.key.name }))
            .put("events", JSONArray(events.toList())).toString(2)
    }
    fun exportBrief() = CompactDiagnostics.format(export())
    @Synchronized fun clear() { events.clear(); captureCounts.clear(); prefs.edit().clear().apply(); latest.value = "诊断记录已清空" }
}
