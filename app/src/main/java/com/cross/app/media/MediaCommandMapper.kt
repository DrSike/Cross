package com.cross.app.media

/** Maps stable watch media-command wire values to Android media operations. */
internal enum class MediaCommand {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    VOLUME_UP,
    VOLUME_DOWN,
}

/** Keeps protocol values separate from the Android transport-control implementation. */
internal object MediaCommandMapper {
    fun fromWireValue(value: String): MediaCommand? = when (value) {
        "play" -> MediaCommand.PLAY
        "pause" -> MediaCommand.PAUSE
        "toggle", "play_pause" -> MediaCommand.PLAY_PAUSE
        "next" -> MediaCommand.NEXT
        "previous" -> MediaCommand.PREVIOUS
        "volume_up" -> MediaCommand.VOLUME_UP
        "volume_down" -> MediaCommand.VOLUME_DOWN
        else -> null
    }
}
