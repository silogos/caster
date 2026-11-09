package com.zerofriction.localcast.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.ConnectedDesktop
import com.zerofriction.localcast.ui.theme.LocalCastTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home renders every cast state and routes the transport button to the
 * right action (Compose on Robolectric — mobile review 2026-11-09, group G).
 */
@RunWith(RobolectricTestRunner::class)
class HomeContentUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var shares = 0
    private var stops = 0

    private fun show(castState: CastState) {
        compose.setContent {
            LocalCastTheme {
                HomeContent(
                    castState = castState,
                    connection = ConnectedDesktop.State.Connected("Gaming PC"),
                    settings = CastSettings.default(),
                    onShareScreen = { shares++ },
                    onStopShareScreen = { stops++ },
                )
            }
        }
    }

    @Test
    fun `connected and idle - standby, and the transport button shares the screen`() {
        show(CastState.Idle)
        compose.onNodeWithText("Standby", ignoreCase = true).assertExists()
        compose.onNodeWithText("Share screen").performScrollTo().performClick()
        assertEquals(1, shares)
        assertEquals(0, stops)
    }

    @Test
    fun `starting reads STARTING`() {
        show(CastState.Starting("Gaming PC"))
        compose.onNodeWithText("Starting", ignoreCase = true).assertExists()
    }

    @Test
    fun `casting - on air, and the transport button stops only the share`() {
        show(CastState.Casting("Gaming PC", startedAtMillis = System.currentTimeMillis()))
        compose.onNodeWithText("On air", ignoreCase = true).assertExists()
        compose.onNodeWithText("Stop share screen").performScrollTo().performClick()
        assertEquals(1, stops)
        assertEquals(0, shares)
    }

    @Test
    fun `a failed cast reads FAULT and shows its plain-words message`() {
        val message = "Couldn't connect to the desktop. Make sure both devices are on the same Wi-Fi network."
        show(CastState.Failed(message))
        compose.onNodeWithText("Fault", ignoreCase = true).assertExists()
        compose.onNodeWithText(message).assertExists()
    }
}
