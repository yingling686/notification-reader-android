package cn.tingxun.app

import org.junit.Assert.*
import org.junit.Test

class ListeningPolicyTest {
    @Test fun anEnabledUnpausedSessionWaitsForFutureNotificationsRatherThanStopping() {
        assertEquals(ListeningState.WAITING, ListeningPolicy.resting(true, false, false))
        assertFalse(ListeningState.WAITING.mediaActive)
    }
    @Test fun explicitStopAndPauseTakePrecedenceOverTemporaryBlocking() {
        assertEquals(ListeningState.STOPPED, ListeningPolicy.resting(false, false, true))
        assertEquals(ListeningState.PAUSED, ListeningPolicy.resting(true, true, true))
        assertEquals(ListeningState.BLOCKED, ListeningPolicy.resting(true, false, true))
    }
    @Test fun mediaControlsAreOnlyActiveWhileProducingSpeech() {
        assertEquals(setOf(ListeningState.SYNTHESIZING, ListeningState.PLAYING), ListeningState.entries.filter { it.mediaActive }.toSet())
    }
}
