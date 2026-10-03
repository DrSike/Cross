package com.cross.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindPhoneAlertPolicyTest {
    @Test
    fun alertIsBoundedToThirtySeconds() {
        assertEquals(30_000L, FindPhoneAlertPolicy.AUTO_STOP_DELAY_MS)
    }

    @Test
    fun onlyRingtoneDispatchCountsAsStarted() {
        assertTrue(FindPhoneAlertPolicy.wasStarted(soundStarted = true))
        assertFalse(FindPhoneAlertPolicy.wasStarted(soundStarted = false))
    }
}
