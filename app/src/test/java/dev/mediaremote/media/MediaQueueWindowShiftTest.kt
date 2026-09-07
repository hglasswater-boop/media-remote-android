package dev.mediaremote.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaQueueWindowShiftTest {
    private fun item(id: Long) = MediaQueueWindowItem(id, "title-$id", "artist-$id")

    @Test fun findsOneTrackSlidingWindowAdvance() {
        assertEquals(
            1,
            MediaQueueWindowShift.forwardShift(
                listOf(item(1264), item(1265), item(1266), item(1267)),
                listOf(item(1265), item(1266), item(1267), item(1268)),
            ),
        )
    }

    @Test fun findsMovementInsideStableFixedWindow() {
        val queue = listOf(item(829), item(830), item(831), item(832), item(833))
        assertEquals(1, MediaQueueWindowShift.fixedWindowMove(queue, queue, 1, 2))
        assertEquals(-1, MediaQueueWindowShift.fixedWindowMove(queue, queue, 3, 2))
    }

    @Test fun refusesFixedWindowMoveWithoutStableQueueProof() {
        val before = listOf(item(829), item(830), item(831), item(832))
        val rebuilt = listOf(item(900), item(901), item(902), item(903))
        assertNull(MediaQueueWindowShift.fixedWindowMove(before, rebuilt, 1, 2))
        assertNull(MediaQueueWindowShift.fixedWindowMove(before, before, 1, 1))
        assertNull(MediaQueueWindowShift.fixedWindowMove(before, before, 0, 3, maxMove = 2))
    }

    @Test fun findsMultiTrackAdvanceOnlyWithConsecutiveIds() {
        assertEquals(
            2,
            MediaQueueWindowShift.forwardShift(
                listOf(item(10), item(11), item(12), item(13), item(14)),
                listOf(item(12), item(13), item(14), item(15)),
            ),
        )
    }

    @Test fun refusesInsufficientOrUnrelatedWindows() {
        assertNull(MediaQueueWindowShift.forwardShift(listOf(item(1), item(2)), listOf(item(2), item(3))))
        assertNull(MediaQueueWindowShift.forwardShift(listOf(item(1), item(2), item(3)), listOf(item(9), item(2), item(3))))
        assertNull(MediaQueueWindowShift.forwardShift(listOf(item(1), item(2), item(3)), listOf(item(-2), item(3), item(4))))
    }

    @Test fun doesNotMistakeRebuiltSameTitleQueueForAForwardShift() {
        val before = listOf(
            MediaQueueWindowItem(100, "Questions", "Maeta"),
            MediaQueueWindowItem(101, "On Your Side", "Milo"),
            item(102),
        )
        val after = listOf(
            MediaQueueWindowItem(200, "Questions", "Maeta"),
            MediaQueueWindowItem(201, "On Your Side", "Milo"),
            item(202),
        )
        assertNull(MediaQueueWindowShift.forwardShift(before, after))
    }
}
