package dev.mediaremote.network

import dev.mediaremote.media.MediaSnapshot
import dev.mediaremote.media.RemoteMediaCommand
import org.json.JSONObject

data class RemoteRequest(
    val token: String,
    val command: String,
    val value: Long = 0L,
    val text: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("token", token)
        .put("command", command)
        .put("value", value)
        .put("text", text)
        .toString()

    companion object {
        fun fromJson(raw: String): RemoteRequest {
            val json = JSONObject(raw)
            return RemoteRequest(
                token = json.getString("token"),
                command = json.getString("command"),
                value = json.optLong("value", 0L),
                text = json.optString("text"),
            )
        }
    }
}

data class RemoteResponse(
    val ok: Boolean,
    val message: String,
    val snapshot: MediaSnapshot? = null,
) {
    fun toJson(): String {
        val json = JSONObject()
            .put("ok", ok)
            .put("message", message)

        snapshot?.let {
            json.put(
                "snapshot",
                JSONObject()
                    .put("available", it.available)
                    .put("title", it.title)
                    .put("artist", it.artist)
                    .put("album", it.album)
                    .put("playing", it.playing)
                    .put("positionMs", it.positionMs)
                    .put("durationMs", it.durationMs)
                    .put("packageName", it.packageName),
            )
        }
        return json.toString()
    }

    companion object {
        fun fromJson(raw: String): RemoteResponse {
            val json = JSONObject(raw)
            val snapshotJson = json.optJSONObject("snapshot")
            return RemoteResponse(
                ok = json.getBoolean("ok"),
                message = json.optString("message"),
                snapshot = snapshotJson?.let {
                    MediaSnapshot(
                        available = it.optBoolean("available"),
                        title = it.optString("title"),
                        artist = it.optString("artist"),
                        album = it.optString("album"),
                        playing = it.optBoolean("playing"),
                        positionMs = it.optLong("positionMs"),
                        durationMs = it.optLong("durationMs"),
                        packageName = it.optString("packageName"),
                    )
                },
            )
        }
    }
}

fun RemoteRequest.toMediaCommand(): RemoteMediaCommand? = when (command) {
    "play" -> RemoteMediaCommand.Play
    "pause" -> RemoteMediaCommand.Pause
    "stop" -> RemoteMediaCommand.Stop
    "next" -> RemoteMediaCommand.Next
    "previous" -> RemoteMediaCommand.Previous
    "seekBy" -> RemoteMediaCommand.SeekBy(value)
    "seekTo" -> RemoteMediaCommand.SeekTo(value)
    "playSearch" -> text.takeIf { it.isNotBlank() }?.let(RemoteMediaCommand::PlayFromSearch)
    "playUrl" -> text.takeIf { it.isNotBlank() }?.let(RemoteMediaCommand::PlayFromUrl)
    else -> null
}
