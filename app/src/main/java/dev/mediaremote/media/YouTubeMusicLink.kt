package dev.mediaremote.media

import android.net.Uri

enum class YouTubeMusicContentType {
    Song,
    Playlist,
    AlbumOrMix,
    Unknown,
}

data class YouTubeMusicLink(
    val uri: Uri,
    val type: YouTubeMusicContentType,
) {
    val url: String get() = uri.toString()

    /**
     * URI used when asking YouTube Music to start playback.
     *
     * A shared playlist URL points at /playlist, which is a library/details screen. For remote
     * playback we intentionally use the watch endpoint with the same list id so YouTube Music can
     * build the playlist watch queue instead of merely opening the playlist page.
     */
    val playbackUri: Uri
        get() {
            if (type != YouTubeMusicContentType.Playlist && type != YouTubeMusicContentType.AlbumOrMix) {
                return uri
            }
            val playlistId = uri.getQueryParameter("list")?.takeIf { it.isNotBlank() } ?: return uri
            if (uri.path == "/watch") return uri
            val playlistIndex = uri.getQueryParameter("index")
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }
            return Uri.Builder()
                .scheme("https")
                .authority("music.youtube.com")
                .path("/watch")
                .appendQueryParameter("list", playlistId)
                .apply {
                    playlistIndex?.let { appendQueryParameter("index", it.toString()) }
                    appendContextParameters(uri)
                }
                .build()
        }

    companion object {
        private val urlPattern = Regex(
            "https?://(?:(?:music\\.)?youtube\\.com|(?:www\\.)?youtube\\.com|youtu\\.be)/[^\\s]+",
            RegexOption.IGNORE_CASE,
        )

        fun extract(text: String): YouTubeMusicLink? {
            val candidate = urlPattern.find(text)?.value ?: text.trim()
            return parse(candidate)
        }

        fun parse(raw: String): YouTubeMusicLink? {
            val source = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
            val host = source.host?.lowercase().orEmpty()
            if (host !in setOf("music.youtube.com", "youtube.com", "www.youtube.com", "youtu.be")) {
                return null
            }

            val shortVideoId = if (host == "youtu.be") {
                source.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val videoId = shortVideoId ?: source.getQueryParameter("v")
            val playlistId = source.getQueryParameter("list")
            val playlistIndex = source.getQueryParameter("index")
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }

            val canonical = when {
                !videoId.isNullOrBlank() -> Uri.Builder()
                    .scheme("https")
                    .authority("music.youtube.com")
                    .path("/watch")
                    .appendQueryParameter("v", videoId)
                    .apply {
                        if (!playlistId.isNullOrBlank()) {
                            appendQueryParameter("list", playlistId)
                            playlistIndex?.let { appendQueryParameter("index", it.toString()) }
                        }
                        appendContextParameters(source)
                    }
                    .build()

                !playlistId.isNullOrBlank() -> Uri.Builder()
                    .scheme("https")
                    .authority("music.youtube.com")
                    .path("/playlist")
                    .appendQueryParameter("list", playlistId)
                    .apply {
                        playlistIndex?.let { appendQueryParameter("index", it.toString()) }
                        appendContextParameters(source)
                    }
                    .build()

                host == "music.youtube.com" -> source

                else -> source.buildUpon()
                    .scheme("https")
                    .authority("music.youtube.com")
                    .build()
            }

            val type = when {
                !videoId.isNullOrBlank() -> YouTubeMusicContentType.Song
                !playlistId.isNullOrBlank() && playlistId.startsWith("OLAK5uy_", ignoreCase = true) ->
                    YouTubeMusicContentType.AlbumOrMix
                !playlistId.isNullOrBlank() -> YouTubeMusicContentType.Playlist
                else -> YouTubeMusicContentType.Unknown
            }

            return YouTubeMusicLink(canonical, type)
        }

        private fun Uri.Builder.appendContextParameters(source: Uri) {
            source.getQueryParameter("ctt")
                ?.takeIf { it.isNotBlank() }
                ?.let { appendQueryParameter("ctt", it) }
            source.getQueryParameter("params")
                ?.takeIf { it.isNotBlank() }
                ?.let { appendQueryParameter("params", it) }
        }
    }
}
