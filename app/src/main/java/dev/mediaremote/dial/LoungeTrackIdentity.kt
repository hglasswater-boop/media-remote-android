package dev.mediaremote.dial

import dev.mediaremote.media.MediaSnapshot

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

internal fun pendingSelectionConfirmed(
    expectedVideoId: String,
    directVideoId: String?,
    requiresTransition: Boolean,
    transitioned: Boolean,
): Boolean = (!requiresTransition && directVideoId == expectedVideoId) || transitioned

internal fun trackIdentityChanged(previous: MediaSnapshot, current: MediaSnapshot): Boolean {
    // Queue replacements count even when two entries have identical visible metadata.
    val comparableQueueItems = minOf(previous.queueWindow.size, current.queueWindow.size, 3)
    if (comparableQueueItems >= 2 && (0 until comparableQueueItems).any { index ->
            previous.queueWindow[index].queueId > 0L &&
                current.queueWindow[index].queueId > 0L &&
                previous.queueWindow[index].queueId != current.queueWindow[index].queueId
        }
    ) return true

    if (previous.queueSize > 1 && previous.queueSize == current.queueSize &&
        previous.queueIndex >= 0 && current.queueIndex >= 0 &&
        previous.queueIndex != current.queueIndex
    ) return true

    val previousId = previous.mediaId.takeIf(VIDEO_ID::matches)
    val currentId = current.mediaId.takeIf(VIDEO_ID::matches)
    if (previousId != null && currentId != null && previousId != currentId) return true

    val previousTitle = previous.title.trim().lowercase()
    val currentTitle = current.title.trim().lowercase()
    if (previousTitle.isNotBlank() && currentTitle.isNotBlank() && previousTitle != currentTitle) return true
    val previousArtist = previous.artist.trim().lowercase()
    val currentArtist = current.artist.trim().lowercase()
    return previousArtist.isNotBlank() && currentArtist.isNotBlank() && previousArtist != currentArtist
}

internal fun canPublishPositionState(
    videoConfirmed: Boolean,
    confirmedSnapshot: MediaSnapshot?,
    freshSnapshot: MediaSnapshot,
): Boolean = videoConfirmed && confirmedSnapshot != null &&
    !trackIdentityChanged(confirmedSnapshot, freshSnapshot)
