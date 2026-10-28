package cn.tingxun.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import kotlinx.coroutines.*

/** Real Android focus negotiation, separately testable without playing through a speaker. */
class PlatformAudioFocus(context: Context, private val diagnostics: Diagnostics) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    suspend fun acquire(allowed: () -> Boolean, onLost: () -> Unit): Closeable = withContext(Dispatchers.Main.immediate) {
        val gain = CompletableDeferred<Unit>()
        var active = true
        var granted = false
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change ->
                if (active) {
                    diagnostics.record(DiagnosticStage.FOCUS_CHANGE, value = change)
                    if (change == AudioManager.AUDIOFOCUS_GAIN) gain.complete(Unit)
                    else if (granted) onLost()
                }
            }, handler).build()
        val lease = object : Closeable {
            override fun close() {
                if (!active) return
                active = false; manager.abandonAudioFocusRequest(request)
            }
        }
        try {
            if (!allowed()) throw CancellationException("会话已取消")
            diagnostics.record(DiagnosticStage.FOCUS_REQUEST)
            val result = manager.requestAudioFocus(request)
            diagnostics.record(DiagnosticStage.FOCUS_RESULT, value = result)
            when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> Unit
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> if (withTimeoutOrNull(2000) { gain.await(); true } != true)
                    throw PlaybackBlocked(PlaybackBlockReason.FOCUS_DELAYED)
                else -> throw PlaybackBlocked(PlaybackBlockReason.FOCUS_DENIED)
            }
            if (!allowed()) throw CancellationException("会话已取消")
            granted = true
            lease
        } catch (e: Exception) { lease.close(); throw e }
    }
}
