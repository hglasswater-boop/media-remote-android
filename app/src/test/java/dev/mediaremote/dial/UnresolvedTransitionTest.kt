package dev.mediaremote.dial

import android.media.session.PlaybackState
import dev.mediaremote.media.MediaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnresolvedTransitionTest {
    @Test fun freezesPreviousPlayingClockWithoutChangingItsIdentity() {
        val previous = MediaSnapshot(
            available = true,
            mediaId = "wxfSBOrRz9s",
            title = "Voodoo?",
            playing = true,
            playbackState = PlaybackState.STATE_PLAYING,
            playbackSpeed = 1f,
            positionMs = 254_900,
            durationMs = 255_000,
        )

        val frozen = unresolvedTransitionSnapshot(previous)

        assertEquals(previous.mediaId, frozen.mediaId)
        assertEquals(previous.title, frozen.title)
        assertEquals(254_900L, frozen.positionMs)
        assertEquals(PlaybackState.STATE_BUFFERING, frozen.playbackState)
        assertEquals(0f, frozen.playbackSpeed)
        assertFalse(frozen.playing)
    }

    @Test fun clampsAlreadyExtrapolatedPositionToDuration() {
        val frozen = unresolvedTransitionSnapshot(
            MediaSnapshot(available = true, positionMs = 274_000, durationMs = 255_000),
        )

        assertEquals(255_000L, frozen.positionMs)
    }
}
