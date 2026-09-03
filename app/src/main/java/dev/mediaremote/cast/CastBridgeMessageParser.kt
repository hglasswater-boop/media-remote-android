package dev.mediaremote.cast

import android.net.Uri
import dev.mediaremote.media.YouTubeMusicLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts Cast V2 / YouTube MDX JSON payloads into the small command vocabulary already used by
 * YT Music Remote. The GmsCore shim intentionally stays dumb and forwards raw payloads here so
 * YouTube-specific compatibility fixes can ship with the normal app update path.
 */
object CastBridgeMessageParser {
    data class Action(
        val command: String,
        val value: Long = 0L,
        val text: String = "",
    )

    fun parse(namespace: String, rawPayload: String): List<Action> {
        val payload = runCatching { JSONObject(rawPayload) }.getOrNull() ?: return emptyList()
        val type = payload.optString("type").trim()

        return when {
            namespace.endsWith("com.google.cast.media") -> parseMedia(type, payload)
            namespace.endsWith("com.google.youtube.mdx") -> parseYouTubeMdx(type, payload)
            else -> emptyList()
        }
    }

    private fun parseMedia(type: String, payload: JSONObject): List<Action> = when (type.uppercase()) {
        "LOAD" -> {
            val contentId = findString(payload, setOf("contentId", "contentUrl", "url"))
            val link = contentId?.let(YouTubeMusicLink::extract)
            if (link != null) listOf(Action("playUrl", text = link.playbackUri.toString())) else emptyList()
        }

        "PLAY" -> listOf(Action("play"))
        "PAUSE" -> listOf(Action("pause"))
        "STOP" -> listOf(Action("stop"))
        "SEEK" -> {
            val seconds = findNumber(payload, setOf("currentTime", "position"))
            if (seconds != null) listOf(Action("seekTo", value = (seconds * 1000.0).toLong())) else emptyList()
        }

        else -> emptyList()
    }

    private fun parseYouTubeMdx(type: String, payload: JSONObject): List<Action> {
        val normalized = type.lowercase()
        val videoId = findString(payload, setOf("videoId", "video_id"))
        val playlistId = findString(payload, setOf("playlistId", "listId", "list_id"))

        if (normalized in setOf("setplaylist", "playvideo", "addvideo", "playlistchanged")) {
            buildYouTubeMusicUrl(videoId, playlistId)?.let {
                return listOf(Action("playUrl", text = it))
            }
        }

        return when (normalized) {
            "play", "resumevideo", "resume" -> listOf(Action("play"))
            "pause", "pausevideo" -> listOf(Action("pause"))
            "stop", "stopvideo" -> listOf(Action("stop"))
            "queuenext", "next", "nextvideo" -> listOf(Action("next"))
            "queueprevious", "previous", "previousvideo" -> listOf(Action("previous"))
            "seekto", "seek" -> {
                val seconds = findNumber(payload, setOf("currentTime", "position", "time"))
                if (seconds != null) listOf(Action("seekTo", value = (seconds * 1000.0).toLong())) else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun buildYouTubeMusicUrl(videoId: String?, playlistId: String?): String? {
        if (videoId.isNullOrBlank() && playlistId.isNullOrBlank()) return null

        val builder = Uri.Builder()
            .scheme("https")
            .authority("music.youtube.com")

        if (!videoId.isNullOrBlank()) {
            builder.path("/watch")
                .appendQueryParameter("v", videoId)
            if (!playlistId.isNullOrBlank()) {
                builder.appendQueryParameter("list", playlistId)
            }
        } else {
            builder.path("/playlist")
                .appendQueryParameter("list", playlistId)
        }

        return builder.build().toString()
    }

    private fun findString(node: Any?, keys: Set<String>): String? = when (node) {
        is JSONObject -> {
            for (key in keys) {
                node.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            }
            val names = node.keys()
            while (names.hasNext()) {
                findString(node.opt(names.next()), keys)?.let { return it }
            }
            null
        }

        is JSONArray -> {
            for (index in 0 until node.length()) {
                findString(node.opt(index), keys)?.let { return it }
            }
            null
        }

        else -> null
    }

    private fun findNumber(node: Any?, keys: Set<String>): Double? = when (node) {
        is JSONObject -> {
            for (key in keys) {
                if (node.has(key)) {
                    node.opt(key)?.let { value ->
                        when (value) {
                            is Number -> return value.toDouble()
                            is String -> value.toDoubleOrNull()?.let { return it }
                        }
                    }
                }
            }
            val names = node.keys()
            while (names.hasNext()) {
                findNumber(node.opt(names.next()), keys)?.let { return it }
            }
            null
        }

        is JSONArray -> {
            for (index in 0 until node.length()) {
                findNumber(node.opt(index), keys)?.let { return it }
            }
            null
        }

        else -> null
    }
}
