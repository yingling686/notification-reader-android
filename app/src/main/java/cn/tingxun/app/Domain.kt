package cn.tingxun.app

import java.security.MessageDigest
import java.time.LocalTime

enum class ReadMode(val title: String) { INSTANT("即时朗读"), PERIODIC("定时朗读"), SUMMARY("AI 定时摘要"), MIXED("混合模式") }
enum class VoiceEngine(val title: String) { SYSTEM("系统机读"), MINIMAX("AI 合成语音") }
enum class CloudProtocol(val title: String) { MINIMAX("MiniMax"), OPENAI("OpenAI 兼容接口") }
enum class TokenField(val title: String) { MAX_TOKENS("max_tokens（常用兼容）"), MAX_COMPLETION_TOKENS("max_completion_tokens"), OMIT("不发送长度参数") }
data class PriorityRule(val app: String, val keyword: String)
data class Config(
    val mode: ReadMode = ReadMode.INSTANT,
    val voice: VoiceEngine = VoiceEngine.SYSTEM,
    val interval: Int = 5,
    val apps: Set<String> = emptySet(),
    val rules: List<PriorityRule> = emptyList(),
    val summaryConsent: Boolean = false,
    val speechConsent: Boolean = false,
    val speechVerified: Boolean = false,
    val summaryProtocol: CloudProtocol = CloudProtocol.MINIMAX,
    val speechProtocol: CloudProtocol = CloudProtocol.MINIMAX,
    val openaiSummaryBase: String = "https://api.openai.com/v1",
    val openaiSummaryModel: String = "",
    val openaiTokenField: TokenField = TokenField.MAX_TOKENS,
    val openaiSpeechBase: String = "https://api.openai.com/v1",
    val openaiSpeechModel: String = "gpt-4o-mini-tts",
    val openaiVoice: String = "alloy",
    val summaryUrl: String = "https://api.minimax.cn/v1/chat/completions",
    val summaryModel: String = "MiniMax-M3.1-Flash-Preview",
    val speechUrl: String = "wss://api.minimax.cn/ws/v1/t2a_v2",
    val speechModel: String = "speech-2.8-turbo",
    val voiceId: String = "male-qn-qingse",
    val systemVoice: String = "",
    val speed: Float = 1f,
    val fallback: Boolean = true,
    val quiet: Boolean = false,
    val quietStart: Int = 22 * 60,
    val quietEnd: Int = 8 * 60,
    val summaryLimit: Int = 300,
    val speechLimit: Int = 300,
    val summaryBlocked: Boolean = false,
    val hourly: Boolean = false,
    val onlyScreenOff: Boolean = false,
    val hourlySummary: Boolean = false
) {
    fun summaryKeyKind() = if (summaryProtocol == CloudProtocol.MINIMAX) "summary" else "openai_summary"
    fun speechKeyKind() = if (speechProtocol == CloudProtocol.MINIMAX) "speech" else "openai_speech"
    fun summaryAddress() = if (summaryProtocol == CloudProtocol.MINIMAX) summaryUrl else ApiAddress.complete(openaiSummaryBase, "chat/completions")
    fun speechAddress() = if (speechProtocol == CloudProtocol.MINIMAX) speechUrl else ApiAddress.complete(openaiSpeechBase, "audio/speech")
    fun summaryModelName() = if (summaryProtocol == CloudProtocol.MINIMAX) summaryModel else openaiSummaryModel
    fun speechModelName() = if (speechProtocol == CloudProtocol.MINIMAX) speechModel else openaiSpeechModel
    fun speechVoiceName() = if (speechProtocol == CloudProtocol.MINIMAX) voiceId else openaiVoice
}

object ApiAddress {
    fun complete(base: String, resource: String): String {
        val clean = base.trim().trimEnd('/')
        require(clean.isNotBlank()) { "请填写 API Base URL" }
        val uri = java.net.URI(clean)
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && !uri.host.isNullOrBlank()) { "接口地址格式无效" }
        if (uri.path.endsWith("/$resource")) return clean
        require(!uri.path.endsWith("/chat/completions") && !uri.path.endsWith("/audio/speech")) { "接口类型不匹配，请填写对应的 Base URL" }
        return clean + (if (uri.path.isNullOrBlank()) "/v1" else "") + "/$resource"
    }
}

object Policy {
    fun quiet(c: Config, now: LocalTime = LocalTime.now()): Boolean {
        if (!c.quiet) return false
        val minute = now.hour * 60 + now.minute
        return if (c.quietStart == c.quietEnd) true
        else if (c.quietStart < c.quietEnd) minute in c.quietStart until c.quietEnd
        else minute >= c.quietStart || minute < c.quietEnd
    }
    fun priority(c: Config, app: String, title: String, text: String) = c.rules.any {
        it.app == app && it.keyword.isNotBlank() && "$title\n$text".contains(it.keyword, ignoreCase = true)
    }
    fun instant(c: Config, app: String, title: String, text: String) = c.mode == ReadMode.INSTANT ||
        (c.mode == ReadMode.MIXED && priority(c, app, title, text))
    fun needsSummary(c: Config) = c.mode == ReadMode.SUMMARY || c.mode == ReadMode.MIXED
    fun fingerprint(key: String, sender: String, text: String, messageTime: Long?) =
        MessageDigest.getInstance("SHA-256").digest("$key\u0000$sender\u0000${messageTime ?: ""}\u0000$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
    fun cleanAnswer(raw: String): String = raw.replace(Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        .replace(Regex("<think>.*$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "").trim()
    fun spokenBody(text: String): String = if (text.length > 3000) text.take(3000) + "。内容较长，请在应用中查看全文。" else text
    fun segments(text: String, max: Int = 1000): List<String> {
        require(max > 0)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + max).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = text.substring(start, end).indexOfLast { it in "。！？；\n.!?" }
                if (boundary >= max / 2) end = start + boundary + 1
                if (end > start && Character.isHighSurrogate(text[end - 1])) end--
            }
            require(end > start)
            result += text.substring(start, end)
            start = end
        }
        return result.filter { it.isNotBlank() }
    }
    fun batch(rows: List<NoticeRow>): List<NoticeRow> {
        val result = mutableListOf<NoticeRow>(); var length = 0
        for (row in rows) {
            val size = wireText(row).length
            if (result.size == 50 || length + size > 12000) break
            result += row; length += size
        }
        return result
    }
    fun wireText(row: NoticeRow) = "[${row.id}] ${row.label}｜${row.title}｜${row.body}\n"
    fun localReading(rows: List<NoticeRow>): String = rows.groupBy { it.app to it.title }.values.joinToString("。\n") { group ->
        "${group.first().label}，${group.first().title}：" + group.joinToString("。") { spokenBody(it.body) }
    }
}
