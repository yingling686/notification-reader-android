package cn.tingxun.app

import org.junit.Assert.*
import org.junit.Test

class SystemVoicePolicyTest {
    private val oplus = "com.oplus.ttsaccessibilityengine"
    @Test fun recognizesTheThreeOfflineChineseVoicesInTheReportedOplusCatalog() {
        val catalog = listOf("chn", "chn", "chn", "usa", "usa")
        assertEquals(3, catalog.count { SystemVoicePolicy.offlineChinese(it, false, oplus) })
        assertEquals("中文", SystemVoicePolicy.label("chn", oplus, "chn"))
        assertEquals("English", SystemVoicePolicy.label("usa", oplus, "English"))
    }
    @Test fun aliasIsRestrictedToThisEngineAndNeverEnablesNetworkVoices() {
        assertFalse(SystemVoicePolicy.offlineChinese("chn", true, oplus))
        assertFalse(SystemVoicePolicy.chinese("chn", "other.engine"))
        assertFalse(SystemVoicePolicy.chinese("chn", null))
        assertFalse(SystemVoicePolicy.offlineChinese("usa", false, oplus))
        assertFalse(SystemVoicePolicy.offlineChinese("en", false, oplus))
    }
    @Test fun standardChineseSupportIsUnchangedAcrossOtherEngines() {
        for (engine in listOf(null, "other.engine", oplus)) {
            assertTrue(SystemVoicePolicy.offlineChinese("zh", false, engine))
            assertFalse(SystemVoicePolicy.offlineChinese("zh", true, engine))
        }
    }
}
