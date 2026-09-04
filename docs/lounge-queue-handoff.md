# RQ queue handoff: 0.6.18 device-validation build

## Confirmed failure boundary

On Sony802SO / Android 10 with YouTube Music **9.34.52**, a Lounge `setPlaylist`
selected the expected song but `playFromUri` produced a song-based mix, not the
sender's queue. Changing HTTPS to `vnd.youtube.music` did not repair it.

A read-only `navigation/resolve_url` comparison using the matching Android Music
client version returned only a video watch endpoint for RQ URLs (with and without
index/ctt/params). An ordinary PL playlist control retained `playlistId`. The
installed client's URI callback uses this resolver. The anonymous backend probe
is not identical to the device's authenticated context, but agrees with its UI.

## Narrow correction

For RQ selections only, use `playFromMediaId` with an embedded WatchEndpoint:

| Message | Wire fields |
| --- | --- |
| MediaItemInfo | 1: video/server ID; 3: Command |
| Command | 48687757: WatchEndpoint |
| WatchEndpoint | 1: video ID; 2: playlist ID; 3: zero-based index (if present) |

Serialize protobuf then URL-safe base64 without padding/wrapping. These fields
were verified against the installed client's generated schema and parsing code.
Its URL parser subtracts one from a URL index; the embedded endpoint is already
zero-based, matching Lounge, so this adapter must NOT subtract one.

A temporary ADB-shell MediaSession diagnostic (no production app changes) sent
the selected video and the received RQ through this encoding. The local queue
then matched all 15 consecutive titles available in the TV `/next` sample,
including later original-playlist songs. This is stronger evidence than the
displayed playlist title alone. A subsequent dispatch with index 25 retained the
selected song/queue; this does not independently prove arbitrary-index behavior.

The sampled RQ already contained extra mix tracks inserted by earlier faulty
playback. Transferring that queue cannot undo its previous mutation or prove that
the original PL playlist ID/name has been recovered. No RQ-to-PL substitution is
performed, and no local synthetic queue is installed.

## Compatibility and unresolved limitations

- Internal format: enabled only on verified YTM version **9.34.52**, with an active
  controller advertising `ACTION_PLAY_FROM_MEDIA_ID`. Other versions fail this
  RQ command explicitly; ordinary PL/song URI behavior remains unchanged.
- `ctt` and `params` remain stored in Lounge state but are **not forwarded** by
  this adapter. Their native field mappings are unverified. The successful probe
  used the existing device account. Cross-account/private-queue access and these
  parameters' additional semantics remain unverified.
- Never silently fall back to the URI route for an RQ: that route is known to
  discard the queue. A dispatch exception/unavailable transport returns false.
- Logs distinguish dispatch from acceptance, include IDs/index and presence flags
  only, and do not include encoded media IDs or opaque credential values.
- Playback acceptance, following tracks, sender display, reconnection, and the
  reported seek-to-end issue still require end-to-end testing after installation.
- Empty-identity transition publications and ambiguous local catalog identities
  are separate issues; this change does not claim to fix them.

## Follow-up: local Next handling (0.6.19)

The player exposes a 25-item **sliding queue window**, not the full Lounge queue.
After `Next`, its active item and window index are both zero, while the first two
or more queue IDs are the previous window shifted left. Use that two-consecutive-ID
proof to advance the stored absolute Lounge index and select the corresponding
received video ID. Do not use title-only matching or catalog search in that case.

This is deliberately limited to a verified forward overlap (at most five items).
Random selections, rewrites, short/unrelated windows and invalid queue IDs still
fall through to the existing media-ID/catalog logic. The RQ display-name limitation
is unchanged: Lounge supplies an RQ queue ID, not the sender's original PL title.

## Validation procedure

Build/test/lint. Update with the same signing certificate (do not clear app data).
Capture the documented diagnostic tags. Reconnect the sender and select a new
song from Favorite Songs, recording title/artist. Correlate incoming IDs/index,
`RQ playFromMediaId dispatched`, detected metadata/queue and outbound nowPlaying
HTTP result. Compare subsequent queue titles with the incoming/server queue;
verify the sender screen separately. HTTP 200 alone is not UI verification.

Credential-bearing trace files and decompiled third-party client files stay
outside the repository. Unit tests cover only the original adapter's wire format
and validation, not an emulation of YouTube's service.
