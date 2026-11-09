package com.zerofriction.localcast.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.ConnectedDesktop
import com.zerofriction.localcast.ui.home.HomeContent
import com.zerofriction.localcast.ui.home.MicPermission
import com.zerofriction.localcast.ui.settings.CastSettingsContent
import com.zerofriction.localcast.ui.theme.LocalCastTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Group F (mobile review 2026-11-09): TalkBack labels and states, 48dp touch
 * targets, nothing clipped at a large system font, and an honest mic row
 * when the permission is missing. Compose on Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: real Skia text metrics — the legacy mode's fake font made
// text-driven sizes meaningless. A tall phone window: every control is on
// screen, so its touch bounds are real (a scrolled-out node measures 0x0).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h4000dp-xxhdpi")
class AccessibilityUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var openedMicSettings = 0

    private fun showHome(
        castState: CastState = CastState.Casting("Gaming PC", startedAtMillis = 0L),
        micState: MicState = MicState.Off,
        micPermission: MicPermission = MicPermission.Granted,
        settings: CastSettings = CastSettings.default(),
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                LocalCastTheme {
                    HomeContent(
                        castState = castState,
                        connection = ConnectedDesktop.State.Connected("Gaming PC"),
                        gameAudioState = GameAudioState.Active,
                        micState = micState,
                        micPermission = micPermission,
                        onOpenMicSettings = { openedMicSettings++ },
                        liveQuality = QualityLevel.PRESETS[1],
                        settings = settings,
                    )
                }
            }
        }
    }

    // ---- TalkBack ----

    @Test
    fun `each switch row is one control that announces its own label`() {
        showHome()
        // Not a bare "Switch, on": the label and the state are one node.
        compose.onNode(isToggleable() and hasText("Game audio")).assertExists()
        compose.onNode(isToggleable() and hasText("Microphone")).assertExists()
        val switches = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .fetchSemanticsNodes()
        assertTrue(switches.isNotEmpty())
        for (node in switches) {
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            assertTrue("a switch without a label: ${node.config}", text.isNotEmpty())
        }
    }

    @Test
    fun `tabs and chips say which one is selected`() {
        showHome(castState = CastState.Idle)
        compose.onNode(hasText("Preset") and isSelected()).assertExists()
        compose.onNode(hasText("Custom") and isNotSelected()).assertExists()
        compose.onNodeWithText("Custom").performClick()
        // The mic picker / custom chips: every chip carries a selected state.
        val chips = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .fetchSemanticsNodes()
        assertTrue(chips.isNotEmpty())
        assertTrue(chips.all { it.config.getOrNull(SemanticsProperties.Selected) != null })
        assertEquals(true, chips.any { it.config.getOrNull(SemanticsProperties.Selected) == true })
    }

    @Test
    fun `the settings back button is announced as Back, not as the screen title`() {
        compose.setContent {
            LocalCastTheme {
                CastSettingsContent(
                    settings = CastSettings.default(),
                    encoderInUse = null,
                    onSetAutoQuality = {},
                    onSetLowLatencyGameAudio = {},
                    onSetEncoderImpl = {},
                    onSetH264HighProfile = {},
                    onSetPreferredCodec = {},
                    onSetDegradationStrategy = {},
                    onSetEncoderFpsLimit = {},
                    onSetBitrateMode = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    // ---- Touch targets ----

    // Compose already expands a small clickable's touch area to 48dp (a 40dp
    // chip still takes a 48dp tap) — these guard that nothing overlapping or
    // custom-drawn ever drops below it.
    @Test
    fun `every tappable control on Home is at least 48dp`() {
        showHome(castState = CastState.Idle)
        compose.onNodeWithText("Custom").performClick()
        assertAllTargetsAtLeast48dp()
    }

    @Test
    fun `every tappable control in settings is at least 48dp`() {
        compose.setContent {
            LocalCastTheme {
                CastSettingsContent(
                    settings = CastSettings.default(),
                    encoderInUse = null,
                    onSetAutoQuality = {},
                    onSetLowLatencyGameAudio = {},
                    onSetEncoderImpl = {},
                    onSetH264HighProfile = {},
                    onSetPreferredCodec = {},
                    onSetDegradationStrategy = {},
                    onSetEncoderFpsLimit = {},
                    onSetBitrateMode = {},
                )
            }
        }
        assertAllTargetsAtLeast48dp()
    }

    private fun assertAllTargetsAtLeast48dp() {
        val minPx = with(compose.density) { 48f * density } - 0.5f
        val small = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            node.touchBoundsInRoot.width < minPx || node.touchBoundsInRoot.height < minPx
        }
        assertTrue(
            "under 48dp: " + small.map {
                (it.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()) +
                    " ${it.touchBoundsInRoot.width / compose.density.density}x${it.touchBoundsInRoot.height / compose.density.density}dp"
            },
            small.isEmpty(),
        )
    }

    // ---- Large font ----

    @Test
    fun `at a 200 percent system font text buttons grow instead of clipping their label`() {
        showHome(castState = CastState.Idle, fontScale = 2f)
        // 14sp at 2x needs at least 28dp of line; the old fixed 40dp button
        // squeezed the label to 24dp — the glyphs were cut (measured with
        // native text metrics).
        val label = compose.onNodeWithText("Disconnect", useUnmergedTree = true).fetchSemanticsNode()
        val heightDp = label.boundsInRoot.height / compose.density.density
        assertTrue("label squeezed to ${heightDp}dp", heightDp >= 28f)
    }

    // ---- Mic permission ----

    private val micWanted = CastSettings.default().copy(mic = true)

    @Test
    fun `a mic waiting for permission is shown off, with the reason`() {
        showHome(micState = MicState.NeedsPermission, micPermission = MicPermission.Askable, settings = micWanted)
        compose.onNode(isToggleable() and hasText("Microphone")).assertIsOff()
        compose.onNodeWithText("Needs microphone access", substring = true).assertExists()
        compose.onNodeWithText("Open settings").assertDoesNotExist()
    }

    @Test
    fun `a permanently denied mic offers the way to Android settings`() {
        showHome(micState = MicState.NeedsPermission, micPermission = MicPermission.Blocked, settings = micWanted)
        compose.onNode(isToggleable() and hasText("Microphone")).assertIsOff()
        compose.onNodeWithText("Open settings").performScrollTo().performClick()
        assertEquals(1, openedMicSettings)
    }

    // Found on the device check: before a cast the row ignored the permission
    // entirely — a blocked mic looked switched on and offered nothing.
    @Test
    fun `before a cast a blocked mic is shown off and offers Android settings`() {
        showHome(castState = CastState.Idle, micPermission = MicPermission.Blocked, settings = micWanted)
        compose.onNode(isToggleable() and hasText("Microphone")).assertIsOff()
        compose.onNodeWithText("Microphone access is off", substring = true).assertExists()
        compose.onNodeWithText("Open settings").performScrollTo().performClick()
        assertEquals(1, openedMicSettings)
    }

    @Test
    fun `a mic switched off says nothing about the permission`() {
        showHome(castState = CastState.Idle, micPermission = MicPermission.Blocked)
        compose.onNode(isToggleable() and hasText("Microphone")).assertIsOff()
        compose.onNodeWithText("Open settings").assertDoesNotExist()
    }
}
