# Playlist handoff

MediaRemote assumes the controller and playback phones use the same Google account in YouTube Music.

## Pairing

1. Start LAN remote mode on the playback phone.
2. Scan the pairing QR with the controller phone.
3. MediaRemote stores the host, port and pairing token locally.

## Playlist handoff

1. On the controller phone, open YouTube Music.
2. Select a playlist.
3. Use Share and choose MediaRemote.
4. MediaRemote extracts the YouTube Music URL and sends it to the paired playback phone.
5. The playback phone first tries the active YouTube Music MediaSession (`playFromUri`). If unavailable, it falls back to opening the URL in YouTube Music.

The app does not store Google credentials or YouTube API keys.

## Install QR

The playback phone also shows a QR pointing at the rolling signed APK:

`https://github.com/hglasswater-boop/media-remote-android/releases/download/debug-latest/MediaRemote-latest.apk`
