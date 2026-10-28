package cn.tingxun.app

enum class ListeningState(val title: String, val mediaActive: Boolean = false) {
    WAITING("自动听读中 · 等待新通知"), SYNTHESIZING("正在准备播报", true), PLAYING("正在通过耳机播报", true),
    PAUSED("用户已暂停"), BLOCKED("自动听读暂缓"), STOPPED("尚未开启听读")
}
enum class CommandSource { UI, NOTIFICATION, MEDIA, RESTORE, AUTOMATIC, SYSTEM }
data class DiagnosticSnapshot(val state: ListeningState, val listening: Boolean, val paused: Boolean, val quietActive: Boolean)

object ListeningPolicy {
    fun resting(running: Boolean, paused: Boolean, blocked: Boolean): ListeningState = when {
        !running -> ListeningState.STOPPED
        paused -> ListeningState.PAUSED
        blocked -> ListeningState.BLOCKED
        else -> ListeningState.WAITING
    }
}
