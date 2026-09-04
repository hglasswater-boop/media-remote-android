package dev.mediaremote.media

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Resolves the canonical YouTube video id when the Android YouTube Music MediaSession exposes only
 * human-readable track metadata.
 *
 * Lounge's `nowPlaying` protocol is keyed by videoId; title/artist alone cannot update a sender.
 * The official Android app occasionally keeps the previous id (or no id at all) after a local skip,
 * even though its notification already shows the new title. In that narrow case we use YouTube
 * Music's anonymous web search endpoint and accept only a high-confidence title/artist/duration
 * match. Ambiguous results are deliberately rejected instead of sending the wrong track identity.
 */
internal object YouTubeMusicTrackResolver {
    private data class Candidate(
        val videoId: String,
        val title: String,
        val searchableText: String,
        val durationSeconds: Int?,
    )

    private data class CacheEntry(
        val videoId: String?,
        val expiresAtMs: Long,
    )

    private val cache = LinkedHashMap<String, CacheEntry>(32, 0.75f, true)

    fun resolve(snapshot: MediaSnapshot): String? = resolve(
        title = snapshot.title,
        artist = snapshot.artist,
        durationMs = snapshot.durationMs,
    )

    fun resolve(snapshot: MediaSnapshot, forceRefresh: Boolean): String? = resolve(
        title = snapshot.title,
        artist = snapshot.artist,
        durationMs = snapshot.durationMs,
        forceRefresh = forceRefresh,
    )

    fun resolve(
        title: String,
        artist: String,
        durationMs: Long,
        forceRefresh: Boolean = false,
    ): String? {
        if (title.isBlank()) return null
        val key = signatureKey(title, artist, durationMs)
        if (!forceRefresh) {
            synchronized(cache) {
                cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.videoId }
            }
        }

        val resolved = runCatching { search(title, artist, durationMs) }
            .onFailure { Log.w(TAG, "YouTube Music catalog identity lookup failed", it) }
            .getOrNull()

        synchronized(cache) {
            cache[key] = CacheEntry(
                videoId = resolved,
                expiresAtMs = System.currentTimeMillis() + if (resolved == null) NEGATIVE_CACHE_MS else POSITIVE_CACHE_MS,
            )
            while (cache.size > MAX_CACHE_ENTRIES) {
                cache.entries.iterator().run {
                    if (hasNext()) {
                        next()
                        remove()
                    }
                }
            }
        }
        return resolved
    }

    private fun search(title: String, artist: String, durationMs: Long): String? {
        val clientVersion = webRemixClientVersion()
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", clientVersion)
            .put("hl", "ja")
            .put("gl", "JP")
        val body = JSONObject()
            .put("context", JSONObject().put("client", client).put("user", JSONObject()))
            .put("query", listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))

        val connection = (URL(URL_SEARCH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Origin", YTM_ORIGIN)
            setRequestProperty("Referer", "$YTM_ORIGIN/")
            setRequestProperty("X-Goog-Api-Format-Version", "1")
            setRequestProperty("X-YouTube-Client-Name", "67")
            setRequestProperty("X-YouTube-Client-Version", clientVersion)
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "YouTube Music search HTTP ${connection.responseCode}")
                return null
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val candidates = mutableListOf<Candidate>()
            collectCandidates(JSONObject(response), candidates, 0)
            return chooseCandidate(candidates, title, artist, durationMs)?.videoId
        } finally {
            connection.disconnect()
        }
    }

    private fun collectCandidates(node: Any?, output: MutableList<Candidate>, depth: Int) {
        if (node == null || depth > MAX_JSON_DEPTH) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                    candidateFromRenderer(renderer)?.let(output::add)
                }
                node.optJSONObject("musicTwoRowItemRenderer")?.let { renderer ->
                    candidateFromRenderer(renderer)?.let(output::add)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "musicResponsiveListItemRenderer" || key == "musicTwoRowItemRenderer") continue
                    collectCandidates(node.opt(key), output, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectCandidates(node.opt(index), output, depth + 1)
                }
            }
        }
    }

    private fun candidateFromRenderer(renderer: JSONObject): Candidate? {
        val videoId = firstVideoId(renderer) ?: return null
        val title = rendererTitle(renderer).takeIf { it.isNotBlank() } ?: return null
        val texts = mutableListOf<String>()
        collectRunTexts(renderer, texts, 0)
        val duration = texts.asSequence().mapNotNull(::parseDurationSeconds).firstOrNull()
        return Candidate(
            videoId = videoId,
            title = title,
            searchableText = texts.joinToString(" "),
            durationSeconds = duration,
        )
    }

    private fun rendererTitle(renderer: JSONObject): String {
        val flexColumns = renderer.optJSONArray("flexColumns")
        if (flexColumns != null) {
            for (index in 0 until minOf(2, flexColumns.length())) {
                val text = flexColumns.optJSONObject(index)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                if (text.isNotBlank()) return text
            }
        }

        val titleRuns = renderer.optJSONObject("title")?.optJSONArray("runs")
        if (titleRuns != null) {
            for (index in 0 until titleRuns.length()) {
                val text = titleRuns.optJSONObject(index)?.optString("text").orEmpty()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun firstVideoId(node: Any?, depth: Int = 0): String? {
        if (node == null || depth > MAX_VIDEO_ID_DEPTH) return null
        return when (node) {
            is JSONObject -> {
                node.optString("videoId")
                    .takeIf(YOUTUBE_VIDEO_ID::matches)
                    ?: run {
                        val keys = node.keys()
                        var result: String? = null
                        while (keys.hasNext() && result == null) {
                            result = firstVideoId(node.opt(keys.next()), depth + 1)
                        }
                        result
                    }
            }
            is JSONArray -> {
                var result: String? = null
                var index = 0
                while (index < node.length() && result == null) {
                    result = firstVideoId(node.opt(index), depth + 1)
                    index++
                }
                result
            }
            else -> null
        }
    }

    private fun collectRunTexts(node: Any?, output: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_TEXT_DEPTH) return
        when (node) {
            is JSONObject -> {
                val runs = node.optJSONArray("runs")
                if (runs != null) {
                    for (index in 0 until runs.length()) {
                        runs.optJSONObject(index)?.optString("text")
                            ?.takeIf { it.isNotBlank() }
                            ?.let(output::add)
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "runs") continue
                    collectRunTexts(node.opt(key), output, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) collectRunTexts(node.opt(index), output, depth + 1)
            }
        }
    }

    private fun chooseCandidate(
        candidates: List<Candidate>,
        title: String,
        artist: String,
        durationMs: Long,
    ): Candidate? {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        val wantedDuration = durationMs.takeIf { it > 0L }?.div(1000.0)

        val scored = candidates.distinctBy { it.videoId }.map { candidate ->
            val candidateTitle = normalize(candidate.title)
            val candidateText = normalize(candidate.searchableText)

            var score = when {
                candidateTitle == wantedTitle -> 120
                candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> 75
                else -> 0
            }

            if (wantedArtist.isNotBlank()) {
                score += if (candidateText.contains(wantedArtist)) 60 else -30
            }

            if (wantedDuration != null && candidate.durationSeconds != null) {
                score += when (abs(candidate.durationSeconds - wantedDuration)) {
                    in 0.0..2.5 -> 50
                    in 2.5..5.5 -> 30
                    in 5.5..15.0 -> 5
                    else -> -40
                }
            }
            candidate to score
        }

        val best = scored.maxByOrNull { it.second } ?: return null
        if (best.second < MIN_ACCEPT_SCORE) {
            Log.i(TAG, "Catalog identity match rejected: score=${best.second} title=${best.first.title.take(80)}")
            return null
        }
        Log.i(TAG, "Catalog identity resolved: ${best.first.videoId} score=${best.second}")
        return best.first
    }

    private fun parseDurationSeconds(value: String): Int? {
        val match = DURATION_PATTERN.matchEntire(value.trim()) ?: return null
        val parts = match.value.split(':').mapNotNull { it.toIntOrNull() }
        if (parts.size !in 2..3) return null
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private fun signatureKey(title: String, artist: String, durationMs: Long): String =
        "${normalize(title)}|${normalize(artist)}|${durationMs.coerceAtLeast(0L) / 1000L}"

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(NORMALIZE_NOISE, "")

    private fun webRemixClientVersion(): String {
        val formatter = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "1.${formatter.format(Date())}.01.00"
    }

    private const val TAG = "YTMTrackResolver"
    private const val YTM_ORIGIN = "https://music.youtube.com"
    private const val URL_SEARCH = "$YTM_ORIGIN/youtubei/v1/search?prettyPrint=false"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT_MS = 3_500
    private const val READ_TIMEOUT_MS = 4_500
    private const val POSITIVE_CACHE_MS = 24 * 60 * 60 * 1000L
    private const val NEGATIVE_CACHE_MS = 2 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 64
    private const val MAX_JSON_DEPTH = 16
    private const val MAX_VIDEO_ID_DEPTH = 10
    private const val MAX_TEXT_DEPTH = 8
    private const val MIN_ACCEPT_SCORE = 150
    private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val DURATION_PATTERN = Regex("^(?:\\d{1,2}:)?\\d{1,2}:\\d{2}$")
    private val NORMALIZE_NOISE = Regex("[^\\p{L}\\p{N}]+")
}
