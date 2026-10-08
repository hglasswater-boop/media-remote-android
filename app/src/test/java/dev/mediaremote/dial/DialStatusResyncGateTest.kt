package dev.mediaremote.dial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialStatusResyncGateTest {
    @Test
    fun regularPollsDoNotRestartFullStateRefresh() {
        val gate = DialStatusResyncGate()
        assertTrue(gate.shouldResync("192.168.0.168", 1_000L))
        for (second in 2..60) {
            assertFalse(gate.shouldResync("192.168.0.168", second * 1_000L))
        }
    }

    @Test
    fun firstPollAfterQuietPeriodRefreshesOnce() {
        val gate = DialStatusResyncGate()
        assertTrue(gate.shouldResync("192.168.0.168", 1_000L))
        assertFalse(gate.shouldResync("192.168.0.168", 6_000L))
        assertTrue(gate.shouldResync("192.168.0.168", 36_000L))
        assertFalse(gate.shouldResync("192.168.0.168", 36_100L))
    }

    @Test
    fun anotherPollerDoesNotHideAQuietSender() {
        val gate = DialStatusResyncGate()
        assertTrue(gate.shouldResync("192.168.0.168", 1_000L))
        assertTrue(gate.shouldResync("192.168.0.186", 2_000L))
        assertFalse(gate.shouldResync("192.168.0.186", 20_000L))
        assertTrue(gate.shouldResync("192.168.0.168", 31_000L))
    }
}
