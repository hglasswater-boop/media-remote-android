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
| WatchEndpoint | 1: video ID; 2: playlist ID; 3: internal index (not used for RQ playback) |

Serialize protobuf then URL-safe base64 without padding/wrapping. Fields 1/2
were verified against the installed client's generated schema and parsing code.
Although field3 exists, it must be omitted for RQ playback: a controlled device
comparison for the same incoming Far Caspian `Questions` video/RQ showed that
including Lounge index22 selected the wrong item, while omitting it selected
Questions / Far Caspian. Do not convert, decrement or otherwise forward the
Lounge position into this private endpoint format.

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

- Internal format: enabled only on verified YTM versions **9.34.52** and
  **9.35.54**, with an active controller advertising `ACTION_PLAY_FROM_MEDIA_ID`.
  Other versions fail this
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
  sender's playlist title still require end-to-end testing after installation.
- For a fresh `setPlaylist`, the receiver now waits for the requested track to be
  confirmed in MediaSession before applying `currentTime`, including zero. This
  prevents the previous track's final position from being clamped to the new
  track's duration and shown as an immediate seek to the end.
- Empty-identity transition publications and ambiguous local catalog identities
  are separate issues; this change does not claim to fix them.

## Follow-up: native RQ index omission (0.6.22)

`playFromMediaId` uses the requested video ID as the RQ anchor. Retain the Lounge
index for outgoing `nowPlaying`/queue context, but omit it from the native
MediaItemInfo. A same-device controlled dispatch with `videoId=mO5kvldneUM`
and the active RQ confirmed Questions / Far Caspian only without field3. This
is a version-scoped interoperability observation, not a claim about the public
meaning of field3.

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

## Follow-up: duplicate selection messages (0.6.20)

Lounge can emit two identical `setPlaylist` messages a fraction of a second apart.
The second can arrive after the MediaSession has begun changing, so replacing the
selection baseline then makes a real transition look unchanged. While the same
target is within the four-second pending-selection guard, retain the first
baseline and command; update the received context but do not dispatch or seek
again. A later intentional same-song selection is not suppressed after the guard.

## Follow-up: same-title queue replacement (0.6.21)

`Questions` demonstrated that a sender can select a different video/queue while
the receiver is already displaying the same title and artist. A title transition
alone cannot confirm it. The MediaSession queue item IDs changed for the first
two visible entries when YTM installed the requested queue; treat that as a
transition for the pending sender target. This lets the received target ID win
over a stale title-based catalog cache. It is not a title-only heuristic.

## Validation procedure

### Questions identity regression (0.6.28)

On 2026-09-06 / YTM 9.35.54, the sender requested `mO5kvldneUM` at index 22.
The receiver confirmed that selection on the transition to Questions, then immediately
deferred nowPlaying with an empty mediaId. Catalog fallback published `7NR7khHSGfM`
without playlist context, and the sender returned a 48-item queue instead of 47.

The publication path reconciled the same snapshot twice, before committing the new
baseline. The second pass invalidated the identity confirmed by the first pass.
Reconcile once before publication; route getNowPlaying through that same synchronized
path, and serialize selection updates and identity reconciliation with publication.

Device regression check: select the white-cover Questions from a fresh sender queue;
verify the first and subsequent nowPlaying keep `mO5kvldneUM`, the received list/index,
and a position near zero. Verify the sender artwork and absence of an inserted Questions
separately. Existing queues may already contain an item inserted by older builds.

`diagnostics/QuestionsIdentityRegression.java` exercises the actual APK's reconciliation
and publication methods with the captured empty-mediaId transition. It uses in-memory
preferences and an inactive session, so it does not touch playback, app data, or the network.
Compile with javac against android.jar, dex the resulting classes with d8, and push the
dex plus the APK to `/data/local/tmp`. Run with
`CLASSPATH=/data/local/tmp/questions-regression.dex:/data/local/tmp/questions-after.apk app_process /system/bin QuestionsIdentityRegression`.
On Sony 802SO, 0.6.27 b1062 fails with "Publication discarded the selected Questions
identity"; the 0.6.28 debug APK passes. This checks publication, not sender UI rendering.

### Same-track restart (0.6.29)

The 0.6.28 device retest exposed a second path: selecting Questions while Questions
was already playing reset playback from about 16 seconds to zero without changing
the title or queue IDs. The four-second guard expired and catalog search again
published `cDSzXrgc7YU`. Include the player's monotonic position-update timestamp
in snapshots and recognize a fresh playing/paused update near the requested position.
An unchanged timestamp, normal progress, or a buffering snapshot cannot acknowledge
this restart. The diagnostic also exercises this same-track case with a stale cached
mediaId; unit tests cover stale timestamps, buffering, progress, and nonzero positions.

### Same-RQ playlist replacement (0.6.30)

The user selected Attempt (`qnqBSZ_m6-4`) from メロウ, while the sender header remained
Favorite Songs. The captured setPlaylist replaced the contents with 57 videos but reused
the same RQ ID. Its videoEntry contained serializedMdxMetadata and no sourceContainerPlaylistId.
Both anonymous and credentialed TV /next returned the generic title キュー; the credentialed
response's expanded video list exactly matched all 57 received IDs. Do not substitute a
guessed original PL ID or hard-code the display name.

Inspection of YTM 9.35.54's playlistModified handler found a concrete missing field:
when videoId is nonempty and firstVideoId is empty, the handler skips its playlist-change
event. Supply firstVideoId separately from the current videoId, and proactively send the
notification after a new selection or changed queue has been confirmed. Do not notify on
each progress tick or on an unchanged sparse update. Preserve repeated video IDs so the
sender's absolute indexes remain valid. The header refresh remains a device-validation
candidate until the sender screen is checked after installing this build.

### Source-context diagnosis (0.6.31)

The user's 0.6.30 retest still showed Favorite Songs for Attempt from メロウ.
Do not treat the firstVideoId change as a fix for the header. In the inspected
9.35.54 client, remote queue synchronization fetches the items, and its completion
skips playback navigation when the RQ ID equals the current playlist ID.

The earlier trace omitted playerParams and serializedMdxMetadata, which the stock
TV receiver carries into its WatchEndpoint source context. Therefore the earlier
/next probes did not reproduce the complete request and cannot rule out a title
in that context. 0.6.31 changes diagnostics only, not playback behavior. During
bounded USB diagnosis, enabling both LoungePlaylistTrace and LoungeSourceTrace
at DEBUG records the selected entry's opaque metadata, playerParams, and listCtt
for an authorized read-only YouTube query. Both are off by default. Do not log
opaque context for all queue entries, publish the trace, or send it elsewhere.
Reset both tags to INFO when capture ends. A new selection is needed because the
missing fields cannot be recovered from the previous allowlisted trace.

Build/test/lint. Update with the same signing certificate (do not clear app data).
Capture the documented diagnostic tags. Reconnect the sender and select a new
song from Favorite Songs, recording title/artist. Correlate incoming IDs/index,
`RQ playFromMediaId dispatched`, detected metadata/queue and outbound nowPlaying
HTTP result. Compare subsequent queue titles with the incoming/server queue;
verify the sender screen separately. HTTP 200 alone is not UI verification.

Credential-bearing trace files and decompiled third-party client files stay
outside the repository. Unit tests cover only the original adapter's wire format
and validation, not an emulation of YouTube's service.
