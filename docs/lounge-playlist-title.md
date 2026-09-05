# Sender playlist title: open compatibility issue

On 2026-09-05, the user reported that selecting a Favorite Songs track with a
genuine Chromecast keeps the Favorite Songs name on the controller, whereas
casting to YT Music Remote changes it to the generic playback queue. This
contradicts the previous README claim that correct track/position alone makes
that behavior normal. Playlist-title synchronization remains incomplete.

## Evidence

- `YouTubeLoungeSession.handlePlaylistMessage` reads `listId`, video IDs, index,
  `ctt`, and `params`. It does not inspect `videoEntry` or `videoEntries`.
- The existing complete 0.6.22 selection captures contain a `videoEntry` key,
  but diagnostics only captured the key name, not its value. The incoming
  `listId` is an RQ queue ID. These captures do **not** prove that no original
  playlist reference was sent.
- Local inspection of YouTube Music 9.34.52's sender implementation shows that
  `videoEntry` can be a JSON string containing `videoId` and the separate
  `sourceContainerPlaylistId`. Queue entries may also use `videoEntries`.
- Google's current TV receiver code parses the same field and builds
  `watchEndpoint.watchEndpointMdxConfig.mdxPlaybackSourceContext` containing
  `mdxPlaybackContainerInfo.sourceContainerPlaylistId`. Its player adapter
  forwards this source context separately from the queue playlist ID.
- Google's receiver builds outbound `nowPlaying` from its loaded/current watch
  endpoint. It sends that endpoint's playlist ID, index, and parameters; a
  guessed `playlistTitle` field is not an established correction.

Primary implementation inspected from [YouTube TV](https://www.youtube.com/tv),
build `youtube.kabuki.web_20260901_15_RC00`, with its MDX modules and linked
`/s/player/f572e43c/tv-player-ias.vflset/tv-player-ias.js`, retrieved 2026-09-05.
The public [reference receiver](https://github.com/patrickkfkan/yt-cast-receiver/blob/master/src/lib/app/Message.ts)
also echoes queue context in `nowPlaying`; it does not settle title behavior.
Third-party source copies and device traces remain outside this repository.

A fresh USB package check on 2026-09-05 found YouTube Music **9.35.54** on the
playback phone. The 0.6.27 adapter enables the native RQ handoff on **9.34.52**
and **9.35.54**, whose MediaItemInfo/WatchEndpoint parser layout matches the
captured wire format. Incoming diagnostics still run before the playback
dispatch and identify the source context without logging opaque values.

A read-only metadata comparison using an old captured RQ, with and without a
known original playlist in `mdxContext.mdxPlaybackSourceContext`, did not restore
that playlist in the response. The old RQ no longer returned its queue contents.
This does not validate an Android handoff or the original-title hypothesis.

## Next discriminating check

Install the extended diagnostic build with the existing signing certificate,
enable the opt-in trace, reconnect the controller, and select a track inside
Favorite Songs. Record the controller's title and selected track. Inspect the
new trace for that selection's `videoEntry.sourceContainerPlaylistId` (or the
corresponding entry in `videoEntries`), separately from `listId`.

If an original playlist ID is supplied, verify the native handoff's source-context
field mapping and compare the receiver's resulting watch context with the
controller's display. If it is absent, compare the Chromecast connection path
and the sender's queue metadata before changing playback behavior. Do not
substitute a user's playlist globally, relabel an RQ, or consider an HTTP 200 or
a correct song title proof of playlist-name synchronization.

The current patch also applies a fresh selection's requested position after the
new MediaSession track is confirmed, including `currentTime=0`; this prevents a
previous track's final position from appearing as an immediate seek to the end.
It does not claim to restore the controller's playlist name.

Pre-publication validation: local `testDebugUnitTest` and `lintDebug` completed
successfully. Version 0.6.27 contains the diagnostics and playback correction;
end-to-end sender title and position verification still requires the signed APK
on the connected phone.
