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

            val canonical = when {
                !videoId.isNullOrBlank() -> Uri.Builder()
                    .scheme("https")
                    .authority("music.youtube.com")
                    .path("/watch")
                    .appendQueryParameter("v", videoId)
                    .apply {
                        if (!playlistId.isNullOrBlank()) {
                            appendQueryParameter("list", playlistId)
                        }
                    }
                    .build()

                !playlistId.isNullOrBlank() -> Uri.Builder()
                    .scheme("https")
                    .authority("music.youtube.com")
                    .path("/playlist")
                    .appendQueryParameter("list", playlistId)
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
    }
}
