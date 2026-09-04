# Playlist handoff

Playlist handoff is supported only through the native YouTube Music Cast picker.

1. Install YT Music Remote on the playback phone.
2. Allow notification access on the playback phone.
3. Open YT Music Remote; Cast listening starts automatically.
4. Put both phones on the same LAN / Wi-Fi.
5. On the controller phone, open YouTube Music and choose `YT Music Remote <device>` from the Cast picker.
6. Select the playlist and the desired song in YouTube Music.

The sender supplies the Lounge `videoId`, queue `listId`, index, and queue IDs. The playback phone
passes the selected queue item to YouTube Music through its MediaSession and reports the resulting
now-playing state back through Lounge.

The controller phone does not need YT Music Remote. The YouTube Music Cast picker is the only
supported controller entry point.
