package dev.mediaremote.media

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore

data class MediaSnapshot(
    val available: Boolean,
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val playbackState: Int = PlaybackState.STATE_NONE,
    val playbackSpeed: Float = 0f,
    val actions: Long = 0L,
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

    private data class SessionSignature(
        val mediaId: String,
        val title: String,
        val artist: String,
        val album: String,
        val state: Int,
        val queueIds: List<Long>,
        val queueTitles: List<String>,
    )

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
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val queue = controller.queue.orEmpty()
        val activeQueueId = playbackState?.activeQueueItemId ?: -1L
        var activeQueueIndex = queue.indexOfFirst { it.queueId == activeQueueId }

        // Some YouTube Music builds leave activeQueueItemId unknown while still publishing a queue.
        // In that case, use the metadata title / artist to identify the active queue entry.
        if (activeQueueIndex < 0 && title.isNotBlank()) {
            activeQueueIndex = queue.indexOfFirst { item ->
                val description = item.description
                val queueTitle = description.title?.toString().orEmpty()
                val queueArtist = description.subtitle?.toString().orEmpty()
                queueTitle == title && (artist.isBlank() || queueArtist.contains(artist, ignoreCase = true))
            }
        }
        val activeDescription = queue.getOrNull(activeQueueIndex)?.description

        // YouTube Music's public MediaSession surface is inconsistent between app versions. The id
        // can live in metadata, a queue description, controller extras, PlaybackState extras or even
        // a custom-action bundle. Scan the complete identity surface before falling back to the last
        // signature-bound id that we previously proved.
        val resolvedMediaId = resolveYouTubeVideoId(
            metadata = metadata,
            activeDescription = activeDescription,
            controllerExtras = controller.extras,
            playbackState = playbackState,
        )
        val mediaId = resolvedMediaId.ifBlank {
            YouTubeMediaIdentityStore.resolve(
                context = context,
                title = title,
                artist = artist,
                durationMs = durationMs,
            ).orEmpty()
        }
        if (YOUTUBE_VIDEO_ID.matches(mediaId)) {
            YouTubeMediaIdentityStore.rememberResolved(
                context = context,
                videoId = mediaId,
                title = title,
                artist = artist,
                durationMs = durationMs,
            )
        }

        return MediaSnapshot(
            available = true,
            mediaId = mediaId,
            title = title,
            artist = artist,
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playing = playbackState?.state == PlaybackState.STATE_PLAYING,
            playbackState = playbackState?.state ?: PlaybackState.STATE_NONE,
            playbackSpeed = playbackState?.playbackSpeed ?: 0f,
            actions = playbackState?.actions ?: 0L,
            positionMs = currentPositionMs(playbackState, durationMs),
            durationMs = durationMs,
            packageName = controller.packageName,
        )
    }

    /**
     * YouTube Music does not consistently expose the video id through METADATA_KEY_MEDIA_ID.
     * Resolve every plausible MediaSession representation into the canonical 11-character YouTube
     * video id. Opaque bundle keys are intentionally scanned too because YouTube Music has changed
     * its internal key names between releases.
     */
    private fun resolveYouTubeVideoId(
        metadata: MediaMetadata?,
        activeDescription: MediaDescription?,
        controllerExtras: Bundle?,
        playbackState: PlaybackState?,
    ): String {
        val candidates = mutableListOf<String>()

        fun add(value: String?) {
            value?.trim()?.takeIf { it.isNotBlank() }?.let(candidates::add)
        }

        add(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        add(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI))
        add(metadata?.description?.mediaId)
        add(metadata?.description?.mediaUri?.toString())
        add(activeDescription?.mediaId)
        add(activeDescription?.mediaUri?.toString())

        // Do not filter metadata by key name. Newer YouTube Music versions can expose the id under
        // obfuscated / generic keys while the value itself still contains a canonical id or URL.
        metadata?.keySet()?.forEach { key ->
            add(runCatching { metadata.getString(key) }.getOrNull())
        }

        addBundleValues(activeDescription?.extras, candidates)
        addBundleValues(controllerExtras, candidates)
        addBundleValues(playbackState?.extras, candidates)
        playbackState?.customActions.orEmpty().forEach { action ->
            addBundleValues(action.extras, candidates)
        }

        return candidates.firstNotNullOfOrNull(::extractYouTubeVideoId).orEmpty()
    }

    private fun addBundleValues(
        bundle: Bundle?,
        output: MutableList<String>,
        depth: Int = 0,
    ) {
        if (bundle == null || depth > MAX_BUNDLE_DEPTH) return
        bundle.keySet().forEach { key ->
            addBundleValue(runCatching { bundle.get(key) }.getOrNull(), output, depth)
        }
    }

    private fun addBundleValue(value: Any?, output: MutableList<String>, depth: Int) {
        when (value) {
            null -> Unit
            is String -> value.trim().takeIf { it.isNotBlank() }?.let(output::add)
            is CharSequence -> value.toString().trim().takeIf { it.isNotBlank() }?.let(output::add)
            is Uri -> output.add(value.toString())
            is Bundle -> addBundleValues(value, output, depth + 1)
            is Array<*> -> value.forEach { addBundleValue(it, output, depth + 1) }
            is Iterable<*> -> value.forEach { addBundleValue(it, output, depth + 1) }
        }
    }

    private fun extractYouTubeVideoId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        if (YOUTUBE_VIDEO_ID.matches(value)) return value

        val uri = runCatching { Uri.parse(value) }.getOrNull()
        val queryVideoId = runCatching { uri?.getQueryParameter("v") }.getOrNull()
            ?.takeIf(YOUTUBE_VIDEO_ID::matches)
        if (queryVideoId != null) return queryVideoId

        if (uri?.host.equals("youtu.be", ignoreCase = true)) {
            uri?.lastPathSegment
                ?.takeIf(YOUTUBE_VIDEO_ID::matches)
                ?.let { return it }
        }

        val tailCandidate = value
            .substringAfterLast(':')
            .substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('&')
            .trim()
        if (YOUTUBE_VIDEO_ID.matches(tailCandidate)) return tailCandidate

        return YOUTUBE_VIDEO_ID_HINT.find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(YOUTUBE_VIDEO_ID::matches)
    }

    /**
     * Android's PlaybackState.position is the position at lastPositionUpdateTime, not necessarily
     * the position at the instant we query it. Extrapolate while playing so remote UIs receive a
     * moving clock instead of repeatedly seeing the same stale base position.
     */
    private fun currentPositionMs(state: PlaybackState?, durationMs: Long): Long {
        if (state == null) return 0L

        var position = state.position.coerceAtLeast(0L)
        if (
            state.state == PlaybackState.STATE_PLAYING &&
            state.lastPositionUpdateTime > 0L &&
            state.playbackSpeed != 0f
        ) {
            val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
                .coerceAtLeast(0L)
            position += (elapsed * state.playbackSpeed).toLong()
        }

        return if (durationMs > 0L) {
            position.coerceIn(0L, durationMs)
        } else {
            position.coerceAtLeast(0L)
        }
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
                    RemoteMediaCommand.Stop -> controls.stop().let { true }
                    RemoteMediaCommand.Next -> controls.skipToNext().let { true }
                    RemoteMediaCommand.Previous -> controls.skipToPrevious().let { true }
                    is RemoteMediaCommand.SeekBy -> {
                        val current = currentPositionMs(
                            activeController.playbackState,
                            activeController.metadata
                                ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                                ?: 0L,
                        )
                        controls.seekTo(clampSeekTarget(activeController, current + command.deltaMs))
                        true
                    }
                    is RemoteMediaCommand.SeekTo -> {
                        controls.seekTo(clampSeekTarget(activeController, command.positionMs))
                        true
                    }
                    is RemoteMediaCommand.PlayFromSearch,
                    is RemoteMediaCommand.PlayFromUrl -> error("Handled above")
                }
            }
        }
    }

    private fun clampSeekTarget(controller: MediaController, targetMs: Long): Long {
        val duration = controller.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0L }
            ?: Long.MAX_VALUE
        return targetMs.coerceIn(0L, duration)
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
            val before = sessionSignature(controller)
            controller.transportControls.playFromSearch(clean, null)
            if (awaitSessionChange(controller, before)) return true
        }

        return launchYouTubeMusic(
            context,
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(TARGET_PACKAGE)
                putExtra(SearchManager.QUERY, clean)
            },
        )
    }

    private fun playFromUrl(
        context: Context,
        controller: MediaController?,
        rawUrl: String,
    ): Boolean {
        val link = YouTubeMusicLink.extract(rawUrl) ?: return false
        val playbackUri = link.playbackUri
        val requestedVideoId = playbackUri.getQueryParameter("v")
            ?.takeIf(YOUTUBE_VIDEO_ID::matches)

        if (controller != null && trySessionUriPlayback(controller, playbackUri)) {
            YouTubeMediaIdentityStore.rememberRequested(context, requestedVideoId)
            return true
        }

        // YouTube Music has historically advertised some media-session capabilities without
        // reliably acting on every URL. An explicit deep link is therefore the authoritative
        // fallback instead of treating a transportControls call as success just because it did
        // not throw.
        val launched = launchYouTubeMusic(
            context,
            Intent(Intent.ACTION_VIEW, playbackUri).apply {
                setPackage(TARGET_PACKAGE)
            },
        )
        if (launched) {
            YouTubeMediaIdentityStore.rememberRequested(context, requestedVideoId)
        }
        return launched
    }

    private fun trySessionUriPlayback(
        controller: MediaController,
        uri: Uri,
    ): Boolean {
        val controls = controller.transportControls
        val actions = controller.playbackState?.actions ?: 0L
        val before = sessionSignature(controller)

        if (actions and PlaybackState.ACTION_PLAY_FROM_URI != 0L) {
            runCatching { controls.playFromUri(uri, null) }
            if (awaitSessionChange(controller, before)) return true
        }

        if (actions and PlaybackState.ACTION_PREPARE_FROM_URI != 0L) {
            runCatching {
                controls.prepareFromUri(uri, null)
                Thread.sleep(180)
                controls.play()
            }
            if (awaitSessionChange(controller, before)) return true
        }

        return false
    }

    private fun awaitSessionChange(
        controller: MediaController,
        before: SessionSignature,
    ): Boolean {
        repeat(6) {
            Thread.sleep(140)
            if (sessionSignature(controller) != before) return true
        }
        return false
    }

    private fun sessionSignature(controller: MediaController): SessionSignature {
        val metadata = controller.metadata
        val queue = controller.queue.orEmpty()
        return SessionSignature(
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            state = controller.playbackState?.state ?: PlaybackState.STATE_NONE,
            queueIds = queue.map { it.queueId },
            queueTitles = queue.map { it.description.title?.toString().orEmpty() },
        )
    }

    private fun launchYouTubeMusic(
        context: Context,
        intent: Intent,
    ): Boolean = runCatching {
        context.startActivity(
            intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        true
    }.getOrDefault(false)

    private const val MAX_BUNDLE_DEPTH = 3
    private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val YOUTUBE_VIDEO_ID_HINT = Regex(
        "(?:[?&]v=|video(?:_|-)?id[=:/\\s]+)([A-Za-z0-9_-]{11})(?:[^A-Za-z0-9_-]|$)",
        RegexOption.IGNORE_CASE,
    )
}

sealed interface RemoteMediaCommand {
    data object Play : RemoteMediaCommand
    data object Pause : RemoteMediaCommand
    data object Stop : RemoteMediaCommand
    data object Next : RemoteMediaCommand
    data object Previous : RemoteMediaCommand
    data class SeekBy(val deltaMs: Long) : RemoteMediaCommand
    data class SeekTo(val positionMs: Long) : RemoteMediaCommand
    data class PlayFromSearch(val query: String) : RemoteMediaCommand
    data class PlayFromUrl(val url: String) : RemoteMediaCommand
}
