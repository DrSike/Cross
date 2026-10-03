package com.cross.app.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the narrow metadata-based notification exclusions. */
class NotificationForwardingPolicyTest {
    @Test
    fun allowsOrdinaryNotificationFromOtherPackage() {
        assertTrue(NotificationForwardingPolicy.isForwardable(metadata()))
    }

    @Test
    fun excludesCrossNotifications() {
        assertFalse(NotificationForwardingPolicy.isForwardable(metadata(packageName = "com.cross.app")))
    }

    @Test
    fun excludesGroupSummariesMediaAndOngoingServices() {
        assertFalse(NotificationForwardingPolicy.isForwardable(metadata(isGroupSummary = true)))
        assertFalse(NotificationForwardingPolicy.isForwardable(metadata(isMediaTransport = true)))
        assertFalse(NotificationForwardingPolicy.isForwardable(metadata(isOngoingService = true)))
    }

    private fun metadata(
        packageName: String = "com.example.mail",
        isGroupSummary: Boolean = false,
        isMediaTransport: Boolean = false,
        isOngoingService: Boolean = false,
    ) = NotificationMetadata(
        packageName = packageName,
        ownPackageName = "com.cross.app",
        isGroupSummary = isGroupSummary,
        isMediaTransport = isMediaTransport,
        isOngoingService = isOngoingService,
    )
}
