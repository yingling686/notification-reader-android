package cn.tingxun.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class PlaybackBlockReason(val title: String, val retryable: Boolean = false) {
    SESSION_INACTIVE("听读会话未开启"), USER_PAUSED("用户已暂停"), QUIET_HOURS("处于免打扰时段"), SCREEN_ON("仅息屏播报：亮屏期间等待"),
    LISTENER_UNAVAILABLE("通知监听未连接"), SERVICE_NOT_READY("媒体前台服务尚未就绪"),
    FOCUS_DENIED("后台音频焦点未获准", true),
    FOCUS_DELAYED("音频焦点仍在等待", true),
    FOCUS_LOST_BEFORE_PLAY("播放前音频焦点被打断", true),
    FOCUS_LOST_DURING_PLAY("播放中音频焦点丢失"),
    HEADPHONES_MISSING("未连接媒体耳机"), CALL_ACTIVE("正在通话或电话状态不可用"),
    ROUTE_UNCONFIRMED("未能确认实际耳机输出"), ROUTE_CHANGED("实际音频输出已变化"),
    TTS_INIT_FAILED("系统语音引擎初始化失败"), TTS_INIT_TIMEOUT("系统语音引擎初始化超时"),
    TTS_LANGUAGE_MISSING("缺少离线中文语音包"), TTS_START_FAILED("系统语音合成启动失败"),
    TTS_FAILED("系统中文语音合成失败"), TTS_TIMEOUT("系统语音合成超时"),
    AUDIO_FAILED("音频准备或播放失败")
}
class PlaybackBlocked(val reason: PlaybackBlockReason) : IllegalStateException(reason.title)

/** Only retries before audible output. The same synthesized file is retained across attempts. */
class PlaybackRetry(private val wait: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun run(allowed: () -> Boolean, retry: (PlaybackBlockReason, Int) -> Unit = { _, _ -> }, play: suspend () -> Unit) {
        val delays = listOf(1000L, 3000L, 8000L)
        var attempt = 0
        while (true) {
            if (!allowed()) throw CancellationException("听读条件已改变")
            try { play(); return }
            catch (e: PlaybackBlocked) {
                if (!e.reason.retryable || attempt == delays.size) throw e
                retry(e.reason, attempt + 1)
                wait(delays[attempt++])
            }
        }
    }
}
