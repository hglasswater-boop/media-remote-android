package dev.mediaremote.media

import android.content.Context

/**
 * Persists the last YouTube video identity that MediaRemote could prove.
 *
 * YouTube Music exposes title / artist / duration reliably through MediaSession, but the 11-character
 * YouTube video id is not guaranteed to be present after the remote receiver process is restarted.
 * Keeping a signature-bound identity lets a later snapshot restore that id without ever attaching a
 * stale id to a different song.
 */
internal object YouTubeMediaIdentityStore {
    private const val PREFS = "youtube_media_identity"
    private const val KEY_VIDEO_ID = "video_id"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_DURATION_MS = "duration_ms"
    private const val KEY_PENDING_VIDEO_ID = "pending_video_id"
    private const val KEY_PENDING_AT_MS = "pending_at_ms"
    private const val PENDING_TTL_MS = 30_000L
    private const val DURATION_TOLERANCE_MS = 2_000L

    fun rememberRequested(context: Context, videoId: String?) {
        if (videoId.isNullOrBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_VIDEO_ID, videoId)
            .putLong(KEY_PENDING_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun rememberResolved(
        context: Context,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
    ) {
        if (videoId.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIDEO_ID, videoId)
            .putString(KEY_TITLE, normalize(title))
            .putString(KEY_ARTIST, normalize(artist))
            .putLong(KEY_DURATION_MS, durationMs.coerceAtLeast(0L))
            .remove(KEY_PENDING_VIDEO_ID)
            .remove(KEY_PENDING_AT_MS)
            .apply()
    }

    fun resolve(
        context: Context,
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedVideoId = prefs.getString(KEY_VIDEO_ID, null)
        if (!storedVideoId.isNullOrBlank()) {
            val storedTitle = prefs.getString(KEY_TITLE, "").orEmpty()
            val storedArtist = prefs.getString(KEY_ARTIST, "").orEmpty()
            val storedDuration = prefs.getLong(KEY_DURATION_MS, 0L)
            if (
                storedTitle == normalize(title) &&
                storedArtist == normalize(artist) &&
                durationMatches(storedDuration, durationMs)
            ) {
                return storedVideoId
            }
        }

        val pendingVideoId = prefs.getString(KEY_PENDING_VIDEO_ID, null)
        val pendingAt = prefs.getLong(KEY_PENDING_AT_MS, 0L)
        if (
            !pendingVideoId.isNullOrBlank() &&
            title.isNotBlank() &&
            pendingAt > 0L &&
            System.currentTimeMillis() - pendingAt in 0..PENDING_TTL_MS
        ) {
            return pendingVideoId
        }

        return null
    }

    private fun durationMatches(stored: Long, current: Long): Boolean {
        if (stored <= 0L || current <= 0L) return true
        return kotlin.math.abs(stored - current) <= DURATION_TOLERANCE_MS
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}
