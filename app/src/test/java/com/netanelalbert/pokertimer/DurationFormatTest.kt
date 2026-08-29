package com.netanelalbert.pokertimer

import com.netanelalbert.pokertimer.timer.formatRemaining
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("20:00", formatRemaining(20 * 60 * 1000L))
        assertEquals("9:05", formatRemaining((9 * 60 + 5) * 1000L))
        assertEquals("0:01", formatRemaining(1_000L))
        assertEquals("0:00", formatRemaining(0L))
    }

    @Test
    fun `rounds up so a level shows its full length the moment it starts`() {
        // 19:59.3 left should read 20:00, not 19:59 — otherwise a 20 minute level appears to
        // start a second short.
        assertEquals("20:00", formatRemaining(19 * 60 * 1000L + 59_300L))
        assertEquals("1:00", formatRemaining(59_100L))
    }

    @Test
    fun `switches to hours only when needed`() {
        assertEquals("1:00:00", formatRemaining(60 * 60 * 1000L))
        assertEquals("2:05:09", formatRemaining((2 * 3600 + 5 * 60 + 9) * 1000L))
        assertEquals("59:59", formatRemaining(59 * 60 * 1000L + 59_000L))
    }

    @Test
    fun `never shows a negative countdown`() {
        assertEquals("0:00", formatRemaining(-5_000L))
    }
}
