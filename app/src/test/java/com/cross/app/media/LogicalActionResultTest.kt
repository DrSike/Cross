package com.cross.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicalActionResultTest {
    @Test
    fun successfulActionReportsLogicalSuccess() {
        assertTrue(logicalActionSucceeded(action = {}, onFailure = {}))
    }

    @Test
    fun failedActionReportsLogicalFailure() {
        var reportedFailure = false

        val succeeded = logicalActionSucceeded(
            action = { throw IllegalStateException("dispatch rejected") },
            onFailure = { reportedFailure = true },
        )

        assertFalse(succeeded)
        assertTrue(reportedFailure)
    }
}
