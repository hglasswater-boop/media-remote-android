package dev.mediaremote.dial

import org.junit.Assert.*
import org.junit.Test

class LoungePlaylistPayloadTest {
    @Test fun queueSwitchNotifiesFirstItemSeparatelyFromSelectedItem() {
        val payload = loungePlaylistPayload("RQsame", listOf("first", "selected"), "selected", 1, null, null)
        assertEquals("first", payload["firstVideoId"])
        assertEquals("selected", payload["videoId"])
        assertEquals(1, payload["currentIndex"])
        assertEquals("RQsame", payload["listId"])
    }

    @Test fun sameQueueIdCanPublishAReplacementList() {
        val old = loungePlaylistPayload("RQsame", listOf("old"), "old", 0, null, null)
        val new = loungePlaylistPayload("RQsame", listOf("attempt", "next"), "attempt", 0, null, null)
        assertEquals(old["listId"], new["listId"])
        assertEquals("attempt", new["firstVideoId"])
        assertNotEquals(old["videoIds"], new["videoIds"])
    }

    @Test fun repeatedTracksRetainTheirAbsolutePositions() {
        val payload = loungePlaylistPayload("RQsame", listOf("a", "b", "a"), "a", 2, null, null)
        assertEquals("a,b,a", payload["videoIds"])
        assertEquals(2, payload["currentIndex"])
    }

    @Test fun partialListStillHasFirstVideoForAndroidToAcceptNotification() {
        val payload = loungePlaylistPayload("RQsame", emptyList(), "attempt", 0, null, null)
        assertEquals("attempt", payload["firstVideoId"])
        assertFalse(payload.containsKey("videoIds"))
    }

    @Test fun emptyPlaylistDoesNotInventAFirstVideo() {
        val payload = loungePlaylistPayload(null, emptyList(), null, null, null, null)
        assertTrue(payload.isEmpty())
    }
}
