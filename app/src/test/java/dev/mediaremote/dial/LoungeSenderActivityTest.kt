package dev.mediaremote.dial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoungeSenderActivityTest {
    @Test
    fun onlyActualSenderCommandsStartPeriodicSync() {
        assertFalse(isSenderActivityMessage("noop"))
        assertFalse(isSenderActivityMessage("loungeStatus"))
        assertFalse(isSenderActivityMessage("unknown"))
        assertTrue(isSenderActivityMessage("setPlaylist"))
        assertTrue(isSenderActivityMessage("getNowPlaying"))
        assertTrue(isSenderActivityMessage("play"))
    }
}
