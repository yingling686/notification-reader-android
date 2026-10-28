package cn.tingxun.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

@android.annotation.SuppressLint("ApplySharedPref")
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val usage = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    init {
        val today = LocalDate.now().toString() + "_"
        val editor = usage.edit()
        usage.all.keys.filterNot { it.startsWith(today) }.forEach { editor.remove(it) }
        editor.commit()
    }
    val config = MutableStateFlow(load())
    val revision = MutableStateFlow(0L)
    var beforeChange: (() -> Unit)? = null
    private val keyCache = mutableMapOf<String, String>()
    private fun load(): Config = try {
        val o = JSONObject(prefs.getString("config", "{}")!!)
        val apps = o.optJSONArray("apps") ?: JSONArray(); val rules = o.optJSONArray("rules") ?: JSONArray()
        Config(
            mode = ReadMode.valueOf(o.optString("mode", "INSTANT")), voice = VoiceEngine.valueOf(o.optString("voice", "SYSTEM")),
            interval = o.optInt("interval", 5), apps = (0 until apps.length()).map { apps.getString(it) }.toSet(),
            rules = (0 until rules.length()).map { val r = rules.getJSONObject(it); PriorityRule(r.getString("app"), r.getString("keyword")) },
            summaryConsent = o.optBoolean("summaryConsent"), speechConsent = o.optBoolean("speechConsent"),
            speechVerified = o.optBoolean("speechVerified"),
            summaryProtocol = CloudProtocol.valueOf(o.optString("summaryProtocol", "MINIMAX")),
            speechProtocol = CloudProtocol.valueOf(o.optString("speechProtocol", "MINIMAX")),
            openaiSummaryBase = o.optString("openaiSummaryBase", Config().openaiSummaryBase),
            openaiSummaryModel = o.optString("openaiSummaryModel", ""),
            openaiTokenField = TokenField.valueOf(o.optString("openaiTokenField", "MAX_TOKENS")),
            openaiSpeechBase = o.optString("openaiSpeechBase", Config().openaiSpeechBase),
            openaiSpeechModel = o.optString("openaiSpeechModel", Config().openaiSpeechModel),
            openaiVoice = o.optString("openaiVoice", "alloy"),
            summaryUrl = o.optString("summaryUrl", Config().summaryUrl), summaryModel = o.optString("summaryModel", Config().summaryModel),
            speechUrl = o.optString("speechUrl", Config().speechUrl), speechModel = o.optString("speechModel", Config().speechModel),
            voiceId = o.optString("voiceId", Config().voiceId), systemVoice = o.optString("systemVoice"),
            speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2f), fallback = o.optBoolean("fallback", true),
            quiet = o.optBoolean("quiet"), quietStart = o.optInt("quietStart", 1320), quietEnd = o.optInt("quietEnd", 480),
            summaryLimit = o.optInt("summaryLimit", 300), speechLimit = o.optInt("speechLimit", 300), summaryBlocked = o.optBoolean("summaryBlocked"),
            hourly = o.optBoolean("hourly"), onlyScreenOff = o.optBoolean("onlyScreenOff"), hourlySummary = o.optBoolean("hourlySummary")
        )
    } catch (_: Exception) { Config() }
    @Synchronized fun update(change: (Config) -> Config) {
        val proposed = change(config.value)
        val old = config.value
        val speechChanged = proposed.speechProtocol != old.speechProtocol || proposed.speechUrl != old.speechUrl || proposed.speechModel != old.speechModel || proposed.voiceId != old.voiceId ||
            proposed.openaiSpeechBase != old.openaiSpeechBase || proposed.openaiSpeechModel != old.openaiSpeechModel || proposed.openaiVoice != old.openaiVoice
        var c = if (!proposed.speechConsent || speechChanged)
            proposed.copy(speechVerified = false, voice = VoiceEngine.SYSTEM) else proposed
        if (proposed.summaryProtocol != old.summaryProtocol || proposed.summaryUrl != old.summaryUrl || proposed.openaiSummaryBase != old.openaiSummaryBase)
            c = c.copy(summaryConsent = false, summaryBlocked = false)
        if (proposed.speechProtocol != old.speechProtocol || proposed.speechUrl != old.speechUrl || proposed.openaiSpeechBase != old.openaiSpeechBase)
            c = c.copy(speechConsent = false)
        val o = JSONObject().put("mode", c.mode.name).put("voice", c.voice.name).put("interval", c.interval)
            .put("apps", JSONArray(c.apps.toList())).put("rules", JSONArray(c.rules.map { JSONObject().put("app", it.app).put("keyword", it.keyword) }))
            .put("summaryConsent", c.summaryConsent).put("speechConsent", c.speechConsent)
            .put("speechVerified", c.speechVerified)
            .put("summaryProtocol", c.summaryProtocol.name).put("speechProtocol", c.speechProtocol.name)
            .put("openaiSummaryBase", c.openaiSummaryBase).put("openaiSummaryModel", c.openaiSummaryModel).put("openaiTokenField", c.openaiTokenField.name)
            .put("openaiSpeechBase", c.openaiSpeechBase).put("openaiSpeechModel", c.openaiSpeechModel).put("openaiVoice", c.openaiVoice)
            .put("summaryUrl", c.summaryUrl).put("summaryModel", c.summaryModel).put("speechUrl", c.speechUrl).put("speechModel", c.speechModel)
            .put("voiceId", c.voiceId).put("systemVoice", c.systemVoice).put("speed", c.speed).put("fallback", c.fallback)
            .put("quiet", c.quiet).put("quietStart", c.quietStart).put("quietEnd", c.quietEnd)
            .put("summaryLimit", c.summaryLimit).put("speechLimit", c.speechLimit).put("summaryBlocked", c.summaryBlocked)
            .put("hourly", c.hourly).put("onlyScreenOff", c.onlyScreenOff).put("hourlySummary", c.hourlySummary)
        beforeChange?.invoke()
        check(prefs.edit().putString("config", o.toString()).commit()) { "设置保存失败" }
        config.value = c; revision.value++
    }
    @Synchronized fun saveKey(kind: String, value: String) {
        require(kind in setOf("summary", "speech", "openai_summary", "openai_speech"))
        require(value.trim().all { it.code in 33..126 }) { "API Key 不能包含空白或控制字符" }
        beforeChange?.invoke()
        val editor = prefs.edit()
        if (value.isBlank()) editor.remove("key_$kind") else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            editor.putString("key_$kind", encode(cipher.iv) + ":" + encode(cipher.doFinal(value.trim().toByteArray())))
        }
        check(editor.commit()) { "密钥保存失败" }
        keyCache[kind] = value.trim()
        if (kind == config.value.speechKeyKind()) update { it.copy(speechVerified = false, voice = VoiceEngine.SYSTEM) }
        revision.value++
    }
    @Synchronized fun key(kind: String): String {
        val cached = keyCache[kind]
        if (cached != null) return cached
        val stored = prefs.getString("key_$kind", "")!!
        if (stored.isBlank()) return ""
        val value = try {
            val parts = stored.split(':'); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, decode(parts[0])))
            String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("密钥无法解密，请重新填写") }
        keyCache[kind] = value
        return value
    }
    fun hasKey(kind: String) = !prefs.getString("key_$kind", "").isNullOrBlank()
    @Synchronized fun consume(kind: String, limit: Int): Int {
        val name = "${LocalDate.now()}_$kind"; val count = usage.getInt(name, 0)
        check(count < limit) { "今日${if (kind == "summary") "摘要" else "语音"}请求已达上限 $limit" }
        check(usage.edit().putInt(name, count + 1).commit()) { "调用计数保存失败" }
        return count + 1
    }
    fun used(kind: String) = usage.getInt("${LocalDate.now()}_$kind", 0)
    @Synchronized fun reset() {
        beforeChange?.invoke()
        check(prefs.edit().clear().commit()); keyCache.clear()
        config.value = Config(); revision.value++
    }
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("tingxun-api", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("tingxun-api", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
