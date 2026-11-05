package com.k4music.android.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileDataRestTest {
    private val second = 1_000_000L

    @Test
    fun `on mobile data reading pauses at two minutes and starts again under one`() {
        val rest = MobileDataRest()
        assertEquals(false, rest.resting(metered = true, bufferedUs = 90 * second))
        assertEquals(true, rest.resting(metered = true, bufferedUs = 120 * second))
        assertEquals(true, rest.resting(metered = true, bufferedUs = 90 * second)) // still resting between the two
        assertEquals(false, rest.resting(metered = true, bufferedUs = 59 * second))
    }

    @Test
    fun `on wifi it never rests`() {
        val rest = MobileDataRest()
        assertEquals(true, rest.resting(metered = true, bufferedUs = 200 * second))
        assertEquals(false, rest.resting(metered = false, bufferedUs = 200 * second))
    }
}
