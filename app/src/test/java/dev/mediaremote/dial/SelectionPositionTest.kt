package dev.mediaremote.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionPositionTest {
    @Test fun preservesZeroStartPosition() {
        assertEquals(0L, selectionPositionMs(0.0))
    }

    @Test fun convertsSecondsToMilliseconds() {
        assertEquals(1_234L, selectionPositionMs(1.234))
    }

    @Test fun rejectsMissingNegativeAndNonFiniteValues() {
        assertNull(selectionPositionMs(null))
        assertNull(selectionPositionMs(-1.0))
        assertNull(selectionPositionMs(Double.NaN))
        assertNull(selectionPositionMs(Double.POSITIVE_INFINITY))
    }
}
