package com.cross.app.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.cross.app.ble.MessageType
import com.cross.app.ble.ProtocolMessage
import com.cross.app.notifications.CrossNotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

/** Reads the active phone media session and executes watch commands through its controls. */
data class MediaState(
    val title: String = "Nothing playing",
    val artist: String = "",
    val album: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val packageName: String? = null,
)

class MediaManager(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state.asStateFlow()
    var onStateChanged: ((MediaState) -> Unit)? = null
    private var controller: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private var destroyedSessionToken: MediaSession.Token? = null

    init {
        runCatching {
            manager?.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                ComponentName(context, CrossNotificationListenerService::class.java),
            )
        }.onFailure { Log.w(TAG, "Could not observe active media sessions", it) }
    }

    fun refresh() {
        val sessions = runCatching {
            manager?.getActiveSessions(ComponentName(context, CrossNotificationListenerService::class.java))
        }.onFailure {
            Log.w(TAG, "Could not read active media sessions", it)
        }.getOrNull().orEmpty()
        updateController(sessions)
    }

    /** Refreshes and clears a media session when its media notification disappears. */
    fun onNotificationRemoved(packageName: String) {
        if (controller?.packageName != packageName) return
        refresh()
        if (controller?.packageName == packageName) {
            Log.d(TAG, "Clearing media session after notification removal package=$packageName")
            clearController()
        }
    }

    fun handle(message: ProtocolMessage): Boolean {
        if (message.type != MessageType.MEDIA_COMMAND) return false
        val wireCommand = try {
            JSONObject(message.payloadJson).optString("command")
        } catch (error: JSONException) {
            Log.w(TAG, "Could not parse media command payload: ${error.javaClass.simpleName}")
            return false
        }
        val command = MediaCommandMapper.fromWireValue(wireCommand)
        if (command == null) {
            Log.w(TAG, "Ignoring unsupported media command")
            return false
        }

        refresh()
        val succeeded = when (command) {
            MediaCommand.PLAY -> executeTransportCommand("play") { it.play() }
            MediaCommand.PAUSE -> executeTransportCommand("pause") { it.pause() }
            MediaCommand.PLAY_PAUSE -> executePlayPause()
            MediaCommand.NEXT -> executeSkipCommand("next", PlaybackState.ACTION_SKIP_TO_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }
            MediaCommand.PREVIOUS -> executeSkipCommand("previous", PlaybackState.ACTION_SKIP_TO_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }
            MediaCommand.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            MediaCommand.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
        }
        refresh()
        if (succeeded) Log.d(TAG, "Handled media command command=$command")
        return succeeded
    }

    /** Adjusts the active media session volume, falling back to the phone music stream. */
    fun adjustVolume(direction: Int): Boolean {
        val activeController = controller
        return if (activeController != null) {
            Log.d(TAG, "Adjusting active media session volume direction=$direction package=${activeController.packageName}")
            logicalActionSucceeded(
                action = { activeController.adjustVolume(direction, AudioManager.FLAG_SHOW_UI) },
                onFailure = { Log.w(TAG, "Media volume command failed: ${it.javaClass.simpleName}") },
            )
        } else if (audioManager != null) {
            // Fallback: volume remains actionable without an active media session because Android's
            // music-stream control is a user-visible, system-supported execution path.
            Log.d(TAG, "Adjusting phone music-stream volume direction=$direction")
            logicalActionSucceeded(
                action = { audioManager.adjustSuggestedStreamVolume(direction, AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI) },
                onFailure = { Log.w(TAG, "Phone volume command failed: ${it.javaClass.simpleName}") },
            )
        } else {
            Log.w(TAG, "Unable to adjust volume because no audio service is available")
            false
        }
    }

    private fun executeTransportCommand(name: String, action: (MediaController.TransportControls) -> Unit): Boolean {
        val activeController = controller
        if (activeController == null) {
            Log.w(TAG, "Unable to execute media command=$name because no active session is available")
            return false
        }
        Log.d(TAG, "Executing media command=$name package=${activeController.packageName}")
        return logicalActionSucceeded(
            action = { action(activeController.transportControls) },
            onFailure = { Log.w(TAG, "Media command failed command=$name: ${it.javaClass.simpleName}") },
        )
    }

    private fun executePlayPause(): Boolean {
        val activeController = controller
        if (activeController == null) {
            Log.w(TAG, "Unable to execute media command=play_pause because no active session is available")
            return false
        }
        val isPlaying = activeController.playbackState?.state == PlaybackState.STATE_PLAYING
        val action = if (isPlaying) "pause" else "play"
        Log.d(TAG, "Executing media command=play_pause action=$action package=${activeController.packageName}")
        return logicalActionSucceeded(
            action = {
                if (isPlaying) activeController.transportControls.pause() else activeController.transportControls.play()
            },
            onFailure = { Log.w(TAG, "Media command failed command=play_pause: ${it.javaClass.simpleName}") },
        )
    }

    private fun executeSkipCommand(
        name: String,
        actionFlag: Long,
        fallbackKeyCode: Int,
        action: (MediaController.TransportControls) -> Unit,
    ): Boolean {
        val activeController = controller
        if (activeController == null) {
            Log.w(TAG, "Unable to execute media command=$name because no active session is available")
            return false
        }
        val actions = activeController.playbackState?.actions ?: 0L
        if (actions and actionFlag == 0L) {
            // Fallback: some controllers omit skip capability flags but still accept the standard
            // Android media key, which is the platform route for this exact action.
            Log.w(TAG, "Active media session does not advertise command=$name; dispatching media-key fallback package=${activeController.packageName}")
            return dispatchMediaKey(fallbackKeyCode)
        }
        Log.d(TAG, "Executing media command=$name package=${activeController.packageName}")
        return logicalActionSucceeded(
            action = { action(activeController.transportControls) },
            onFailure = { Log.w(TAG, "Media command failed command=$name: ${it.javaClass.simpleName}") },
        )
    }

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val activeAudioManager = audioManager
        if (activeAudioManager == null) {
            Log.w(TAG, "Unable to dispatch media-key fallback keyCode=$keyCode because no audio service is available")
            return false
        }
        return logicalActionSucceeded(
            action = {
                activeAudioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                activeAudioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            },
            onFailure = { Log.w(TAG, "Media-key fallback failed keyCode=$keyCode: ${it.javaClass.simpleName}") },
        )
    }

    private fun onActiveSessionsChanged(sessions: List<MediaController>?) {
        updateController(sessions.orEmpty())
    }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener(::onActiveSessionsChanged)

    private fun callbackFor(target: MediaController) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state in TERMINAL_STATES && controller?.sessionToken == target.sessionToken) {
                Log.i(TAG, "Active media session stopped package=${target.packageName}")
                clearController()
                refresh()
            } else if (controller?.sessionToken == target.sessionToken) {
                publish(target, playback = state)
            }
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            if (controller?.sessionToken == target.sessionToken) publish(target, metadata = metadata)
        }

        override fun onSessionDestroyed() {
            if (controller?.sessionToken != target.sessionToken) return
            Log.i(TAG, "Active media session destroyed package=${target.packageName}")
            destroyedSessionToken = target.sessionToken
            clearController()
            refresh()
        }
    }

    private fun publish(
        value: MediaController?,
        metadata: android.media.MediaMetadata? = value?.metadata,
        playback: PlaybackState? = value?.playbackState,
    ) {
        val next = buildState(value, metadata, playback)
        if (_state.value == next) return
        _state.value = next
        onStateChanged?.invoke(next)
    }

    private fun buildState(
        value: MediaController?,
        metadata: android.media.MediaMetadata?,
        playback: PlaybackState?,
    ): MediaState {
        if (value == null) return MediaState()
        val durationMs = (metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        return MediaState(
            title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing",
            artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            positionMs = projectedPlaybackPositionMs(
                state = playback?.state,
                positionMs = playback?.position ?: 0L,
                lastPositionUpdateTimeMs = playback?.lastPositionUpdateTime ?: 0L,
                playbackSpeed = playback?.playbackSpeed ?: 0f,
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                durationMs = durationMs,
            ),
            durationMs = durationMs,
            packageName = value.packageName,
        )
    }

    private fun updateController(sessions: List<MediaController>) {
        if (destroyedSessionToken != null && sessions.none { it.sessionToken == destroyedSessionToken }) {
            destroyedSessionToken = null
        }
        val nextIndex = MediaSessionSelection.selectActiveIndex(
            sessions.map { session ->
                if (session.sessionToken == destroyedSessionToken) PlaybackState.STATE_STOPPED
                else session.playbackState?.state
            },
        )
        val next = nextIndex?.let(sessions::get)
        if (controller?.sessionToken != next?.sessionToken) {
            detachController()
            controller = next
            if (next != null) {
                val callback = callbackFor(next)
                controllerCallback = callback
                runCatching { next.registerCallback(callback) }
                    .onFailure { Log.w(TAG, "Could not register media session callback package=${next.packageName}", it) }
            }
        }
        if (controller == null) {
            publish(null)
        } else {
            publish(controller)
        }
    }

    private fun detachController() {
        val oldController = controller
        val oldCallback = controllerCallback
        if (oldController != null && oldCallback != null) {
            runCatching { oldController.unregisterCallback(oldCallback) }
                .onFailure { Log.w(TAG, "Could not unregister media session callback package=${oldController.packageName}", it) }
        }
        controllerCallback = null
        controller = null
    }

    private fun clearController() {
        detachController()
        publish(null)
    }

    private companion object {
        const val TAG = "MediaManager"
        val TERMINAL_STATES = setOf(
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR,
        )
    }
}

internal inline fun logicalActionSucceeded(
    action: () -> Unit,
    onFailure: (RuntimeException) -> Unit,
): Boolean = try {
    action()
    true
} catch (error: RuntimeException) {
    onFailure(error)
    false
}
