package dev.mediaremote.media

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicQueueMediaIdTest {
    private fun decodedHex(index: Int?): String = Base64.getUrlDecoder()
        .decode(YouTubeMusicQueueMediaId.encode("abcdefghijk", "RQtest", index))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun embedsWatchEndpointWithZeroBasedIndex() {
        // Independently specified wire fixture: MediaItemInfo{1:video,3:Command{
        // 48687757:WatchEndpoint{1:video,2:RQtest,3:25}}}. No URL index conversion.
        assertEquals(
            "0a0b6162636465666768696a6b1a1deaa8ddb90117" +
                "0a0b6162636465666768696a6b12065251746573741819",
            decodedHex(25),
        )
    }

    @Test fun absentIndexIsNotEncodedAsZero() {
        assertEquals(
            "0a0b6162636465666768696a6b1a1beaa8ddb90115" +
                "0a0b6162636465666768696a6b1206525174657374",
            decodedHex(null),
        )
        assertEquals(decodedHex(25).dropLast(2) + "00", decodedHex(0))
    }

    @Test fun loungeQueueAdapterAlwaysOmitsNativeIndex() {
        val actual = Base64.getUrlDecoder()
            .decode(YouTubeMusicQueueMediaId.encodeLoungeQueue("abcdefghijk", "RQtest"))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(
            "0a0b6162636465666768696a6b1a1beaa8ddb90115" +
                "0a0b6162636465666768696a6b1206525174657374",
            actual,
        )
    }

    @Test fun indexUsesUnsignedVarintBeyondOneByte() {
        assertEquals(
            "0a0b6162636465666768696a6b1a1eeaa8ddb90118" +
                "0a0b6162636465666768696a6b120652517465737418ac02",
            decodedHex(300),
        )
    }

    @Test fun rejectsNonQueueAndMalformedIdentifiers() {
        for (list in listOf("PLtest", "RQ", "rqtest", "RQ/other", "RQ" + "a".repeat(255))) {
            assertNull(YouTubeMusicQueueMediaId.encode("abcdefghijk", list, 0))
        }
        for (video in listOf("", "short", "abcdefghij/", "abcdefghijkl")) {
            assertNull(YouTubeMusicQueueMediaId.encode(video, "RQtest", 0))
        }
        assertNull(YouTubeMusicQueueMediaId.encode("abcdefghijk", "RQtest", -1))
    }

    @Test fun usesUnpaddedUrlSafeBase64() {
        val encoded = YouTubeMusicQueueMediaId.encode("-EGCD9iwH-Y", "RQ_-test", Int.MAX_VALUE)!!
        assertFalse(encoded.any { it == '=' || it == '+' || it == '/' || it.isWhitespace() })
        assertEquals(encoded, Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Base64.getUrlDecoder().decode(encoded)))
    }
}
