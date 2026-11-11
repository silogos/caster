package com.zerofriction.localcast.service

import androidx.test.core.app.ApplicationProvider
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.Direction
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.QualityChange
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.Reason
import com.zerofriction.localcast.adaptive.QualityLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A step of the cast's quality in the words the user reads (ADR-025): what, why, and that it comes back. */
@RunWith(RobolectricTestRunner::class)
class QualityChangeTextTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val profile = QualityLevel(1920, 60, 4_000_000, 12_000_000)
    private val smaller = profile.copy(longEdgePx = 1280)

    @Test
    fun `a thermal step says what was lowered, why, and that it waits for the phone to cool`() {
        assertEquals(
            "Lowered to 1280 px: the phone is too hot. Comes back once it cools.",
            qualityChangeLine(context, QualityChange(profile, smaller, Direction.DOWN, Reason.THERMAL), belowProfile = true),
        )
        assertEquals(
            "Lowered to 30 fps: the phone is too hot. Comes back once it cools.",
            qualityChangeLine(context, QualityChange(profile, profile.copy(fps = 30), Direction.DOWN, Reason.THERMAL), belowProfile = true),
        )
    }

    @Test
    fun `a step back up says whether more is still to come`() {
        val up = QualityChange(profile.copy(longEdgePx = 960), smaller, Direction.UP, Reason.RECOVERED)
        assertEquals("Up to 1280 px: the phone cooled down. The rest comes back by itself.", qualityChangeLine(context, up, belowProfile = true))
        val home = QualityChange(smaller, profile, Direction.UP, Reason.RECOVERED)
        assertEquals("Back to 1920 px: the phone cooled down.", qualityChangeLine(context, home, belowProfile = false))
    }

    @Test
    fun `a restore that undoes two different steps names both`() {
        val restore = QualityChange(QualityLevel(1280, 30, 4_000_000, 12_000_000), profile, Direction.UP, Reason.USER)
        assertEquals("Restored to 1920 px · 60 fps.", qualityChangeLine(context, restore, belowProfile = false))
        assertEquals("Cast quality restored.", qualityChangeNotification(context, restore))
    }
}
