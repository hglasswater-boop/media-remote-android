package dev.mediaremote.dial

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.mediaremote.media.MediaSessionBridge
import dev.mediaremote.media.RemoteMediaCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal YouTube Lounge receiver used by the DIAL path.
 *
 * This intentionally implements the receiver behavior needed by YouTube Music instead of embedding
 * a JavaScript runtime. The protocol flow mirrors the MIT licensed yt-cast-receiver project:
 * screen id -> lounge token -> initial bind -> long-poll RPC -> DIAL pairing-code registration.
 */
internal class YouTubeLoungeSession(
    context: Context,
    private val screenName: String,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val bootstrapExecutor = Executors.newSingleThreadExecutor()
    private val rpcExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val bindParams = LoungeBindParams(
        deviceId = DialIdentityStore.loungeDeviceId(appContext),
        screenName = screenName,
        modelName = Build.MODEL,
    )

    @Volatile private var screenId: String? = null
    @Volatile private var rpcConnection: HttpURLConnection? = null
    @Volatile private var rpcFuture: Future<*>? = null
    @Volatile private var sessionReady = false
    private val outgoingOffset = AtomicInteger(0)
    @Volatile private var currentVideoId: String? = null
    @Volatile private var currentListId: String? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        bootstrapExecutor.execute {
            while (running.get() && !sessionReady) {
                runCatching { establish() }
                    .onFailure {
                        Log.w(TAG, "Lounge establish failed", it)
                        onStatus("YouTube接続準備を再試行中")
                        sleepInterruptibly(3_000)
                    }
            }
            if (running.get() && sessionReady) startRpcLoop()
        }
    }

    fun stop() {
        running.set(false)
        sessionReady = false
        rpcConnection?.disconnect()
        rpcConnection = null
        rpcFuture?.cancel(true)
        rpcFuture = null
        bootstrapExecutor.shutdownNow()
        rpcExecutor.shutdownNow()
        sendExecutor.shutdownNow()
    }

    fun registerPairingCode(code: String): Boolean {
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) return false

        // DIAL launch can arrive immediately after discovery. Give session bootstrap a short window
        // so the sender does not have to retry just because the lounge token is still being fetched.
        val deadline = System.currentTimeMillis() + 7_000
        while (running.get() && !sessionReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        val sid = screenId ?: return false

        val response = runCatching {
            LoungeHttp.postForm(
                URL_REGISTER_PAIRING_CODE,
                linkedMapOf(
                    "access_type" to "permanent",
                    "app" to SCREEN_APP,
                    "pairing_code" to cleanCode,
                    "screen_id" to sid,
                    "screen_name" to screenName,
                    "device_id" to DialIdentityStore.loungeDeviceId(appContext),
                ),
            )
        }.getOrElse {
            Log.w(TAG, "Pairing registration failed", it)
            return false
        }

        if (response.code !in 200..299) {
            Log.w(TAG, "Pairing registration HTTP ${response.code}: ${response.body}")
            return false
        }
        onStatus("YouTube Musicから接続されました")
        return true
    }

    private fun establish() {
        sessionReady = false

        val storedSid = DialIdentityStore.screenId(appContext)
            ?.takeIf { it.isNotBlank() }
        val initialSid = storedSid ?: generateScreenId().also {
            DialIdentityStore.saveScreenId(appContext, it)
        }

        val (activeSid, token) = if (storedSid == null) {
            initialSid to getLoungeToken(initialSid)
        } else {
            runCatching { storedSid to getLoungeToken(storedSid) }
                .getOrElse {
                    // A stored screen id can expire server-side. Generate one fresh id and retry once.
                    DialIdentityStore.clearScreenId(appContext)
                    val freshSid = generateScreenId()
                    DialIdentityStore.saveScreenId(appContext, freshSid)
                    freshSid to getLoungeToken(freshSid)
                }
        }

        screenId = activeSid
        bindParams.loungeIdToken = token

        val initUrl = "$URL_BIND?${bindParams.initSessionQuery()}"
        val init = LoungeHttp.postForm(initUrl, mapOf("count" to "0"))
        check(init.code in 200..299) { "Initial Lounge bind HTTP ${init.code}" }

        val initialMessages = LoungeMessage.parseMany(init.body)
        initialMessages.forEach(bindParams::updateFrom)
        check(!bindParams.sid.isNullOrBlank()) { "Lounge bind did not provide SID" }
        check(!bindParams.gsessionId.isNullOrBlank()) { "Lounge bind did not provide gsessionid" }

        sessionReady = true
        onStatus("YouTube Music Cast待受中")
        Log.i(TAG, "Lounge session ready for theme=m screenId=$activeSid")
    }

    private fun generateScreenId(): String {
        val response = LoungeHttp.get(URL_GENERATE_SCREEN_ID)
        check(response.code in 200..299) { "generate_screen_id HTTP ${response.code}" }
        return response.body.trim().also { check(it.isNotBlank()) { "Empty screen id" } }
    }

    private fun getLoungeToken(sid: String): String {
        val response = LoungeHttp.postForm(
            URL_GET_LOUNGE_TOKEN,
            mapOf("screen_ids" to sid),
        )
        check(response.code in 200..299) { "get_lounge_token_batch HTTP ${response.code}" }
        val screen = JSONObject(response.body)
            .optJSONArray("screens")
            ?.optJSONObject(0)
            ?: error("Missing lounge token screen")
        return screen.optString("loungeToken").takeIf { it.isNotBlank() }
            ?: error("Missing loungeToken")
    }

    private fun startRpcLoop() {
        rpcFuture = rpcExecutor.submit {
            while (running.get()) {
                try {
                    val url = "$URL_BIND?${bindParams.rpcQuery()}"
                    val connection = LoungeHttp.openLongPoll(url)
                    rpcConnection = connection
                    BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                        while (running.get()) {
                            val line = reader.readLine() ?: break
                            val messages = LoungeMessage.parseMany(line)
                            messages.forEach { message ->
                                bindParams.updateFrom(message)
                                handleIncoming(message)
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (running.get()) {
                        Log.w(TAG, "Lounge RPC disconnected", error)
                        sleepInterruptibly(750)
                    }
                } finally {
                    rpcConnection?.disconnect()
                    rpcConnection = null
                }
            }
        }
    }

    private fun handleIncoming(message: LoungeMessage) {
        val payload = message.payload as? JSONObject
        when (message.name) {
            "setPlaylist", "updatePlaylist" -> {
                val videoId = payload?.optString("videoId")?.takeIf { it.isNotBlank() }
                val listId = payload?.optString("listId")?.takeIf { it.isNotBlank() }
                if (videoId != null) currentVideoId = videoId
                if (listId != null) currentListId = listId

                if (message.name == "setPlaylist" && videoId != null) {
                    val url = buildMusicUrl(videoId, listId)
                    MediaSessionBridge.execute(appContext, RemoteMediaCommand.PlayFromUrl(url))
                    onStatus("YouTube Musicから選曲を受信")
                    sendNowPlaying(message.aid)
                }
            }
            "play" -> {
                MediaSessionBridge.execute(appContext, RemoteMediaCommand.Play)
                sendNowPlaying(message.aid)
            }
            "pause" -> {
                MediaSessionBridge.execute(appContext, RemoteMediaCommand.Pause)
                sendNowPlaying(message.aid)
            }
            "stopVideo" -> {
                MediaSessionBridge.execute(appContext, RemoteMediaCommand.Stop)
                sendNowPlaying(message.aid)
            }
            "next" -> MediaSessionBridge.execute(appContext, RemoteMediaCommand.Next)
            "previous" -> MediaSessionBridge.execute(appContext, RemoteMediaCommand.Previous)
            "seekTo" -> {
                val seconds = payload?.optString("newTime")?.toDoubleOrNull()
                    ?: payload?.optDouble("newTime", Double.NaN)?.takeUnless { it.isNaN() }
                if (seconds != null) {
                    MediaSessionBridge.execute(
                        appContext,
                        RemoteMediaCommand.SeekTo((seconds * 1000.0).toLong()),
                    )
                    sendNowPlaying(message.aid)
                }
            }
            "getNowPlaying" -> sendNowPlaying(message.aid)
            "getVolume" -> sendVolume(message.aid)
            "getPlaylist" -> sendPlaylist(message.aid)
            "getSubtitlesTrack" -> sendMessage(message.aid, "onSubtitlesTrackChanged", emptyMap())
            "loungeStatus" -> onStatus("YouTube Music送信端末が接続中")
        }
    }

    private fun sendNowPlaying(aid: Int) {
        val snapshot = MediaSessionBridge.snapshot(appContext)
        val payload = linkedMapOf<String, Any>(
            "currentTime" to (snapshot.positionMs / 1000.0),
            "duration" to (snapshot.durationMs / 1000.0),
            "loadedTime" to (snapshot.durationMs / 1000.0),
            "state" to when {
                !snapshot.available -> -1
                snapshot.playing -> 1
                else -> 2
            },
            "seekableStartTime" to 0,
            "seekableEndTime" to (snapshot.durationMs / 1000.0),
        )
        currentVideoId?.let { payload["videoId"] = it }
        currentListId?.let { payload["listId"] = it }
        sendMessage(aid, "nowPlaying", payload)
    }

    private fun sendPlaylist(aid: Int) {
        val payload = linkedMapOf<String, Any>()
        currentListId?.let { payload["listId"] = it }
        currentVideoId?.let { payload["videoId"] = it }
        sendMessage(aid, "playlistModified", payload)
    }

    private fun sendVolume(aid: Int) {
        val audio = appContext.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        sendMessage(
            aid,
            "onVolumeChanged",
            mapOf(
                "volume" to (current * 100.0 / max),
                "muted" to (current == 0),
            ),
        )
    }

    private fun sendMessage(aid: Int?, name: String, values: Map<String, Any>) {
        if (!sessionReady || !running.get()) return
        sendExecutor.execute {
            runCatching {
                val form = linkedMapOf<String, String>(
                    "count" to "1",
                    "ofs" to outgoingOffset.getAndIncrement().toString(),
                    "req0__sc" to name,
                )
                values.forEach { (key, value) ->
                    form["req0_$key"] = when (value) {
                        is JSONObject, is JSONArray -> value.toString()
                        else -> value.toString()
                    }
                }
                val url = "$URL_BIND?${bindParams.sendMessageQuery(aid)}"
                val response = LoungeHttp.postForm(url, form)
                check(response.code in 200..299) { "send message HTTP ${response.code}" }
            }.onFailure { Log.w(TAG, "Failed to send Lounge response $name", it) }
        }
    }

    private fun buildMusicUrl(videoId: String, listId: String?): String = Uri.Builder()
        .scheme("https")
        .authority("music.youtube.com")
        .path("/watch")
        .appendQueryParameter("v", videoId)
        .apply { listId?.let { appendQueryParameter("list", it) } }
        .build()
        .toString()

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "YouTubeLoungeSession"
        private const val SCREEN_APP = "ytcr"
        private const val BASE = "https://www.youtube.com"
        private const val URL_GENERATE_SCREEN_ID = "$BASE/api/lounge/pairing/generate_screen_id"
        private const val URL_GET_LOUNGE_TOKEN = "$BASE/api/lounge/pairing/get_lounge_token_batch"
        private const val URL_REGISTER_PAIRING_CODE = "$BASE/api/lounge/pairing/register_pairing_code"
        private const val URL_BIND = "$BASE/api/lounge/bc/bind"
    }
}
