package dev.mediaremote.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

data class MediaSnapshot(
    val available: Boolean,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val packageName: String = "",
)

object MediaSessionBridge {
    const val TARGET_PACKAGE = "com.google.android.apps.youtube.music"

    private fun controller(context: Context): MediaController? {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaNotificationListener::class.java)

        return try {
            manager.getActiveSessions(listener)
                .firstOrNull { it.packageName == TARGET_PACKAGE }
        } catch (_: SecurityException) {
            null
        }
    }

    fun snapshot(context: Context): MediaSnapshot {
        val controller = controller(context) ?: return MediaSnapshot(available = false)
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        return MediaSnapshot(
            available = true,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playing = playbackState?.state == PlaybackState.STATE_PLAYING,
            positionMs = playbackState?.position ?: 0L,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            packageName = controller.packageName,
        )
    }

    fun execute(context: Context, command: RemoteMediaCommand): Boolean {
        val controller = controller(context) ?: return false
        val controls = controller.transportControls

        when (command) {
            RemoteMediaCommand.Play -> controls.play()
            RemoteMediaCommand.Pause -> controls.pause()
            RemoteMediaCommand.Next -> controls.skipToNext()
            RemoteMediaCommand.Previous -> controls.skipToPrevious()
            is RemoteMediaCommand.SeekBy -> {
                val current = controller.playbackState?.position ?: 0L
                val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: Long.MAX_VALUE
                val target = (current + command.deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
                controls.seekTo(target)
            }
        }
        return true
    }
}

sealed interface RemoteMediaCommand {
    data object Play : RemoteMediaCommand
    data object Pause : RemoteMediaCommand
    data object Next : RemoteMediaCommand
    data object Previous : RemoteMediaCommand
    data class SeekBy(val deltaMs: Long) : RemoteMediaCommand
}
