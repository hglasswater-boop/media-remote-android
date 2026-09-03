package dev.mediaremote.dial

import org.json.JSONArray

internal data class LoungeMessage(
    val aid: Int,
    val name: String,
    val payload: Any? = null,
) {
    companion object {
        // Ported from the message envelope used by yt-cast-receiver. The upstream protocol sends
        // entries such as [12,["setPlaylist",{...}]] in long-poll chunks.
        private val incomingPattern = Regex("\\[(\\d+),\\[\"(.+?)\"(?:,(.*?))?\\]\\]")

        fun parseMany(raw: String): List<LoungeMessage> {
            val flattened = raw.replace("\r", "").replace("\n", "")
            return incomingPattern.findAll(flattened).mapNotNull { match ->
                val aid = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val name = match.groupValues[2]
                val rawPayload = match.groupValues.getOrNull(3).orEmpty()
                val payload = if (rawPayload.isBlank()) {
                    null
                } else {
                    runCatching {
                        JSONArray("[$rawPayload]").let { array ->
                            if (array.length() == 1) array.opt(0) else array
                        }
                    }.getOrNull()
                }
                LoungeMessage(aid, name, payload)
            }.toList()
        }
    }
}
