package cn.tingxun.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.media.AudioDeviceInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun row(fingerprint: String = "unique") = NoticeRow(fingerprint = fingerprint, sourceKey = "key", app = "test.app", label = "测试应用", title = "同事", body = "请确认明天下午三点的会议", receivedAt = System.currentTimeMillis(), instant = false)
    @Test fun databaseDeduplicationRecoveryCursorAndClearArePersistent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TingDatabase::class.java).build()
        try {
            val dao = db.dao(); val id = dao.insert(row()); assertTrue(id > 0); assertEquals(-1, dao.insert(row()))
            val batch = dao.insertBatch(BatchRow(noticeIds = "$id", text = "测试", createdAt = System.currentTimeMillis()))
            dao.state(listOf(id), "queued"); dao.batchState(batch, "playing"); dao.cursor(batch, 1)
            dao.holdBatches(); assertNull(dao.nextBatch()); assertEquals(1, dao.batch(batch)!!.cursor)
            dao.releaseBatches(); assertEquals(batch, dao.nextBatch()!!.id)
            dao.clearBatches(); dao.clearPending(); assertNull(dao.nextBatch()); assertEquals(0, dao.countPending().first())
        } finally { db.close() }
    }
    @Test fun encryptionAndIndependentConsentSurviveReload() {
        val settings = context.graph.settings
        settings.saveKey("summary", "fixture-summary"); settings.saveKey("speech", "fixture-speech")
        settings.update { it.copy(summaryConsent = true, speechConsent = false) }
        val reloaded = Settings(context)
        assertEquals("fixture-summary", reloaded.key("summary")); assertEquals("fixture-speech", reloaded.key("speech"))
        assertTrue(reloaded.config.value.summaryConsent); assertFalse(reloaded.config.value.speechConsent)
        val raw = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("fixture-summary")); assertFalse(raw.contains("fixture-speech"))
        settings.reset()
    }
    @Test fun clearingUsageDoesNotResetDailyCounter() {
        val settings = context.graph.settings
        val before = settings.used("summary"); settings.consume("summary", 10000); settings.reset()
        assertEquals(before + 1, Settings(context).used("summary"))
    }
    @Test fun builtInAndBleSpeakersAreNotAcceptedAsHeadphones() {
        assertFalse(AudioGate.isHeadphone(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(AudioGate.isHeadphone(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertFalse(AudioGate.isHeadphone(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertTrue(AudioGate.isHeadphone(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(AudioGate.isHeadphone(AudioDeviceInfo.TYPE_USB_HEADSET))
    }
    @Test fun settingsNavigationDoesNotChangeConfigurationAndCloudDefaultsAreOff() {
        val settings = context.graph.settings
        settings.reset()
        val before = settings.config.value
        val revision = settings.revision.value
        ui.onNodeWithText("接化发俱乐部出品").assertExists()
        ui.onNodeWithTag("home-control").assertExists()
        ui.onNodeWithText("现在播报").assertDoesNotExist()
        screenshot("01-home")
        ui.onNodeWithContentDescription("设置").performClick()
        screenshot("02-settings-directory")
        listOf(Screen.APPS, Screen.CONTENT, Screen.TIMING, Screen.SOUND, Screen.QUIET, Screen.HOURLY, Screen.HISTORY,
            Screen.SESSION, Screen.PERMISSIONS, Screen.DIAGNOSTICS, Screen.PRIVACY, Screen.ABOUT).forEach { page ->
            ui.onNodeWithTag("menu-${page.title}").performScrollTo().performClick()
            if (page == Screen.ABOUT) {
                ui.onNodeWithContentDescription("接化发俱乐部 JIE HUA FA CLUB 完整 Logo").assertExists()
                screenshot("04-about")
            }
            if (page == Screen.SOUND) {
                ui.onNodeWithText("试听当前声音").assertExists()
                screenshot("06-sound")
            }
            if (page == Screen.TIMING) screenshot("07-screen-only")
            if (page == Screen.HOURLY) screenshot("08-hourly")
            ui.onNodeWithContentDescription("返回").performClick()
        }
        ui.onNodeWithTag("menu-AI 服务配置").performScrollTo().performClick()
        ui.onNodeWithText("摘要服务").performClick()
        ui.onNodeWithText("内容 AI").assertExists()
        screenshot("03-summary-settings")
        androidx.test.espresso.Espresso.pressBack()
        ui.onNodeWithText("语音服务").performClick()
        ui.onNodeWithText("发声 AI").assertExists()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        assertEquals(before, settings.config.value)
        assertEquals(revision, settings.revision.value)
        assertFalse(settings.config.value.summaryConsent)
        assertFalse(settings.config.value.speechConsent)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("home-control").assertExists()
    }
    @Test fun providerSwitchRetainsSeparateKeysAndRevokesChangedDestinationConsent() {
        val settings = context.graph.settings
        settings.saveKey("summary", "minimax-summary"); settings.saveKey("speech", "minimax-speech")
        settings.saveKey("openai_summary", "generic-summary"); settings.saveKey("openai_speech", "generic-speech")
        settings.update { it.copy(summaryConsent = true, speechConsent = true, speechVerified = true) }
        settings.update { it.copy(summaryProtocol = CloudProtocol.OPENAI, openaiSummaryModel = "test-text") }
        assertFalse(settings.config.value.summaryConsent); assertTrue(settings.config.value.speechConsent)
        assertTrue(settings.config.value.speechVerified)
        settings.update { it.copy(speechProtocol = CloudProtocol.OPENAI) }
        assertFalse(settings.config.value.speechConsent); assertFalse(settings.config.value.speechVerified)
        val reloaded = Settings(context)
        assertEquals("minimax-summary", reloaded.key("summary")); assertEquals("minimax-speech", reloaded.key("speech"))
        assertEquals("generic-summary", reloaded.key("openai_summary")); assertEquals("generic-speech", reloaded.key("openai_speech"))
        assertEquals("test-text", reloaded.config.value.openaiSummaryModel)
        settings.reset()
    }
    @Test fun oldConfigWithoutProviderFieldsPreservesMiniMaxOnUpgrade() {
        val settings = context.graph.settings
        settings.saveKey("summary", "legacy-key")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("config",
            "{\"summaryUrl\":\"https://example.com/custom/chat/completions\",\"summaryModel\":\"legacy-model\",\"summaryConsent\":true,\"interval\":10}").commit()
        val restored = Settings(context)
        assertEquals(CloudProtocol.MINIMAX, restored.config.value.summaryProtocol)
        assertEquals("https://example.com/custom/chat/completions", restored.config.value.summaryUrl)
        assertEquals("legacy-model", restored.config.value.summaryModel); assertTrue(restored.config.value.summaryConsent)
        assertEquals(10, restored.config.value.interval); assertEquals("legacy-key", restored.key("summary"))
        settings.reset()
    }
    @Test fun genericProviderCanBeSelectedAndConfiguredWithoutSendingData() {
        context.graph.settings.reset()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithTag("menu-AI 服务配置").performScrollTo().performClick()
        ui.onNodeWithText("摘要服务").performClick()
        ui.onAllNodesWithText("MiniMax").onFirst().performClick()
        ui.onNodeWithText("OpenAI 兼容接口").performClick()
        ui.onNodeWithText("Base URL 或完整 /chat/completions 地址").assertExists()
        assertEquals(CloudProtocol.OPENAI, context.graph.settings.config.value.summaryProtocol)
        assertFalse(context.graph.settings.config.value.summaryConsent)
        screenshot("05-openai-settings")
        context.graph.settings.reset()
    }
    @Test fun noHeadphonesMeansNewMessagesAreHeldAndClearCancelsBacklog() = runBlocking {
        val g = context.graph
        withContext(Dispatchers.Main) { g.settings.update { it.copy(apps = setOf("test.app")) } }
        delay(300)
        withContext(Dispatchers.Main) { g.engine.start() }
        val item = row("held-${System.nanoTime()}")
        withContext(Dispatchers.Main) { g.engine.ingest(item) }
        val stored = g.dao.observe().first().first { it.fingerprint == item.fingerprint }
        assertEquals("held", stored.state)
        withContext(Dispatchers.Main) { g.engine.clearPending() }
        delay(300)
        assertEquals(0, g.dao.countPending().first())
        withContext(Dispatchers.Main) { g.engine.stop(); g.settings.reset() }
    }
    @Test fun lateSummaryAfterQueueClearCannotUpdateStatusOrCreatePlayback() = runBlocking {
        val g = context.graph
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val original = g.summaryProvider
        g.summaryProvider = object : SummaryProvider {
            override suspend fun summarize(c: Config, key: String, input: String): SummaryResult = withContext(NonCancellable) {
                entered.complete(Unit); release.await(); SummaryResult(true, "迟到的结果不应显示或播报")
            }
        }
        try {
            withContext(Dispatchers.Main) {
                g.settings.update { it.copy(summaryConsent = true) }; g.settings.saveKey("summary", "dummy")
            }
            delay(400)
            withContext(Dispatchers.Main) { g.engine.testSummary() }
            withTimeout(5000) { entered.await() }
            withContext(Dispatchers.Main) { g.engine.clearPending() }
            release.complete(Unit); delay(400)
            assertFalse(g.engine.status.value.contains("迟到的结果"))
            assertNull(g.dao.nextBatch())
        } finally {
            release.complete(Unit); g.summaryProvider = original
            withContext(Dispatchers.Main) { g.settings.reset() }
        }
    }
    @Test fun notificationListenerCapturesOnlyNewSelectedNotificationsAndDeduplicatesUpdates() = runBlocking {
        val g = context.graph
        fun shell(command: String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { parcel ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).use { it.readBytes() }
            }
        }
        shell("cmd notification allow_listener cn.tingxun.app/cn.tingxun.app.NotificationCapture")
        withTimeout(10_000) { while (!g.listenerConnected.value || !g.engine.ready) delay(100) }
        // Numeric markers can trigger Android 15 OTP redaction; test ordinary notification content here.
        val marker = "fixture" + java.util.UUID.randomUUID().toString().filter { it.isLetter() }
        withContext(Dispatchers.Main) { g.settings.update { it.copy(apps = setOf("com.android.shell")) } }
        delay(300)
        shell("pm grant cn.tingxun.app android.permission.POST_NOTIFICATIONS")
        shell("pm grant cn.tingxun.app android.permission.READ_PHONE_STATE")
        withContext(Dispatchers.Main) { ui.activity.startReading() }
        val started = withTimeoutOrNull(15000) { while (!g.engine.running.value) delay(100); true } ?: false
        assertTrue("前台服务启动失败：${g.engine.status.value}", started)
        shell("cmd notification post -t fixture-contact $marker $marker")
        val captured = withTimeoutOrNull(15000) { while (g.dao.observe().first().none { it.body == marker }) delay(100); true } ?: false
        assertTrue("通知收录失败：running=${g.engine.running.value}, listener=${g.listenerConnected.value}, status=${g.engine.status.value}", captured)
        shell("cmd notification post -t fixture-contact $marker $marker")
        delay(400)
        assertEquals(1, g.dao.observe().first().count { it.body == marker })
        withContext(Dispatchers.Main) { g.settings.update { it.copy(apps = emptySet()) } }
        shell("cmd notification post -t fixture-contact ${marker}x ${marker}x")
        delay(400)
        assertEquals(0, g.dao.observe().first().count { it.body == marker + "x" })
        withContext(Dispatchers.Main) { ui.activity.stopService(android.content.Intent(context, ListeningService::class.java)); g.engine.stop(); g.settings.reset() }
        shell("cmd notification disallow_listener cn.tingxun.app/cn.tingxun.app.NotificationCapture")
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { parcel ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).use { it.readBytes() }
        }
    }
    @Test fun screenOffNotificationsDispatchImmediatelyAndDrainArrivalsDuringSynthesis() = runBlocking {
        val g = context.graph
        val originalOutput = g.output; val originalSpeech = g.speechProvider
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val spoken = java.util.concurrent.atomic.AtomicInteger()
        val synthesized = java.util.concurrent.atomic.AtomicInteger()
        val power = context.getSystemService(android.os.PowerManager::class.java)
        g.output = object : ReadingOutput {
            override fun connected() = true
            override fun callActive() = false
            override fun stop() { }
            override suspend fun play(file: File, allowed: () -> Boolean) {
                assertTrue(allowed()); assertFalse("测试必须在息屏状态调度播放", power.isInteractive)
                assertTrue("合成到播放之间应持续受 CPU 保护", g.wake.active.get() > 0)
                spoken.incrementAndGet()
            }
        }
        g.speechProvider = object : SpeechProvider {
            override suspend fun synthesize(c: Config, key: String, text: String): ByteArray {
                assertTrue(g.wake.active.get() > 0)
                if (synthesized.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
                return byteArrayOf(1, 2, 3) // Fake output consumes no audible media on emulator.
            }
        }
        val marker = "background" + java.util.UUID.randomUUID().toString().filter { it.isLetter() }
        try {
            shell("cmd notification allow_listener cn.tingxun.app/cn.tingxun.app.NotificationCapture")
            shell("pm grant cn.tingxun.app android.permission.POST_NOTIFICATIONS")
            shell("pm grant cn.tingxun.app android.permission.READ_PHONE_STATE")
            withTimeout(10_000) { while (!g.listenerConnected.value || !g.engine.ready) delay(100) }
            withContext(Dispatchers.Main) {
                g.engine.clearPending()
                g.settings.saveKey("speech", "fixture")
                g.settings.update { it.copy(apps = setOf("com.android.shell"), mode = ReadMode.INSTANT,
                    voice = VoiceEngine.MINIMAX, speechConsent = true, speechVerified = true, quiet = false) }
            }
            delay(500)
            withContext(Dispatchers.Main) { ui.activity.startReading() }
            withTimeout(10_000) { while (!g.engine.running.value) delay(50) }
            shell("input keyevent 3")
            shell("input keyevent 223")
            withTimeout(5000) { while (power.isInteractive) delay(50) }
            shell("cmd notification post -t background-contact $marker $marker")
            withTimeout(10_000) { entered.await() } // No manual now()/releaseHeld() call.
            shell("cmd notification post -t background-contact ${marker}second ${marker}second")
            withTimeout(5000) { while (g.dao.observe().first().none { it.body == marker + "second" }) delay(50) }
            release.complete(Unit)
            withTimeout(10_000) {
                while (g.dao.observe().first().count { it.body.startsWith(marker) && it.state == "done" } != 2) delay(50)
            }
            assertEquals(2, spoken.get()); assertEquals(2, synthesized.get())
            shell("cmd notification post -t background-contact ${marker}second ${marker}second")
            delay(400)
            assertEquals("同一通知更新不得重复朗读", 2, spoken.get())
            withTimeout(5000) { while (g.wake.active.get() != 0) delay(50) }
            assertTrue(g.engine.running.value)
            assertFalse(power.isInteractive)
        } finally {
            // Cancel the suspended synthesis before waking the screen for cleanup.
            // Otherwise a prior timeout can release playback into the awake cleanup and hide its cause.
            withContext(Dispatchers.Main) { g.engine.stop() }
            release.complete(Unit)
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            shell("am start -W -n cn.tingxun.app/cn.tingxun.app.MainActivity")
            withContext(Dispatchers.Main) {
                context.stopService(android.content.Intent(context, ListeningService::class.java))
                g.engine.stop(); g.settings.reset()
            }
            delay(400)
            g.output = originalOutput; g.speechProvider = originalSpeech
            shell("cmd notification disallow_listener cn.tingxun.app/cn.tingxun.app.NotificationCapture")
        }
    }
    @Test fun workWakeLeaseIsIdempotentAndDoesNotKeepIdleSessionAwake() {
        val wake = ProcessingWake(context)
        assertEquals(0, wake.active.get())
        val first = wake.acquire(2000); val second = wake.acquire(2000)
        assertEquals(2, wake.active.get())
        first.close(); first.close(); assertEquals(1, wake.active.get())
        second.close(); assertEquals(0, wake.active.get())
    }
    @Test fun recreatedSessionKeepsUserPauseAndExplicitStartResetsIt() {
        ui.runOnUiThread {
            val engine = context.graph.engine
            engine.start(); engine.togglePause(); assertTrue(engine.paused.value)
            engine.stop(); engine.start(restore = true); assertTrue(engine.paused.value)
            engine.stop(); engine.start(); assertFalse(engine.paused.value)
            engine.stop()
        }
    }
    private suspend fun startPlatformSession(selectedApp: String = "test.app") {
        val g = context.graph
        shell("cmd notification allow_listener cn.tingxun.app/cn.tingxun.app.NotificationCapture")
        shell("pm grant cn.tingxun.app android.permission.POST_NOTIFICATIONS")
        shell("pm grant cn.tingxun.app android.permission.READ_PHONE_STATE")
        withTimeout(10_000) { while (!g.listenerConnected.value || !g.engine.ready) delay(50) }
        withContext(Dispatchers.Main) {
            g.engine.clearPending()
            g.settings.update { it.copy(apps = setOf(selectedApp), mode = ReadMode.INSTANT, voice = VoiceEngine.SYSTEM, quiet = false) }
        }
        delay(400)
        withContext(Dispatchers.Main) { ui.activity.startReading() }
        withTimeout(10_000) { while (!g.sessionReady.value || !g.engine.running.value) delay(50) }
    }
    @Test fun newOptionsPersistAndOldSettingsDefaultThemOff() {
        val settings = context.graph.settings
        settings.reset()
        assertFalse(Settings(context).config.value.hourly)
        assertFalse(Settings(context).config.value.onlyScreenOff)
        assertFalse(Settings(context).config.value.hourlySummary)
        settings.update { it.copy(hourly = true, onlyScreenOff = true, hourlySummary = true) }
        assertTrue(Settings(context).config.value.hourly)
        assertTrue(Settings(context).config.value.onlyScreenOff)
        assertTrue(Settings(context).config.value.hourlySummary)
        settings.reset()
    }
    @Test fun refreshingDownloadedSystemVoicesRecreatesEngineWithoutConfigOrSessionChanges(): Unit = runBlocking {
        val g = context.graph
        try {
            startPlatformSession()
            val before = g.settings.config.value; val revision = g.settings.revision.value
            val old = g.local; val oldRevision = g.localRevision.value
            withContext(Dispatchers.Main) { g.engine.refreshSystemSpeech() }
            withTimeout(20_000) { while (g.localRevision.value == oldRevision) delay(50) }
            assertNotSame(old, g.local)
            assertTrue(g.engine.running.value); assertFalse(g.engine.paused.value)
            assertEquals(before, g.settings.config.value); assertEquals(revision, g.settings.revision.value)
            val report = org.json.JSONObject(g.diagnostics.exportBrief())
            assertTrue(report.getJSONObject("systemSpeech").getBoolean("initialized"))
            assertTrue(report.getJSONObject("systemSpeech").has("offlineChineseCount"))
            assertTrue(g.diagnostics.exportBrief().length <= 6000)
            assertTrue(report.getJSONObject("runtime").has("gateReason"))
        } finally { stopPlatformSession() }
    }
    @Test fun notificationSelfCheckUsesRealListenerWithoutSpeechQueueOrConfigChanges(): Unit = runBlocking {
        val g = context.graph
        try {
            startPlatformSession()
            val before = g.settings.config.value; val revision = g.settings.revision.value
            val pending = g.dao.countPending().first()
            g.diagnostics.clear()
            withContext(Dispatchers.Main) { g.listenerCheck.run(); g.listenerCheck.run() }
            withTimeout(25_000) { while (g.listenerCheck.busy.value) delay(50) }
            assertTrue(g.listenerCheck.result.value, g.listenerCheck.result.value.startsWith("自检通过"))
            assertEquals(before, g.settings.config.value); assertEquals(revision, g.settings.revision.value)
            assertEquals(pending, g.dao.countPending().first())
            val report = org.json.JSONObject(g.diagnostics.export())
            val events = report.getJSONArray("events")
            val stages = (0 until events.length()).map { events.getJSONObject(it).getString("stage") }
            assertEquals(1, stages.count { it == "LISTENER_PROBE_RECEIVED" })
            assertFalse(stages.contains("TTS_BEGIN")); assertFalse(stages.contains("NOTICE_STORED"))
            assertTrue(report.getJSONObject("environment").getBoolean("notificationAccessGranted"))
            assertTrue(report.getJSONObject("environment").getBoolean("listenerServiceEnabled"))
            assertTrue(report.getJSONObject("processExits").getBoolean("available"))
            assertTrue(report.has("exportedAt")); assertTrue(report.getInt("processId") > 0)
        } finally { stopPlatformSession() }
    }
    @Test fun notificationSelfCheckReportsMissingAuthorizationWithoutStartingASession(): Unit = runBlocking {
        val g = context.graph
        stopPlatformSession()
        shell("cmd notification disallow_listener cn.tingxun.app/cn.tingxun.app.NotificationCapture")
        try {
            withContext(Dispatchers.Main) { g.listenerCheck.run() }
            withTimeout(5000) { while (g.listenerCheck.busy.value) delay(50) }
            assertTrue(g.listenerCheck.result.value.contains("尚未授权"))
            assertFalse(g.engine.running.value)
        } finally { shell("cmd notification allow_listener cn.tingxun.app/cn.tingxun.app.NotificationCapture") }
    }
    @Test fun hourlyRecapQueryExcludesOtherHoursAppsAndManualHoldsWithoutConsumingRows(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TingDatabase::class.java).build()
        try {
            val dao = db.dao(); val end = System.currentTimeMillis(); val start = end - 3600000
            for ((i, state) in listOf("done", "pending", "queued", "unimportant", "held", "failed", "skipped").withIndex())
                dao.insert(row("recap-$i").copy(receivedAt = start + i, state = state))
            dao.insert(row("before").copy(receivedAt = start - 1))
            dao.insert(row("after").copy(receivedAt = end))
            dao.insert(row("other-app").copy(receivedAt = start, app = "excluded"))
            val selected = dao.hourlyNotices(start, end, listOf("test.app"))
            assertEquals(setOf("done", "pending", "queued", "unimportant"), selected.map { it.state }.toSet())
            assertEquals(4, selected.size)
            assertEquals("done", dao.observe().first().first { it.fingerprint == "recap-0" }.state)
            assertTrue(dao.hourlyNotices(start, end, emptyList()).isEmpty())
        } finally { db.close() }
    }
    @Test fun hourlyRecapUsesConsentFallsBackToTimeAndCancelsLateResults(): Unit = runBlocking {
        val g = context.graph; val output = g.output; val speech = g.speechProvider
        val summary = g.summaryProvider; val clock = g.clock
        var now = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(2).withMinute(59)
        var completed = 0; var requests = 0; var answer = "important"
        var entered = CompletableDeferred<Unit>(); var release = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        g.clock = { now }
        g.output = object : ReadingOutput {
            override fun connected() = true
            override fun callActive() = false
            override fun stop() { }
            override suspend fun play(file: File, allowed: () -> Boolean) { assertTrue(allowed()); completed++ }
        }
        g.speechProvider = object : SpeechProvider {
            override suspend fun synthesize(c: Config, key: String, text: String): ByteArray { spoken += text; return byteArrayOf(1) }
        }
        g.summaryProvider = object : SummaryProvider {
            override suspend fun summarize(c: Config, key: String, input: String): SummaryResult {
                assertTrue(c.summaryConsent); assertTrue(input.contains("请确认")); requests++
                if (answer == "late") { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                if (answer == "error") throw CloudFailure(retryable = true, message = "模拟网络失败")
                return SummaryResult(answer != "empty", "请回复会议安排")
            }
        }
        suspend fun tick(withNotice: Boolean = true) {
            val next = HourlyPolicy.next(now)
            if (withNotice) g.dao.insert(row("recap-hour-" + next.toInstant().toEpochMilli()).copy(receivedAt = next.toInstant().toEpochMilli() - 1000, state = "done"))
            withContext(Dispatchers.Main) { now = next; g.engine.pollHourly() }
        }
        suspend fun waitCompleted(n: Int) { withTimeout(5000) { while (completed != n) delay(20) }; delay(150) }
        try {
            startPlatformSession()
            withContext(Dispatchers.Main) {
                g.settings.saveKey("speech", "fixture"); g.settings.saveKey("summary", "fixture")
                g.settings.update { it.copy(hourly = true, hourlySummary = false, voice = VoiceEngine.MINIMAX,
                    speechConsent = true, speechVerified = true, summaryConsent = true, summaryBlocked = false) }
            }
            delay(400); tick(); waitCompleted(1)
            assertEquals(0, requests); assertEquals(HourlyPolicy.text(now.hour), spoken.last())
            withContext(Dispatchers.Main) { g.settings.update { it.copy(hourlySummary = true) } }
            delay(400); tick(); waitCompleted(2)
            assertEquals(1, requests); assertTrue(spoken.last().contains("上一小时通知重点：请回复会议安排"))
            val before = requests; tick(false); waitCompleted(3); assertEquals(before, requests)
            answer = "empty"; tick(); waitCompleted(4); assertEquals(HourlyPolicy.text(now.hour), spoken.last())
            answer = "error"; tick(); waitCompleted(5); assertEquals(HourlyPolicy.text(now.hour), spoken.last())
            assertFalse(g.settings.config.value.summaryBlocked)
            for (revoke in listOf(false, true)) {
                answer = "late"; entered = CompletableDeferred(); release = CompletableDeferred()
                tick(); withTimeout(5000) { entered.await() }
                withContext(Dispatchers.Main) {
                    if (revoke) g.settings.update { it.copy(summaryConsent = false) } else g.engine.clearPending()
                }
                release.complete(Unit); delay(500); assertEquals(5, completed)
            }
            val count = requests; tick(); waitCompleted(6); assertEquals(count, requests)
            assertEquals(HourlyPolicy.text(now.hour), spoken.last())
        } finally {
            release.complete(Unit); stopPlatformSession()
            g.output = output; g.speechProvider = speech; g.summaryProvider = summary; g.clock = clock
        }
    }
    @Test fun screenOnlyDefersBrightArrivalsAndAutomaticallyResumesAfterScreenOff(): Unit = runBlocking {
        val g = context.graph; val original = g.output; val speech = g.speechProvider
        var completed = 0; var hold = false; var began = CompletableDeferred<Unit>()
        g.output = object : ReadingOutput {
            override fun connected() = true
            override fun callActive() = false
            override fun stop() { }
            override suspend fun play(file: File, allowed: () -> Boolean) {
                assertTrue(allowed())
                if (hold) { began.complete(Unit); awaitCancellation() }
                completed++
            }
        }
        g.speechProvider = object : SpeechProvider { override suspend fun synthesize(c: Config, key: String, text: String) = byteArrayOf(1, 2) }
        try {
            startPlatformSession()
            withContext(Dispatchers.Main) {
                g.settings.saveKey("speech", "fixture")
                g.settings.update { it.copy(voice = VoiceEngine.MINIMAX, speechConsent = true, speechVerified = true, onlyScreenOff = true) }
            }
            delay(400)
            val first = row("screen-bright-" + System.nanoTime())
            withContext(Dispatchers.Main) { g.engine.ingest(first) }
            delay(300); assertEquals(0, completed)
            assertEquals("pending", g.dao.observe().first().first { it.fingerprint == first.fingerprint }.state)
            shell("input keyevent 223")
            withTimeout(5000) { while (completed != 1 || g.dao.observe().first().none { it.fingerprint == first.fingerprint && it.state == "done" }) delay(20) }
            assertEquals("done", g.dao.observe().first().first { it.fingerprint == first.fingerprint }.state)
            hold = true
            val second = row("screen-mid-play-" + System.nanoTime())
            withContext(Dispatchers.Main) { g.engine.ingest(second) }
            withTimeout(5000) { began.await() }
            val secondId = g.dao.observe().first().first { it.fingerprint == second.fingerprint }.id
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            withTimeout(5000) { while (g.dao.observeBatches().first().none { secondId in it.ids() && it.state == "ready" }) delay(20) }
            assertEquals(1, completed)
            hold = false
            shell("input keyevent 223")
            withTimeout(5000) { while (completed != 2) delay(20) }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            withContext(Dispatchers.Main) { g.engine.ingest(row("clear-while-bright-" + System.nanoTime())); g.engine.clearPending() }
            delay(300); shell("input keyevent 223"); delay(500)
            assertEquals(2, completed)
        } finally { stopPlatformSession(); g.output = original; g.speechProvider = speech }
    }
    @Test fun hourlySpeechIsDeduplicatedSkippedWhenPausedAndCancelledByClear(): Unit = runBlocking {
        val g = context.graph; val original = g.output; val speech = g.speechProvider; val clock = g.clock
        var now = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(2).withMinute(59)
        var completed = 0; var late = false; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val texts = mutableListOf<String>()
        g.clock = { now }
        g.output = object : ReadingOutput {
            override fun connected() = true
            override fun callActive() = false
            override fun stop() { }
            override suspend fun play(file: File, allowed: () -> Boolean) { assertTrue(allowed()); completed++ }
        }
        g.speechProvider = object : SpeechProvider {
            override suspend fun synthesize(c: Config, key: String, text: String): ByteArray {
                texts += text
                if (late) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                return byteArrayOf(1, 2)
            }
        }
        try {
            startPlatformSession()
            withContext(Dispatchers.Main) {
                g.settings.saveKey("speech", "fixture")
                g.settings.update { it.copy(hourly = true, voice = VoiceEngine.MINIMAX, speechConsent = true, speechVerified = true) }
            }
            delay(400)
            now = HourlyPolicy.next(now)
            if (android.os.Build.VERSION.SDK_INT >= 31) shell("appops set cn.tingxun.app SCHEDULE_EXACT_ALARM allow")
            val alarms = context.getSystemService(android.app.AlarmManager::class.java)
            val operation = android.app.PendingIntent.getBroadcast(context, 1888, android.content.Intent(context, HourlyAlarmReceiver::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, operation)
            } else fail("Dedicated test alarm permission was not granted")
            shell("input keyevent 223")
            withContext(Dispatchers.Main) { repeat(3) { g.engine.pollHourly() } }
            withTimeout(5000) { while (completed != 1) delay(20) }
            withTimeout(8000) { while (!g.diagnostics.export().contains("HOURLY_ALARM")) delay(20) }
            assertEquals(1, completed)
            assertEquals(listOf(HourlyPolicy.text(now.hour)), texts)
            withContext(Dispatchers.Main) { g.engine.pause() }
            now = now.plusHours(1)
            withContext(Dispatchers.Main) { g.engine.resume(); g.engine.pollHourly() }
            delay(400); assertEquals(1, completed)
            late = true; now = now.plusHours(1)
            withContext(Dispatchers.Main) { g.engine.pollHourly() }
            withTimeout(5000) { entered.await() }
            withContext(Dispatchers.Main) { g.engine.clearPending() }
            release.complete(Unit); delay(500)
            assertEquals(1, completed)
            withTimeout(5000) { while (g.wake.active.get() != 0) delay(20) }
        } finally {
            release.complete(Unit); stopPlatformSession(); g.output = original; g.speechProvider = speech; g.clock = clock
            // Revoking this permission kills the app on newer Android. The dedicated
            // emulator runner restores the app-op after instrumentation exits.
        }
    }
    @Test fun settingsAndActivityRecreationDoNotPauseAnActiveSession(): Unit = runBlocking {
        val g = context.graph
        try {
            startPlatformSession()
            val revision = g.settings.revision.value
            val config = g.settings.config.value
            ui.onNodeWithContentDescription("设置").performClick()
            ui.onNodeWithTag("menu-声音与语速").performScrollTo().performClick()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("试听当前声音").assertExists()
            assertTrue(g.engine.running.value)
            assertFalse(g.engine.paused.value)
            assertEquals(revision, g.settings.revision.value)
            assertEquals(config, g.settings.config.value)
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNodeWithTag("home-control").assertExists()
        } finally { stopPlatformSession() }
    }
    private suspend fun stopPlatformSession() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        shell("am start -W -n cn.tingxun.app/cn.tingxun.app.MainActivity")
        withContext(Dispatchers.Main) {
            context.stopService(android.content.Intent(context, ListeningService::class.java))
            context.graph.engine.stop(); context.graph.settings.reset()
        }
        withTimeout(5000) { while (context.graph.sessionReady.value) delay(50) }
        shell("cmd notification disallow_listener cn.tingxun.app/cn.tingxun.app.NotificationCapture")
        delay(300)
    }
    private suspend fun goHomeAndWait() {
        shell("input keyevent 3")
        withTimeout(5000) {
            while (withContext(Dispatchers.Main) { ui.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }) delay(50)
        }
    }
    @Test fun realAudioFocusIsGrantedWithMediaForegroundServiceAtHomeAndScreenOff() = runBlocking {
        val g = context.graph
        try {
            startPlatformSession()
            assertTrue(g.diagnostics.foregroundType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
            val focus = PlatformAudioFocus(context, g.diagnostics)
            suspend fun checkFocus() = withContext(Dispatchers.Main) {
                val lease = focus.acquire({ g.sessionReady.value && g.engine.running.value }) { }
                try { delay(100) } finally { lease.close() }
            }
            checkFocus() // Foreground baseline uses the same Android implementation.
            goHomeAndWait()
            checkFocus()
            shell("am start -W -a android.settings.SETTINGS")
            checkFocus()
            shell("input keyevent 223")
            withTimeout(5000) { while (context.getSystemService(android.os.PowerManager::class.java).isInteractive) delay(50) }
            checkFocus()
            val records = org.json.JSONObject(g.diagnostics.export()).getJSONArray("events")
            assertTrue((0 until records.length()).any { records.getJSONObject(it).let { event ->
                event.getString("stage") == "FOCUS_RESULT" && !event.getBoolean("screenOn") &&
                    event.getInt("value") == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } })
            val controller = android.media.session.MediaController(context, g.mediaSession!!.sessionToken)
            controller.transportControls.pause()
            withTimeout(5000) { while (!g.engine.paused.value) delay(50) }
            controller.transportControls.play()
            withTimeout(5000) { while (g.engine.paused.value) delay(50) }
            assertTrue(g.sessionReady.value)
        } finally { stopPlatformSession() }
    }
    @Test fun androidTtsBinderAndFileCallbacksCompleteInBackgroundAndAfterScreenOffWait() = runBlocking {
        val g = context.graph
        var speech: LocalSpeech? = null
        try {
            startPlatformSession()
            val lease = g.wake.acquire(60_000)
            try {
                // This engine is installed only in the instrumentation APK. Calls go through Android's real TTS service.
                withContext(Dispatchers.Main) { speech = LocalSpeech(context, g.diagnostics, "cn.tingxun.app.test") }
                assertTrue(speech!!.voices().any { it.first == "fixture-zh" })
                suspend fun synthesizeFixture() {
                    val file = withContext(Dispatchers.Main) { speech!!.synthesize("这是后台系统语音测试。", Config()) }
                    try {
                        assertTrue(file.length() > 44)
                        assertEquals("RIFF", file.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
                    } finally { file.delete() }
                }
                synthesizeFixture()
                goHomeAndWait(); synthesizeFixture()
                shell("am start -W -a android.settings.SETTINGS"); synthesizeFixture()
                shell("input keyevent 223")
                withTimeout(5000) { while (context.getSystemService(android.os.PowerManager::class.java).isInteractive) delay(50) }
                synthesizeFixture(); delay(10_000); synthesizeFixture()
                val report = org.json.JSONObject(g.diagnostics.export()).getJSONArray("events")
                assertTrue((0 until report.length()).any { report.getJSONObject(it).let { event ->
                    event.getString("stage") == "TTS_DONE" && !event.getBoolean("screenOn")
                } })
            } finally { lease.close() }
        } finally {
            withContext(Dispatchers.Main) { speech?.shutdown() }
            stopPlatformSession()
        }
    }
    @Test fun focusRetryReusesSynthesizedFileAndClearCancelsDelayedPlayback() = runBlocking {
        val g = context.graph; val originalOutput = g.output; val originalSpeech = g.speechProvider
        var attempts = 0; var syntheses = 0; var forceFailure = false
        val paths = mutableListOf<String>()
        g.output = object : ReadingOutput {
            override fun connected() = true
            override fun callActive() = false
            override fun stop() { }
            override suspend fun play(file: File, allowed: () -> Boolean) {
                assertTrue(allowed()); paths.add(file.absolutePath)
                if (++attempts < 3 || forceFailure) throw PlaybackBlocked(PlaybackBlockReason.FOCUS_DENIED)
            }
        }
        g.speechProvider = object : SpeechProvider {
            override suspend fun synthesize(c: Config, key: String, text: String): ByteArray { syntheses++; return byteArrayOf(1, 2) }
        }
        try {
            startPlatformSession()
            withContext(Dispatchers.Main) {
                g.settings.saveKey("speech", "fixture")
                g.settings.update { it.copy(voice = VoiceEngine.MINIMAX, speechConsent = true, speechVerified = true) }
            }
            delay(400)
            val item = row("retry-" + System.nanoTime())
            withContext(Dispatchers.Main) { g.engine.ingest(item) }
            withTimeout(12_000) { while (g.dao.observe().first().none { it.fingerprint == item.fingerprint && it.state == "done" }) delay(50) }
            assertEquals(3, attempts); assertEquals(1, syntheses); assertEquals(1, paths.toSet().size)
            assertFalse(File(paths.first()).exists())
            attempts = 0; forceFailure = true; paths.clear()
            withContext(Dispatchers.Main) { g.engine.ingest(row("clear-retry-" + System.nanoTime())) }
            withTimeout(5000) { while (attempts == 0) delay(20) }
            withContext(Dispatchers.Main) { g.engine.clearPending() }
            delay(1600)
            assertEquals(1, attempts); assertEquals(0, g.dao.countPending().first())
            assertFalse(File(paths.first()).exists())
            withTimeout(5000) { while (g.wake.active.get() != 0) delay(50) }
        } finally {
            stopPlatformSession(); g.output = originalOutput; g.speechProvider = originalSpeech
        }
    }
    @Test fun diagnosticsAreBoundedExpireAndCannotExportNotificationOrKeyFields() {
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        prefs.edit().putString("events", org.json.JSONArray().put(org.json.JSONObject()
            .put("stage", "NOTICE_STORED").put("time", System.currentTimeMillis() - 90_000_000L)
            .put("body", "private-notification").put("key", "private-key")).toString()).commit()
        val diagnostics = Diagnostics(context)
        assertEquals(0, org.json.JSONObject(diagnostics.export()).getJSONArray("events").length())
        repeat(205) { diagnostics.record(DiagnosticStage.FOCUS_RESULT, value = it) }
        val exported = diagnostics.export()
        assertFalse(exported.contains("private-notification")); assertFalse(exported.contains("private-key"))
        val events = org.json.JSONObject(exported).getJSONArray("events")
        assertEquals(200, events.length()); assertEquals(5, events.getJSONObject(0).getInt("value"))
        val restored = Diagnostics(context)
        assertEquals(200, org.json.JSONObject(restored.export()).getJSONArray("events").length())
        restored.clear(); assertEquals(0, org.json.JSONObject(restored.export()).getJSONArray("events").length())
    }
    @Test fun realDefaultTtsAndFocusProcessBackgroundNotificationsWithoutOpeningActivity() = runBlocking {
        val g = context.graph; val original = g.output
        val played = java.util.concurrent.atomic.AtomicInteger()
        val focus = PlatformAudioFocus(context, g.diagnostics)
        g.output = object : ReadingOutput {
            override fun connected() = true // Emulator has no physical Bluetooth headset.
            override fun callActive() = false
            override fun stop() { }
            override suspend fun play(file: File, allowed: () -> Boolean) {
                val lease = focus.acquire(allowed) { }
                try {
                    assertTrue(file.length() > 44)
                    assertEquals("RIFF", file.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
                    played.incrementAndGet() // Consume the real system TTS file without sending it to speakers.
                } finally { lease.close() }
            }
        }
        val marker = "systemtts" + java.util.UUID.randomUUID().toString().filter { it.isLetter() }
        try {
            startPlatformSession()
            assertTrue("模拟器必须安装默认引擎的离线中文声音，本测试不允许用模拟 TTS 替代", g.local.voices().isNotEmpty())
            withContext(Dispatchers.Main) { g.settings.update { it.copy(apps = setOf("com.android.shell"), voice = VoiceEngine.SYSTEM) } }
            delay(400)
            suspend fun postAndAwait(suffix: String) {
                val body = "这是一条后台中文测试消息" + marker + suffix
                shell("cmd notification post -t system-tts ${marker + suffix} $body")
                withTimeout(35_000) { while (g.dao.observe().first().none { it.body == body && it.state == "done" }) delay(50) }
            }
            postAndAwait("front")
            goHomeAndWait(); postAndAwait("home")
            shell("am start -W -a android.settings.SETTINGS"); postAndAwait("settings")
            shell("input keyevent 223")
            withTimeout(5000) { while (context.getSystemService(android.os.PowerManager::class.java).isInteractive) delay(50) }
            postAndAwait("locked"); delay(10_000); postAndAwait("idle")
            assertEquals(5, played.get())
        } finally { stopPlatformSession(); g.output = original }
    }
    @Test fun duplicatePlayAndResumeCommandsDoNotCancelSpeechOrRequireAnotherClick() = runBlocking {
        val g = context.graph; val originalOutput = g.output; val originalSpeech = g.speechProvider
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var spoken = 0; var synthesized = 0
        g.output = object : ReadingOutput {
            override fun connected() = true
            override fun callActive() = false
            override fun stop() { }
            override suspend fun play(file: File, allowed: () -> Boolean) {
                g.engine.transition(ListeningState.PLAYING)
                entered.complete(Unit); release.await(); assertTrue(allowed()); spoken++
            }
        }
        g.speechProvider = object : SpeechProvider {
            override suspend fun synthesize(c: Config, key: String, text: String): ByteArray { synthesized++; return byteArrayOf(1, 2) }
        }
        try {
            startPlatformSession()
            withContext(Dispatchers.Main) {
                g.settings.saveKey("speech", "fixture")
                g.settings.update { it.copy(voice = VoiceEngine.MINIMAX, speechConsent = true, speechVerified = true) }
            }
            delay(400)
            val item = row("idempotent-" + System.nanoTime())
            val start = g.engine.startedAt
            withContext(Dispatchers.Main) { g.engine.ingest(item) }
            withTimeout(5000) { entered.await() }
            val controller = android.media.session.MediaController(context, g.mediaSession!!.sessionToken)
            controller.transportControls.play(); controller.transportControls.play()
            withContext(Dispatchers.Main) { g.engine.resume(); g.engine.resume(); ui.activity.startReading() }
            delay(300)
            assertFalse(g.engine.paused.value); assertEquals(ListeningState.PLAYING, g.engine.state.value)
            assertEquals(start, g.engine.startedAt)
            release.complete(Unit)
            withTimeout(5000) { while (g.dao.observe().first().none { it.fingerprint == item.fingerprint && it.state == "done" }) delay(50) }
            assertEquals(1, spoken); assertEquals(1, synthesized)
            withTimeout(5000) { while (g.engine.state.value != ListeningState.WAITING) delay(50) }
            assertFalse(g.mediaSession!!.isActive)
            withContext(Dispatchers.Main) { g.engine.pause(); g.engine.pause() }
            assertTrue(g.engine.paused.value); assertEquals(ListeningState.PAUSED, g.engine.state.value)
            val pausedItem = row("paused-" + System.nanoTime())
            withContext(Dispatchers.Main) { g.engine.ingest(pausedItem) }
            val resumedItem = row("resumed-" + System.nanoTime())
            withContext(Dispatchers.Main) { g.engine.resume(); g.engine.resume(); g.engine.ingest(resumedItem) }
            withTimeout(5000) { while (g.dao.observe().first().none { it.fingerprint == resumedItem.fingerprint && it.state == "done" }) delay(50) }
            withTimeout(5000) { while (g.engine.state.value != ListeningState.WAITING) delay(50) }
            assertFalse(g.engine.paused.value)
            assertEquals(2, spoken); assertEquals(2, synthesized)
            assertEquals("held", g.dao.observe().first().first { it.fingerprint == pausedItem.fingerprint }.state)
            val racingItem = row("resume-barrier-" + System.nanoTime())
            withContext(Dispatchers.Main) { g.engine.pause(); g.engine.resume(); g.engine.ingest(racingItem) }
            withTimeout(5000) { while (g.dao.observe().first().none { it.fingerprint == racingItem.fingerprint && it.state == "done" }) delay(50) }
            assertEquals(3, spoken); assertEquals(3, synthesized)
        } finally { release.complete(Unit); stopPlatformSession(); g.output = originalOutput; g.speechProvider = originalSpeech }
    }
    @Test fun firstNotificationAutomaticallyStartsRealPlayerWithoutAnyPlayCommand() = runBlocking {
        org.junit.Assume.assumeTrue("Real player probe is isolated to the task's no-audio emulator", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val g = context.graph; val originalOutput = g.output
        val manager = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val strict = android.os.Build.VERSION.SDK_INT >= 37
        var hardeningCommand = ""
        val baseline = shellText("logcat -d -s AudioHardening:V AudioService:V")
        g.output = EmulatorPlayerProbe(context)
        try {
            if (strict) {
                // Some API 37 images silently ignore unknown shell verbs, even "help".
                // Verify the platform override rather than relying on a successful exit code.
                for (verb in listOf("set-enable-hardening", "set-hardening")) {
                    shellText("cmd audio $verb throw")
                    if (shellText("dumpsys audio").contains("mHardeningOverride=3")) { hardeningCommand = verb; break }
                }
                assertTrue("Strict throw override was not applied", hardeningCommand.isNotBlank())
                android.util.Log.i("TingXunAutoTest", "hardening=$hardeningCommand override=3 mode=throw")
            }
            for (scenario in listOf("home", "settings", "lock", "lockwait")) {
                startPlatformSession("com.android.shell")
                val marker = "automatic" + java.util.UUID.randomUUID().toString().filter { it.isLetter() }
                // No foreground preview, media play command, or manual now()/releaseHeld() is permitted.
                if (scenario == "home") goHomeAndWait()
                else if (scenario == "settings") shell("am start -W -a android.settings.SETTINGS")
                else {
                    shell("input keyevent 223")
                    withTimeout(5000) { while (power.isInteractive) delay(50) }
                    if (scenario == "lockwait") delay(10_000)
                }
                assertTrue("Default engine needs an offline Chinese voice", g.local.voices().isNotEmpty())
                val count = if (scenario == "lockwait") 5 else 1
                for (i in 0 until count) {
                    val tag = marker + ('a' + i)
                    shell("cmd notification post -t message $tag 你好")
                    withTimeout(40_000) {
                        while (g.dao.observe().first().none { it.sourceKey.contains(tag) && it.state == "done" }) delay(50)
                    }
                    withTimeout(5000) { while (g.engine.state.value != ListeningState.WAITING) delay(50) }
                    assertTrue(g.engine.running.value); assertFalse(g.engine.paused.value)
                    assertFalse("Waiting for next notification must not expose a resumable media session", g.mediaSession!!.isActive)
                    withTimeout(5000) {
                        while (manager.activeNotifications.firstOrNull { it.id == 42 }?.notification?.extras?.getString(Notification.EXTRA_TEMPLATE) == Notification.MediaStyle::class.java.name) delay(50)
                    }
                }
                val events = org.json.JSONObject(g.diagnostics.export()).getJSONArray("events")
                assertFalse((0 until events.length()).any { events.getJSONObject(it).let { e ->
                    e.getString("stage") == "MEDIA_PLAY_COMMAND" && e.getLong("time") >= g.engine.startedAt
                } })
                android.util.Log.i("TingXunAutoTest", "case=$scenario sdk=${android.os.Build.VERSION.SDK_INT} notifications=$count realTts=true realFocus=true realPlayer=true manualPlay=0")
                stopPlatformSession()
            }
            if (strict) {
                val after = shellText("logcat -d -s AudioHardening:V AudioService:V")
                val added = after.lineSequence().filter { it !in baseline && it.contains("AudioHardening") && it.contains("cn.tingxun.app") }.toList()
                assertTrue("AudioHardening rejected the foreground autoplay session: $added", added.isEmpty())
            }
        } finally {
            if (hardeningCommand.isNotBlank()) shell("cmd audio $hardeningCommand enable")
            stopPlatformSession(); g.output = originalOutput
        }
    }
    private fun shellText(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { parcel ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).use { String(it.readBytes(), Charsets.UTF_8) }
        }

    @Test fun missingCallbackIsRecoveredFromSnapshotInBackgroundAndRemainsDeduplicated() = runBlocking {
        val g = context.graph; val originalOutput = g.output; val originalSpeech = g.speechProvider
        var played = 0
        g.output = object : ReadingOutput {
            override fun connected() = true
            override fun callActive() = false
            override fun stop() { }
            override suspend fun play(file: File, allowed: () -> Boolean) { assertTrue(allowed()); played++ }
        }
        g.speechProvider = object : SpeechProvider {
            override suspend fun synthesize(c: Config, key: String, text: String) = byteArrayOf(1, 2)
        }
        try {
            startPlatformSession()
            withContext(Dispatchers.Main) {
                g.settings.saveKey("speech", "fixture")
                g.settings.update { it.copy(voice = VoiceEngine.MINIMAX, speechConsent = true, speechVerified = true) }
            }
            delay(400); goHomeAndWait()
            shell("input keyevent 223")
            withTimeout(5000) { while (context.getSystemService(android.os.PowerManager::class.java).isInteractive) delay(50) }
            val capture = g.capture.get()!!
            fun notice(tag: String, body: String, post: Long = System.currentTimeMillis(), flags: Int = 0): android.service.notification.StatusBarNotification {
                val n = Notification.Builder(context, ListeningService.CHANNEL).setSmallIcon(R.drawable.ic_status)
                    .setContentTitle("fixture").setContentText(body).build().apply { this.flags = flags }
                return android.service.notification.StatusBarNotification("test.app", "test.app", tag.hashCode(), tag,
                    android.os.Process.myUid(), 0, 0, n, android.os.Process.myUserHandle(), post)
            }
            val marker = "missed-" + System.nanoTime()
            val first = notice(marker, "first")
            withContext(Dispatchers.Main) { capture.acceptSnapshot(arrayOf(first, notice(marker + "old", "old", g.engine.startedAt - 1),
                notice(marker + "ongoing", "status", flags = Notification.FLAG_ONGOING_EVENT),
                notice(marker + "group", "group", flags = Notification.FLAG_GROUP_SUMMARY))) }
            withTimeout(5000) { while (played != 1) delay(50) }
            withContext(Dispatchers.Main) { capture.acceptSnapshot(arrayOf(first)); capture.onNotificationPosted(first) }
            delay(300); assertEquals(1, played)
            val changed = notice(marker, "changed", first.postTime)
            withContext(Dispatchers.Main) { capture.acceptSnapshot(arrayOf(changed)) }
            withTimeout(5000) { while (played != 2) delay(50) }
            withContext(Dispatchers.Main) { g.engine.clearPending() }; delay(300)
            withContext(Dispatchers.Main) { capture.acceptSnapshot(arrayOf(changed)); capture.onNotificationPosted(changed) }
            delay(300); assertEquals(2, played)
            val report = org.json.JSONObject(g.diagnostics.export())
            assertTrue(report.getJSONArray("events").let { e -> (0 until e.length()).any { e.getJSONObject(it).getString("stage") == "NOTICE_RECOVERED" } })
            assertTrue(report.getJSONObject("capture").getLong("SNAPSHOT_OK") > 0)
            assertEquals("INSTANT", report.getJSONObject("runtime").getString("mode"))
        } finally { stopPlatformSession(); g.output = originalOutput; g.speechProvider = originalSpeech }
    }
}
