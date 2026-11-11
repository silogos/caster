package com.zerofriction.localcast.ui.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CustomProfile
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.EncoderImplementation
import com.zerofriction.localcast.config.PreferredVideoCodec
import com.zerofriction.localcast.config.ProfileValues
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.toValues
import com.zerofriction.localcast.ui.theme.LocalCastTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Editing and adding a profile (ADR-024): each control changes the one value
 * it names, a name is saved only when it is one a profile may have, and a
 * new profile is a copy of what was picked. Compose on Robolectric; a tall
 * window, so every control is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp-xxhdpi")
class ProfileScreensUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val sharp60 = QualityProfile.SHARP.toValues().copy(fps = 60)
    private var profile by mutableStateOf(CustomProfile(1, "Stream 60", sharp60))
    private val renames = mutableListOf<String>()
    private var edits = 0
    private var duplicates = 0
    private var deletes = 0

    private fun showEdit(canDuplicate: Boolean = true) {
        compose.setContent {
            LocalCastTheme {
                ProfileEditContent(
                    profile = profile,
                    selected = true,
                    canDuplicate = canDuplicate,
                    onRename = { renames.add(it) },
                    onEdit = { edit ->
                        edits++
                        profile = profile.copy(values = edit(profile.values))
                    },
                    onDuplicate = { duplicates++ },
                    onDelete = { deletes++ },
                )
            }
        }
    }

    // ---- Edit ----

    @Test
    fun `each choice changes the one value it names`() {
        showEdit()
        compose.onNodeWithText("960 px").performScrollTo().performClick()
        compose.onNodeWithText("Keep frame rate").performScrollTo().performClick()
        compose.onNodeWithText("Software").performScrollTo().performClick()
        compose.onNodeWithText("Low latency").performScrollTo().performClick()
        assertEquals(
            sharp60.copy(
                longEdgePx = 960,
                degradationStrategy = DegradationStrategy.MAINTAIN_FRAMERATE,
                encoderImpl = EncoderImplementation.SOFTWARE,
                lowLatencyGameAudio = true,
            ),
            profile.values,
        )
        assertEquals(4, edits)
    }

    @Test
    fun `the H264 profile is a choice only while H264 is the codec`() {
        showEdit()
        compose.onNodeWithText("Baseline").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("VP8").performScrollTo().performClick()
        assertEquals(PreferredVideoCodec.VP8, profile.values.preferredCodec)
        compose.onNodeWithText("Baseline").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Profile applies to H.264 only.").assertExists()
    }

    @Test
    fun `a name is saved as it is typed, trimmed`() {
        showEdit()
        compose.onNode(hasSetTextAction()).performTextReplacement("Evening ")
        assertEquals(listOf("Evening"), renames)
    }

    @Test
    fun `an empty name is not saved and says what is missing`() {
        showEdit()
        compose.onNode(hasSetTextAction()).performTextReplacement("   ")
        assertTrue(renames.isEmpty())
        compose.onNodeWithText("Give the profile a name.").assertExists()
    }

    @Test
    fun `typing past twenty characters is ignored`() {
        showEdit()
        compose.onNode(hasSetTextAction()).performTextReplacement("abcdefghijklmnopqrstuvwxyz")
        assertEquals(listOf("abcdefghijklmnopqrst"), renames)
        compose.onNodeWithText("20 / 20").assertExists()
    }

    @Test
    fun `the bitrate slider has two thumbs, the least and the most, and says both`() {
        showEdit()
        compose.onNodeWithText("Bitrate — 8 to 12 Mbps").assertExists()
        assertEquals(2, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes().size)
    }

    @Test
    fun `moving the least saves the range once and leaves the most alone`() {
        showEdit()
        val thumbs = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        thumbs[0].performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.waitForIdle()
        assertEquals(1, edits)
        assertEquals(4_000_000 to 12_000_000, profile.values.bitrateMinBps to profile.values.bitrateMaxBps)
        compose.onNodeWithText("Bitrate — 4 to 12 Mbps").assertExists()
        assertEquals("only the slider moved", sharp60.fps, profile.values.fps)
    }

    @Test
    fun `moving the most saves the range and leaves the least alone`() {
        showEdit()
        val thumbs = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        thumbs[1].performSemanticsAction(SemanticsActions.SetProgress) { it(16f) }
        compose.waitForIdle()
        assertEquals(8_000_000 to 16_000_000, profile.values.bitrateMinBps to profile.values.bitrateMaxBps)
    }

    @Test
    fun `deleting asks first`() {
        showEdit()
        compose.onNodeWithText("Delete profile").performScrollTo().performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Delete “Stream 60”?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun `a copy is offered only while one more profile fits`() {
        showEdit(canDuplicate = false)
        compose.onNodeWithText("Duplicate").performScrollTo().assertIsNotEnabled()
        assertEquals(0, duplicates)
    }

    // ---- Add ----

    private val created = mutableListOf<Pair<String, ProfileValues>>()
    private var cancels = 0

    private fun showAdd(settings: CastSettings) {
        compose.setContent {
            LocalCastTheme {
                AddProfileContent(
                    settings = settings,
                    onCreate = { name, values -> created.add(name to values) },
                    onCancel = { cancels++ },
                )
            }
        }
    }

    @Test
    fun `a new profile starts as a copy of the values on screen, under the next free name`() {
        showAdd(CastSettings.default().withProfileAdded("Custom 1", sharp60))
        compose.onNodeWithText("1920 px · 60 fps · 8–12 Mbps").assertExists()
        compose.onNodeWithText("Keep resolution · Hardware · H.264 High").assertExists()
        compose.onNodeWithText("Create").performClick()
        assertEquals(listOf("Custom 2" to sharp60), created)
    }

    @Test
    fun `it can start from any profile instead, and the strip shows what will be copied`() {
        showAdd(CastSettings.default())
        compose.onNodeWithText("Performance").performClick()
        compose.onNodeWithText("1280 px · 60 fps · 6–10 Mbps").assertExists()
        compose.onNodeWithText("Keep frame rate · Hardware · H.264 High").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement(" Fast ")
        compose.onNodeWithText("Create").performClick()
        assertEquals(listOf("Fast" to QualityProfile.PERFORMANCE.toValues()), created)
    }

    @Test
    fun `without a name nothing can be created, and Cancel creates nothing`() {
        showAdd(CastSettings.default())
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(created.isEmpty())
        assertEquals(1, cancels)
    }

    /** A real finger drag: small steps past the touch slop, not one jump. */
    private fun TouchInjectionScope.dragRightBy(fractionOfWidth: Float) {
        val steps = 20
        repeat(steps) { moveBy(Offset(width * fractionOfWidth / steps, 0f)) }
    }
}
