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
    put("fields", fields)
}
