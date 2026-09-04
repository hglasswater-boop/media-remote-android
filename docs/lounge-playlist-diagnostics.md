# Opt-in playlist diagnostics (0.6.17)

This build does not change playback routing or synchronization. It adds a device-only,
disabled-by-default trace before `setPlaylist` / `updatePlaylist` parsing.

Enable for a short USB reproduction:

```text
adb shell setprop log.tag.LoungePlaylistTrace DEBUG
adb logcat -v time LoungePlaylistTrace:D YouTubeLoungeSession:I MediaSessionBridge:I YTMTrackResolver:I MediaNotificationListener:I *:S
```

After collecting the reproduction, disable the trace:

```text
adb shell setprop log.tag.LoungePlaylistTrace INFO
```

The trace contains raw `videoId`, `listId`, `currentIndex`, `videoIds`, `ctt`,
`params`, and `currentTime`, plus the names (not values) of other payload fields.
Missing fields, explicit nulls, and original JSON types are preserved.
RPC envelopes and authentication/pairing values outside that allowlist are not dumped.
Treat `ctt` / `params` as potentially sensitive opaque data: keep captures local and
do not attach them to public issues or commits without reviewing/redacting them.

Each JSON document has a process-local trace number, Lounge `aid`, message name,
numbered chunks, original character length, and truncation flag. Reassemble the
`json=` portions in order. Documents longer than 65,536 characters are explicitly
marked truncated and must not be interpreted as complete evidence. Logging is
bounded and must not cause a playback exception.

Compare a sender selection with the original playlist link. An `RQ` identifier is
not automatically interchangeable with a shareable `PL` identifier. Success from
`playFromUri` alone is also insufficient: verify the resulting song and displayed
playlist context independently.
