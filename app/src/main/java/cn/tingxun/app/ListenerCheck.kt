package cn.tingxun.app

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Fixed local notification only; never synthesizes speech or feeds the user's queue. */
class ListenerCheck(private val g: Graph) {
    val result = MutableStateFlow("尚未运行通知接收自检")
    val busy = MutableStateFlow(false)
    private var waiter: CompletableDeferred<Unit>? = null
    private var tag: String? = null
    private var job: Job? = null
    private val manager get() = g.context.getSystemService(NotificationManager::class.java)
    fun granted() = manager.isNotificationListenerAccessGranted(ComponentName(g.context, NotificationCapture::class.java))
    fun requestRebind(): Boolean {
        if (g.listenerConnected.value) return true
        return try {
            if (!granted()) { g.diagnostics.record(DiagnosticStage.LISTENER_ACCESS_MISSING); return false }
            g.diagnostics.record(DiagnosticStage.LISTENER_REBIND)
            NotificationListenerService.requestRebind(ComponentName(g.context, NotificationCapture::class.java))
            g.diagnostics.record(DiagnosticStage.LISTENER_REBIND_RETURNED); true
        } catch (e: Exception) {
            g.diagnostics.record(DiagnosticStage.LISTENER_REBIND_FAILED, value = when (e) { is SecurityException -> 1; is IllegalStateException -> 2; else -> 3 }); false
        }
    }
    fun accept(sbn: StatusBarNotification) {
        if (sbn.packageName == g.context.packageName && tag != null && sbn.tag == tag && sbn.id == 901) {
            g.diagnostics.record(DiagnosticStage.LISTENER_PROBE_RECEIVED)
            waiter?.complete(Unit)
        }
    }
    fun run() {
        if (busy.value) return
        busy.value = true
        job = g.scope.launch {
            try {
                if (!granted()) {
                    result.value = "通知访问尚未授权；请先在系统设置中开启"
                    g.diagnostics.record(DiagnosticStage.LISTENER_ACCESS_MISSING); return@launch
                }
                result.value = "正在检查系统是否已绑定通知监听…"
                if (!g.listenerConnected.value) {
                    if (!requestRebind()) { result.value = "重连调用失败，请复制诊断"; return@launch }
                    val connected = withTimeoutOrNull(10_000L) { while (!g.listenerConnected.value) delay(100); true } == true
                    if (!connected) {
                        g.diagnostics.record(DiagnosticStage.LISTENER_CONNECT_TIMEOUT)
                        result.value = "通知访问已授权，但系统 10 秒内未连接监听；不是语音合成问题。请复制诊断"
                        return@launch
                    }
                }
                if (Build.VERSION.SDK_INT >= 33 && g.context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled()) {
                    result.value = "监听已连接，但听讯通知权限未允许，无法发送自检通知"; return@launch
                }
                val token = "tingxun-check-" + UUID.randomUUID()
                tag = token; waiter = CompletableDeferred()
                manager.createNotificationChannel(NotificationChannel("listener-check", "通知接收自检", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
                if (manager.getNotificationChannel("listener-check")?.importance == NotificationManager.IMPORTANCE_NONE) {
                    result.value = "自检通知频道已被关闭，请在系统听讯通知设置中允许该频道"; return@launch
                }
                result.value = "正在发送固定自检通知，不会朗读…"
                g.diagnostics.record(DiagnosticStage.LISTENER_PROBE_POSTED)
                manager.notify(token, 901, Notification.Builder(g.context, "listener-check").setSmallIcon(R.drawable.ic_status)
                    .setContentTitle("听讯通知接收自检").setContentText("固定测试通知，不朗读，不访问其他消息")
                    .setTimeoutAfter(12_000).build())
                val received = withTimeoutOrNull(10_000L) { waiter?.await(); true } == true
                result.value = if (received) "自检通过：系统监听已连接，固定通知回调已收到。请再发送一条选中应用的新消息"
                    else "监听显示已连接，但 10 秒内没有自检回调；请复制诊断"
                if (!received) g.diagnostics.record(DiagnosticStage.LISTENER_PROBE_TIMEOUT)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { result.value = "系统检查失败，请复制诊断"; g.diagnostics.record(DiagnosticStage.LISTENER_PROBE_FAILED) }
            finally {
                tag?.let { runCatching { manager.cancel(it, 901) } }; tag = null; waiter = null; busy.value = false
            }
        }.also { task -> task.invokeOnCompletion { busy.value = false } }
    }
    fun clear() { job?.cancel(); result.value = "尚未运行通知接收自检" }
}

object DeviceEvidence {
    fun environment(context: Context): JSONObject = JSONObject().apply {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            val component = ComponentName(context, NotificationCapture::class.java)
            put("notificationAccessGranted", manager.isNotificationListenerAccessGranted(component))
            put("notificationsEnabled", manager.areNotificationsEnabled())
            put("phonePermission", context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
            put("postPermission", Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            put("listenerComponentSetting", context.packageManager.getComponentEnabledSetting(component))
            @Suppress("DEPRECATION") val service = context.packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
            put("listenerServiceEnabled", service.enabled && service.applicationInfo.enabled)
            val user = context.getSystemService(UserManager::class.java)
            put("managedProfile", user.isManagedProfile); put("userUnlocked", user.isUserUnlocked)
            val activity = context.getSystemService(ActivityManager::class.java)
            put("backgroundRestricted", activity.isBackgroundRestricted)
            put("batteryExempt", context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
            put("outputDeviceTypes", JSONArray(context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }))
        }.onFailure { put("snapshotFailed", true) }
    }
    fun exits(context: Context): JSONObject = JSONObject().apply {
        runCatching {
            val rows = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val cutoff = System.currentTimeMillis() - 86_400_000L
            put("available", true)
            put("records", JSONArray(rows.filter { it.timestamp >= cutoff }.map {
                JSONObject().put("time", it.timestamp).put("reason", it.reason).put("status", it.status).put("importance", it.importance)
            }))
        }.onFailure { put("available", false) }
    }
}
