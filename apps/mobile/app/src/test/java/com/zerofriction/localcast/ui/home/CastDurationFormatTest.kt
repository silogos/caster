package com.zerofriction.localcast.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home hero's live-elapsed format (designs/mobile-app.html): mm:ss
 * under an hour, h:mm:ss above — zero-padded, tabular digits on screen.
 */
class CastDurationFormatTest {

    @Test fun zeroIsZeroedMinutesAndSeconds() {
        assertEquals("00:00", formatCastDuration(0))
    }

    @Test fun secondsPadToTwoDigits() {
        assertEquals("00:09", formatCastDuration(9))
    }

    @Test fun minutesAndSecondsFormat() {
        assertEquals("01:05", formatCastDuration(65))
    }

    @Test fun justUnderAnHourStaysMinuteFormat() {
        assertEquals("59:59", formatCastDuration(59 * 60 + 59))
    }

    @Test fun anHourSwitchesToHourFormatWithPaddedMinutesAndSeconds() {
        assertEquals("1:00:00", formatCastDuration(3600))
        assertEquals("1:02:05", formatCastDuration(3725))
    }

    @Test fun longSessionsKeepHourFormat() {
        assertEquals("12:34:56", formatCastDuration(12 * 3600 + 34 * 60 + 56))
    }

    @Test fun negativeInputClampsToZero() {
        assertEquals("00:00", formatCastDuration(-5))
    }
}
