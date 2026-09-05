package dev.mediaremote.dial

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in device diagnostics. Never dump RPC envelopes, pairing keys, or Lounge auth tokens. */
internal object LoungePlaylistTrace {
    const val TAG = "LoungePlaylistTrace"
    private val sequence = AtomicInteger()

    fun incoming(aid: Int, name: String, payload: JSONObject?) {
        // Enable only during USB diagnosis: adb shell setprop log.tag.LoungePlaylistTrace DEBUG
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        runCatching {
            val traceId = sequence.incrementAndGet()
            val text = playlistTracePayload(payload).toString()
            val chunks = text.take(65_536).chunked(800)
            chunks.forEachIndexed { index, chunk ->
                Log.d(
                    TAG,
                    "incoming trace=$traceId aid=$aid name=$name " +
                        "part=${index + 1}/${chunks.size} chars=${text.length} " +
                        "truncated=${text.length > 65_536} json=$chunk",
                )
            }
        }.onFailure { Log.w(TAG, "Playlist diagnostic serialization failed") }
    }
}

/** Preserve absent vs null and raw types; only playback-context values are included. */
internal fun playlistTracePayload(payload: JSONObject?): JSONObject = JSONObject().apply {
    put("payloadPresent", payload != null)
    if (payload == null) return@apply
    put("keys", JSONArray(payload.keys().asSequence().toList().sorted()))
    val fields = JSONObject()
    listOf("videoId", "listId", "currentIndex", "videoIds", "ctt", "params", "currentTime")
        .filter(payload::has)
        .forEach { key -> fields.put(key, payload.opt(key)) }
    // Newer senders carry the original playlist separately from listId (the Lounge queue).
    // Do not dump the whole entry: it can also contain opaque serialized MDX metadata.
    listOf("videoEntry", "videoEntries")
        .filter(payload::has)
        .forEach { key -> fields.put(key, playlistEntryTrace(payload.opt(key), array = key == "videoEntries")) }
    put("fields", fields)
}

/** Retain wire shape and only the two known identity fields inside an entry. */
private fun playlistEntryTrace(raw: Any?, array: Boolean): Any {
    if (raw == null || raw === JSONObject.NULL) return JSONObject.NULL
    val parsed = if (raw is String) {
        runCatching { if (array) JSONArray(raw) else JSONObject(raw) }.getOrNull()
    } else {
        raw
    }
    if (array && parsed is JSONArray) {
        val entries = JSONArray()
        for (index in 0 until parsed.length()) {
            entries.put(playlistEntryTrace(parsed.opt(index), array = false))
        }
        return if (raw is String) entries.toString() else entries
    }
    if (!array && parsed is JSONObject) {
        val entry = JSONObject()
        entry.put("keys", JSONArray(parsed.keys().asSequence().toList().sorted()))
        listOf("videoId", "sourceContainerPlaylistId")
            .filter(parsed::has)
            .forEach { key ->
                val value = parsed.opt(key)
                // Don't let unexpected nested objects expand the allowlist.
                entry.put(key, if (value is String || value === JSONObject.NULL) value else entryTraceShape(value))
            }
        return if (raw is String) entry.toString() else entry
    }
    // Malformed entry text may include sensitive data; record its shape only.
    return entryTraceShape(raw)
}

private fun entryTraceShape(value: Any?): JSONObject = JSONObject().apply {
    put("unparsed", true)
    put("type", when (value) {
        is String -> "string"
        is JSONArray -> "array"
        is JSONObject -> "object"
        is Number -> "number"
        is Boolean -> "boolean"
        else -> "null"
    })
    if (value is String) put("chars", value.length)
}
