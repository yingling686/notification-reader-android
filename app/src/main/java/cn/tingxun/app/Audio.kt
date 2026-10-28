package cn.tingxun.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

interface EarphonePlayer { suspend fun play(file: File, allowed: () -> Boolean); fun stop() }
interface ReadingOutput : EarphonePlayer { fun connected(): Boolean; fun callActive(): Boolean }

class AudioGate(private val context: Context, private val diagnostics: Diagnostics) : ReadingOutput {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    val deviceLabel = MutableStateFlow("未连接耳机")
    var onUnsafe: (() -> Unit)? = null
    private var player: MediaPlayer? = null
    private val audioFocus = PlatformAudioFocus(context, diagnostics)
    var onAudible: (() -> Unit)? = null
    private var initialized = false
    private var lastActual = "尚未尝试播放"
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refresh() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // A call-only SCO profile sleeping does not mean the media headset disconnected.
            refresh(); if (removedDevices.any { RoutePolicy.mediaHeadphone(kind(it.type)) }) { stop(); onUnsafe?.invoke() }
        }
    }
    fun initialize() {
        if (initialized) return
        initialized = true; manager.registerAudioDeviceCallback(callback, handler); refresh()
    }
    private fun available() = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    fun device(): AudioDeviceInfo? {
        val devices = available()
        val selected = RoutePolicy.choose(devices.map { endpoint(it) }) ?: return null
        return devices.firstOrNull { it.id == selected.id }
    }
    fun diagnostic(): String = "媒体耳机：" + available().filter { RoutePolicy.mediaHeadphone(kind(it.type)) }
        .joinToString("、") { "${it.productName}（${typeLabel(it.type)}，端口 ${it.id}）" }.ifBlank { "未发现；请确认蓝牙已开启媒体音频" } +
        "\n最近实际输出：$lastActual\n请在手机的媒体输出选择中确认耳机已被选中。"
    private fun actual(mp: MediaPlayer): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 36) mp.routedDevices else listOfNotNull(mp.routedDevice)
    private fun confirmedRoute(mp: MediaPlayer): List<RouteEndpoint>? {
        val routes = actual(mp)
        lastActual = routes.joinToString("、") { "${it.productName}（类型 ${it.type}，端口 ${it.id}）" }.ifBlank { "系统暂未返回路由" }
        val snapshot = routes.map { endpoint(it) }
        return snapshot.takeIf { RoutePolicy.confirmed(it, available().map { endpoint(it) }) }
    }
    fun refresh() { deviceLabel.value = device()?.let { "${it.productName} · ${typeLabel(it.type)}" } ?: "未连接耳机" }
    override fun connected() = device() != null
    @Suppress("DEPRECATION")
    override fun callActive(): Boolean {
        if (manager.mode != AudioManager.MODE_NORMAL) return true
        return try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
                context.getSystemService(TelephonyManager::class.java)?.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (_: Exception) { true }
    }
    override suspend fun play(file: File, allowed: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
        if (!allowed()) throw CancellationException("听读会话已改变")
        val target = device() ?: throw PlaybackBlocked(PlaybackBlockReason.HEADPHONES_MISSING)
        if (callActive()) throw PlaybackBlocked(PlaybackBlockReason.CALL_ACTIVE)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        var focusLost = false
        var wasAudible = false
        var focusFailure: ((PlaybackBlockReason) -> Unit)? = null
        val lease = audioFocus.acquire(allowed) {
            focusLost = true; stop()
            focusFailure?.invoke(if (wasAudible) PlaybackBlockReason.FOCUS_LOST_DURING_PLAY else PlaybackBlockReason.FOCUS_LOST_BEFORE_PLAY)
        }
        try {
            withTimeout(10 * 60_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val mp = MediaPlayer(); player = mp
                    var audible = false
                    var verifiedIds = emptySet<Int>()
                    var seeking = false
                    var rewound = false
                    var deadline = 0L
                    fun fail(reason: PlaybackBlockReason) {
                        runCatching { mp.setVolume(0f, 0f); mp.pause() }
                        if (cont.isActive) {
                            diagnostics.record(DiagnosticStage.BLOCKED, reason = reason)
                            cont.resumeWithException(PlaybackBlocked(reason))
                        }
                    }
                    focusFailure = { fail(it) }
                    val guard = object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            if (!allowed()) { cont.cancel(); return }
                            if (callActive()) { fail(PlaybackBlockReason.CALL_ACTIVE); return }
                            if (device() == null) { fail(PlaybackBlockReason.HEADPHONES_MISSING); return }
                            val routes = confirmedRoute(mp)
                            if (audible && (routes == null || !RoutePolicy.unchanged(routes, verifiedIds))) { fail(PlaybackBlockReason.ROUTE_CHANGED); return }
                            handler.postDelayed(this, 50)
                        }
                    }
                    try {
                        diagnostics.record(DiagnosticStage.PLAYER_PREPARE)
                        mp.setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                        mp.setAudioAttributes(attributes); mp.setDataSource(file.absolutePath); mp.setVolume(0f, 0f)
                        // Some vendors decline the preference while already routing to a valid headset.
                        mp.setPreferredDevice(target)
                        mp.addOnRoutingChangedListener({ _ ->
                            if (cont.isActive && audible) {
                                val routes = confirmedRoute(mp)
                                if (routes == null || !RoutePolicy.unchanged(routes, verifiedIds)) fail(PlaybackBlockReason.ROUTE_CHANGED)
                            }
                        }, handler)
                        mp.setOnErrorListener { _, _, _ -> fail(PlaybackBlockReason.AUDIO_FAILED); true }
                        mp.setOnCompletionListener {
                            if (cont.isActive) {
                                if (audible) { diagnostics.record(DiagnosticStage.PLAY_DONE); cont.resume(Unit) }
                                else fail(PlaybackBlockReason.ROUTE_UNCONFIRMED)
                            }
                        }
                        val verify = object : Runnable {
                                override fun run() {
                                    if (!cont.isActive || audible) return
                                    if (!allowed()) { cont.cancel(); return }
                                    if (device() == null) { fail(PlaybackBlockReason.HEADPHONES_MISSING); return }
                                    if (callActive()) { fail(PlaybackBlockReason.CALL_ACTIVE); return }
                                    if (focusLost) { fail(PlaybackBlockReason.FOCUS_LOST_BEFORE_PLAY); return }
                                    val routes = confirmedRoute(mp)
                                    if (routes != null && !seeking) {
                                        if (!rewound) {
                                            seeking = true
                                            deadline = SystemClock.elapsedRealtime() + RoutePolicy.CONFIRM_TIMEOUT_MS
                                            mp.seekTo(0L, MediaPlayer.SEEK_CLOSEST_SYNC)
                                            handler.postDelayed(this, 50)
                                        } else {
                                            verifiedIds = routes.map { it.id }.toSet()
                                            mp.isLooping = false; audible = true; wasAudible = true
                                            diagnostics.record(DiagnosticStage.ROUTE_CONFIRMED, routes = actual(mp).map { it.type })
                                            onAudible?.invoke(); mp.setVolume(1f, 1f); handler.post(guard)
                                        }
                                    } else if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed(this, 50)
                                    else fail(PlaybackBlockReason.ROUTE_UNCONFIRMED)
                                }
                        }
                        mp.setOnSeekCompleteListener {
                            if (cont.isActive && seeking) {
                                seeking = false; rewound = true
                                deadline = SystemClock.elapsedRealtime() + RoutePolicy.CONFIRM_TIMEOUT_MS
                                handler.post(verify)
                            }
                        }
                        mp.setOnPreparedListener {
                            diagnostics.record(DiagnosticStage.PLAYER_PREPARED)
                            if (!allowed()) { cont.cancel(); return@setOnPreparedListener }
                            if (device() == null) { fail(PlaybackBlockReason.HEADPHONES_MISSING); return@setOnPreparedListener }
                            if (callActive()) { fail(PlaybackBlockReason.CALL_ACTIVE); return@setOnPreparedListener }
                            if (focusLost) { fail(PlaybackBlockReason.FOCUS_LOST_BEFORE_PLAY); return@setOnPreparedListener }
                            // Loop silently while Bluetooth wakes up, so a short preview cannot finish unheard.
                            mp.isLooping = true; mp.start()
                            deadline = SystemClock.elapsedRealtime() + RoutePolicy.CONFIRM_TIMEOUT_MS
                            handler.post(verify)
                        }
                        cont.invokeOnCancellation { handler.post { runCatching { mp.setVolume(0f, 0f); mp.pause() } } }
                        mp.prepareAsync()
                    } catch (_: Exception) { fail(PlaybackBlockReason.AUDIO_FAILED) }
                }
            }
        } finally {
            runCatching { player?.setVolume(0f, 0f); player?.release() }; player = null
            focusFailure = null; lease.close()
        }
    }
    override fun stop() { runCatching { player?.setVolume(0f, 0f); player?.pause() } }
    companion object {
        private fun endpoint(d: AudioDeviceInfo) = RouteEndpoint(d.id, kind(d.type), d.productName.toString())
        private fun kind(type: Int): RouteKind = when {
            type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET) -> RouteKind.WIRED
            type == AudioDeviceInfo.TYPE_USB_HEADSET -> RouteKind.USB
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> RouteKind.BLUETOOTH_MEDIA
            Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> RouteKind.BLE_HEADSET
            type == AudioDeviceInfo.TYPE_HEARING_AID -> RouteKind.HEARING_AID
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> RouteKind.BLUETOOTH_CALL
            else -> RouteKind.OTHER
        }
        fun isHeadphone(type: Int) = type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID) || (android.os.Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        fun typeLabel(type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳机"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
            else -> "有线耳机 / 助听设备"
        }
    }
}

class LocalSpeech(private val context: Context, private val diagnostics: Diagnostics, enginePackage: String? = null) {
    private val ready = CompletableDeferred<Int>()
    private val engineAtInit = enginePackage ?: android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.TTS_DEFAULT_SYNTH)
    private var initStatus: Int? = null
    private var nativeError: Int? = null
    fun evidence(): org.json.JSONObject = org.json.JSONObject().put("initialized", true)
        .put("requestedEngineAtInit", engineAtInit).put("initStatus", initStatus ?: org.json.JSONObject.NULL)
        .put("lastNativeError", nativeError ?: org.json.JSONObject.NULL).apply {
            if (initStatus == TextToSpeech.SUCCESS) runCatching {
                val all = tts.voices.orEmpty().sortedBy { it.name }
                val chinese = all.filter { SystemVoicePolicy.chinese(it.locale.language, engineAtInit) }
                put("voiceCount", all.size).put("chineseCount", chinese.size)
                put("offlineChineseCount", chinese.count { !it.isNetworkConnectionRequired })
                put("chineseAvailability", tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE))
                put("voiceSample", org.json.JSONArray((chinese.ifEmpty { all }).take(10).map { voice ->
                    org.json.JSONObject().put("name", voice.name.take(80)).put("locale", voice.locale.toLanguageTag())
                        .put("networkRequired", voice.isNetworkConnectionRequired)
                        .put("features", org.json.JSONArray(voice.features.orEmpty().sorted().take(6).map { it.take(64) }))
                }))
            }.onFailure { put("queryFailed", true) }
        }

    private val tts: TextToSpeech
    init {
        diagnostics.record(DiagnosticStage.TTS_INIT)
        tts = TextToSpeech(context.applicationContext, { status ->
            initStatus = status
            diagnostics.record(if (status == TextToSpeech.SUCCESS) DiagnosticStage.TTS_READY else DiagnosticStage.BLOCKED,
                reason = if (status == TextToSpeech.SUCCESS) null else PlaybackBlockReason.TTS_INIT_FAILED)
            ready.complete(status)
        }, enginePackage)
    }
    suspend fun voices(): List<Pair<String, String>> {
        if (withTimeoutOrNull(15_000) { ready.await() } != TextToSpeech.SUCCESS) return emptyList()
        return tts.voices.orEmpty().filter { SystemVoicePolicy.offlineChinese(it.locale.language, it.isNetworkConnectionRequired, engineAtInit) }
            .sortedBy { it.name }.map { it.name to "${SystemVoicePolicy.label(it.locale.language, engineAtInit, it.locale.displayName)} · ${it.name}" }
    }
    suspend fun synthesize(text: String, c: Config): File {
        nativeError = null
        diagnostics.record(DiagnosticStage.TTS_BEGIN)
        val status = withTimeoutOrNull(15_000) { ready.await() }
        if (status != TextToSpeech.SUCCESS) {
            val reason = if (status == null) PlaybackBlockReason.TTS_INIT_TIMEOUT else PlaybackBlockReason.TTS_INIT_FAILED
            diagnostics.record(DiagnosticStage.BLOCKED, reason = reason); throw PlaybackBlocked(reason)
        }
        val available = tts.voices.orEmpty().filter { SystemVoicePolicy.offlineChinese(it.locale.language, it.isNetworkConnectionRequired, engineAtInit) }
        val voice = available.firstOrNull { it.name == c.systemVoice } ?: available.firstOrNull { it.locale.country == "CN" } ?: available.firstOrNull()
        if (voice == null) { diagnostics.record(DiagnosticStage.BLOCKED, reason = PlaybackBlockReason.TTS_LANGUAGE_MISSING); throw PlaybackBlocked(PlaybackBlockReason.TTS_LANGUAGE_MISSING) }
        val selected = tts.setVoice(voice)
        if (selected != TextToSpeech.SUCCESS) { nativeError = selected; throw PlaybackBlocked(PlaybackBlockReason.TTS_FAILED) }
        tts.setSpeechRate(c.speed)
        val file = File(context.cacheDir, "speech-${UUID.randomUUID()}.wav")
        try {
            withTimeout(30_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val id = UUID.randomUUID().toString()
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                        @Deprecated("Legacy callback") override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resumeWithException(PlaybackBlocked(PlaybackBlockReason.TTS_FAILED)) }
                        override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) nativeError = errorCode; onError(utteranceId) }
                    })
                    cont.invokeOnCancellation { tts.stop(); file.delete() }
                    val started = tts.synthesizeToFile(text, android.os.Bundle(), file, id)
                    if (started != TextToSpeech.SUCCESS && cont.isActive) {
                        nativeError = started
                        cont.resumeWithException(PlaybackBlocked(PlaybackBlockReason.TTS_START_FAILED))
                    }
                }
            }
            check(file.length() > 0 && file.length() <= MiniMaxClient.MAX_AUDIO) { "系统语音文件为空或过大" }
            diagnostics.record(DiagnosticStage.TTS_DONE)
            return file
        } catch (e: TimeoutCancellationException) {
            file.delete(); diagnostics.record(DiagnosticStage.BLOCKED, reason = PlaybackBlockReason.TTS_TIMEOUT)
            throw PlaybackBlocked(PlaybackBlockReason.TTS_TIMEOUT)
        } catch (e: CancellationException) { file.delete(); diagnostics.record(DiagnosticStage.WORK_CANCELLED); throw e }
        catch (e: Exception) {
            file.delete(); diagnostics.record(DiagnosticStage.BLOCKED, reason = (e as? PlaybackBlocked)?.reason ?: PlaybackBlockReason.TTS_FAILED); throw e
        }
    }
    fun stop() { tts.stop() }
    internal fun shutdown() { tts.stop(); tts.shutdown() }
}
