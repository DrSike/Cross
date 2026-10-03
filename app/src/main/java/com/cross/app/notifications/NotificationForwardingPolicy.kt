package com.cross.app.notifications

/** Pure notification metadata policy for excluding Cross and unambiguous system noise. */
data class NotificationMetadata(
    val packageName: String,
    val ownPackageName: String,
    val isGroupSummary: Boolean,
    val isMediaTransport: Boolean,
    val isOngoingService: Boolean,
)

/** Decides whether notification metadata is safe to forward without changing app rules. */
object NotificationForwardingPolicy {
    fun isForwardable(metadata: NotificationMetadata): Boolean =
        metadata.packageName != metadata.ownPackageName &&
            !metadata.isGroupSummary &&
            !metadata.isMediaTransport &&
            !metadata.isOngoingService
}
