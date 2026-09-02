package dev.mediaremote.media

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore

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

/**
 * Bridge for the official YouTube Music Android app only.
 *
 * MediaRemote deliberately does not try to be a generic media controller. Keeping the target package
 * explicit makes command routing and fallback behavior predictable.
 */
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
        val controller = controller(context)

        return when (command) {
            is RemoteMediaCommand.PlayFromSearch -> playFromSearch(context, controller, command.query)
            is RemoteMediaCommand.PlayFromUrl -> playFromUrl(context, controller, command.url)
            else -> {
                val activeController = controller ?: return false
                val controls = activeController.transportControls
                when (command) {
                    RemoteMediaCommand.Play -> controls.play().let { true }
                    RemoteMediaCommand.Pause -> controls.pause().let { true }
                    RemoteMediaCommand.Next -> controls.skipToNext().let { true }
                    RemoteMediaCommand.Previous -> controls.skipToPrevious().let { true }
                    is RemoteMediaCommand.SeekBy -> {
                        val current = activeController.playbackState?.position ?: 0L
                        val duration = activeController.metadata
                            ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                            ?.takeIf { it > 0L }
                            ?: Long.MAX_VALUE
                        val target = (current + command.deltaMs).coerceIn(0L, duration)
                        controls.seekTo(target)
                        true
                    }
                    is RemoteMediaCommand.PlayFromSearch,
                    is RemoteMediaCommand.PlayFromUrl -> error("Handled above")
                }
            }
        }
    }

    private fun playFromSearch(
        context: Context,
        controller: MediaController?,
        query: String,
    ): Boolean {
        val clean = query.trim()
        if (clean.isBlank()) return false

        val actions = controller?.playbackState?.actions ?: 0L
        if (controller != null && actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
            controller.transportControls.playFromSearch(clean, null)
            return true
        }

        return runCatching {
            context.startActivity(
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage(TARGET_PACKAGE)
                    putExtra(SearchManager.QUERY, clean)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)
    }

    private fun playFromUrl(
        context: Context,
        controller: MediaController?,
        rawUrl: String,
    ): Boolean {
        val link = YouTubeMusicLink.extract(rawUrl) ?: return false
        val actions = controller?.playbackState?.actions ?: 0L

        if (controller != null && actions and PlaybackState.ACTION_PLAY_FROM_URI != 0L) {
            controller.transportControls.playFromUri(link.uri, null)
            return true
        }

        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, link.uri).apply {
                    setPackage(TARGET_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
            true
        }.getOrDefault(false)
    }
}

sealed interface RemoteMediaCommand {
    data object Play : RemoteMediaCommand
    data object Pause : RemoteMediaCommand
    data object Next : RemoteMediaCommand
    data object Previous : RemoteMediaCommand
    data class SeekBy(val deltaMs: Long) : RemoteMediaCommand
    data class PlayFromSearch(val query: String) : RemoteMediaCommand
    data class PlayFromUrl(val url: String) : RemoteMediaCommand
}
