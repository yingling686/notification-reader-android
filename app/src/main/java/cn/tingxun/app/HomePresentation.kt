package cn.tingxun.app

enum class Screen(val title: String) {
    HOME("听讯"), SETTINGS("设置"), APPS("应用与重要规则"), CONTENT("内容与定时"),
    SOUND("声音与语速"), AI("AI 服务配置"), SUMMARY("摘要服务"), SPEECH("语音服务"),
    QUIET("免打扰"), TIMING("播报时机"), HOURLY("整点报时"), HISTORY("播报记录与待播"), SESSION("会话控制"),
    PERMISSIONS("权限与后台运行"), DIAGNOSTICS("诊断与设备"), PRIVACY("隐私与数据"), ABOUT("关于听讯");
    val parent: Screen get() = when (this) {
        HOME, SETTINGS -> HOME
        SUMMARY, SPEECH -> AI
        else -> SETTINGS
    }
}

enum class HomeAction { START, PAUSE, RESUME, RESOLVE }
data class HomeControl(val label: String, val action: HomeAction)

object HomePresentation {
    fun control(running: Boolean, paused: Boolean, state: ListeningState): HomeControl = when {
        !running -> HomeControl("开启听读", HomeAction.START)
        paused -> HomeControl("恢复听读", HomeAction.RESUME)
        state == ListeningState.BLOCKED -> HomeControl("查看原因", HomeAction.RESOLVE)
        else -> HomeControl("暂停听读", HomeAction.PAUSE)
    }
}
