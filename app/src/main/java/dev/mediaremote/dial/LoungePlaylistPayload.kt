package dev.mediaremote.dial

/** Android Music ignores a nonempty playlistModified.videoId without firstVideoId. */
internal fun loungePlaylistPayload(
    listId: String?,
    videoIds: List<String>,
    videoId: String?,
    index: Int?,
    ctt: String?,
    params: String?,
): Map<String, Any> = linkedMapOf<String, Any>().apply {
    listId?.let { put("listId", it) }
    if (videoIds.isNotEmpty()) put("videoIds", videoIds.joinToString(","))
    videoId?.let { put("videoId", it) }
    // The first item is separate from the playing item, which can be in the middle of the list.
    (videoIds.firstOrNull() ?: videoId)?.let { put("firstVideoId", it) }
    index?.let { put("currentIndex", it) }
    ctt?.let { put("ctt", it) }
    params?.let { put("params", it) }
}
