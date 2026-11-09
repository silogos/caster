package com.zerofriction.localcast.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.performTouchInput
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.ui.theme.LocalCastTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The quality console's bitrate dial (mobile review 2026-11-09): one live
 * request per gesture, and a live readout that changes mid-drag (auto
 * quality stepping) must not crash the app — it used to throw on NaN.
 */
@RunWith(RobolectricTestRunner::class)
class QualityConsoleCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val start = QualityLevel.PRESETS[2]
    private val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
    private val applied = mutableListOf<QualityLevel>()
    private var live by mutableStateOf<QualityLevel?>(null)

    private fun showCasting() {
        live = start
        compose.setContent {
            LocalCastTheme {
                QualityConsoleCard(
                    casting = true,
                    settings = CastSettings.default(),
                    liveQuality = live,
                    startedAtMillis = 0L,
                    onSelectProfile = {},
                    onSelectLongEdge = {},
                    onSelectFps = {},
                    onSetManualBitrateMax = {},
                    onApplyLive = { applied.add(it) },
                )
            }
        }
        compose.onNodeWithText("Custom").performClick()
    }

    @Test
    fun `a drag applies once on release, keeping the min-to-max window`() {
        showCasting()
        compose.onNode(slider).performTouchInput {
            down(center)
            dragRightBy(0.4f)
            up()
        }
        compose.waitForIdle()

        val pick = applied.single()
        assertTrue(pick.bitrateMaxBps > start.bitrateMaxBps)
        assertTrue(pick.bitrateMinBps < pick.bitrateMaxBps)
        assertEquals(start.longEdgePx, pick.longEdgePx) // only the dial moved
        assertEquals(start.fps, pick.fps)
    }

    @Test
    fun `auto quality stepping mid-drag does not crash and applies nothing stale`() {
        showCasting()
        compose.onNode(slider).performTouchInput {
            down(center)
            dragRightBy(0.3f)
        }
        compose.waitForIdle()
        val draggingFrom = applied.size
        // The policy steps down while the finger is still on the dial.
        live = QualityLevel.PRESETS[0]
        compose.waitForIdle()
        compose.onNode(slider).performTouchInput { up() }
        compose.waitForIdle()

        // The re-keyed drag is dropped: no crash, no stale value sent.
        assertEquals(draggingFrom, applied.size)
        assertTrue(applied.isEmpty())
    }

    /** A real finger drag: small steps past the touch slop, not one jump. */
    private fun TouchInjectionScope.dragRightBy(fractionOfWidth: Float) {
        val steps = 20
        repeat(steps) { moveBy(Offset(width * fractionOfWidth / steps, 0f)) }
    }
}
