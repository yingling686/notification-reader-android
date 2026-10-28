package cn.tingxun.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackRetryTest {
    @Test fun deniedFocusRetriesAtOneThreeEightSecondsAndStopsAfterSuccess() = runBlocking {
        val waits = mutableListOf<Long>(); var attempts = 0; var completed = 0
        PlaybackRetry { waits.add(it) }.run({ true }) {
            if (++attempts < 4) throw PlaybackBlocked(PlaybackBlockReason.FOCUS_DENIED)
            completed++
        }
        assertEquals(listOf(1000L, 3000L, 8000L), waits); assertEquals(4, attempts); assertEquals(1, completed)
    }
    @Test fun delayedFocusHasFiniteRetryBudget() = runBlocking {
        var attempts = 0
        try {
            PlaybackRetry { }.run({ true }) { attempts++; throw PlaybackBlocked(PlaybackBlockReason.FOCUS_DELAYED) }
            fail("must report exhausted focus")
        } catch (e: PlaybackBlocked) { assertEquals(PlaybackBlockReason.FOCUS_DELAYED, e.reason) }
        assertEquals(4, attempts)
    }
    @Test fun lossBeforeAudibleOutputMayRetry() = runBlocking {
        var attempts = 0
        PlaybackRetry { }.run({ true }) { if (++attempts == 1) throw PlaybackBlocked(PlaybackBlockReason.FOCUS_LOST_BEFORE_PLAY) }
        assertEquals(2, attempts)
    }
    @Test fun safetyInterruptionsAndMidSpeechFocusLossNeverAutoReplay() = runBlocking {
        for (reason in listOf(PlaybackBlockReason.HEADPHONES_MISSING, PlaybackBlockReason.CALL_ACTIVE,
            PlaybackBlockReason.ROUTE_CHANGED, PlaybackBlockReason.ROUTE_UNCONFIRMED, PlaybackBlockReason.FOCUS_LOST_DURING_PLAY)) {
            var attempts = 0
            try {
                PlaybackRetry { fail("safety failures must not retry") }.run({ true }) { attempts++; throw PlaybackBlocked(reason) }
                fail("expected block")
            } catch (e: PlaybackBlocked) { assertEquals(reason, e.reason) }
            assertEquals(1, attempts)
        }
    }
    @Test fun pauseOrClearDuringDelayPreventsAnyFurtherAttempt() = runBlocking {
        var allowed = true; var attempts = 0
        try {
            PlaybackRetry { allowed = false }.run({ allowed }) { attempts++; throw PlaybackBlocked(PlaybackBlockReason.FOCUS_DENIED) }
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }
    @Test fun cancellationDuringWaitPropagatesWithoutAnotherPlay() = runBlocking {
        var attempts = 0
        try {
            PlaybackRetry { throw CancellationException("clear") }.run({ true }) { attempts++; throw PlaybackBlocked(PlaybackBlockReason.FOCUS_DELAYED) }
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }
    @Test fun nonFocusFailuresAreNotRetriedOrHidden() = runBlocking {
        val error = IllegalStateException("fixture error")
        try {
            PlaybackRetry { fail("unexpected retry") }.run({ true }) { throw error }
            fail("expected error")
        } catch (e: IllegalStateException) { assertSame(error, e) }
    }
}
