package dev.mediaremote.dial

import dev.mediaremote.media.MediaQueueWindowItem
import dev.mediaremote.media.MediaSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoungeTrackIdentityTest {
    private val confirmed = MediaSnapshot(
        available = true, mediaId = "jvz8VARzV0Q", title = "Original Girl", artist = "Emmett Kai",
        queueIndex = 0, queueSize = 25,
        queueWindow = listOf(
            MediaQueueWindowItem(100, "Original Girl", "Emmett Kai"),
            MediaQueueWindowItem(101, "Paradigm", "Pink Laces"),
        ),
    )

    @Test fun newLocalTrackCannotBeSentAsAPositionUpdateForThePreviousVideo() {
        val next = confirmed.copy(mediaId = "", title = "Paradigm", artist = "Pink Laces", queueIndex = 1)
        assertFalse(canPublishPositionState(true, confirmed, next))
    }

    @Test fun sameTitleQueueMoveStillWaitsForTrackConfirmation() {
        assertFalse(canPublishPositionState(true, confirmed, confirmed.copy(mediaId = "", queueIndex = 1)))
    }

    @Test fun missingRawIdDoesNotBlockAnUnchangedConfirmedTrack() {
        assertTrue(canPublishPositionState(true, confirmed, confirmed.copy(mediaId = "", positionMs = 60_000)))
    }

    @Test fun pendingSelectionCannotPublishItsUnconfirmedPosition() {
        assertFalse(canPublishPositionState(false, confirmed, confirmed))
        assertFalse(canPublishPositionState(true, null, confirmed))
    }
}
