package dev.mediaremote.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoungeBindParamsTest {
    private fun params() = LoungeBindParams("device", "receiver", "model") { values ->
        values.entries.joinToString("&") { "${it.key}=${it.value}" }
    }.apply {
        loungeIdToken = "test-token"
        sid = "test-session"
        gsessionId = "test-global-session"
    }

    @Test fun hoursOfOutgoingPlaybackUpdatesDoNotSkipTheNextIncomingCommand() {
        val params = params()
        params.updateFrom(LoungeMessage(10, "pause", null))
        repeat(18_000) {
            assertTrue(params.sendMessageQuery(null).split('&').contains("AID=10"))
        }
        assertEquals(10, params.aid)
        assertTrue(params.rpcQuery().split('&').contains("AID=10"))
        params.updateFrom(LoungeMessage(11, "play", null))
        assertTrue(params.sendMessageQuery(11).split('&').contains("AID=11"))
        assertTrue(params.rpcQuery().split('&').contains("AID=11"))
    }

    @Test fun olderResponsesDoNotMoveTheReceiveCursorBackwards() {
        val params = params()
        params.updateFrom(LoungeMessage(20, "noop", null))
        assertTrue(params.sendMessageQuery(15).split('&').contains("AID=20"))
        assertEquals(20, params.aid)
    }

    @Test fun aNewBindAcknowledgesOnlyArraysActuallyReceived() {
        val params = params()
        params.updateFrom(LoungeMessage(20, "noop", null))
        params.resetForNewSession()
        assertEquals(-1, params.aid)
        params.updateFrom(LoungeMessage(0, "noop", null))
        assertEquals(0, params.aid)
    }
}
