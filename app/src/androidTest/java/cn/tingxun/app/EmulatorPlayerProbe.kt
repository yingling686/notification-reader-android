package cn.tingxun.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** Test APK only: exercises the real platform player on a no-audio emulator, never on a user phone. */
class EmulatorPlayerProbe(private val context: Context) : ReadingOutput {
    private val focus = PlatformAudioFocus(context, context.graph.diagnostics)
    private var player: MediaPlayer? = null
    override fun connected() = true
    override fun callActive() = false
    override fun stop() { runCatching { player?.pause() } }
    override suspend fun play(file: File, allowed: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Audio probe is emulator-only" }
        val lease = focus.acquire(allowed) { stop() }
        val mp = MediaPlayer(); player = mp
        try {
            withTimeout(20_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener {
                        if (!allowed()) { cont.cancel(); return@setOnPreparedListener }
                        mp.start(); context.graph.engine.transition(ListeningState.PLAYING)
                    }
                    mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                    mp.setOnErrorListener { _, what, extra ->
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Emulator platform player: $what/$extra"))
                        true
                    }
                    cont.invokeOnCancellation { runCatching { mp.pause() } }
                    mp.prepareAsync()
                }
            }
        } finally { mp.release(); player = null; lease.close() }
    }
}
