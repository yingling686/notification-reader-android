package cn.tingxun.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.os.PowerManager
import java.time.ZonedDateTime
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TingApp : Application() {
    lateinit var graph: Graph
    override fun onCreate() { super.onCreate(); graph = Graph(this) }
}
val Context.graph: Graph get() = (applicationContext as TingApp).graph

class Graph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val diagnostics = Diagnostics(context)
    val settings = Settings(context)
    val db = TingDatabase.open(context)
    val dao = db.dao()
    val cloud = MiniMaxClient()
    internal var summaryProvider: SummaryProvider = cloud
    internal var speechProvider: SpeechProvider = cloud
    val audio = AudioGate(context, diagnostics)
    internal var output: ReadingOutput = audio
    val wake = ProcessingWake(context)
    private var localDelegate = lazy { LocalSpeech(context, diagnostics) }
    val local get() = localDelegate.value
    fun stopLocal() { if (localDelegate.isInitialized()) local.stop() }
    val localRevision = MutableStateFlow(0)
    internal fun refreshLocal() {
        if (localDelegate.isInitialized()) localDelegate.value.shutdown()
        localDelegate = lazy { LocalSpeech(context, diagnostics) }
    }
    val sessionReady = MutableStateFlow(false)
    internal var clock: () -> ZonedDateTime = { ZonedDateTime.now() }
    internal var screenInteractive: () -> Boolean = { context.getSystemService(PowerManager::class.java).isInteractive }
    internal var mediaSession: MediaSession? = null
    fun renderMedia(state: ListeningState) {
        mediaSession?.apply {
            isActive = state.mediaActive
            val playback = when (state) {
                ListeningState.SYNTHESIZING -> PlaybackState.STATE_BUFFERING
                ListeningState.PLAYING -> PlaybackState.STATE_PLAYING
                ListeningState.PAUSED -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_NONE
            }
            setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                .setState(playback, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state == ListeningState.PLAYING) 1f else 0f).build())
        }
    }
    val engine = ReadingEngine(this)
    val listenerConnected = MutableStateFlow(false)
    val hourly = HourlyAlarms(this)
    val listenerCheck = ListenerCheck(this)
    internal var capture = java.lang.ref.WeakReference<NotificationCapture>(null)
    init {
        settings.beforeChange = { engine.invalidate() }
        context.cacheDir.listFiles()?.filter { it.name.startsWith("speech-") }?.forEach { it.delete() }
        audio.initialize()
        diagnostics.snapshot = { DiagnosticSnapshot(engine.state.value, engine.running.value, engine.paused.value, Policy.quiet(settings.config.value)) }
        diagnostics.systemSpeech = { if (localDelegate.isInitialized()) local.evidence() else org.json.JSONObject().put("initialized", false) }
        diagnostics.runtime = { settings.config.value.let { c -> org.json.JSONObject()
            .put("mode", c.mode.name).put("voice", c.voice.name).put("selectedApps", c.apps.size)
            .put("intervalMinutes", c.interval).put("listenerConnected", listenerConnected.value)
            .put("gateReason", engine.diagnosticGate())
            .put("hourly", c.hourly).put("onlyScreenOff", c.onlyScreenOff).put("hourlySummary", c.hourlySummary) } }
        audio.onAudible = { engine.transition(ListeningState.PLAYING) }
        audio.onUnsafe = { engine.interrupt("耳机或音频状态变化，积压内容请手动补读") }
        scope.launch {
            db.withTransaction { dao.holdPending(); dao.holdBatches(); expire() }
            engine.ready = true
        }
        scope.launch { settings.revision.drop(1).collect { engine.settingsChanged() } }
        diagnostics.record(DiagnosticStage.PROCESS_STARTED)
    }
    suspend fun expire() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        dao.expireNotices(cutoff); dao.expireBatches(cutoff)
    }
}

class ReadingEngine(private val g: Graph) {
    val state = MutableStateFlow(ListeningState.STOPPED)
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val status = MutableStateFlow("尚未开启听读")
    val nextAt = MutableStateFlow(0L)
    var ready = false
    var startedAt = Long.MAX_VALUE
        private set
    private var epoch = 0L
    private var work: Job? = null
    private var maintenance: Job? = null
    private val lock = Mutex()
    private var dueElapsed = Long.MAX_VALUE
    private var hourlyCleanup = 0L
    private var lastQuiet = false
    private var lastDevice = false
    private var lastCall = false
    private var current: BatchRow? = null
    private var dispatchRequested = false
    private var processingBlocked = false
    private var screenDeferredEpoch: Long? = null
    private var pendingHour: HourAnnouncement? = null
    fun transition(next: ListeningState) {
        if (state.value == next) return
        // Publish only after native media state is applied; observers must never see "waiting" with an active player card.
        g.renderMedia(next); state.value = next
        g.diagnostics.record(DiagnosticStage.STATE_CHANGED, reason = if (next == ListeningState.BLOCKED) blockReason() else null, source = CommandSource.AUTOMATIC)
    }
    private fun resting() { transition(ListeningPolicy.resting(running.value, paused.value, processingBlocked || blockReason() != null)) }
    init {
        g.scope.launch {
            while (isActive) {
                delay(1000)
                if (!ready || !running.value) continue
                pollHourly()
                val c = g.settings.config.value
                val quiet = Policy.quiet(c); val device = g.output.connected(); val call = g.output.callActive()
                if ((quiet && !lastQuiet) || (!device && lastDevice) || (call && !lastCall)) interrupt("播报条件变化，积压内容需手动补读")
                lastQuiet = quiet; lastDevice = device; lastCall = call
                if (work?.isActive != true && maintenance?.isActive != true) resting()
                if (SystemClock.elapsedRealtime() > hourlyCleanup && work?.isActive != true && maintenance?.isActive != true) {
                    g.expire(); hourlyCleanup = SystemClock.elapsedRealtime() + 60 * 60_000
                }
                if (!canPlay() || work?.isActive == true || maintenance?.isActive == true) continue
                // Timer is only for scheduled modes and recovery; instant reading is event-driven.
                if (SystemClock.elapsedRealtime() >= dueElapsed) dispatch()
            }
        }
    }
    fun start(restore: Boolean = false) {
        if (running.value) { if (!restore) resume(CommandSource.UI); return }
        val session = g.context.getSharedPreferences("session", Context.MODE_PRIVATE)
        startedAt = System.currentTimeMillis(); paused.value = restore && session.getBoolean("paused", false)
        if (!restore) session.edit().putBoolean("paused", false).apply()
        lastQuiet = Policy.quiet(g.settings.config.value); lastDevice = g.output.connected(); lastCall = g.output.callActive()
        schedule(); status.value = if (paused.value) "听读服务已恢复，保持暂停" else "后台听读已开启；新通知可在息屏时播报"; running.value = true
        g.diagnostics.record(DiagnosticStage.SESSION_STARTED)
        processingBlocked = false; resting()
        if (!restore) g.hourly.tracker.baseline(g.clock())
        g.hourly.schedule()
    }
    fun stop() { running.value = false; startedAt = Long.MAX_VALUE; paused.value = false; interrupt("听读会话已停止"); nextAt.value = 0; g.hourly.cancel() }
    fun pause(source: CommandSource = CommandSource.UI) {
        if (!running.value || paused.value) return
        paused.value = true
        g.hourly.cancel()
        g.context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("paused", true).apply()
        g.diagnostics.record(DiagnosticStage.SESSION_PAUSED, source = source)
        interrupt("已暂停，期间消息待确认补读"); schedule()
    }
    fun resume(source: CommandSource = CommandSource.UI) {
        if (!running.value) return
        if (paused.value) {
            paused.value = false
            // A sleeping paused session must not catch up an hour whose alarm was cancelled.
            g.hourly.tracker.baseline(g.clock()); g.hourly.schedule()
            g.context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("paused", false).apply()
            g.diagnostics.record(DiagnosticStage.SESSION_RESUMED, source = source)
            status.value = "自动听读已恢复；旧积压仍需确认补读"; schedule()
        }
        // Repeated play commands never cancel work or toggle a running session back to paused.
        if (work?.isActive != true) resting()
        dispatch()
    }
    fun togglePause() { if (paused.value) resume() else pause() }
    private fun schedule() {
        val duration = g.settings.config.value.interval * 60_000L
        dueElapsed = SystemClock.elapsedRealtime() + duration; nextAt.value = System.currentTimeMillis() + duration
    }
    fun settingsChanged() {
        interrupt("设置已更新，积压内容请确认后补读")
        g.hourly.tracker.baseline(g.clock()); g.hourly.schedule()
        if (running.value) schedule()
    }
    fun screenChanged() {
        if (!running.value || !g.settings.config.value.onlyScreenOff) return
        if (g.screenInteractive()) {
            // This deferral is automatic, unlike explicit pause, unsafe routes, or quiet hours.
            invalidate(forScreen = true)
            if (!processingBlocked) status.value = "仅息屏播报：亮屏消息保留，息屏后自动处理"
            resting()
        } else {
            if (!processingBlocked) status.value = "已息屏，继续自动听读"
            dispatch()
        }
    }
    internal fun pollHourly() {
        if (!ready || !running.value || !g.settings.config.value.hourly) return
        val item = g.hourly.tracker.take(g.clock()) ?: return
        if (!canPlay()) { g.diagnostics.record(DiagnosticStage.HOURLY_SKIPPED, value = item.hour, reason = blockReason()); return }
        pendingHour = item
        g.diagnostics.record(DiagnosticStage.HOURLY_DUE, value = item.hour)
        dispatch()
    }
    fun listenerReady() { if (running.value && !paused.value) dispatch() }
    private fun blockReason(): PlaybackBlockReason? = when {
        !running.value -> PlaybackBlockReason.SESSION_INACTIVE
        paused.value -> PlaybackBlockReason.USER_PAUSED
        Policy.quiet(g.settings.config.value) -> PlaybackBlockReason.QUIET_HOURS
        g.settings.config.value.onlyScreenOff && g.screenInteractive() -> PlaybackBlockReason.SCREEN_ON
        !g.sessionReady.value -> PlaybackBlockReason.SERVICE_NOT_READY
        !g.output.connected() -> PlaybackBlockReason.HEADPHONES_MISSING
        g.output.callActive() -> PlaybackBlockReason.CALL_ACTIVE
        !g.listenerConnected.value -> PlaybackBlockReason.LISTENER_UNAVAILABLE
        else -> null
    }
    private fun canPlay() = blockReason() == null
    internal fun diagnosticGate() = blockReason()?.name ?: if (processingBlocked) "PROCESSING_FAILED" else "NONE"
    fun invalidate(forScreen: Boolean = false) {
        screenDeferredEpoch = if (forScreen) epoch else null
        pendingHour = null
        epoch++; dispatchRequested = false; work?.cancel(); g.output.stop(); g.stopLocal()
        resting()
        g.diagnostics.record(DiagnosticStage.WORK_CANCELLED)
    }
    fun interrupt(message: String) {
        val previous = work; invalidate(); status.value = message
        val earlier = maintenance
        maintenance = g.scope.launch {
            earlier?.join(); previous?.join()
            lock.withLock { g.db.withTransaction { g.dao.holdPending(); g.dao.holdBatches() } }
        }.also { job -> job.invokeOnCompletion { g.scope.launch { if (dispatchRequested) dispatch() } } }
    }
    private fun launchWork(block: suspend () -> Unit) {
        // Acquire before launching: the notification callback's CPU allowance may end immediately.
        val lease = g.wake.acquire()
        work = g.scope.launch {
            try { lock.withLock {
                try { withTimeout(600_000L) { block() } }
                catch (_: TimeoutCancellationException) { processingBlocked = true; status.value = "处理超时，待手动补读"; g.dao.holdPending(); g.dao.holdBatches() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { processingBlocked = true; status.value = safeError(e); g.dao.holdPending(); g.dao.holdBatches() }
                finally { current = null; resting() }
            } } finally { lease.close() }
        }
        // Also covers cancellation before the coroutine body starts.
        work?.invokeOnCompletion {
            lease.close()
            g.scope.launch { if (dispatchRequested && work?.isActive != true) dispatch() }
        }
    }
    private fun dispatch() {
        if (!ready) return
        blockReason()?.let { resting(); g.diagnostics.record(DiagnosticStage.BLOCKED, reason = it); return }
        if (work?.isActive == true || maintenance?.isActive == true) { dispatchRequested = true; return }
        dispatchRequested = false
        processingBlocked = false
        launchWork {
            // Drain new arrivals in this same awake session, including arrivals during synthesis/playback.
            do {
                val hour = pendingHour.also { pendingHour = null }
                if (hour != null && HourlyPolicy.fresh(hour, g.clock())) playHour(hour)
                else process(SystemClock.elapsedRealtime() >= dueElapsed)
                val more = g.dao.nextBatch() != null || g.dao.pending(true).isNotEmpty() ||
                    pendingHour != null || (SystemClock.elapsedRealtime() >= dueElapsed && g.dao.pending(false).isNotEmpty())
            } while (canPlay() && more)
        }
    }
    private fun control(message: String, action: suspend () -> Unit) {
        interrupt(message)
        val previous = maintenance
        maintenance = g.scope.launch {
            previous?.join(); lock.withLock { action(); processingBlocked = false; resting() }
        }.also { job -> job.invokeOnCompletion { g.scope.launch { if (dispatchRequested) dispatch() } } }
    }
    fun releaseHeld() {
        control("积压消息已加入补读；未播完的段落会重新开始") {
            val c = g.settings.config.value
            for (batch in g.dao.heldBatches()) {
                val sources = g.dao.byIds(batch.ids())
                if (sources.any { it.app !in c.apps } || (batch.summary && !c.summaryConsent)) {
                    g.dao.batchState(batch.id, "skipped")
                    g.dao.state(batch.ids(), "held")
                }
            }
            for (row in g.dao.waiting()) {
                if (row.app !in c.apps) g.dao.state(listOf(row.id), "skipped")
                else g.dao.classify(row.id, Policy.instant(c, row.app, row.title, row.body))
            }
            g.dao.releaseNotices(); g.dao.releaseBatches(); dueElapsed = 0; nextAt.value = System.currentTimeMillis()
            dispatchRequested = true
        }
    }
    fun now() {
        if (!canPlay()) { status.value = "请开启会话、连接耳机并授予通知和电话状态权限；免打扰期间不播报"; return }
        if (work?.isActive == true || maintenance?.isActive == true) { status.value = "已有任务正在处理"; return }
        dueElapsed = 0; dispatch()
    }
    fun clearPending() { control("待播已清空") { g.db.withTransaction { g.dao.clearPending(); g.dao.clearBatches() } } }
    fun skip() {
        val batch = current
        control(if (batch == null) "当前没有播放任务" else "已跳过当前播报") {
            if (batch != null) g.db.withTransaction { g.dao.batchState(batch.id, "skipped"); g.dao.state(batch.ids(), "skipped") }
        }
    }
    fun deleteAll() {
        g.listenerCheck.clear()
        stop()
        control("本地数据与密钥已删除；今日调用计数保留") {
            g.db.withTransaction { g.dao.deleteBatches(); g.dao.deleteNotices() }; g.settings.reset()
            g.context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().apply()
            g.hourly.reset()
            g.diagnostics.clear()
            g.context.cacheDir.listFiles()?.filter { it.name.startsWith("speech-") }?.forEach { it.delete() }
        }
    }
    suspend fun ingest(row: NoticeRow) {
        if (!ready || !running.value || row.receivedAt < startedAt) return
        val arrivalEpoch = epoch
        val heldAtArrival = paused.value || Policy.quiet(g.settings.config.value) || !g.output.connected() || g.output.callActive()
        // Finish an earlier pause/settings/clear barrier before inserting a later notification.
        // Otherwise holdPending() could accidentally capture a message that arrived after resume.
        maintenance?.join()
        if (!running.value || row.receivedAt < startedAt) return
        val c = g.settings.config.value
        if (row.app !in c.apps) return
        val held = heldAtArrival || arrivalEpoch != epoch || paused.value || Policy.quiet(c) || !g.output.connected() || g.output.callActive()
        val copy = row.copy(instant = Policy.instant(c, row.app, row.title, row.body), state = if (held) "held" else "pending")
        val inserted = g.dao.insert(copy)
        if (inserted > 0 && arrivalEpoch != epoch) { g.dao.state(listOf(inserted), "held"); return }
        g.diagnostics.record(if (inserted > 0) DiagnosticStage.NOTICE_STORED else DiagnosticStage.NOTICE_DUPLICATE, id = inserted)
        if (inserted > 0 && held) blockReason()?.let { g.diagnostics.record(DiagnosticStage.BLOCKED, id = inserted, reason = it) }
        if (inserted > 0 && !held && copy.instant) {
            g.diagnostics.record(DiagnosticStage.AUTO_DISPATCH, id = inserted, source = CommandSource.AUTOMATIC)
            dispatch()
        }
    }
    private suspend fun process(due: Boolean) {
        if (!canPlay()) return
        val c = g.settings.config.value; val token = epoch
        fun valid() = token == epoch && canPlay()
        // Complete prepared work first; persisted cursors prevent replay of completed segments.
        val existing = g.dao.nextBatch()
        if (existing != null) { playBatch(existing, token); return }
        val immediate = Policy.batch(g.dao.pending(true))
        val rows = if (immediate.isNotEmpty()) immediate else if (due) Policy.batch(g.dao.pending(false)) else emptyList()
        if (rows.isEmpty()) { if (due) schedule(); return }
        val instant = immediate.isNotEmpty()
        val ids = rows.map { it.id }
        var text = Policy.localReading(rows)
        val summary = !instant && Policy.needsSummary(c)
        if (summary) {
            if (!c.summaryConsent || c.summaryBlocked || !g.settings.hasKey(c.summaryKeyKind())) {
                g.dao.state(ids, "held"); status.value = "摘要未授权、鉴权暂停或未配置密钥，请检查设置"; schedule(); return
            }
            status.value = "正在生成通知摘要…"; transition(ListeningState.SYNTHESIZING)
            try {
                g.settings.consume("summary", c.summaryLimit)
                val result = g.summaryProvider.summarize(c, g.settings.key(c.summaryKeyKind()), rows.joinToString("") { Policy.wireText(it) })
                if (!valid()) return
                if (!result.important) { g.dao.state(ids, "unimportant"); status.value = "本轮没有需要行动的重要内容"; advanceIfRemaining(); return }
                text = result.text
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is CloudFailure && e.auth) g.settings.update { it.copy(summaryBlocked = true) }
                g.dao.attempt(ids)
                val retry = e is CloudFailure && e.retryable && rows.all { it.attempts == 0 }
                if (!retry) g.dao.state(ids, "failed")
                status.value = safeError(e) + if (retry) "；下轮重试一次" else "；请检查后手动补读"
                schedule(); return
            }
        }
        if (!valid()) return
        val batchId = g.db.withTransaction {
            val id = g.dao.insertBatch(BatchRow(noticeIds = ids.joinToString(","), text = text, createdAt = System.currentTimeMillis(), instant = instant, summary = summary))
            g.dao.state(ids, "queued"); id
        }
        g.dao.batch(batchId)?.let { playBatch(it, token) }
    }
    private suspend fun advanceIfRemaining() {
        if (g.dao.pending(false).isNotEmpty()) { dueElapsed = 0; nextAt.value = System.currentTimeMillis() }
        else schedule()
    }
    private suspend fun playBatch(batch: BatchRow, token: Long) {
        val c = g.settings.config.value
        if (batch.summary && !c.summaryConsent) { g.dao.batchState(batch.id, "held"); status.value = "此摘要的云端授权已撤销，未播报"; return }
        current = batch
        g.dao.batchState(batch.id, "playing")
        try {
            val parts = Policy.segments(batch.text)
            for (i in batch.cursor until parts.size) {
                ensureCurrent(token)
                status.value = "正在合成 / 播放 ${i + 1}/${parts.size} 段"
                transition(ListeningState.SYNTHESIZING)
                val file = synthesize(parts[i], c)
                try {
                    ensureCurrent(token)
                    playFile(file, token)
                    ensureCurrent(token)
                    g.dao.cursor(batch.id, i + 1)
                } finally { file.delete() }
            }
            ensureCurrent(token)
            g.db.withTransaction { g.dao.batchState(batch.id, "done"); g.dao.state(batch.ids(), "done") }
            status.value = "播报完成"
            if (!batch.instant) advanceIfRemaining()
        } catch (e: CancellationException) {
            val screenDeferral = screenDeferredEpoch == token || (token == epoch && g.settings.config.value.onlyScreenOff &&
                g.screenInteractive() && running.value && !paused.value && !Policy.quiet(g.settings.config.value) && g.output.connected() && !g.output.callActive())
            withContext(NonCancellable) { g.dao.batchState(batch.id, if (screenDeferral) "ready" else "held") }
            throw e
        }
        catch (e: Exception) {
            processingBlocked = true
            (e as? PlaybackBlocked)?.let { g.diagnostics.record(DiagnosticStage.BLOCKED, id = batch.id, reason = it.reason) }
            g.dao.batchState(batch.id, "held"); status.value = safeError(e) + "；待手动补读"
        }
        finally { current = null }
    }
    private fun ensureCurrent(token: Long) { if (token != epoch || !canPlay()) throw CancellationException("听读条件已改变") }
    private suspend fun playHour(item: HourAnnouncement) {
        val token = epoch
        val c = g.settings.config.value
        var authFailure = false
        try {
            ensureCurrent(token)
            if (!HourlyPolicy.fresh(item, g.clock())) return
            var recap = ""
            var failure: String? = null
            if (c.hourlySummary) {
                try {
                    check(c.summaryConsent && !c.summaryBlocked && g.settings.hasKey(c.summaryKeyKind())) {
                        "整点摘要未授权、鉴权暂停或未配置密钥"
                    }
                    val window = HourlyRecap.window(item)
                    val rows = g.dao.hourlyNotices(window.first, window.second, c.apps.toList())
                    val selection = HourlyRecap.select(rows)
                    if (selection.rows.isNotEmpty()) {
                        ensureCurrent(token)
                        status.value = "正在总结上一小时通知"
                        transition(ListeningState.SYNTHESIZING)
                        g.settings.consume("summary", c.summaryLimit)
                        val result = withTimeout(35_000L) {
                            g.summaryProvider.summarize(c, g.settings.key(c.summaryKeyKind()), selection.input)
                        }
                        ensureCurrent(token)
                        recap = HourlyRecap.spoken(result, selection.truncated)
                    }
                } catch (e: TimeoutCancellationException) { failure = "整点摘要超时" }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Persist auth pause after pure time playback: updating settings invalidates this task.
                    failure = safeError(e)
                    if (e is CloudFailure && e.auth) authFailure = true
                }
            }
            ensureCurrent(token)
            if (!HourlyPolicy.fresh(item, g.clock())) return
            status.value = "正在整点报时"
            for (part in Policy.segments(item.text + recap)) {
                ensureCurrent(token)
                if (!HourlyPolicy.fresh(item, g.clock())) return
                val file = synthesize(part, c)
                try {
                    ensureCurrent(token)
                    if (!HourlyPolicy.fresh(item, g.clock())) return
                    playFile(file, token); ensureCurrent(token)
                } finally { file.delete() }
            }
            g.diagnostics.record(DiagnosticStage.HOURLY_DONE, value = item.hour)
            status.value = failure?.let { "报时完成；上一小时摘要未播：$it" } ?: "整点报时完成"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            status.value = "整点报时未完成；" + safeError(e)
            g.diagnostics.record(DiagnosticStage.HOURLY_SKIPPED, value = item.hour, reason = (e as? PlaybackBlocked)?.reason)
        } finally {
            if (authFailure && token == epoch) g.settings.update { it.copy(summaryBlocked = true) }
        }
    }
    private suspend fun playFile(file: File, token: Long) {
        PlaybackRetry().run(allowed = { token == epoch && canPlay() }, retry = { reason, attempt ->
            transition(ListeningState.SYNTHESIZING)
            g.diagnostics.record(DiagnosticStage.RETRY, id = current?.id ?: 0, value = attempt, reason = reason)
            status.value = "${reason.title}；自动重试 $attempt/3"
        }) { g.output.play(file) { token == epoch && canPlay() } }
    }
    private suspend fun synthesize(text: String, c: Config): File {
        transition(ListeningState.SYNTHESIZING)
        if (c.voice == VoiceEngine.SYSTEM) return g.local.synthesize(text, c)
        try {
            check(c.speechConsent) { "未授权云端语音" }
            g.settings.consume("speech", c.speechLimit)
            g.diagnostics.record(DiagnosticStage.CLOUD_BEGIN)
            val bytes = g.speechProvider.synthesize(c, g.settings.key(c.speechKeyKind()), text)
            g.diagnostics.record(DiagnosticStage.CLOUD_DONE)
            val file = File(g.context.cacheDir, "speech-${UUID.randomUUID()}.mp3")
            try { withContext(Dispatchers.IO) { file.writeBytes(bytes) }; return file }
            catch (e: Exception) { file.delete(); throw e }
        } catch (e: TimeoutCancellationException) {
            if (!c.fallback) throw IllegalStateException("AI 语音合成超时")
            status.value = "AI 语音超时；本段改用系统机读"
            return g.local.synthesize(text, c)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!c.fallback) throw e
            status.value = safeError(e) + "；本段改用系统机读"
            return g.local.synthesize(text, c)
        }
    }
    fun refreshSystemSpeech() {
        control("正在重新连接系统语音；旧积压保留待确认") {
            try {
                g.refreshLocal()
                val voices = g.local.voices()
                status.value = if (voices.isEmpty()) "当前引擎仍未提供离线中文声音，请复制精简诊断"
                    else "系统声音已刷新，找到 ${voices.size} 个离线中文声音，请试听；旧积压待确认补读"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                status.value = "系统语音刷新失败，请复制精简诊断"
                g.diagnostics.record(DiagnosticStage.BLOCKED, reason = PlaybackBlockReason.TTS_INIT_FAILED)
            } finally { g.localRevision.value++ }
        }
    }
    fun preview() = previewText("你好，我是听讯。耳机已连接，接下来为你朗读重要通知。")
    fun previewHour() = previewText("整点报时试听。现在是十五点整。")
    private fun previewText(text: String) {
        if (!canPlay()) { status.value = "试听前请开启听读、连接耳机并授予电话状态权限"; return }
        if (work?.isActive == true || maintenance?.isActive == true) { status.value = "已有任务正在处理，请稍后试听"; return }
        launchWork {
            val token = epoch; val file = synthesize(text, g.settings.config.value)
            try { ensureCurrent(token); playFile(file, token); status.value = "试听完成" }
            finally { file.delete() }
        }
    }
    fun testSummary() {
        if (work?.isActive == true || maintenance?.isActive == true) { status.value = "已有任务正在处理"; return }
        launchWork {
            val token = epoch; val c = g.settings.config.value
            check(c.summaryConsent) { "请先主动开启摘要授权" }
            g.settings.consume("summary", c.summaryLimit)
            status.value = "正在测试摘要接口，使用固定测试文字…"
            val result = g.summaryProvider.summarize(c, g.settings.key(c.summaryKeyKind()), "[测试资料] 同事：明天下午三点开会，请回复是否参加。")
            if (token == epoch) { status.value = "摘要连接成功：${result.text}"; if (c.summaryBlocked) g.settings.update { it.copy(summaryBlocked = false) } }
        }
    }
    fun testSpeech() {
        if (!canPlay()) { status.value = "语音测试前请开启听读、连接耳机并授予电话状态权限"; return }
        if (work?.isActive == true || maintenance?.isActive == true) { status.value = "已有任务正在处理"; return }
        launchWork {
            val token = epoch; val c = g.settings.config.value
            check(c.speechConsent) { "请先主动开启云端语音授权" }
            g.settings.consume("speech", c.speechLimit); status.value = "正在测试${c.speechProtocol.title}语音，不启用机读回退…"
            val bytes = g.speechProvider.synthesize(c, g.settings.key(c.speechKeyKind()), "你好，我是听讯。这是一段 AI 合成语音连接测试。")
            ensureCurrent(token)
            val file = File(g.context.cacheDir, "speech-${UUID.randomUUID()}.mp3")
            try {
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                ensureCurrent(token); playFile(file, token); ensureCurrent(token)
                g.settings.update { it.copy(speechVerified = true) }
                status.value = "${c.speechProtocol.title}语音测试通过，可选择 AI 语音"
            } finally { file.delete() }
        }
    }
    companion object {
        fun safeError(e: Exception): String = when (e) {
            is CloudFailure -> e.message ?: "云端请求失败"
            is TimeoutCancellationException -> "处理超时"
            is IllegalStateException, is IllegalArgumentException -> e.message?.take(160) ?: "配置或音频状态异常"
            else -> "处理失败，请检查配置、网络与耳机状态"
        }
    }
}
