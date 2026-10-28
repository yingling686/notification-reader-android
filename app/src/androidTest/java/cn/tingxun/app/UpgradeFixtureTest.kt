package cn.tingxun.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly invoked against signed release APKs on a disposable emulator only. */
@RunWith(AndroidJUnit4::class)
class UpgradeFixtureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val snapshot get() = context.getSharedPreferences("upgrade_fixture", Context.MODE_PRIVATE)
    @Test fun seed() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradePhase") == "seed")
        val g = context.graph
        g.settings.reset()
        for (kind in listOf("summary", "speech", "openai_summary", "openai_speech")) g.settings.saveKey(kind, "upgrade-fixture-$kind")
        g.settings.update { it.copy(apps = setOf("test.app", "com.android.settings"), rules = listOf(PriorityRule("test.app", "fixture-rule")),
            mode = ReadMode.PERIODIC, voice = VoiceEngine.SYSTEM, interval = 10, speed = 1.3f,
            summaryProtocol = CloudProtocol.OPENAI, openaiSummaryBase = "https://example.invalid/v1", openaiSummaryModel = "fixture-text",
            speechProtocol = CloudProtocol.OPENAI, openaiSpeechBase = "https://example.invalid/v1", openaiSpeechModel = "fixture-speech", openaiVoice = "fixture-voice",
            quiet = true, quietStart = 1380, quietEnd = 420, fallback = false, summaryLimit = 123, speechLimit = 234) }
        g.settings.update { it.copy(summaryConsent = true, speechConsent = true, speechVerified = true) }
        g.settings.consume("summary", 10000)
        g.dao.insert(NoticeRow(fingerprint = "upgrade-fixture", sourceKey = "fixture", app = "test.app", label = "测试应用",
            title = "测试", body = "合成升级测试资料", receivedAt = System.currentTimeMillis(), instant = false))
        snapshot.edit().putString("config", context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("config", ""))
            .putInt("usage", g.settings.used("summary")).commit()
        assertFalse(g.engine.running.value)
    }
    @Test fun verify() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradePhase") == "verify")
        val g = context.graph
        assertEquals(snapshot.getString("config", "missing"), context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("config", ""))
        for (kind in listOf("summary", "speech", "openai_summary", "openai_speech")) assertEquals("upgrade-fixture-$kind", g.settings.key(kind))
        assertEquals(snapshot.getInt("usage", -1), g.settings.used("summary"))
        assertEquals(2, g.settings.config.value.apps.size)
        assertEquals(1, g.settings.config.value.rules.size)
        assertTrue(g.settings.config.value.summaryConsent)
        assertTrue(g.settings.config.value.speechConsent)
        assertTrue(g.settings.config.value.speechVerified)
        assertFalse(g.settings.config.value.hourly)
        assertFalse(g.settings.config.value.onlyScreenOff)
        assertFalse(g.settings.config.value.hourlySummary)
        assertTrue(g.dao.observe().first().any { it.fingerprint == "upgrade-fixture" })
        assertFalse(g.engine.running.value)
    }
}
