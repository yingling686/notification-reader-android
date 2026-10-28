package cn.tingxun.app

import org.junit.Assert.*
import org.junit.Test

class HomePresentationTest {
    @Test fun sessionStatesHaveUnambiguousActions() {
        assertEquals(HomeAction.START, HomePresentation.control(false, false, ListeningState.STOPPED).action)
        for (state in listOf(ListeningState.WAITING, ListeningState.SYNTHESIZING, ListeningState.PLAYING)) {
            assertEquals(HomeControl("暂停听读", HomeAction.PAUSE), HomePresentation.control(true, false, state))
        }
        assertEquals(HomeAction.RESUME, HomePresentation.control(true, true, ListeningState.PAUSED).action)
        assertEquals(HomeAction.RESOLVE, HomePresentation.control(true, false, ListeningState.BLOCKED).action)
    }
    @Test fun pausedAndStoppedFlagsWinDuringStateTransition() {
        assertEquals(HomeAction.START, HomePresentation.control(false, true, ListeningState.PLAYING).action)
        assertEquals(HomeAction.RESUME, HomePresentation.control(true, true, ListeningState.BLOCKED).action)
    }
    @Test fun everySettingsPageReturnsToItsDirectory() {
        assertEquals(Screen.HOME, Screen.SETTINGS.parent)
        assertEquals(Screen.AI, Screen.SUMMARY.parent)
        assertEquals(Screen.AI, Screen.SPEECH.parent)
        Screen.entries.filter { it !in listOf(Screen.HOME, Screen.SETTINGS, Screen.SUMMARY, Screen.SPEECH) }.forEach {
            assertEquals(Screen.SETTINGS, it.parent)
        }
    }
}
