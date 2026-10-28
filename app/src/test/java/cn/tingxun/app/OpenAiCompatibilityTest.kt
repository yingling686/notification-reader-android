package cn.tingxun.app

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class OpenAiCompatibilityTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }
    private fun client(timeout: Long = 1000) = MiniMaxClient(OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build(), true, timeout)
    private fun config() = Config(summaryProtocol = CloudProtocol.OPENAI, speechProtocol = CloudProtocol.OPENAI,
        summaryConsent = true, speechConsent = true, openaiSummaryBase = server.url("/v1").newBuilder().host("localhost").build().toString(),
        openaiSummaryModel = "provider-text", openaiSpeechBase = server.url("/v1").newBuilder().host("localhost").build().toString(),
        openaiSpeechModel = "provider-speech", openaiVoice = "provider-voice")
    private fun answer() = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
        JSONObject().put("content", "{\"important\":true,\"summary\":\"同事请你确认明天会议。\"}")))).toString()
    @Test fun baseUrlsAndFullEndpointsResolveWithoutDuplicatePaths() {
        assertEquals("https://example.com/v1/chat/completions", ApiAddress.complete("https://example.com", "chat/completions"))
        assertEquals("https://example.com/v1/chat/completions", ApiAddress.complete("https://example.com/v1/", "chat/completions"))
        assertEquals("https://example.com/v1/audio/speech", ApiAddress.complete("https://example.com/v1/audio/speech/", "audio/speech"))
        assertEquals("https://example.com/proxy/api/audio/speech", ApiAddress.complete("https://example.com/proxy/api", "audio/speech"))
    }
    @Test fun wrongResourceAndCredentialBearingUrlsAreRejected() {
        for (base in listOf("https://example.com/v1/audio/speech", "https://secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#fragment")) {
            try { ApiAddress.complete(base, "chat/completions"); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun genericSummarySendsOnlyPortableFieldsAndConfiguredModel() = runBlocking {
        server.enqueue(MockResponse().setBody(answer()))
        assertTrue(client().summarize(config(), "summary-dummy", "固定通知").important)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("provider-text", body.getString("model")); assertEquals(4096, body.getInt("max_tokens"))
        assertFalse(body.has("max_completion_tokens")); assertFalse(body.has("reasoning_split")); assertFalse(body.has("reasoning_effort"))
        assertEquals("Bearer summary-dummy", request.getHeader("Authorization"))
    }
    @Test fun genericSummarySupportsNewOrOmittedTokenLimitWithoutRetrying() = runBlocking {
        for (field in listOf(TokenField.MAX_COMPLETION_TOKENS, TokenField.OMIT)) {
            server.enqueue(MockResponse().setBody(answer()))
            client().summarize(config().copy(openaiTokenField = field), "dummy", "固定通知")
            val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(body.has("max_tokens")); assertEquals(field == TokenField.MAX_COMPLETION_TOKENS, body.has("max_completion_tokens"))
        }
        assertEquals(2, server.requestCount)
    }
    @Test fun genericSpeechPostsStandardPayloadAndReturnsBinaryMp3() = runBlocking {
        val bytes = "ID3fixture-audio".toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(bytes)))
        assertArrayEquals(bytes, client().synthesize(config().copy(speed = 1.2f), "speech-dummy", "固定试听"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/v1/audio/speech", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("provider-speech", body.getString("model")); assertEquals("固定试听", body.getString("input"))
        assertEquals("provider-voice", body.getString("voice")); assertEquals("mp3", body.getString("response_format"))
        assertEquals(1.2, body.getDouble("speed"), 0.001); assertFalse(body.has("voice_setting"))
        assertEquals("Bearer speech-dummy", request.getHeader("Authorization"))
    }
    @Test fun fullSpeechEndpointIsNotAppendedTwice() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("ID3audio"))
        client().synthesize(config().copy(openaiSpeechBase = config().openaiSpeechBase + "/audio/speech"), "dummy", "固定试听")
        assertEquals("/v1/audio/speech", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }
    @Test fun jsonHtmlAndEmptyAudioAreNeverPlayed() = runBlocking {
        for (response in listOf(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"error\":\"secret\"}"),
            MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login</html>"),
            MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("{}"), MockResponse().setBody(""))) {
            server.enqueue(response)
            try { client().synthesize(config(), "dummy", "固定试听"); fail() } catch (e: CloudFailure) { assertFalse(e.message.orEmpty().contains("secret")) }
        }
    }
    @Test fun speechAuthFailureIsReportedWithoutForwardingKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret"))
        try { client().synthesize(config(), "dummy", "固定试听"); fail() } catch (e: CloudFailure) { assertTrue(e.auth); assertFalse(e.message.orEmpty().contains("secret")) }
        assertEquals(1, server.requestCount)
    }
    @Test fun speechRedirectDoesNotForwardKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        try { client().synthesize(config(), "dummy", "固定试听"); fail() } catch (_: CloudFailure) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun speechTimeoutCancelsHttpRequest() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("ID3audio"))
        try { client(100).synthesize(config(), "dummy", "固定试听"); fail() } catch (_: TimeoutCancellationException) { }
    }
    @Test fun genericSpeechStillRequiresExplicitConsent() = runBlocking {
        try { client().synthesize(config().copy(speechConsent = false), "dummy", "固定试听"); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun providerKeysAreDistinctAndLegacySettingsKeepTheirNames() {
        assertEquals("summary", Config().summaryKeyKind()); assertEquals("speech", Config().speechKeyKind())
        assertEquals("openai_summary", config().summaryKeyKind()); assertEquals("openai_speech", config().speechKeyKind())
    }
}
