package dev.mediaremote.media

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Narrow adapter for the MediaItemInfo accepted by YouTube Music 9.34.52 and 9.35.54.
 *
 * RQ watch URLs lose their playlist at navigation/resolve_url. An embedded WatchEndpoint avoids
 * that resolver. This is an internal, version-specific format, not a public Android contract.
 * Only videoId, playlistId and the zero-based Lounge index have verified field mappings. In
 * particular, do not guess protobuf fields for ctt/params or log their credential-bearing values.
 * The two supported builds expose the same MediaItemInfo/WatchEndpoint parser layout.
 * See docs/lounge-queue-handoff.md for device evidence and remaining limitations.
 */
internal object YouTubeMusicQueueMediaId {
    const val VERIFIED_VERSION = "9.34.52"
    const val VERIFIED_VERSION_9_35_54 = "9.35.54"
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val queueIdPattern = Regex("RQ[A-Za-z0-9_-]{1,254}")

    fun supportsVersion(version: String?): Boolean =
        version == VERIFIED_VERSION || version == VERIFIED_VERSION_9_35_54

    fun encode(videoId: String, playlistId: String, index: Int?): String? {
        if (!videoIdPattern.matches(videoId) || !queueIdPattern.matches(playlistId)) return null
        if (index != null && index < 0) return null
        val watch = ByteArrayOutputStream().apply {
            bytesField(1, videoId.toByteArray(Charsets.UTF_8))
            bytesField(2, playlistId.toByteArray(Charsets.UTF_8))
            if (index != null) {
                varint(3L shl 3)
                varint(index.toLong())
            }
        }.toByteArray()
        val command = ByteArrayOutputStream().apply { bytesField(48687757, watch) }.toByteArray()
        val item = ByteArrayOutputStream().apply {
            bytesField(1, videoId.toByteArray(Charsets.UTF_8))
            bytesField(3, command)
        }.toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(item)
    }

    /**
     * RQ playback is anchored by its requested video. Including the Lounge position in
     * WatchEndpoint field 3 selected a different same-title item on the supported YTM builds;
     * omit it.
     */
    fun encodeLoungeQueue(videoId: String, playlistId: String): String? =
        encode(videoId, playlistId, index = null)

    private fun ByteArrayOutputStream.bytesField(number: Int, value: ByteArray) {
        varint((number.toLong() shl 3) or 2)
        varint(value.size.toLong())
        write(value)
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var remaining = value
        do {
            val next = remaining and 0x7f
            remaining = remaining ushr 7
            write(next.toInt() or if (remaining != 0L) 0x80 else 0)
        } while (remaining != 0L)
    }
}
