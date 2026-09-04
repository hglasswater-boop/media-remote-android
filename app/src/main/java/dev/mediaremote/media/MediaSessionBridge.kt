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
import android.util.Log

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
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
)

object MediaSessionBridge {
    const val TARGET_PACKAGE = "com.google.android.apps.youtube.music"

    private data class ControllerSource(
        val controller: MediaController,
        val notification: YouTubeMusicNotificationSession? = null,
    )

    private data class SessionSignature(
        val mediaId: String,
        val title: String,
        val artist: String,
        val album: String,
        val state: Int,
        val queueIds: List<Long>,
        val queueTitles: List<String>,
    )

    private fun controllerSource(context: Context): ControllerSource? {
        MediaNotificationListener.currentYouTubeMusicSession(context)?.let { (controller, notification) ->
            return ControllerSource(controller, notification)
        }

        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaNotificationListener::class.java)
        val sessions = try {
            manager.getActiveSessions(listener).filter { it.packageName == TARGET_PACKAGE }
        } catch (_: SecurityException) {
            emptyList()
        }

        return sessions.maxWithOrNull(
            compareBy<MediaController> { sessionRank(it.playbackState?.state ?: PlaybackState.STATE_NONE) }
                .thenBy { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().isNotBlank() }
                .thenBy { it.playbackState?.lastPositionUpdateTime ?: 0L },
        )?.let(::ControllerSource)
    }

    private fun controller(context: Context): MediaController? = controllerSource(context)?.controller

    private fun sessionRank(state: Int): Int = when (state) {
        PlaybackState.STATE_PLAYING -> 5
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 4
        PlaybackState.STATE_PAUSED -> 3
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> 2
        else -> 1
    }

    fun snapshot(context: Context): MediaSnapshot {
        val source = controllerSource(context) ?: return MediaSnapshot(available = false)
        val controller = source.controller
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val fallbackTitle = source.notification?.title?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val fallbackArtist = source.notification?.artist?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

        val queue = controller.queue.orEmpty()
        val activeQueueId = playbackState?.activeQueueItemId ?: -1L
        val stateQueueIndex = queue.indexOfFirst { it.queueId == activeQueueId }
        var activeQueueIndex = stateQueueIndex

        if (activeQueueIndex < 0 && fallbackTitle.isNotBlank()) {
            activeQueueIndex = queue.indexOfFirst { item ->
                queueItemMatches(item.description, fallbackTitle, fallbackArtist)
            }
        }
        if (activeQueueIndex < 0 && queue.size == 1) activeQueueIndex = 0
        val activeDescription = queue.getOrNull(activeQueueIndex)?.description

        // PlaybackState.activeQueueItemId points at the actual queue item even on YouTube Music
        // builds where the top-level MediaMetadata remains frozen on the previous song. Prefer the
        // active queue description in that case, both for visible metadata and video identity.
        val queueIdentityIsAuthoritative = stateQueueIndex >= 0
        val title = if (queueIdentityIsAuthoritative) {
            activeDescription?.title?.toString()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        } else {
            fallbackTitle
        }
        val artist = if (queueIdentityIsAuthoritative) {
            activeDescription?.subtitle?.toString()?.takeIf { it.isNotBlank() } ?: fallbackArtist
        } else {
            fallbackArtist
        }

        val candidateMediaId = resolveYouTubeVideoId(
            metadata = metadata,
            activeDescription = activeDescription,
            controllerExtras = controller.extras,
            playbackState = playbackState,
        )
        val resolvedMediaId = candidateMediaId.takeIf {
            it.isNotBlank() && YouTubeMediaIdentityStore.acceptsCandidate(
                context = context,
                videoId = it,
                title = title,
                artist = artist,
                durationMs = durationMs,
            )
        }.orEmpty()
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
            queueIndex = activeQueueIndex,
            queueSize = queue.size,
        )
    }

    private fun queueItemMatches(description: MediaDescription, title: String, artist: String): Boolean {
        val queueTitle = description.title?.toString().orEmpty()
        val queueArtist = description.subtitle?.toString().orEmpty()
        if (normalizeMediaText(queueTitle) != normalizeMediaText(title)) return false
        if (artist.isBlank()) return true

        val wantedArtist = normalizeMediaText(artist)
        val candidateArtist = normalizeMediaText(queueArtist)
        return candidateArtist == wantedArtist ||
            candidateArtist.contains(wantedArtist) ||
            wantedArtist.contains(candidateArtist)
    }

    private fun normalizeMediaText(value: String): String = value
        .lowercase()
        .replace(MEDIA_TEXT_NOISE, "")

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

        // activeDescription belongs to PlaybackState.activeQueueItemId, so it outranks top-level
        // metadata that YouTube Music is known to leave stale while playback continues.
        add(activeDescription?.mediaId)
        add(activeDescription?.mediaUri?.toString())
        addBundleValues(activeDescription?.extras, candidates)

        add(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        add(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI))
        add(metadata?.description?.mediaId)
        add(metadata?.description?.mediaUri?.toString())
        addBundleValues(metadata?.description?.extras, candidates)

        addBundleValues(controllerExtras, candidates)
        addBundleValues(playbackState?.extras, candidates)
        playbackState?.customActions.orEmpty().forEach { action ->
            addBundleValues(action.extras, candidates)
        }

        return candidates.firstNotNullOfOrNull(::extractYouTubeVideoId).orEmpty()
    }

    private fun addBundleValues(bundle: Bundle?, output: MutableList<String>, depth: Int = 0) {
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
            uri?.lastPathSegment?.takeIf(YOUTUBE_VIDEO_ID::matches)?.let { return it }
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

    private fun currentPositionMs(state: PlaybackState?, durationMs: Long): Long {
        if (state == null) return 0L
        var position = state.position.coerceAtLeast(0L)
        if (
            state.state == PlaybackState.STATE_PLAYING &&
            state.lastPositionUpdateTime > 0L &&
            state.playbackSpeed != 0f
        ) {
            val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
            position += (elapsed * state.playbackSpeed).toLong()
        }
        return if (durationMs > 0L) position.coerceIn(0L, durationMs) else position.coerceAtLeast(0L)
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
                            activeController.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
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

    private fun playFromSearch(context: Context, controller: MediaController?, query: String): Boolean {
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

    private fun playFromUrl(context: Context, controller: MediaController?, rawUrl: String): Boolean {
        val link = YouTubeMusicLink.extract(rawUrl) ?: return false
        val playbackUri = link.playbackUri
        val playlistId = playbackUri.getQueryParameter("list")?.takeIf { it.isNotBlank() }
        val isLoungeQueue = playlistId?.startsWith("RQ", ignoreCase = true) == true
        if (isLoungeQueue) return playLoungeQueue(context, controller, playbackUri)

        // Lounge delivers this command while MediaRemote is normally in the background. Android can
        // reject a background startActivity, so always try the MediaSession transport command first.
        // Ordinary playlist links retain their existing URI transport. RQ queues must not use it.
        if (controller != null && trySessionUriPlayback(controller, playbackUri)) {
            Log.i(
                TAG,
                "PlayFromUrl handled by MediaSession videoId=${playbackUri.getQueryParameter("v")} " +
                    "listId=${playlistId ?: "<none>"} scheme=${playbackUri.scheme}",
            )
            return true
        }

        val launched = launchYouTubeMusic(
            context,
            Intent(Intent.ACTION_VIEW, playbackUri).apply { setPackage(TARGET_PACKAGE) },
        )
        Log.i(
            TAG,
            "PlayFromUrl deep-link fallback launched=$launched " +
                "videoId=${playbackUri.getQueryParameter("v")} listId=${playlistId ?: "<none>"} " +
                "scheme=${playbackUri.scheme}",
        )
        return launched
    }

    @Suppress("DEPRECATION")
    private fun playLoungeQueue(context: Context, controller: MediaController?, uri: Uri): Boolean {
        val version = runCatching {
            context.packageManager.getPackageInfo(TARGET_PACKAGE, 0).versionName
        }.getOrNull()
        val actions = controller?.playbackState?.actions ?: 0L
        if (version != YouTubeMusicQueueMediaId.VERIFIED_VERSION || controller == null ||
            actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID == 0L
        ) {
            Log.w(TAG, "RQ handoff unavailable: ytmVersion=$version controller=${controller != null} " +
                "playFromMediaId=${actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID != 0L}; no URI fallback")
            return false
        }
        val videoId = uri.getQueryParameter("v").orEmpty()
        val listId = uri.getQueryParameter("list").orEmpty()
        val rawIndex = uri.getQueryParameter("index")
        val index = rawIndex?.toIntOrNull()
        if (rawIndex != null && index == null) return false
        val mediaId = YouTubeMusicQueueMediaId.encode(videoId, listId, index) ?: return false
        // A binder dispatch is not proof of playback or queue acceptance. The Lounge selection
        // guard and subsequent MediaSession/nowPlaying logs supply that evidence asynchronously.
        return runCatching {
            controller.transportControls.playFromMediaId(mediaId, null)
            Log.i(TAG, "RQ playFromMediaId dispatched videoId=$videoId listId=$listId " +
                "currentIndex=${index ?: "<absent>"} ytmVersion=$version " +
                "cttPresent=${!uri.getQueryParameter("ctt").isNullOrBlank()} " +
                "paramsPresent=${!uri.getQueryParameter("params").isNullOrBlank()} " +
                "opaqueContextForwarded=false acceptance=unverified")
            true
        }.getOrElse {
            // Exception messages can include the encoded request. Do not log credentials/URLs.
            Log.w(TAG, "RQ playFromMediaId failed type=${it.javaClass.simpleName}; no URI fallback")
            false
        }
    }

    private fun trySessionUriPlayback(controller: MediaController, uri: Uri): Boolean {
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

    private fun awaitSessionChange(controller: MediaController, before: SessionSignature): Boolean {
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

    private fun launchYouTubeMusic(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(
            intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) },
        )
        true
    }.getOrDefault(false)

    private const val TAG = "MediaSessionBridge"
    private const val MAX_BUNDLE_DEPTH = 3
    private val MEDIA_TEXT_NOISE = Regex("[^\\p{L}\\p{N}]+")
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
