package cn.tingxun.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF176B60)
private val Ink = Color(0xFF163B36)
private val Muted = Color(0xFF61736F)
private val Canvas = Color(0xFFF8F5ED)
private val Accent = Color(0xFFE0ECE2)
private data class AppEntry(val pkg: String, val label: String)

class MainActivity : ComponentActivity() {
    val batteryExempt = mutableStateOf(false)
    val uiRevision = mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Teal, background = Canvas, surface = Color.White,
                onPrimary = Color.White, onBackground = Ink, onSurface = Ink, surfaceVariant = Accent)) {
                TingScreen(this)
            }
        }
    }
    fun requestRuntimePermissions() {
        val requested = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }
    override fun onResume() {
        super.onResume(); uiRevision.intValue++
        batteryExempt.value = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        graph.diagnostics.record(DiagnosticStage.UI_VISIBLE)
    }
    override fun onPause() { graph.diagnostics.record(DiagnosticStage.UI_HIDDEN); super.onPause() }
    fun openAppInfo() {
        runCatching { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            .onFailure { graph.engine.status.value = "请在系统设置中打开听讯的应用信息" }
    }
    fun openSettings(action: String) { runCatching { startActivity(Intent(action)) }.onFailure { graph.engine.status.value = "系统没有此设置入口，请从应用信息中打开" } }
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }.onFailure { graph.engine.status.value = "请在系统应用权限中查找“闹钟与提醒”" }
    }
    fun startReading() {
        if (!getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, NotificationCapture::class.java))) {
            graph.engine.status.value = "请先开启通知访问权限"; openSettings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS); return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            graph.engine.status.value = "请允许通知和电话状态权限，然后再次点击开启"; requestRuntimePermissions(); return
        }
        if (!graph.engine.ready) { graph.engine.status.value = "数据正在初始化，请稍后开启"; return }
        if (graph.settings.config.value.apps.isEmpty()) { graph.engine.status.value = "请先到设置中的“应用与重要规则”选择需要朗读通知的应用"; return }
        graph.engine.status.value = "正在启动自动听读，等待媒体前台服务确认…"
        val visible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        graph.diagnostics.record(DiagnosticStage.START_REQUEST, value = if (visible) 1 else 0, source = CommandSource.UI)
        runCatching { startForegroundService(Intent(this, ListeningService::class.java).setAction(ListeningService.START_AUTOMATIC)
            .putExtra(ListeningService.SOURCE, CommandSource.UI.name).putExtra(ListeningService.VISIBLE, visible)) }
            .onFailure { graph.engine.status.value = "前台听读服务未能开启，请检查系统后台限制" }
    }
}

@Composable
private fun TingScreen(activity: MainActivity) {
    val g = activity.graph
    val c by g.settings.config.collectAsStateWithLifecycle()
    val status by g.engine.status.collectAsStateWithLifecycle()
    var pageName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    val page = Screen.valueOf(pageName)
    fun navigate(to: Screen) { pageName = to.name }
    BackHandler(page != Screen.HOME) { navigate(page.parent) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            activity.packageManager.queryIntentActivities(intent, 0).map {
                AppEntry(it.activityInfo.packageName, it.loadLabel(activity.packageManager).toString())
            }.filter { it.pkg != activity.packageName }.distinctBy { it.pkg }.sortedBy { it.label }
        }
    }
    fun update(change: (Config) -> Config) { runCatching { g.settings.update(change) }.onFailure { message = "设置保存失败，请检查存储空间" } }
    Scaffold(containerColor = Canvas) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != Screen.HOME) IconButton(onClick = { navigate(page.parent) }, modifier = Modifier.semantics { contentDescription = "返回" }) {
                    Text("‹", fontSize = 34.sp, color = Teal)
                }
                Column(Modifier.weight(1f)) {
                    Text(page.title, fontSize = if (page == Screen.HOME) 30.sp else 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                    if (page == Screen.HOME) Text("把重要的事，轻轻说给你听", fontSize = 12.sp, color = Muted)
                }
                if (page == Screen.HOME) IconButton(onClick = { navigate(Screen.SETTINGS) }, modifier = Modifier.testTag("open-settings")) {
                    Icon(SettingsIcon, contentDescription = "设置", tint = Teal, modifier = Modifier.size(26.dp))
                }
            }
            if (page != Screen.HOME && page !in listOf(Screen.SETTINGS, Screen.AI, Screen.ABOUT)) {
                Surface(color = Accent, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Text(status, Modifier.padding(12.dp), color = Ink, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
            when (page) {
                Screen.HOME -> HomePage(activity, c, ::navigate)
                Screen.SETTINGS -> SettingsDirectory(c, ::navigate)
                Screen.APPS -> AppsPage(g, c, apps, { update(it) }, { message = it })
                Screen.HISTORY -> HistoryPage(g, { confirm = it })
                Screen.AI -> MenuPage {
                    MenuEntry("摘要服务", "MiniMax / OpenAI · 独立授权", { navigate(Screen.SUMMARY) })
                    MenuEntry("语音服务", "MiniMax / OpenAI · 独立授权", { navigate(Screen.SPEECH) })
                }
                Screen.ABOUT -> AboutPage()
                else -> SettingsPage(activity, c, page, apps, { update(it) }, { confirm = it }, { message = it })
            }
        }
    }
    if (message != null) AlertDialog(onDismissRequest = { message = null }, title = { Text("听讯") }, text = { Text(message!!) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } })
    if (confirm != null) {
        val kind = confirm!!
        val details = when (kind) {
            "summary" -> "开启后，选定应用的通知标题、发送人及正文会发送到你配置的摘要服务商。当前：${c.summaryProtocol.title}，地址：${runCatching { c.summaryAddress() }.getOrDefault("未配置")}。你可以随时关闭；已发送的内容无法从手机撤回。连接测试使用固定文字，也会消耗接口额度。"
            "speech" -> "开启后，待播文字会发送到所配置的服务商合成 AI 语音。当前：${c.speechProtocol.title}，地址：${runCatching { c.speechAddress() }.getOrDefault("未配置")}。直接读通知时发送原通知正文；读摘要时发送摘要。此授权独立于摘要授权，可能产生语音费用。"
            "clear" -> "停止当前播报并清空全部待播队列，记录保留到24小时清理。"
            else -> "停止听读并删除全部通知、摘要、规则、设置与保存的密钥。今日调用计数保留，避免重置后超出用量上限。"
        }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(when(kind) { "summary" -> "开启云端摘要"; "speech" -> "开启云端语音"; "clear" -> "清空待播"; else -> "删除全部本地数据" }) },
            text = { Text(details) }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = {
                when (kind) {
                    "summary" -> update { it.copy(summaryConsent = true) }
                    "speech" -> update { it.copy(speechConsent = true) }
                    "clear" -> g.engine.clearPending()
                    else -> { activity.stopService(Intent(activity, ListeningService::class.java)); g.engine.deleteAll() }
                }; confirm = null
            }) { Text(if (kind in listOf("summary", "speech")) "同意并开启" else "确认删除") } })
    }
}


@Composable private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
            content()
        }
    }
}
@Composable private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
@Composable private fun Toggle(label: String, detail: String? = null, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 15.sp); if (detail != null) Text(detail, fontSize = 12.sp, color = Muted) }
        Switch(checked = checked, onCheckedChange = changed)
    }
}
@Composable private fun <T> Choice(label: String, values: List<T>, selected: T, title: (T) -> String, changed: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 13.sp, color = Muted)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(title(selected), modifier = Modifier.weight(1f)); Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { value -> DropdownMenuItem(text = { Text(title(value)) }, onClick = { expanded = false; changed(value) }) }
            }
        }
    }
}

private val SettingsIcon: ImageVector = ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = androidx.compose.ui.graphics.SolidColor(Teal), strokeLineWidth = 1.8f) {
        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15.7f, 6f); lineTo(18f, 7.3f)
        lineTo(21f, 7f); lineTo(23f, 12f); lineTo(20.5f, 14f); lineTo(20f, 16.5f)
        lineTo(21f, 19f); lineTo(16.5f, 22f); lineTo(14f, 20f); lineTo(10f, 20f)
        lineTo(7.5f, 22f); lineTo(3f, 19f); lineTo(4f, 16.5f); lineTo(3.5f, 14f)
        lineTo(1f, 12f); lineTo(3f, 7f); lineTo(6f, 7.3f); lineTo(8.3f, 6f); close()
        moveTo(15.5f, 12f); arcTo(3.5f, 3.5f, 0f, true, true, 8.5f, 12f)
        arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 12f); close()
    }
}.build()

@Composable private fun HomePage(activity: MainActivity, c: Config, navigate: (Screen) -> Unit) {
    val g = activity.graph
    val running by g.engine.running.collectAsStateWithLifecycle()
    val paused by g.engine.paused.collectAsStateWithLifecycle()
    val state by g.engine.state.collectAsStateWithLifecycle()
    val status by g.engine.status.collectAsStateWithLifecycle()
    val device by g.audio.deviceLabel.collectAsStateWithLifecycle()
    val ready by g.sessionReady.collectAsStateWithLifecycle()
    val listener by g.listenerConnected.collectAsStateWithLifecycle()
    val next by g.engine.nextAt.collectAsStateWithLifecycle()
    // Re-evaluate permission state after returning from Android settings.
    @Suppress("UNUSED_VARIABLE") val revision = activity.uiRevision.intValue
    val listenerAllowed = activity.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(activity, NotificationCapture::class.java))
    val permissions = listenerAllowed && ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    val quiet = running && Policy.quiet(c)
    val needsPermission = !permissions
    val control = HomePresentation.control(running, paused, state)
    val problem = when {
        paused -> "暂停期间的消息需确认补读"
        quiet -> "免打扰至 ${timeString(c.quietEnd)}，暂不自动播报"
        needsPermission -> "请开启通知访问、通知及电话状态权限"
        c.apps.isEmpty() -> "先选择需要朗读通知的应用"
        running && c.onlyScreenOff && g.screenInteractive() -> "仅息屏播报已开启，亮屏消息保留，息屏后自动处理"
        running && !ready -> "听读服务正在准备，请稍候"
        running && !listener -> "通知访问已授权，但监听未连接；请到诊断与设备运行自检"
        running && !g.audio.connected() -> "未连接媒体耳机，连接后自动处理新通知"
        running && g.audio.callActive() -> "通话期间暂缓，通话结束后继续处理新通知"
        state == ListeningState.BLOCKED -> status
        !running && status !in listOf("尚未开启听读", "听读会话已停止") -> status
        else -> null
    }
    val title = when {
        paused -> "听读已暂停"
        problem != null && running -> "听读暂缓"
        state == ListeningState.WAITING -> "自动听读中"
        state == ListeningState.SYNTHESIZING -> "正在准备播报"
        state == ListeningState.PLAYING -> "正在耳机播报"
        else -> "准备好听见重要的事"
    }
    val detail = problem ?: when (state) {
        ListeningState.WAITING -> if (c.mode == ReadMode.INSTANT) "等待新通知 · 支持后台与息屏听读" else "等待新通知 · 下轮预计 ${clock(next)}"
        ListeningState.SYNTHESIZING, ListeningState.PLAYING -> status
        else -> "连接耳机，开启后自动听取新通知"
    }
    fun resolve() { navigate(when {
        needsPermission -> Screen.PERMISSIONS
        !listener -> Screen.DIAGNOSTICS
        c.apps.isEmpty() -> Screen.APPS
        quiet -> Screen.QUIET
        c.onlyScreenOff && g.screenInteractive() -> Screen.TIMING
        else -> Screen.DIAGNOSTICS
    }) }
    HomeContent(state, title, detail, device, "${c.mode.title} · ${c.voice.title}${if (c.onlyScreenOff) " · 仅息屏" else ""}", control) {
        when (control.action) {
            HomeAction.START -> when { needsPermission -> navigate(Screen.PERMISSIONS); c.apps.isEmpty() -> navigate(Screen.APPS); else -> activity.startReading() }
            HomeAction.PAUSE -> g.engine.pause()
            HomeAction.RESUME -> g.engine.resume()
            HomeAction.RESOLVE -> resolve()
        }
    }
}

@Composable internal fun HomeContent(state: ListeningState, title: String, detail: String, device: String, summary: String, control: HomeControl, onControl: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minimumHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minimumHeight).padding(horizontal = 26.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                VoiceEmblem(state)
                Spacer(Modifier.height(26.dp))
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(detail, fontSize = 13.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Surface(color = Accent, shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("♪", color = Teal, fontSize = 21.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(device, color = Teal, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(summary, fontSize = 12.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(30.dp))
                Button(onClick = onControl, shape = CircleShape, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(88.dp).testTag("home-control").semantics { contentDescription = control.label },
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
                    ControlGlyph(control.action)
                }
                Spacer(Modifier.height(10.dp))
                Text(control.label, fontSize = 15.sp, color = Ink, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.padding(top = 32.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.club_emblem), contentDescription = "接化发俱乐部标志", modifier = Modifier.size(40.dp))
                Text("接化发俱乐部出品", Modifier.padding(start = 10.dp), color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun VoiceEmblem(state: ListeningState) {
    val moving = state == ListeningState.SYNTHESIZING || state == ListeningState.PLAYING
    // Do not keep an animation clock running while waiting, paused, blocked or in the background.
    val progress = if (moving) {
        val transition = rememberInfiniteTransition(label = "listening-state")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == ListeningState.PLAYING) 1200 else 2200, easing = LinearEasing)), label = "voice-motion")
        value
    } else 0f
    DrawCanvas(Modifier.size(216.dp).semantics { contentDescription = "${state.title}，状态声纹" }) {
        val radius = size.minDimension / 2f
        drawCircle(Accent.copy(alpha = .28f), radius)
        drawCircle(Accent.copy(alpha = .7f), radius * .82f, style = Stroke(1.dp.toPx()))
        drawCircle(Accent.copy(alpha = .24f), radius * .65f)
        val base = listOf(.22f, .42f, .67f, .87f, .56f, .74f, .45f, .3f, .18f)
        val gap = size.width * .056f
        base.forEachIndexed { i, h ->
            val wave = if (state == ListeningState.PLAYING) .65f + .35f * kotlin.math.sin(progress * 2f * Math.PI + i * .8).toFloat()
                else if (moving) .9f + .1f * kotlin.math.sin(progress * 2f * Math.PI).toFloat() else 1f
            val half = h * radius * .43f * wave
            val x = center.x + (i - 4) * gap
            drawLine(Teal, Offset(x, center.y - half), Offset(x, center.y + half), 6.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable private fun ControlGlyph(action: HomeAction) {
    DrawCanvas(Modifier.size(30.dp)) {
        when (action) {
            HomeAction.PAUSE -> {
                drawLine(Color.White, Offset(size.width * .3f, size.height * .18f), Offset(size.width * .3f, size.height * .82f), 5.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White, Offset(size.width * .7f, size.height * .18f), Offset(size.width * .7f, size.height * .82f), 5.dp.toPx(), StrokeCap.Round)
            }
            HomeAction.RESOLVE -> {
                drawCircle(Color.White, size.minDimension * .43f, style = Stroke(2.dp.toPx()))
                drawLine(Color.White, Offset(center.x, size.height * .25f), Offset(center.x, size.height * .53f), 2.dp.toPx(), StrokeCap.Round)
                drawCircle(Color.White, 1.5.dp.toPx(), Offset(center.x, size.height * .73f))
            }
            else -> {
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * .3f, size.height * .15f); lineTo(size.width * .85f, center.y); lineTo(size.width * .3f, size.height * .85f); close() }
                drawPath(path, Color.White)
            }
        }
    }
}

@Composable private fun MenuPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable private fun MenuEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = Color.White, modifier = Modifier.fillMaxWidth().testTag("menu-$title")) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
            }
            Text("›", fontSize = 25.sp, color = Muted, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable private fun SettingsDirectory(c: Config, navigate: (Screen) -> Unit) {
    MenuPage {
        Text("按你的节奏听", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
        MenuEntry(Screen.APPS.title, "已选 ${c.apps.size} 个应用 · ${c.rules.size} 条重要规则", { navigate(Screen.APPS) })
        MenuEntry(Screen.CONTENT.title, "${c.mode.title} · ${c.interval} 分钟", { navigate(Screen.CONTENT) })
        MenuEntry(Screen.TIMING.title, if (c.onlyScreenOff) "仅息屏时播报" else "亮屏、息屏均可播报", { navigate(Screen.TIMING) })
        MenuEntry(Screen.SOUND.title, "${c.voice.title} · ${"%.1f".format(c.speed)} 倍", { navigate(Screen.SOUND) })
        MenuEntry(Screen.AI.title, "摘要与语音分别配置、分别授权", { navigate(Screen.AI) })
        MenuEntry(Screen.QUIET.title, if (c.quiet) "${timeString(c.quietStart)}—${timeString(c.quietEnd)}" else "未开启", { navigate(Screen.QUIET) })
        MenuEntry(Screen.HOURLY.title, if (c.hourly) "已开启 · 遵守耳机与免打扰设置" else "未开启", { navigate(Screen.HOURLY) })
        Text("记录与会话", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
        MenuEntry(Screen.HISTORY.title, "手动播报、补读、跳过与清空", { navigate(Screen.HISTORY) })
        MenuEntry(Screen.SESSION.title, "暂停、恢复或停止听读", { navigate(Screen.SESSION) })
        Text("设备与支持", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
        MenuEntry(Screen.PERMISSIONS.title, "通知权限、电池优化与后台设置", { navigate(Screen.PERMISSIONS) })
        MenuEntry(Screen.DIAGNOSTICS.title, "耳机输出与后台诊断", { navigate(Screen.DIAGNOSTICS) })
        MenuEntry(Screen.PRIVACY.title, "数据保留、授权说明与删除", { navigate(Screen.PRIVACY) })
        MenuEntry(Screen.ABOUT.title, "接化发俱乐部出品 · ${BuildConfig.VERSION_NAME}", { navigate(Screen.ABOUT) })
    }
}

@Composable private fun AboutPage() {
    MenuPage {
        Image(painterResource(R.drawable.club_logo), contentDescription = "接化发俱乐部 JIE HUA FA CLUB 完整 Logo", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        Text("听讯", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("把重要的事，轻轻说给你听", color = Muted)
        Text("接化发俱乐部出品", color = Teal, fontWeight = FontWeight.Medium)
        Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", color = Muted, fontSize = 13.sp)
        Text("手机重启或应用被强行停止后，请重新开启听读。", color = Muted, fontSize = 12.sp)
    }
}


@Composable private fun AppsPage(g: Graph, c: Config, apps: List<AppEntry>, update: ((Config) -> Config) -> Unit, message: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    var packageDraft by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var ruleApp by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    val choices = c.apps.sorted()
    val selectedApp = if (ruleApp in choices) ruleApp else choices.firstOrNull().orEmpty()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Section("重要联系人与关键词", "混合模式下，命中规则的通知即时朗读，其余定时总结") {
                if (choices.isEmpty()) Text("先在下方选择需要监听的应用。", fontSize = 13.sp, color = Muted)
                else {
                    Choice("限定应用", choices, selectedApp, { pkg -> apps.find { it.pkg == pkg }?.label ?: pkg }) { ruleApp = it }
                    OutlinedTextField(value = keyword, onValueChange = { keyword = it.take(100) }, label = { Text("备注名、群名或关键词") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = {
                        val rule = PriorityRule(selectedApp, keyword.trim())
                        if (rule.keyword.isNotBlank()) { update { it.copy(rules = (it.rules + rule).distinct()) }; keyword = "" }
                    }, enabled = keyword.isNotBlank()) { Text("添加规则") }
                }
                c.rules.forEach { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${apps.find { it.pkg == rule.app }?.label ?: rule.app} · ${rule.keyword}", modifier = Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { update { it.copy(rules = it.rules - rule) } }) { Text("删除") }
                    }
                }
                OutlinedTextField(value = preview, onValueChange = { preview = it }, label = { Text("粘贴通知文字，预览是否命中") }, modifier = Modifier.fillMaxWidth())
                if (preview.isNotBlank()) Text(if (Policy.priority(c, selectedApp, "", preview)) "命中：该应用此通知会即时读" else "未命中：按当前模式处理", color = Teal, fontSize = 13.sp)
            }
        }
        item {
            Section("选择要听的应用", "未选中的应用不会进入听讯记录") {
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("搜索应用名称或包名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = packageDraft, onValueChange = { packageDraft = it.trim() }, label = { Text("可选：手动添加应用包名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(onClick = {
                    if (packageDraft.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { update { it.copy(apps = it.apps + packageDraft) }; packageDraft = "" }
                    else message("请输入有效包名，例如 com.tencent.mm")
                }) { Text("添加包名") }
            }
        }
        val allApps = (apps + c.apps.filter { pkg -> apps.none { it.pkg == pkg } }.map { AppEntry(it, it) }).distinctBy { it.pkg }
        items(allApps.filter { it.label.contains(search, true) || it.pkg.contains(search, true) }, key = { it.pkg }) { app ->
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = app.pkg in c.apps, onCheckedChange = { checked -> update { it.copy(apps = if (checked) it.apps + app.pkg else it.apps - app.pkg) } })
                    Column(Modifier.weight(1f)) { Text(app.label, fontWeight = FontWeight.Medium); Text(app.pkg, fontSize = 11.sp, color = Muted) }
                }
            }
        }
    }
}

@Composable private fun HistoryPage(g: Graph, confirm: (String) -> Unit) {
    val rows by remember(g) { g.dao.observe() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val batches by remember(g) { g.dao.observeBatches() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var summaries by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Section("播报记录", "通知和摘要保留 24 小时；本页最多展示最近 300 条通知、100 次播报") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !summaries, onClick = { summaries = false }, label = { Text("通知") })
                FilterChip(selected = summaries, onClick = { summaries = true }, label = { Text("播报 / 摘要") })
            }
            val pending by remember(g) { g.dao.countPending() }.collectAsStateWithLifecycle(initialValue = 0)
            Text("$pending 条待处理", color = Teal)
            Row { TextButton(onClick = { g.engine.now() }) { Text("现在播报") }; TextButton(onClick = { g.engine.skip() }) { Text("跳过本次") } }
            Row { TextButton(onClick = { g.engine.releaseHeld() }) { Text("确认补读") }; TextButton(onClick = { confirm("clear") }) { Text("清空待播") } }
        } }
        if ((!summaries && rows.isEmpty()) || (summaries && batches.isEmpty())) item { Text("还没有记录。开启听读后，新通知会显示在这里。", color = Muted, modifier = Modifier.padding(18.dp)) }
        if (summaries) items(batches, key = { it.id }) { batch ->
            Section(if (batch.summary) "AI 摘要 · ${clock(batch.createdAt)}" else "通知播报 · ${clock(batch.createdAt)}") {
                Text(stateLabel(batch.state) + " · 已播 ${batch.cursor} 段", color = Teal, fontSize = 12.sp)
                Text(batch.text, fontSize = 14.sp)
            }
        } else items(rows, key = { it.id }) { row ->
            Section("${row.label} · ${clock(row.receivedAt)}") {
                Text("${stateLabel(row.state)}${if (row.instant) " · 即时" else " · 定时"}", color = Teal, fontSize = 12.sp)
                if (row.title.isNotBlank()) Text(row.title, fontWeight = FontWeight.Medium)
                Text(row.body, fontSize = 14.sp)
            }
        }
    }
}

@Composable private fun SettingsPage(activity: MainActivity, c: Config, page: Screen, apps: List<AppEntry>, update: ((Config) -> Config) -> Unit, confirm: (String) -> Unit, message: (String) -> Unit) {
    val g = activity.graph
    val genericSummary = c.summaryProtocol == CloudProtocol.OPENAI
    val genericSpeech = c.speechProtocol == CloudProtocol.OPENAI
    var summaryUrl by remember(c.summaryProtocol, c.summaryUrl, c.openaiSummaryBase) { mutableStateOf(if (genericSummary) c.openaiSummaryBase else c.summaryUrl) }
    var summaryModel by remember(c.summaryProtocol, c.summaryModel, c.openaiSummaryModel) { mutableStateOf(c.summaryModelName()) }
    var summaryKey by remember(c.summaryProtocol) { mutableStateOf("") }
    var tokenField by remember(c.summaryProtocol, c.openaiTokenField) { mutableStateOf(c.openaiTokenField) }
    var speechUrl by remember(c.speechProtocol, c.speechUrl, c.openaiSpeechBase) { mutableStateOf(if (genericSpeech) c.openaiSpeechBase else c.speechUrl) }
    var speechModel by remember(c.speechProtocol, c.speechModel, c.openaiSpeechModel) { mutableStateOf(c.speechModelName()) }
    var voiceId by remember(c.speechProtocol, c.voiceId, c.openaiVoice) { mutableStateOf(c.speechVoiceName()) }
    var speechKey by remember(c.speechProtocol) { mutableStateOf("") }
    var summaryLimit by remember(c.summaryLimit) { mutableStateOf(c.summaryLimit.toString()) }
    var speechLimit by remember(c.speechLimit) { mutableStateOf(c.speechLimit.toString()) }
    var quietStart by remember(c.quietStart) { mutableStateOf(timeString(c.quietStart)) }
    var quietEnd by remember(c.quietEnd) { mutableStateOf(timeString(c.quietEnd)) }
    var voices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val localRevision by g.localRevision.collectAsStateWithLifecycle()
    LaunchedEffect(page, localRevision) { if (page == Screen.SOUND) voices = g.local.voices() }
    val diagnostic by g.diagnostics.latest.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val uiRevision = activity.uiRevision.intValue
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (page == Screen.TIMING) Section("播报时机") {
            Toggle("仅息屏时播报", "关闭时亮屏、息屏均可播报；仍须连接耳机", c.onlyScreenOff) { enabled -> update { it.copy(onlyScreenOff = enabled) } }
            Text("开启后，亮屏收到的新消息保留在待播队列，息屏后按当前内容模式自动处理。正在播报时亮屏会停止，息屏后从尚未完成的段落重新开始；已播完段落不重复。", fontSize = 13.sp, color = Muted)
            Text("适用于通知、摘要、整点报时和试听。暂停、免打扰、断连或通话造成的旧积压仍需手动确认补读。切换设置沿用现有积压确认规则。", fontSize = 12.sp, color = Muted)
        }
        if (page == Screen.HOURLY) Section("整点报时") {
            Toggle("开启整点报时", "仅在听读会话开启期间报时", c.hourly) { enabled -> update { it.copy(hourly = enabled) } }
            Text("使用当前发声方式，例如“现在是十五点整”。遵守耳机、暂停、免打扰、通话和仅息屏设置；AI 语音需原有授权并计入语音调用额度。", fontSize = 13.sp, color = Muted)
            Toggle("AI 总结上一小时通知", "报时后概括上一小时的重要消息，默认关闭", c.hourlySummary) { enabled ->
                if (enabled && (!c.summaryConsent || c.summaryBlocked || !g.settings.hasKey(c.summaryKeyKind())))
                    message("请先到 AI 服务配置 → 摘要，配置接口、主动授权并测试连接")
                else update { it.copy(hourlySummary = enabled) }
            }
            Text("需同时开启整点报时。沿用 AI 摘要接口与授权，通知内容会发送至所配置服务，计入摘要额度。回顾上一个自然小时选中应用的通知（含已播），不改变原待播状态；跳过已清空、失败及待确认补读内容。没有重要内容或摘要失败时只报时间。", fontSize = 12.sp, color = Muted)
            Text("每次最多概括最近 50 条、12,000 字符；超出时语音明确说明范围。摘要最多等待 35 秒，整点第一分钟内才播报；受耳机、息屏与免打扰设置限制，不补报。", fontSize = 12.sp, color = Muted)
            LabelValue("下个整点", clock(HourlyPolicy.next(g.clock()).toInstant().toEpochMilli()))
            TextButton(onClick = { g.engine.previewHour() }) { Text("试听整点报时示例") }
            Text("试听使用固定的十五点示例，需先开启听读并连接耳机；同样遵守仅息屏与免打扰设置。", fontSize = 12.sp, color = Muted)
            LabelValue("准时提醒", if (g.hourly.exactAvailable()) "系统已允许精确提醒" else "未获精确提醒许可，可能延后")
            if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { activity.openExactAlarmSettings() }) { Text("打开系统“闹钟与提醒”权限") }
            Text("从开启后的下一个整点开始。已有播报时先等当前任务；暂停、亮屏限制等条件不满足时跳过本次，不补报。系统若延迟超过该整点的第 1 分钟，也跳过本次。没有精确提醒权限时按系统允许的方式尽力运行。", fontSize = 12.sp, color = Muted)
        }
        if (page == Screen.CONTENT) Section("按你的节奏听", "联系人规则只匹配通知中实际显示的文字") {
            Choice("内容处理", ReadMode.entries, c.mode, { it.title }) { mode ->
                if (Policy.needsSummary(c.copy(mode = mode)) && (!c.summaryConsent || !g.settings.hasKey(c.summaryKeyKind()))) message("请先在设置中填写摘要密钥并主动开启云端摘要")
                else update { it.copy(mode = mode) }
            }
            Choice("定时间隔", listOf(1, 3, 5, 10, 15, 30), c.interval, { "$it 分钟" }) { minutes -> update { it.copy(interval = minutes) } }
            LabelValue("已选应用", "${c.apps.size} 个 · ${c.apps.take(4).joinToString("、") { pkg -> apps.find { it.pkg == pkg }?.label ?: pkg }}")
            Text("即时朗读在通知到达后自动触发，无需点“现在播报”。息屏不暂停听读；合成与播放期间保持 CPU 运行，空闲时释放。耳机断开期间的消息需确认补读。", color = Muted, fontSize = 12.sp)
        }
        if (page == Screen.PERMISSIONS) Section("后台与息屏听读") {
            Text("听读会话会每 2 秒核对系统活动通知，补收未及时回调的新消息；系统冻结进程或延迟通知送达时仍可能延后。", fontSize = 12.sp, color = Muted)
            LabelValue("最近阶段", diagnostic)
            TextButton(onClick = {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("听讯后台诊断", g.diagnostics.exportBrief()))
                message("后台诊断已复制，不含消息正文、联系人、音频或密钥。请先复现退后台不读，再复制给我分析。")
            }) { Text("复制精简诊断") }
            LabelValue("电池优化", if (activity.batteryExempt.value) "已豁免系统电池优化" else "未豁免，系统可能限制后台运行")
            Text("红米 / HyperOS：在听讯的应用信息中允许后台运行，把电池策略设为“无限制”，并开启后台自启动。保持听读常驻通知，开启会话后可直接锁屏。具体选项以手机系统为准。", fontSize = 12.sp, color = Muted)
            OutlinedButton(onClick = { activity.openAppInfo() }, modifier = Modifier.fillMaxWidth()) { Text("打开听讯应用信息 · 设置后台运行") }
            TextButton(onClick = { activity.openSettings(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("检查系统电池优化名单") }
            Text("此状态只检查 Android 电池优化；厂商的后台策略需另行确认。强行停止、重启后需重新开启会话；通知本身延迟送达或被系统隐藏正文时无法提前朗读。", fontSize = 12.sp, color = Muted)
        }
        if (page == Screen.PERMISSIONS) Section("首次使用", "所有权限都由你在系统中开启") {
            OutlinedButton(onClick = { activity.openSettings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("1 · 开启通知访问") }
            OutlinedButton(onClick = { activity.requestRuntimePermissions() }, modifier = Modifier.fillMaxWidth()) { Text("2 · 允许通知与电话状态") }
            Text("电话状态仅用于通话时暂缓，不读取号码或通讯录。请在设置中的“应用与重要规则”选择要听的 App。", fontSize = 12.sp, color = Muted)
            Text("红米适配：如系统提示受限设置，可到应用信息允许受限设置；再检查自启动和电池后台限制。具体入口以手机系统为准。", fontSize = 12.sp, color = Muted)
        }
        if (page == Screen.DIAGNOSTICS) Section("诊断与设备") {
            val listener by g.listenerConnected.collectAsStateWithLifecycle()
            val checkResult by g.listenerCheck.result.collectAsStateWithLifecycle()
            val checkBusy by g.listenerCheck.busy.collectAsStateWithLifecycle()
            LabelValue("通知访问授权", if (g.listenerCheck.granted()) "已授权" else "未授权")
            LabelValue("监听实际连接", if (listener) "已连接" else "未连接，暂时收不到通知")
            Text(checkResult, fontSize = 13.sp, color = Ink)
            Button(onClick = { g.listenerCheck.run() }, enabled = !checkBusy) { Text(if (checkBusy) "正在自检…" else "运行通知接收自检") }
            Text("自检只发送一条固定测试通知，不朗读、不读取其他消息，也不改变应用选择或云端配置。完成后请让另一台设备发一条新的消息，再复制诊断。", fontSize = 12.sp, color = Muted)
            OutlinedButton(onClick = { activity.openSettings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("打开系统通知访问设置") }

            LabelValue("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
            LabelValue("系统", "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            LabelValue("最近阶段", diagnostic)
            TextButton(onClick = { message(g.audio.diagnostic()) }) { Text("查看耳机输出检测") }
            TextButton(onClick = {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("听讯后台诊断", g.diagnostics.exportBrief()))
                message("后台诊断已复制，不含通知正文、联系人、音频或密钥")
            }) { Text("复制精简诊断") }
        }
        if (page == Screen.SESSION) Section("会话控制") {
            val running by g.engine.running.collectAsStateWithLifecycle()
            val paused by g.engine.paused.collectAsStateWithLifecycle()
            val state by g.engine.state.collectAsStateWithLifecycle()
            Text(state.title)
            if (running) {
                Button(onClick = { if (paused) g.engine.resume() else g.engine.pause() }) { Text(if (paused) "恢复听读" else "暂停听读") }
                OutlinedButton(onClick = { activity.stopService(Intent(activity, ListeningService::class.java)) }) { Text("停止会话") }
            } else Text("回到首页开启听读")
        }
        if (page == Screen.SUMMARY) Section("内容 AI", "MiniMax 与 OpenAI 兼容配置分别保存") {
            Choice("摘要接口类型", CloudProtocol.entries, c.summaryProtocol, { it.title }) { protocol ->
                update { it.copy(summaryProtocol = protocol, mode = if (Policy.needsSummary(it)) ReadMode.PERIODIC else it.mode) }
            }
            Toggle("允许云端摘要", "发送选定应用通知到所配置的服务商", c.summaryConsent) { enabled ->
                if (enabled) confirm("summary") else update { it.copy(summaryConsent = false, mode = if (Policy.needsSummary(it)) ReadMode.PERIODIC else it.mode) }
            }
            Field(if (genericSummary) "Base URL 或完整 /chat/completions 地址" else "摘要接口（完整 HTTPS 地址）", summaryUrl) { summaryUrl = it }
            Field("模型名称", summaryModel) { summaryModel = it }
            if (genericSummary) {
                Choice("输出长度参数", TokenField.entries, tokenField, { it.title }) { tokenField = it }
                Text("支持第三方 OpenAI 兼容服务。文本模型名由服务商提供；仅提供 Responses 协议的接口暂不支持。", fontSize = 12.sp, color = Muted)
            }
            SecretField("摘要 API Key", summaryKey, g.settings.hasKey(c.summaryKeyKind())) { summaryKey = it }
            Field("每日请求上限（含测试）", summaryLimit, true) { summaryLimit = it }
            Button(onClick = {
                val limit = summaryLimit.toIntOrNull()
                if (!validEndpoint(summaryUrl, "https") || summaryModel.isBlank() || limit == null || limit !in 1..10000) message("请检查 HTTPS 地址、模型名和请求上限（1～10000）")
                else runCatching {
                    if (genericSummary) ApiAddress.complete(summaryUrl, "chat/completions")
                    if (summaryKey.isNotBlank()) g.settings.saveKey(c.summaryKeyKind(), summaryKey)
                    update { if (genericSummary) it.copy(openaiSummaryBase = summaryUrl.trim(), openaiSummaryModel = summaryModel.trim(), openaiTokenField = tokenField, summaryLimit = limit, summaryBlocked = false)
                        else it.copy(summaryUrl = summaryUrl.trim(), summaryModel = summaryModel.trim(), summaryLimit = limit, summaryBlocked = false) }; summaryKey = ""
                    message("摘要配置已保存；地址变化后请重新开启授权，再点击连接测试")
                }.onFailure { message("配置保存失败，未记录密钥") }
            }) { Text("保存摘要配置") }
            Row { TextButton(onClick = { g.engine.testSummary() }) { Text("连接测试") }; TextButton(onClick = { runCatching { g.settings.saveKey(c.summaryKeyKind(), "") }; update { it.copy(summaryConsent = false, mode = ReadMode.PERIODIC) } }) { Text("移除当前密钥") } }
            if (!genericSummary) Text("M3.1 需具备 M Plan / MiniMax Code 对应访问权限；普通语音密钥不一定可调用。不会自动替换模型。", fontSize = 12.sp, color = Muted)
            Text("今日摘要请求 ${g.settings.used("summary")} / ${c.summaryLimit}${if (c.summaryBlocked) " · 鉴权暂停，请修正配置" else ""}", fontSize = 12.sp, color = Teal)
        }
        if (page == Screen.SPEECH) Section("发声 AI", "MiniMax WebSocket 或 OpenAI 兼容 HTTP 语音") {
            Choice("语音接口类型", CloudProtocol.entries, c.speechProtocol, { it.title }) { protocol -> update { it.copy(speechProtocol = protocol) } }
            Toggle("允许云端语音", "直接读通知时会发送原通知正文", c.speechConsent) { enabled ->
                if (enabled) confirm("speech") else update { it.copy(speechConsent = false, voice = VoiceEngine.SYSTEM) }
            }
            Field(if (genericSpeech) "Base URL 或完整 /audio/speech 地址" else "语音接口（完整 WSS 地址）", speechUrl) { speechUrl = it }
            Field("语音模型", speechModel) { speechModel = it }
            Field("音色 voice ID", voiceId) { voiceId = it }
            SecretField("语音 API Key", speechKey, g.settings.hasKey(c.speechKeyKind())) { speechKey = it }
            if (genericSpeech) Text("接口须支持 /audio/speech，返回 MP3 音频。语音由 AI 合成；语音模型与 voice 名称按服务商填写。", fontSize = 12.sp, color = Muted)
            Field("每日请求上限（每段及测试计一次）", speechLimit, true) { speechLimit = it }
            Button(onClick = {
                val limit = speechLimit.toIntOrNull()
                if (!validEndpoint(speechUrl, if (genericSpeech) "https" else "wss") || speechModel.isBlank() || voiceId.isBlank() || limit == null || limit !in 1..10000) message("请检查接口地址、模型、音色和请求上限（1～10000）")
                else runCatching {
                    if (genericSpeech) ApiAddress.complete(speechUrl, "audio/speech")
                    if (speechKey.isNotBlank()) g.settings.saveKey(c.speechKeyKind(), speechKey)
                    update { if (genericSpeech) it.copy(openaiSpeechBase = speechUrl.trim(), openaiSpeechModel = speechModel.trim(), openaiVoice = voiceId.trim(), speechLimit = limit)
                        else it.copy(speechUrl = speechUrl.trim(), speechModel = speechModel.trim(), voiceId = voiceId.trim(), speechLimit = limit) }; speechKey = ""
                    message("语音配置已保存；地址变化后请重新授权。开启会话并连接耳机后测试")
                }.onFailure { message("配置保存失败，未记录密钥") }
            }) { Text("保存语音配置") }
            Row { TextButton(onClick = { g.engine.testSpeech() }) { Text("连接测试并试听") }; TextButton(onClick = { runCatching { g.settings.saveKey(c.speechKeyKind(), "") }; update { it.copy(speechConsent = false, voice = VoiceEngine.SYSTEM) } }) { Text("移除当前密钥") } }
            Text(if (c.speechVerified) "语音测试已通过，可在“声音与语速”选择 AI 语音" else "尚未通过语音测试；测试不会回退机读", fontSize = 12.sp, color = Teal)
            Text("今日语音请求 ${g.settings.used("speech")} / ${c.speechLimit}", fontSize = 12.sp, color = Teal)
        }
        if (page == Screen.SOUND) Section("声音与语速") {
            Choice("发声方式", VoiceEngine.entries, c.voice, { it.title }) { voice ->
                if (voice == VoiceEngine.MINIMAX && (!c.speechConsent || !c.speechVerified || !g.settings.hasKey(c.speechKeyKind()))) message("请先配置语音密钥、主动授权，并完成当前接口的语音测试")
                else update { it.copy(voice = voice) }
            }
            Toggle("AI 语音失败改用机读", "仅回退尚未播放的段落", c.fallback) { enabled -> update { it.copy(fallback = enabled) } }
            TextButton(onClick = { g.engine.preview() }) { Text("试听当前声音") }
            OutlinedButton(onClick = { g.engine.refreshSystemSpeech() }) { Text("刷新已下载的系统声音") }
            Text("下载语音包或更换系统语音引擎后可刷新，再试听。刷新会停止当前任务，旧积压保留待确认补读。", fontSize = 12.sp, color = Muted)
            val voiceNames = listOf("") + voices.map { it.first }
            Choice("系统离线中文声音", voiceNames, c.systemVoice.takeIf { it in voiceNames } ?: "", { id -> if (id.isBlank()) "默认离线中文声音" else voices.find { it.first == id }?.second ?: id }) { id -> update { it.copy(systemVoice = id) } }
            Text("语速 ${"%.1f".format(c.speed)} 倍", fontSize = 13.sp)
            var speed by remember(c.speed) { mutableFloatStateOf(c.speed) }
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2f, steps = 14, onValueChangeFinished = { update { it.copy(speed = speed) } })
            TextButton(onClick = { activity.openSettings("com.android.settings.TTS_SETTINGS") }) { Text("打开系统语音设置 / 下载中文包") }
        }
        if (page == Screen.QUIET) Section("免打扰时段") {
            Toggle("启用免打扰时段", "结束后积压内容需手动补读", c.quiet) { enabled -> update { it.copy(quiet = enabled) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = quietStart, onValueChange = { quietStart = it.take(5) }, label = { Text("开始 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = quietEnd, onValueChange = { quietEnd = it.take(5) }, label = { Text("结束 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            TextButton(onClick = {
                val start = parseTime(quietStart); val end = parseTime(quietEnd)
                if (start == null || end == null || start == end) message("请输入不同的开始与结束时间，例如 22:00 和 08:00")
                else update { it.copy(quietStart = start, quietEnd = end) }
            }) { Text("保存时段") }
        }
        if (page == Screen.PRIVACY) Section("隐私与数据") {
            Text("仅保存选定应用通知，通知与摘要保留 24 小时。密钥经 Android Keystore 加密；临时音频播放后删除。不读取通讯录、屏幕或聊天数据库。", fontSize = 12.sp, color = Muted)
            Text("云端服务可能按其规则保留已发送数据。本地删除无法撤回云端数据。系统机读仅选择标记为离线的中文声音。", fontSize = 12.sp, color = Muted)
            TextButton(onClick = { activity.openSettings(AndroidSettings.ACTION_SETTINGS) }) { Text("打开系统设置，检查自启动与后台限制") }
            OutlinedButton(onClick = { confirm("all") }, modifier = Modifier.fillMaxWidth()) { Text("删除全部本地数据与密钥") }
            Text("个人测试原型：手机重启或应用被强行停止后，请重新开启会话；后台准时性和断连停播需实机验收。", fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable private fun Field(label: String, value: String, numeric: Boolean = false, changed: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = changed, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text))
}
@Composable private fun SecretField(label: String, value: String, stored: Boolean, changed: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = changed, label = { Text(label) }, singleLine = true,
        placeholder = { Text(if (stored) "已加密保存；留空保持原密钥" else "请输入密钥") }, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}
private fun validEndpoint(value: String, scheme: String) = runCatching {
    val uri = java.net.URI(value.trim()); uri.scheme == scheme && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
}.getOrDefault(false)
private fun clock(time: Long) = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(time))
private fun timeString(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
private fun parseTime(text: String): Int? {
    val parts = text.split(':'); if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null; val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}
private fun stateLabel(state: String) = when (state) {
    "pending" -> "等待处理"; "held" -> "待确认补读"; "queued" -> "已生成播报"; "failed" -> "失败待重试"
    "ready" -> "等待播放"; "playing" -> "正在播放"; "done" -> "已播报"; "unimportant" -> "无重要内容"; "skipped" -> "已跳过"; else -> state
}
