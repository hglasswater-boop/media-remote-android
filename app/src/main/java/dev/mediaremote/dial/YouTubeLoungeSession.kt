package dev.mediaremote.dial

import android.content.Context
import android.media.AudioManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.mediaremote.media.MediaSessionBridge
import dev.mediaremote.media.MediaSnapshot
import dev.mediaremote.media.RemoteMediaCommand
import dev.mediaremote.media.YouTubeMediaIdentityStore
import dev.mediaremote.media.YouTubeMusicTrackResolver
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

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
    private val mediaSyncExecutor = Executors.newSingleThreadScheduledExecutor()
    private val identityResolverExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val stateSyncQueued = AtomicBoolean(false)
    private val stateSyncDirty = AtomicBoolean(false)
    private val bindParams = LoungeBindParams(
        deviceId = DialIdentityStore.loungeDeviceId(appContext),
        screenName = screenName,
        modelName = Build.MODEL,
    )

    @Volatile private var screenId: String? = null
    @Volatile private var rpcConnection: HttpURLConnection? = null
    @Volatile private var rpcFuture: Future<*>? = null
    @Volatile private var mediaSyncFuture: ScheduledFuture<*>? = null
    @Volatile private var sessionReady = false
    @Volatile private var rpcEstablished = false
    @Volatile private var senderConnected = false
    @Volatile private var pendingStateAid: Int? = null
    @Volatile private var pendingIdentityKey: String? = null
    private val outgoingOffset = AtomicInteger(0)
    @Volatile private var currentVideoId: String? = null
    @Volatile private var currentListId: String? = null
    @Volatile private var currentVideoIds: List<String> = emptyList()
    @Volatile private var currentIndex: Int? = null
    @Volatile private var currentCtt: String? = null
    @Volatile private var currentParams: String? = null
    @Volatile private var senderExpectedVideoId: String? = null
    @Volatile private var senderSelectionDeadlineMs: Long = 0L
    @Volatile private var currentCpn: String = newCpn()
    private var lastMediaSnapshot: MediaSnapshot? = null

    fun start(onReady: (() -> Unit)? = null) {
        if (!running.compareAndSet(false, true)) return
        bootstrapExecutor.execute {
            onStatus("YouTube Loungeを準備中")
            while (running.get() && !sessionReady) {
                runCatching { establish() }
                    .onFailure {
                        Log.w(TAG, "Lounge establish failed", it)
                        onStatus("YouTube Lounge準備を再試行中")
                        sleepInterruptibly(3_000)
                    }
            }
            if (running.get() && sessionReady) {
                onStatus("YouTube Lounge初期bind完了")
                startRpcLoop {
                    if (!running.get()) return@startRpcLoop
                    onStatus("YouTube Lounge準備完了")
                    runCatching { onReady?.invoke() }
                        .onFailure { Log.e(TAG, "Lounge ready callback failed", it) }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        sessionReady = false
        rpcEstablished = false
        senderConnected = false
        stateSyncDirty.set(false)
        synchronized(this) {
            pendingStateAid = null
            pendingIdentityKey = null
        }
        mediaSyncFuture?.cancel(true)
        mediaSyncFuture = null
        rpcConnection?.disconnect()
        rpcConnection = null
        rpcFuture?.cancel(true)
        rpcFuture = null
        bootstrapExecutor.shutdownNow()
        rpcExecutor.shutdownNow()
        sendExecutor.shutdownNow()
        mediaSyncExecutor.shutdownNow()
        identityResolverExecutor.shutdownNow()
    }

    fun registerPairingCode(code: String): Boolean {
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) return false
        onStatus("YouTube MusicのpairingCodeを受信")

        val deadline = System.currentTimeMillis() + 1_500
        while (
            running.get() &&
            (!sessionReady || !rpcEstablished) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }
        val sid = screenId
        if (!sessionReady || !rpcEstablished || sid.isNullOrBlank()) {
            Log.w(TAG, "Pairing requested before Lounge RPC was ready")
            onStatus("Lounge RPC未準備のためpairing失敗")
            return false
        }

        onStatus("YouTube Loungeへpairing登録中")
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
            onStatus("YouTube Lounge pairing通信エラー")
            return false
        }

        if (response.code !in 200..299) {
            Log.w(TAG, "Pairing registration HTTP ${response.code}: ${response.body}")
            onStatus("YouTube Lounge pairing失敗 HTTP ${response.code}")
            return false
        }
        onStatus("YouTube Musicのpairing登録成功")
        return true
    }

    private fun establish() {
        sessionReady = false
        rpcEstablished = false

        val storedSid = DialIdentityStore.screenId(appContext)?.takeIf { it.isNotBlank() }
        val initialSid = storedSid ?: generateScreenId().also {
            DialIdentityStore.saveScreenId(appContext, it)
        }

        val (activeSid, token) = if (storedSid == null) {
            initialSid to getLoungeToken(initialSid)
        } else {
            runCatching { storedSid to getLoungeToken(storedSid) }
                .getOrElse {
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
        Log.i(TAG, "Lounge initial bind ready for theme=m screenId=$activeSid")
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

    private fun startRpcLoop(onFirstConnected: (() -> Unit)? = null) {
        rpcFuture = rpcExecutor.submit {
            var readyCallbackFired = false
            while (running.get()) {
                try {
                    val url = "$URL_BIND?${bindParams.rpcQuery()}"
                    val connection = LoungeHttp.openLongPoll(url)
                    rpcConnection = connection
                    BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                        if (!readyCallbackFired) {
                            rpcEstablished = true
                            readyCallbackFired = true
                            Log.i(TAG, "Lounge RPC connection established")
                            onStatus("YouTube Lounge RPC接続完了")
                            runCatching { onFirstConnected?.invoke() }
                                .onFailure { Log.e(TAG, "Lounge RPC ready callback failed", it) }
                        }

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
            "setPlaylist", "updatePlaylist" -> handlePlaylistMessage(message, payload)
            "play" -> {
                MediaSessionBridge.execute(appContext, RemoteMediaCommand.Play)
                requestMediaSync(message.aid, force = true, delayMs = 120)
            }
            "pause" -> {
                MediaSessionBridge.execute(appContext, RemoteMediaCommand.Pause)
                requestMediaSync(message.aid, force = true, delayMs = 120)
            }
            "stopVideo" -> {
                MediaSessionBridge.execute(appContext, RemoteMediaCommand.Stop)
                requestMediaSync(message.aid, force = true, delayMs = 120)
            }
            "next" -> {
                MediaSessionBridge.execute(appContext, RemoteMediaCommand.Next)
                requestMediaSync(message.aid, force = true, delayMs = 220)
            }
            "previous" -> {
                MediaSessionBridge.execute(appContext, RemoteMediaCommand.Previous)
                requestMediaSync(message.aid, force = true, delayMs = 220)
            }
            "seekTo" -> {
                val seconds = payload?.optString("newTime")?.toDoubleOrNull()
                    ?: payload?.optDouble("newTime", Double.NaN)?.takeUnless { it.isNaN() }
                if (seconds != null) {
                    MediaSessionBridge.execute(
                        appContext,
                        RemoteMediaCommand.SeekTo((seconds * 1000.0).toLong()),
                    )
                    requestMediaSync(message.aid, force = true, delayMs = 120)
                }
            }
            "getNowPlaying" -> {
                val snapshot = MediaSessionBridge.snapshot(appContext)
                sendNowPlaying(message.aid, snapshot)
                queueStateChange(message.aid)
            }
            "getVolume" -> sendVolume(message.aid)
            "getPlaylist" -> sendPlaylist(message.aid)
            "getSubtitlesTrack" -> sendMessage(message.aid, "onSubtitlesTrackChanged", emptyMap())
            "loungeStatus" -> handleLoungeStatus(message.aid, payload)
        }
    }

    private fun handlePlaylistMessage(message: LoungeMessage, payload: JSONObject?) {
        val videoId = payload?.optString("videoId")?.takeIf(YOUTUBE_VIDEO_ID::matches)
        val listId = payload?.optString("listId")?.takeIf { it.isNotBlank() }
        val videoIds = parsePlaylistVideoIds(payload)
        val index = payload?.takeIf { it.has("currentIndex") }
            ?.optInt("currentIndex")
            ?.takeIf { it >= 0 }
        val ctt = payload?.optString("ctt")?.takeIf { it.isNotBlank() }
        val params = payload?.optString("params")?.takeIf { it.isNotBlank() }
        val currentTimeSeconds = payload?.optString("currentTime")?.toDoubleOrNull()
            ?: payload?.optDouble("currentTime", Double.NaN)?.takeUnless { it.isNaN() }

        val oldListId = currentListId
        if (listId == null) {
            clearPlaylistContext()
        } else {
            currentListId = listId
            currentVideoIds = videoIds
            currentIndex = index ?: videoId?.let { id ->
                videoIds.indexOf(id).takeIf { it >= 0 }
            }
            currentParams = params
            if (message.name == "setPlaylist" || oldListId != listId) {
                currentCtt = ctt
            }
        }

        if (videoId != null) {
            setCurrentVideo(videoId)
            senderExpectedVideoId = if (message.name == "setPlaylist") videoId else null
            senderSelectionDeadlineMs = if (message.name == "setPlaylist") {
                SystemClock.elapsedRealtime() + SENDER_SELECTION_GUARD_MS
            } else {
                0L
            }
        } else if (message.name == "setPlaylist") {
            clearCurrentVideoIdentity(clearIndex = false)
        }

        Log.i(
            TAG,
            "${message.name}: videoId=${videoId ?: "<none>"} listId=${listId ?: "<none>"} " +
                "index=${currentIndex ?: -1} videoIds=${videoIds.size} ctt=${ctt != null} params=${params != null}",
        )

        if (message.name == "setPlaylist" && videoId != null) {
            val url = buildMusicUrl(videoId, listId, currentIndex)
            MediaSessionBridge.execute(appContext, RemoteMediaCommand.PlayFromUrl(url))
            if (currentTimeSeconds != null && currentTimeSeconds > 1.0) {
                mediaSyncExecutor.schedule(
                    {
                        if (running.get()) {
                            MediaSessionBridge.execute(
                                appContext,
                                RemoteMediaCommand.SeekTo((currentTimeSeconds * 1000.0).toLong()),
                            )
                        }
                    },
                    SET_PLAYLIST_SEEK_DELAY_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
            onStatus("YouTube Musicから選曲を受信")
            requestMediaSync(message.aid, force = true, delayMs = 450)
        } else {
            requestMediaSync(message.aid, force = true, delayMs = 80)
        }
    }

    private fun parsePlaylistVideoIds(payload: JSONObject?): List<String> {
        val value = payload?.opt("videoIds") ?: return emptyList()
        val raw = when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                value.optString(index).takeIf(YOUTUBE_VIDEO_ID::matches)
            }
            else -> value.toString()
                .split(',')
                .map(String::trim)
                .filter(YOUTUBE_VIDEO_ID::matches)
        }
        return raw.distinct()
    }

    private fun handleLoungeStatus(aid: Int, payload: JSONObject?) {
        val senderPresent = remoteSenderPresent(payload)
        if (senderPresent == false) {
            if (senderConnected) {
                senderConnected = false
                stateSyncDirty.set(false)
                synchronized(this) {
                    pendingStateAid = null
                    pendingIdentityKey = null
                    lastMediaSnapshot = null
                    clearPlaybackContext()
                }
                onStatus("YouTube Music送信端末とLounge接続解除")
            }
            return
        }

        val wasConnected = senderConnected
        senderConnected = true
        if (!wasConnected) {
            stateSyncDirty.set(false)
            synchronized(this) {
                pendingStateAid = null
                lastMediaSnapshot = null
            }
            onStatus("YouTube Music送信端末とLounge接続成立")
        }

        publishSenderConnectedState(aid)
        sendVolume(aid)
        startMediaSync()
    }

    private fun remoteSenderPresent(payload: JSONObject?): Boolean? {
        val rawDevices = payload?.optString("devices")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val devices = JSONArray(rawDevices)
            (0 until devices.length()).any { index ->
                devices.optJSONObject(index)
                    ?.optString("type")
                    ?.equals("REMOTE_CONTROL", ignoreCase = true) == true
            }
        }.getOrElse { error ->
            Log.w(TAG, "Unable to parse Lounge devices list", error)
            null
        }
    }

    private fun startMediaSync() {
        if (mediaSyncFuture != null) return
        mediaSyncFuture = mediaSyncExecutor.scheduleAtFixedRate(
            {
                if (running.get() && senderConnected) {
                    runCatching { publishMediaState(aid = null, force = false) }
                        .onFailure { Log.w(TAG, "Periodic media state sync failed", it) }
                }
            },
            0,
            MEDIA_SYNC_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun requestMediaSync(aid: Int?, force: Boolean, delayMs: Long) {
        if (!running.get()) return
        mediaSyncExecutor.schedule(
            {
                if (running.get()) {
                    runCatching { publishMediaState(aid, force) }
                        .onFailure { Log.w(TAG, "Requested media state sync failed", it) }
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    private fun publishSenderConnectedState(aid: Int?) {
        if (!sessionReady || !senderConnected) return

        val snapshot = MediaSessionBridge.snapshot(appContext)
        syncCurrentVideo(snapshot)
        sendHasPreviousNextChanged(aid, snapshot)
        sendNowPlaying(aid, snapshot)
        queueStateChange(aid)
        lastMediaSnapshot = snapshot
    }

    @Synchronized
    private fun publishMediaState(aid: Int?, force: Boolean) {
        if (!sessionReady || !senderConnected) return

        val snapshot = MediaSessionBridge.snapshot(appContext)
        val previous = lastMediaSnapshot
        val trackChanged = previous != null && trackIdentityChanged(previous, snapshot)

        syncCurrentVideo(snapshot, invalidateWhenMissing = trackChanged)
        if (currentVideoId == null && snapshot.title.isNotBlank()) {
            scheduleIdentityResolution(snapshot)
        }

        val mediaChanged = previous == null ||
            previous.mediaId != snapshot.mediaId ||
            previous.title != snapshot.title ||
            previous.artist != snapshot.artist ||
            previous.album != snapshot.album ||
            previous.durationMs != snapshot.durationMs ||
            previous.queueIndex != snapshot.queueIndex ||
            previous.queueSize != snapshot.queueSize
        val stateChanged = previous == null || previous.playbackState != snapshot.playbackState
        val actionsChanged = previous == null || previous.actions != snapshot.actions
        val positionChanged = previous == null ||
            abs(previous.positionMs - snapshot.positionMs) >= POSITION_CHANGE_THRESHOLD_MS

        if (trackChanged) {
            Log.i(
                TAG,
                "Local track changed: title=${snapshot.title.take(80)} mediaId=${snapshot.mediaId.ifBlank { "<unresolved>" }} " +
                    "queue=${snapshot.queueIndex}/${snapshot.queueSize}",
            )
        }

        if (force || stateChanged || positionChanged) queueStateChange(aid)
        if (force || mediaChanged || stateChanged) sendNowPlaying(aid, snapshot)
        if (force || mediaChanged || actionsChanged) sendHasPreviousNextChanged(aid, snapshot)

        lastMediaSnapshot = snapshot
    }

    private fun trackIdentityChanged(previous: MediaSnapshot, current: MediaSnapshot): Boolean {
        if (
            previous.queueSize > 1 &&
            previous.queueSize == current.queueSize &&
            previous.queueIndex >= 0 &&
            current.queueIndex >= 0 &&
            previous.queueIndex != current.queueIndex
        ) {
            return true
        }

        val previousId = previous.mediaId.takeIf(YOUTUBE_VIDEO_ID::matches)
        val currentId = current.mediaId.takeIf(YOUTUBE_VIDEO_ID::matches)
        if (previousId != null && currentId != null && previousId != currentId) return true

        val previousTitle = normalizeTrackText(previous.title)
        val currentTitle = normalizeTrackText(current.title)
        if (previousTitle.isNotBlank() && currentTitle.isNotBlank() && previousTitle != currentTitle) return true

        val previousArtist = normalizeTrackText(previous.artist)
        val currentArtist = normalizeTrackText(current.artist)
        return previousArtist.isNotBlank() && currentArtist.isNotBlank() && previousArtist != currentArtist
    }

    private fun normalizeTrackText(value: String): String = value.trim().lowercase()

    private fun sendNowPlaying(aid: Int?, snapshot: MediaSnapshot = MediaSessionBridge.snapshot(appContext)) {
        syncCurrentVideo(snapshot)
        if (currentVideoId == null && snapshot.title.isNotBlank()) {
            scheduleIdentityResolution(snapshot)
        }

        val videoId = currentVideoId
        if (videoId == null) {
            // The reference receiver never publishes a half-populated nowPlaying object. Sending
            // time/state without a videoId makes YouTube Music retain the previous track while only
            // its seek bar advances, which is exactly the stale-title symptom we need to avoid.
            sendMessage(aid, "nowPlaying", emptyMap())
            return
        }

        val state = loungePlayerState(snapshot)
        val durationSeconds = snapshot.durationMs / 1000.0
        val payload = linkedMapOf<String, Any>(
            "currentTime" to (snapshot.positionMs / 1000.0),
            "duration" to durationSeconds,
            "loadedTime" to if (state == 1 || state == 2 || state == 3) durationSeconds else 0,
            "videoId" to videoId,
            "state" to state,
            "seekableStartTime" to 0,
            "seekableEndTime" to durationSeconds,
            "cpn" to currentCpn,
        )

        if (playlistContextMatches(videoId)) {
            currentListId?.let { payload["listId"] = it }
            currentIndex?.let { payload["currentIndex"] = it }
            currentCtt?.let { payload["ctt"] = it }
            currentParams?.let { payload["params"] = it }
        }
        sendMessage(aid, "nowPlaying", payload)
    }

    private fun syncCurrentVideo(snapshot: MediaSnapshot, invalidateWhenMissing: Boolean = false) {
        val expected = senderExpectedVideoId
            ?.takeIf { SystemClock.elapsedRealtime() <= senderSelectionDeadlineMs }
        if (senderExpectedVideoId != null && expected == null) {
            senderExpectedVideoId = null
            senderSelectionDeadlineMs = 0L
        }

        val playlistResolved = playlistVideoIdFromSnapshot(snapshot)
        val directResolved = snapshot.mediaId.takeIf(YOUTUBE_VIDEO_ID::matches)

        if (expected != null) {
            if (playlistResolved == expected || directResolved == expected) {
                senderExpectedVideoId = null
                senderSelectionDeadlineMs = 0L
                setCurrentVideo(expected)
                alignPlaylistContextForLocalVideo(expected, snapshot)
            }
            // While YouTube Music is still switching queues, the sender's setPlaylist videoId is
            // authoritative. Do not let lagging old MediaSession metadata overwrite it.
            return
        }

        if (playlistResolved != null) {
            setCurrentVideo(playlistResolved)
            currentIndex = snapshot.queueIndex
            return
        }

        if (directResolved != null) {
            setCurrentVideo(directResolved)
            alignPlaylistContextForLocalVideo(directResolved, snapshot)
        } else if (invalidateWhenMissing && snapshot.title.isNotBlank()) {
            clearCurrentVideoIdentity(clearIndex = false)
        }
    }

    private fun playlistVideoIdFromSnapshot(snapshot: MediaSnapshot): String? {
        val ids = currentVideoIds
        if (currentListId == null || ids.isEmpty()) return null
        if (snapshot.queueSize <= 1 || snapshot.queueSize != ids.size) return null
        return ids.getOrNull(snapshot.queueIndex)
    }

    @Synchronized
    private fun alignPlaylistContextForLocalVideo(videoId: String, snapshot: MediaSnapshot) {
        val ids = currentVideoIds
        if (currentListId == null) return

        if (ids.isNotEmpty()) {
            val matchedIndex = ids.indexOf(videoId)
            if (matchedIndex >= 0) {
                currentIndex = matchedIndex
                return
            }

            // A proven video outside the sender's queue means local YouTube Music moved to a
            // different context. Do not keep echoing an unrelated listId/currentIndex pair.
            clearPlaylistContext()
            return
        }

        if (snapshot.queueIndex >= 0) currentIndex = snapshot.queueIndex
    }

    @Synchronized
    private fun playlistContextMatches(videoId: String): Boolean {
        val listId = currentListId ?: return false
        if (listId.isBlank()) return false
        val index = currentIndex ?: return currentVideoIds.isEmpty()
        val ids = currentVideoIds
        return ids.isEmpty() || ids.getOrNull(index) == videoId
    }

    /**
     * Android sometimes gives us the new title/artist but no usable videoId. Resolve that identity
     * off the media-sync thread, then re-check the live track before publishing it. This prevents a
     * slow catalog response for track A from overwriting track B after a rapid skip.
     */
    private fun scheduleIdentityResolution(snapshot: MediaSnapshot) {
        if (!running.get() || !senderConnected || snapshot.title.isBlank()) return
        if (YOUTUBE_VIDEO_ID.matches(snapshot.mediaId)) return

        val key = identityKey(snapshot)
        synchronized(this) {
            if (pendingIdentityKey == key) return
            pendingIdentityKey = key
        }

        identityResolverExecutor.execute {
            try {
                val videoId = YouTubeMusicTrackResolver.resolve(snapshot) ?: return@execute
                if (!running.get() || !senderConnected) return@execute

                val fresh = MediaSessionBridge.snapshot(appContext)
                if (!sameTrack(snapshot, fresh)) {
                    Log.i(TAG, "Discarded catalog identity for superseded track: ${snapshot.title.take(80)}")
                    return@execute
                }

                YouTubeMediaIdentityStore.rememberResolved(
                    context = appContext,
                    videoId = videoId,
                    title = fresh.title,
                    artist = fresh.artist,
                    durationMs = fresh.durationMs,
                )
                val hydrated = MediaSessionBridge.snapshot(appContext)
                if (hydrated.mediaId != videoId || !sameTrack(snapshot, hydrated)) return@execute

                setCurrentVideo(videoId)
                alignPlaylistContextForLocalVideo(videoId, hydrated)
                Log.i(TAG, "Resolved local track identity: ${hydrated.title.take(80)} -> $videoId")
                sendNowPlaying(aid = null, snapshot = hydrated)
                sendHasPreviousNextChanged(aid = null, snapshot = hydrated)
                queueStateChange(aid = null)
                synchronized(this) { lastMediaSnapshot = hydrated }
            } finally {
                synchronized(this) {
                    if (pendingIdentityKey == key) pendingIdentityKey = null
                }
            }
        }
    }

    private fun identityKey(snapshot: MediaSnapshot): String =
        "${normalizeTrackText(snapshot.title)}|${normalizeTrackText(snapshot.artist)}|${snapshot.durationMs / 1000L}"

    private fun sameTrack(expected: MediaSnapshot, current: MediaSnapshot): Boolean {
        if (normalizeTrackText(expected.title) != normalizeTrackText(current.title)) return false
        val expectedArtist = normalizeTrackText(expected.artist)
        val currentArtist = normalizeTrackText(current.artist)
        if (expectedArtist.isNotBlank() && currentArtist.isNotBlank() && expectedArtist != currentArtist) return false
        if (expected.durationMs > 0L && current.durationMs > 0L) {
            if (abs(expected.durationMs - current.durationMs) > TRACK_DURATION_TOLERANCE_MS) return false
        }
        return true
    }

    private fun queueStateChange(aid: Int?) {
        if (!sessionReady || !running.get() || !senderConnected) return
        if (aid != null) {
            synchronized(this) {
                pendingStateAid = pendingStateAid?.let { maxOf(it, aid) } ?: aid
            }
        }
        stateSyncDirty.set(true)
        startStateSyncDrain()
    }

    private fun startStateSyncDrain() {
        if (!stateSyncQueued.compareAndSet(false, true)) return
        sendExecutor.execute {
            try {
                while (
                    sessionReady &&
                    running.get() &&
                    senderConnected &&
                    stateSyncDirty.getAndSet(false)
                ) {
                    val responseAid = synchronized(this) {
                        pendingStateAid.also { pendingStateAid = null }
                    }
                    val freshSnapshot = MediaSessionBridge.snapshot(appContext)
                    syncCurrentVideo(freshSnapshot)
                    sendMessageNow(
                        responseAid,
                        "onStateChange",
                        stateChangePayload(freshSnapshot),
                    )
                }
            } finally {
                stateSyncQueued.set(false)
                if (stateSyncDirty.get() && sessionReady && running.get() && senderConnected) {
                    startStateSyncDrain()
                }
            }
        }
    }

    private fun stateChangePayload(snapshot: MediaSnapshot): Map<String, Any> {
        val state = loungePlayerState(snapshot)
        val durationSeconds = snapshot.durationMs / 1000.0
        return linkedMapOf(
            "state" to state,
            "currentTime" to (snapshot.positionMs / 1000.0),
            "duration" to durationSeconds,
            "loadedTime" to if (state == 1 || state == 2 || state == 3) durationSeconds else 0,
            "seekableStartTime" to 0,
            "seekableEndTime" to durationSeconds,
            "cpn" to currentCpn,
        )
    }

    private fun sendHasPreviousNextChanged(aid: Int?, snapshot: MediaSnapshot) {
        sendMessage(
            aid,
            "onHasPreviousNextChanged",
            mapOf(
                "hasPrevious" to (snapshot.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L),
                "hasNext" to (snapshot.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L),
            ),
        )
    }

    private fun loungePlayerState(snapshot: MediaSnapshot): Int = when {
        !snapshot.available -> -1
        snapshot.playbackState == PlaybackState.STATE_PLAYING -> 1
        snapshot.playbackState == PlaybackState.STATE_PAUSED -> 2
        snapshot.playbackState == PlaybackState.STATE_BUFFERING ||
            snapshot.playbackState == PlaybackState.STATE_CONNECTING -> 3
        snapshot.playbackState == PlaybackState.STATE_STOPPED -> 4
        else -> -1
    }

    private fun sendPlaylist(aid: Int) {
        val payload = linkedMapOf<String, Any>()
        currentListId?.let { payload["listId"] = it }
        if (currentVideoIds.isNotEmpty()) payload["videoIds"] = currentVideoIds.joinToString(",")
        currentVideoId?.let { payload["videoId"] = it }
        currentIndex?.let { payload["currentIndex"] = it }
        currentCtt?.let { payload["ctt"] = it }
        currentParams?.let { payload["params"] = it }
        sendMessage(aid, "playlistModified", payload)
    }

    private fun sendVolume(aid: Int?) {
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
        sendExecutor.execute { sendMessageNow(aid, name, values) }
    }

    private fun sendMessageNow(aid: Int?, name: String, values: Map<String, Any>) {
        if (!sessionReady || !running.get()) return
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

    @Synchronized
    private fun setCurrentVideo(videoId: String) {
        if (videoId == currentVideoId) return
        currentVideoId = videoId
        currentCpn = newCpn()
    }

    @Synchronized
    private fun clearCurrentVideoIdentity(clearIndex: Boolean = true) {
        if (currentVideoId != null) {
            currentVideoId = null
            currentCpn = newCpn()
        }
        if (clearIndex) currentIndex = null
    }

    @Synchronized
    private fun clearPlaylistContext() {
        currentListId = null
        currentVideoIds = emptyList()
        currentIndex = null
        currentCtt = null
        currentParams = null
    }

    @Synchronized
    private fun clearPlaybackContext() {
        clearCurrentVideoIdentity()
        clearPlaylistContext()
        senderExpectedVideoId = null
        senderSelectionDeadlineMs = 0L
    }

    private fun buildMusicUrl(videoId: String, listId: String?, index: Int?): String = Uri.Builder()
        .scheme("https")
        .authority("music.youtube.com")
        .path("/watch")
        .appendQueryParameter("v", videoId)
        .apply {
            listId?.let { appendQueryParameter("list", it) }
            if (listId != null && index != null && index >= 0) {
                appendQueryParameter("index", index.toString())
            }
        }
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
        private const val MEDIA_SYNC_INTERVAL_MS = 1_000L
        private const val POSITION_CHANGE_THRESHOLD_MS = 400L
        private const val TRACK_DURATION_TOLERANCE_MS = 2_500L
        private const val SENDER_SELECTION_GUARD_MS = 4_000L
        private const val SET_PLAYLIST_SEEK_DELAY_MS = 650L
        private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        private fun newCpn(): String = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(16)
    }
}
