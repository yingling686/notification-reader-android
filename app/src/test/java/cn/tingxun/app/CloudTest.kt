package cn.tingxun.app

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CloudTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }
    private fun client(timeout: Long = 1000) = MiniMaxClient(OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build(), true, timeout)
    private fun config() = Config(summaryConsent = true, speechConsent = true,
        summaryUrl = server.url("/v1/chat/completions").newBuilder().host("localhost").build().toString(),
        speechUrl = server.url("/ws/v1/t2a_v2").newBuilder().host("localhost").build().toString().replaceFirst("http", "ws"))
    private fun answer(content: String, reason: String = "stop") = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", reason)
        .put("message", JSONObject().put("content", content).put("reasoning_content", "绝不能朗读的思考")))).toString()
    @Test fun summaryUsesOnlyFinalBodyAndLowEffort() = runBlocking {
        server.enqueue(MockResponse().setBody(answer("<think>隐藏</think>{\"important\":true,\"summary\":\"明天下午三点开会，请回复。\"}")))
        val result = client().summarize(config(), "dummy", "固定测试通知")
        assertTrue(result.important); assertEquals("明天下午三点开会，请回复。", result.text)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        val body = JSONObject(request.body.readUtf8())
        assertEquals("low", body.getString("reasoning_effort")); assertEquals("Bearer dummy", request.getHeader("Authorization"))
        assertFalse(body.has("tools")); assertFalse(body.has("thinking"))
    }
    @Test fun unimportantMessagesProduceNoSpeech() = runBlocking {
        server.enqueue(MockResponse().setBody(answer("{\"important\":false,\"summary\":\"闲聊\"}")))
        val result = client().summarize(config(), "dummy", "闲聊")
        assertFalse(result.important); assertEquals("", result.text)
    }
    @Test fun unauthorizedSummaryDoesNotMakeNetworkRequest() = runBlocking {
        try { client().summarize(config().copy(summaryConsent = false), "dummy", "内容"); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun unauthorizedSpeechDoesNotMakeNetworkRequest() = runBlocking {
        try { client().synthesize(config().copy(speechConsent = false), "dummy", "内容"); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun authFailureAndRateLimitAreDistinguished() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { client().summarize(config(), "dummy", "通知"); fail() } catch (e: CloudFailure) { assertTrue(e.auth); assertFalse(e.retryable) }
        server.enqueue(MockResponse().setResponseCode(429))
        try { client().summarize(config(), "dummy", "通知"); fail() } catch (e: CloudFailure) { assertTrue(e.retryable); assertFalse(e.auth) }
    }
    @Test fun malformedEmptyAndLengthLimitedResponsesAreRejected() = runBlocking {
        for (body in listOf(answer(""), answer("普通文本"), answer("{}"), answer("{\"important\":true,\"summary\":\"\"}"), answer("", "length"))) {
            server.enqueue(MockResponse().setBody(body))
            try { client().summarize(config(), "dummy", "通知"); fail("无效响应不应被接受") } catch (_: CloudFailure) { }
        }
    }
    @Test fun redirectedRequestsDoNotForwardCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        try { client().summarize(config(), "dummy", "通知"); fail() } catch (_: CloudFailure) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun websocketUsesOrderedProtocolAndConcatenatesHexFragments() = runBlocking {
        val events = mutableListOf<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"event\":\"connected_success\"}") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = JSONObject(text); events += o.getString("event")
                when (o.getString("event")) {
                    "task_start" -> { assertEquals("speech-2.8-turbo", o.getString("model")); webSocket.send("{\"event\":\"task_started\"}") }
                    "task_continue" -> webSocket.send("{\"event\":\"task_continued\",\"data\":{\"audio\":\"4944\"}}")
                    "task_finish" -> { webSocket.send("{\"data\":{\"audio\":\"33\"},\"is_final\":true}"); webSocket.send("{\"event\":\"task_finished\"}") }
                }
            }
        }))
        assertArrayEquals("ID3".toByteArray(), client(3000).synthesize(config(), "dummy", "测试语音"))
        assertEquals(listOf("task_start", "task_continue", "task_finish"), events)
    }
    @Test fun websocketBusinessFailureIsNotAudio() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"event\":\"task_failed\",\"base_resp\":{\"status_code\":1004}}") }
        }))
        try { client().synthesize(config(), "dummy", "测试"); fail() } catch (e: CloudFailure) { assertTrue(e.auth) }
    }
    @Test fun websocketTimeoutIsCancellable() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        try { client(150).synthesize(config(), "dummy", "测试"); fail() } catch (_: TimeoutCancellationException) { }
    }
    @Test fun websocketPrematureCloseFails() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1000, "early") }
        }))
        try { client().synthesize(config(), "dummy", "测试"); fail() } catch (_: CloudFailure) { }
    }
    @Test fun invalidHexAndOversizedAudioAreRejected() {
        for (hex in listOf("abc", "zz", "00".repeat(MiniMaxClient.MAX_AUDIO + 1))) {
            try { MiniMaxClient.appendAudio(JSONObject().put("data", JSONObject().put("audio", hex)), ByteArrayOutputStream()); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun productionRejectsInsecureEndpointsBeforeNetwork() = runBlocking {
        try { MiniMaxClient().summarize(config(), "dummy", "通知"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }
}
