package cn.tingxun.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun compactLayoutWithLargeTextKeepsControlsAndCreditReachable() {
        ui.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.6f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 500.dp)) {
                        HomeContent(ListeningState.BLOCKED, "听读暂缓", "免打扰至 08:00，暂不自动播报", "蓝牙耳机已连接", "即时朗读 · 系统机读",
                            HomePresentation.control(true, false, ListeningState.BLOCKED)) { }
                    }
                }
            }
        }
        ui.onNodeWithTag("home-control").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("接化发俱乐部出品").performScrollTo().assertIsDisplayed()
    }
    @Test fun allSixStatesRenderAndButtonDeliversExactlyOneAction() {
        val state = mutableStateOf(ListeningState.STOPPED)
        var clicks = 0
        ui.setContent {
            MaterialTheme {
                val value = state.value
                HomeContent(value, value.title, "测试状态，不采集麦克风", "蓝牙耳机已连接", "即时朗读 · 系统机读",
                    HomePresentation.control(value != ListeningState.STOPPED, value == ListeningState.PAUSED, value)) { clicks++ }
            }
        }
        ListeningState.entries.forEachIndexed { index, value ->
            ui.runOnIdle { state.value = value }
            ui.onNodeWithText(value.title).assertExists()
            ui.onNodeWithText("接化发俱乐部出品").assertExists()
            ui.onNodeWithTag("home-control").performScrollTo().assertHasClickAction().performClick()
            ui.runOnIdle { assertEquals(index + 1, clicks) }
        }
    }
}
