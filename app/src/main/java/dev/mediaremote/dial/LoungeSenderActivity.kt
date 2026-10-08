package dev.mediaremote.dial

/** Exclude bind keepalives and receiver status events from sender liveness checks. */
private val SENDER_ACTIVITY_MESSAGES = setOf(
    "setPlaylist",
    "updatePlaylist",
    "play",
    "pause",
    "stopVideo",
    "next",
    "previous",
    "seekTo",
    "getNowPlaying",
    "getPlaylist",
    "getVolume",
    "getSubtitlesTrack",
)

internal fun isSenderActivityMessage(name: String): Boolean = name in SENDER_ACTIVITY_MESSAGES
