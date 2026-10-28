package cn.tingxun.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

class NotificationCapture : NotificationListenerService() {
    private val ledger = CaptureLedger()
    override fun onCreate() { super.onCreate(); graph.diagnostics.record(DiagnosticStage.LISTENER_CREATED) }
    override fun onListenerConnected() {
        graph.capture = java.lang.ref.WeakReference(this)
        graph.listenerConnected.value = true
        graph.diagnostics.record(DiagnosticStage.LISTENER_CONNECTED)
        graph.engine.listenerReady()
        // Only notifications posted since the current user-started session; normal ingestion deduplicates updates.
        graph.scope.launch { recoverActive() }
    }
    override fun onListenerDisconnected() {
        graph.capture.clear()
        graph.listenerConnected.value = false; graph.diagnostics.record(DiagnosticStage.LISTENER_DISCONNECTED)
        graph.engine.interrupt("通知监听已断开，正在尝试重新连接")
        graph.listenerCheck.requestRebind()
    }
    override fun onDestroy() {
        graph.diagnostics.record(DiagnosticStage.LISTENER_DESTROYED)
        if (graph.capture.get() === this) { graph.capture.clear(); graph.listenerConnected.value = false }
        super.onDestroy()
    }
    /** Called by the user-started foreground session, independent of Activity/MediaSession controls. */
    internal suspend fun recoverActive() {
        if (!graph.engine.running.value || !graph.listenerConnected.value) return
        try {
            val notices = withContext(Dispatchers.IO) { activeNotifications ?: emptyArray() }
            graph.diagnostics.capture(CaptureSignal.SNAPSHOT_OK)
            acceptSnapshot(notices)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            graph.diagnostics.capture(CaptureSignal.SNAPSHOT_FAILED)
            graph.diagnostics.record(DiagnosticStage.LISTENER_QUERY_FAILED)
        }
    }
    internal fun acceptSnapshot(notices: Array<StatusBarNotification>) {
        for (sbn in notices) {
            if (!graph.engine.running.value || sbn.packageName !in graph.settings.config.value.apps || sbn.packageName == packageName) continue
            val stamp = if (sbn.postTime < graph.engine.startedAt) "old:${sbn.postTime}" else stamp(sbn)
            if (ledger.observe(sbn.key, stamp)) capture(sbn, recovered = true)
        }
    }
    private fun stamp(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val messages = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages.orEmpty() }.getOrDefault(emptyList())
        val content = listOf(n.extras.getCharSequence(Notification.EXTRA_TITLE), n.extras.getCharSequence(Notification.EXTRA_TEXT),
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT), n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(),
            messages.joinToString { "${it.timestamp}:${it.person?.name}:${it.text}" }).joinToString("\n")
        return Policy.fingerprint(sbn.key, n.flags.toString(), content, sbn.postTime)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) { graph.listenerCheck.accept(sbn); return }
        graph.diagnostics.capture(CaptureSignal.CALLBACK)
        if (sbn.packageName !in graph.settings.config.value.apps) { graph.diagnostics.capture(CaptureSignal.NOT_SELECTED); return }
        graph.diagnostics.capture(CaptureSignal.SELECTED_CALLBACK)
        if (!graph.engine.running.value || sbn.postTime < graph.engine.startedAt) { capture(sbn, recovered = false); return }
        ledger.observe(sbn.key, stamp(sbn))
        capture(sbn, recovered = false)
    }
    private fun capture(sbn: StatusBarNotification, recovered: Boolean) {
        val g = graph; val n = sbn.notification
        fun filtered(signal: CaptureSignal) {
            g.diagnostics.capture(signal)
            g.diagnostics.record(DiagnosticStage.NOTICE_FILTERED, value = signal.ordinal)
        }
        when {
            !g.engine.running.value -> { filtered(CaptureSignal.SESSION_OFF); return }
            sbn.postTime < g.engine.startedAt -> { filtered(CaptureSignal.BEFORE_SESSION); return }
            n.flags and Notification.FLAG_ONGOING_EVENT != 0 -> { filtered(CaptureSignal.ONGOING); return }
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 -> { filtered(CaptureSignal.GROUP_SUMMARY); return }
        }
        if (recovered) g.diagnostics.capture(CaptureSignal.RECOVERED)
        g.diagnostics.record(if (recovered) DiagnosticStage.NOTICE_RECOVERED else DiagnosticStage.NOTICE_RECEIVED)
        val lease = g.wake.acquire(60_000L)
        val capture = g.scope.launch {
            try {
                val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(300)
                val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
                if (style != null && style.messages.isNotEmpty()) {
                    var available = false
                    for (message in style.messages.takeLast(50)) {
                        if (message.timestamp < g.engine.startedAt) continue
                        val body = message.text?.toString().orEmpty().take(9000)
                        if (body.isBlank()) continue
                        available = true
                        val sender = message.person?.name?.toString().orEmpty().take(300)
                        val conversation = style.conversationTitle?.toString()?.take(300) ?: title.ifBlank { sender }
                        val content = if (sender.isNotBlank() && sender != conversation) "$sender：$body" else body
                        g.engine.ingest(NoticeRow(fingerprint = Policy.fingerprint(sbn.key, sender, body, message.timestamp), sourceKey = sbn.key,
                            app = sbn.packageName, label = label.take(200), title = conversation, body = content,
                            receivedAt = System.currentTimeMillis(), instant = false))
                    }
                    if (!available) filtered(CaptureSignal.OLD_MESSAGES)
                } else {
                    val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
                    val body = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                        ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: lines.joinToString("\n")).take(9000)
                    if (body.isBlank() && title.isBlank()) { filtered(CaptureSignal.EMPTY_CONTENT); return@launch }
                    val content = body.ifBlank { "通知未提供正文" }
                    g.engine.ingest(NoticeRow(fingerprint = Policy.fingerprint(sbn.key, title, content, null), sourceKey = sbn.key,
                        app = sbn.packageName, label = label.take(200), title = title, body = content,
                        receivedAt = System.currentTimeMillis(), instant = false))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { g.diagnostics.record(DiagnosticStage.NOTICE_FAILED); g.engine.status.value = "部分通知无法解析，已跳过" }
            finally { lease.close() }
        }
        capture.invokeOnCompletion { lease.close() }
    }
}

class ListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var registered = false
    private var starting: Job? = null
    private var watching = false
    private var registrationFailed = false
    private lateinit var media: MediaSession
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) graph.engine.interrupt("耳机输出发生变化，已停止播报")
            else if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_SCREEN_OFF) {
                graph.diagnostics.record(DiagnosticStage.SCREEN_CHANGED); graph.engine.screenChanged()
            } else graph.hourly.schedule()
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "听读会话", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示耳机听读状态与暂停、停止入口"; setSound(null, null); enableVibration(false)
        })
        media = MediaSession(this, "TingXunReading").apply {
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "听讯 · 耳机通知听读").build())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    graph.diagnostics.record(DiagnosticStage.MEDIA_PLAY_COMMAND, source = CommandSource.MEDIA)
                    if (registered && graph.engine.running.value) graph.engine.resume(CommandSource.MEDIA)
                    else startForegroundService(Intent(this@ListeningService, ListeningService::class.java).setAction(RESUME).putExtra(SOURCE, CommandSource.MEDIA.name))
                    updateNotification()
                }
                override fun onPause() {
                    graph.diagnostics.record(DiagnosticStage.MEDIA_PAUSE_COMMAND, source = CommandSource.MEDIA)
                    graph.engine.pause(CommandSource.MEDIA); updateNotification()
                }
                override fun onStop() { graph.engine.stop(); stopSelf() }
            })
            isActive = false
        }
        graph.mediaSession = media; graph.renderMedia(graph.engine.state.value)
        scope.launch { graph.engine.state.collect { if (registered) updateNotification() } }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(noisy, filter)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { graph.engine.stop(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == PAUSE) {
            if (!registered || !graph.engine.running.value) { stopSelf(); return START_NOT_STICKY }
            graph.engine.pause(CommandSource.NOTIFICATION); updateNotification(); return START_STICKY
        }
        val source = if (intent == null) CommandSource.RESTORE else runCatching {
            CommandSource.valueOf(intent.getStringExtra(SOURCE) ?: CommandSource.NOTIFICATION.name)
        }.getOrDefault(CommandSource.SYSTEM)
        if (!registered) {
            try {
                graph.diagnostics.startSource = source
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                startForeground(42, notification(), types)
                graph.diagnostics.foreground(foregroundServiceType)
                registered = true; graph.sessionReady.value = true
            } catch (_: Exception) {
                registrationFailed = true
                graph.sessionReady.value = false; graph.diagnostics.record(DiagnosticStage.SERVICE_FAILED)
                graph.engine.status.value = "媒体前台服务注册失败，请复制后台诊断"; stopSelf(); return START_NOT_STICKY
            }
        }
        if (!graph.engine.running.value && starting?.isActive != true) {
            starting = scope.launch {
                while (!graph.engine.ready) delay(25)
                graph.engine.start(restore = intent == null)
                graph.diagnostics.record(DiagnosticStage.START_ACK, value = if (intent?.getBooleanExtra(VISIBLE, false) == true) 1 else 0, source = source)
                updateNotification()
                // Bind the configured system engine while the user-started audio session is already foreground.
                graph.local.voices()
            }
        } else if (graph.engine.running.value && source != CommandSource.RESTORE) {
            graph.engine.resume(source)
            graph.diagnostics.record(DiagnosticStage.START_ACK, source = source)
        }
        if (!watching) {
            watching = true
            scope.launch {
                var lastRebind = 0L; var lastHeartbeat = 0L
                var waitingSince = SystemClock.elapsedRealtime(); var timeoutReported = false
                while (isActive) {
                    delay(2000)
                    val granted = try { graph.listenerCheck.granted() } catch (_: Exception) {
                        graph.diagnostics.record(DiagnosticStage.LISTENER_STATUS_FAILED); continue
                    }
                    if (!granted) { graph.diagnostics.record(DiagnosticStage.SESSION_PERMISSION_LOST); graph.engine.stop(); stopSelf(); break }
                    val elapsed = SystemClock.elapsedRealtime()
                    if (!graph.listenerConnected.value && elapsed - lastRebind >= 30_000) {
                        graph.listenerCheck.requestRebind()
                        lastRebind = elapsed
                    }
                    if (graph.listenerConnected.value) { waitingSince = elapsed; timeoutReported = false }
                    else if (!timeoutReported && elapsed - waitingSince >= 10_000L) {
                        timeoutReported = true; graph.diagnostics.record(DiagnosticStage.LISTENER_CONNECT_TIMEOUT)
                    }
                    graph.capture.get()?.recoverActive()
                    if (elapsed - lastHeartbeat >= 60_000) { graph.diagnostics.record(DiagnosticStage.SERVICE_HEARTBEAT); lastHeartbeat = elapsed }
                    updateNotification()
                }
            }
        }
        // Android may recreate an active user-started session after reclaiming its process.
        // Explicit stop/force-stop and reboot still require the user to start a new session.
        return START_STICKY
    }
    private fun action(action: String): PendingIntent {
        val intent = Intent(this, ListeningService::class.java).setAction(action).putExtra(SOURCE, CommandSource.NOTIFICATION.name)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == RESUME) PendingIntent.getForegroundService(this, action.hashCode(), intent, flags)
        else PendingIntent.getService(this, action.hashCode(), intent, flags)
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val state = graph.engine.state.value
        val config = graph.settings.config.value
        val description = if (Policy.quiet(config)) "免打扰至 %02d:%02d".format(java.util.Locale.ROOT, config.quietEnd / 60, config.quietEnd % 60)
            else if (!graph.listenerConnected.value) "通知访问已授权，但监听未连接；到诊断与设备运行自检"
            else graph.audio.deviceLabel.value
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_status).setContentTitle("听讯 · ${state.title}")
            .setContentText(description).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(if (state.mediaActive) Notification.CATEGORY_TRANSPORT else Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, if (graph.engine.paused.value) "恢复" else "暂停", action(if (graph.engine.paused.value) RESUME else PAUSE)).build())
            .addAction(Notification.Action.Builder(null, "停止", action(STOP)).build())
        if (state.mediaActive) builder.setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1))
        return builder.build()
    }
    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(42, notification()) }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        // Android's existing dumpsys service permission gate; no exported debug receiver or private file access.
        writer.println(graph.diagnostics.export())
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        graph.sessionReady.value = false; graph.engine.stop(); scope.cancel()
        graph.mediaSession = null; media.isActive = false; media.release()
        graph.diagnostics.stopped(); unregisterReceiver(noisy); super.onDestroy()
        if (registrationFailed) graph.engine.status.value = "媒体前台服务注册失败，请复制后台诊断"
    }
    companion object {
        const val CHANNEL = "reading-session"
        const val STOP = "cn.tingxun.STOP"
        const val PAUSE = "cn.tingxun.PAUSE"
        const val RESUME = "cn.tingxun.RESUME"
        const val START_AUTOMATIC = "cn.tingxun.START_AUTOMATIC"
        const val SOURCE = "source"
        const val VISIBLE = "visible_at_request"
    }
}
