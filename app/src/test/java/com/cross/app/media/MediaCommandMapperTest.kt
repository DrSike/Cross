package com.cross.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies that watch wire values retain their existing Android media meanings. */
class MediaCommandMapperTest {
    @Test
    fun mapsAllSupportedWireValues() {
        assertEquals(MediaCommand.PLAY, MediaCommandMapper.fromWireValue("play"))
        assertEquals(MediaCommand.PAUSE, MediaCommandMapper.fromWireValue("pause"))
        assertEquals(MediaCommand.PLAY_PAUSE, MediaCommandMapper.fromWireValue("toggle"))
        assertEquals(MediaCommand.PLAY_PAUSE, MediaCommandMapper.fromWireValue("play_pause"))
        assertEquals(MediaCommand.NEXT, MediaCommandMapper.fromWireValue("next"))
        assertEquals(MediaCommand.PREVIOUS, MediaCommandMapper.fromWireValue("previous"))
        assertEquals(MediaCommand.VOLUME_DOWN, MediaCommandMapper.fromWireValue("volume_down"))
        assertEquals(MediaCommand.VOLUME_UP, MediaCommandMapper.fromWireValue("volume_up"))
    }

    @Test
    fun rejectsUnknownWireValues() {
        assertNull(MediaCommandMapper.fromWireValue("fast_forward"))
    }
}
