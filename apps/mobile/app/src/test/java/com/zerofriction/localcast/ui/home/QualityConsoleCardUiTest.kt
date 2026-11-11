package com.zerofriction.localcast.ui.home

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CustomProfileRules
import com.zerofriction.localcast.config.ProfileSelection
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
 * The profile list on Home (ADR-024): what is listed, which row is lit, what
 * a pick does idle and while casting, and what only the user's own profiles
 * offer. Compose on Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
// A tall window: every row is on screen, so a tap on it lands.
@Config(qualifiers = "w411dp-h4000dp-xxhdpi")
class QualityConsoleCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val sharp60 = QualityProfile.SHARP.toValues().copy(fps = 60)
    private val withMine = CastSettings.default().withProfileAdded("Stream 60", sharp60)
    private val mine = withMine.customProfiles.single()

    private val selected = mutableListOf<ProfileSelection>()
    private val applied = mutableListOf<QualityLevel>()
    private val edited = mutableListOf<Int>()
    private val duplicated = mutableListOf<Int>()
    private val deleted = mutableListOf<Int>()
    private var adds = 0

    private fun show(settings: CastSettings, casting: Boolean = false, live: QualityLevel? = null) {
        compose.setContent {
            LocalCastTheme {
                QualityConsoleCard(
                    casting = casting,
                    settings = settings,
                    liveQuality = live,
                    startedAtMillis = 0L,
                    onSelect = { selected.add(it) },
                    onApplyLive = { applied.add(it) },
                    onAddProfile = { adds++ },
                    onEditProfile = { edited.add(it) },
                    onDuplicateProfile = { duplicated.add(it) },
                    onDeleteProfile = { deleted.add(it) },
                )
            }
        }
    }

    @Test
    fun `the built-in profiles are listed first, then the user's own, each with what it gives up`() {
        show(withMine)
        for (name in listOf("Cool", "Balanced", "Performance", "Sharp", "Stream 60")) {
            compose.onNodeWithText(name).assertExists()
        }
        compose.onNodeWithText("Your profiles", ignoreCase = true).assertExists()
        // Sharp and the copy made from it both keep resolution; Performance keeps frame rate.
        assertEquals(2, compose.onAllNodes(hasText("KEEP RESOLUTION")).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText("KEEP FRAME RATE")).fetchSemanticsNodes().size)
        compose.onNodeWithText("1920 px · 60 fps · 8–12 Mbps").assertExists()
    }

    @Test
    fun `without profiles of the user's own there is no empty zone, only the way to add one`() {
        show(CastSettings.default())
        compose.onNodeWithText("Your profiles", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Add profile").performClick()
        assertEquals(1, adds)
    }

    @Test
    fun `idle - the stored selection is lit and a pick selects for the next cast`() {
        show(withMine)
        compose.onNode(hasText("Stream 60") and isSelected()).assertExists()
        compose.onNode(hasText("Balanced") and isNotSelected()).assertExists()
        compose.onNodeWithText("Cool").performClick()
        assertEquals(listOf<ProfileSelection>(ProfileSelection.BuiltIn(QualityProfile.COOL)), selected)
        assertTrue("nothing is applied to a cast that is not running", applied.isEmpty())
    }

    @Test
    fun `casting - a pick steers the running cast and leaves the stored selection alone`() {
        show(CastSettings.default(), casting = true, live = QualityLevel.PRESETS[1])
        compose.onNodeWithText("Sharp").performClick()
        assertEquals(listOf(QualityLevel(1920, 30, 8_000_000, 12_000_000)), applied)
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `casting - a pick of the user's profile applies that profile's picture`() {
        show(withMine, casting = true, live = QualityLevel.PRESETS[1])
        compose.onNodeWithText("Stream 60").performClick()
        assertEquals(listOf(QualityLevel(1920, 60, 8_000_000, 12_000_000)), applied)
    }

    @Test
    fun `casting - the lit row follows the running picture, not the stored selection`() {
        // Balanced is stored; auto quality stepped the cast down to Cool's picture.
        show(CastSettings.default(), casting = true, live = QualityLevel.PRESETS[0])
        compose.onNode(hasText("Cool") and isSelected()).assertExists()
        compose.onNode(hasText("Balanced") and isNotSelected()).assertExists()
    }

    @Test
    fun `only the user's own profiles have a menu`() {
        show(withMine)
        compose.onNodeWithContentDescription("More for Stream 60").assertExists()
        compose.onNodeWithContentDescription("More for Sharp").assertDoesNotExist()
        compose.onNodeWithContentDescription("More for Balanced").assertDoesNotExist()
    }

    @Test
    fun `the menu opens the profile for editing or copies it`() {
        show(withMine)
        compose.onNodeWithContentDescription("More for Stream 60").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithContentDescription("More for Stream 60").performClick()
        compose.onNodeWithText("Duplicate").performClick()
        assertEquals(listOf(mine.id), edited)
        assertEquals(listOf(mine.id), duplicated)
    }

    @Test
    fun `deleting asks first, says where the next cast lands, and Cancel deletes nothing`() {
        show(withMine)
        compose.onNodeWithContentDescription("More for Stream 60").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete “Stream 60”?").assertExists()
        compose.onNodeWithText("The next cast uses Balanced instead.", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(deleted.isEmpty())

        compose.onNodeWithContentDescription("More for Stream 60").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete").performClick() // the dialog's own button
        assertEquals(listOf(mine.id), deleted)
    }

    @Test
    fun `at the limit, adding and copying are off and the card says why`() {
        var full = CastSettings.default()
        repeat(CustomProfileRules.MAX_PROFILES) { full = full.withProfileAdded("P$it", sharp60) }
        show(full)
        compose.onNodeWithText("Add profile").assertIsNotEnabled()
        compose.onNodeWithText("${CustomProfileRules.MAX_PROFILES} profiles is the most.", substring = true).assertExists()
        compose.onNodeWithContentDescription("More for P0").performClick()
        compose.onNodeWithText("Duplicate").assertDoesNotExist()
    }
}
