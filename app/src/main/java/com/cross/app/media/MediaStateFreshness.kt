package com.cross.app.media

import android.media.session.PlaybackState
import kotlin.math.roundToLong

/** Pure media-session rules used to select usable sessions and calculate fresh positions. */
internal object MediaSessionSelection {
    private val terminalStates = setOf(
        PlaybackState.STATE_NONE,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_ERROR,
    )

    fun selectActiveIndex(playbackStates: List<Int?>): Int? = playbackStates
        .indexOfFirst { state -> state == null || state !in terminalStates }
        .takeIf { it >= 0 }
}

/** Calculates a playback position from the last session snapshot and elapsed realtime. */
internal fun projectedPlaybackPositionMs(
    state: Int?,
    positionMs: Long,
    lastPositionUpdateTimeMs: Long,
    playbackSpeed: Float,
    nowElapsedRealtimeMs: Long,
    durationMs: Long,
): Long {
    val projected = if (
        state == PlaybackState.STATE_PLAYING &&
        lastPositionUpdateTimeMs > 0L &&
        nowElapsedRealtimeMs >= lastPositionUpdateTimeMs
    ) {
        positionMs + ((nowElapsedRealtimeMs - lastPositionUpdateTimeMs) * playbackSpeed).roundToLong()
    } else {
        positionMs
    }
    return projected.coerceAtLeast(0L).let { value ->
        if (durationMs > 0L) value.coerceAtMost(durationMs) else value
    }
}
