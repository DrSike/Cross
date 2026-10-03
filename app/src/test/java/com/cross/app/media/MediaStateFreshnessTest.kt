package com.cross.app.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies terminal-session selection and bounded playback-position projection. */
class MediaStateFreshnessTest {
    @Test
    fun skipsTerminalSessionsAndSelectsFirstUsableSession() {
        assertEquals(
            2,
            MediaSessionSelection.selectActiveIndex(
                listOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR, PlaybackState.STATE_PAUSED),
            ),
        )
    }

    @Test
    fun returnsNoSessionWhenAllSessionsAreTerminal() {
        assertEquals(
            null,
            MediaSessionSelection.selectActiveIndex(
                listOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR),
            ),
        )
    }

    @Test
    fun projectsPlayingPositionFromElapsedRealtimeAndClampsToDuration() {
        assertEquals(
            10_000L,
            projectedPlaybackPositionMs(
                state = PlaybackState.STATE_PLAYING,
                positionMs = 8_000L,
                lastPositionUpdateTimeMs = 1_000L,
                playbackSpeed = 1f,
                nowElapsedRealtimeMs = 3_000L,
                durationMs = 10_000L,
            ),
        )
    }

    @Test
    fun doesNotAdvancePausedPosition() {
        assertEquals(
            8_000L,
            projectedPlaybackPositionMs(
                state = PlaybackState.STATE_PAUSED,
                positionMs = 8_000L,
                lastPositionUpdateTimeMs = 1_000L,
                playbackSpeed = 1f,
                nowElapsedRealtimeMs = 3_000L,
                durationMs = 10_000L,
            ),
        )
    }
}
