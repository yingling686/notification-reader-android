package cn.tingxun.app

import android.content.Context
import android.os.PowerManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/** Event-scoped CPU protection: never held by the idle session or to keep the screen on. */
class ProcessingWake(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    internal val active = AtomicInteger(0)
    fun acquire(timeoutMs: Long = 660_000L): Closeable {
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tingxun:notification-work")
        wake.setReferenceCounted(false)
        wake.acquire(timeoutMs)
        active.incrementAndGet()
        return object : Closeable {
            private var closed = false
            @Synchronized override fun close() {
                if (closed) return
                closed = true
                runCatching { if (wake.isHeld) wake.release() }
                active.decrementAndGet()
            }
        }
    }
}
