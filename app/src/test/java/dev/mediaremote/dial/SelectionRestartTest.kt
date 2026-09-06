package dev.mediaremote.dial

import dev.mediaremote.media.MediaSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionRestartTest {
    private val baseline = MediaSnapshot(
        available = true,
        title = "Questions",
        artist = "Far Caspian",
        playbackState = 3,
        positionMs = 16_448,
        positionUpdatedAtMs = 100_000,
        queueIndex = 0,
        queueSize = 25,
    )

    @Test fun sameQuestionsQueueCanAcknowledgeRestartWithoutTitleOrQueueChange() {
        val restarted = baseline.copy(positionMs = 250, positionUpdatedAtMs = 116_500)
        assertTrue(selectionRestartObserved(baseline, restarted, 0))
    }

    @Test fun stalePlayerTimestampCannotAcknowledgeSelection() {
        assertFalse(selectionRestartObserved(baseline, baseline.copy(positionMs = 0), 0))
        assertFalse(selectionRestartObserved(baseline,
            baseline.copy(positionMs = 0, positionUpdatedAtMs = 99_999), 0))
    }

    @Test fun ordinaryProgressOrBufferingDoesNotAcknowledgeRestart() {
        assertFalse(selectionRestartObserved(baseline,
            baseline.copy(positionMs = 17_000, positionUpdatedAtMs = 117_000), 0))
        assertFalse(selectionRestartObserved(baseline,
            baseline.copy(positionMs = 0, positionUpdatedAtMs = 117_000, playbackState = 6), 0))
    }

    @Test fun missingOrEmptySelectionDoesNotAcknowledgeRestart() {
        val restarted = baseline.copy(positionMs = 0, positionUpdatedAtMs = 117_000)
        assertFalse(selectionRestartObserved(baseline, restarted, null))
        assertFalse(selectionRestartObserved(baseline, restarted.copy(title = ""), 0))
        assertFalse(selectionRestartObserved(baseline.copy(positionUpdatedAtMs = 0), restarted, 0))
    }

    @Test fun resumedSelectionMustMatchRequestedPosition() {
        val restarted = baseline.copy(positionMs = 30_500, positionUpdatedAtMs = 117_000)
        assertTrue(selectionRestartObserved(baseline, restarted, 30_000))
        assertFalse(selectionRestartObserved(baseline, restarted, 0))
    }
}
