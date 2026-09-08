# YT Music Remote

[日本語](README.md)

![Android CI](https://github.com/hglasswater-boop/media-remote-android/actions/workflows/android.yml/badge.svg)
![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)

**An unofficial Android app that lets one YouTube Music device remotely control another, using a Cast-like flow.**

You only install YT Music Remote on the playback Android device. The controller keeps using the regular YouTube Music app and selects `YT Music Remote <device name>` as a Cast target.

> [!IMPORTANT]
> This project is not an official Google, YouTube, or YouTube Music product. It relies on compatibility with undocumented DIAL / YouTube Lounge behavior, so future YouTube Music changes may break compatibility.

## How it works

```text
Controller Android
YouTube Music
     |
     | Cast / DIAL + YouTube Lounge
     v
Playback Android
YT Music Remote
     |
     | Android MediaSession
     v
YouTube Music
```

YT Music Remote does not store Google account credentials, cookies, or passwords. It does not spoof Google Cast device certificates and does not use Accessibility automation to drive the YouTube Music UI.

## Features

- Start YouTube Music tracks and playlists on the playback device
- Preserve selected videoId and playlist context when handing off playback
- Play / pause / previous / next / seek
- Sync title, artist, artwork, and playback position back to the controller
- Distinguish different videos that share the same title and artist
- Check for and install signed APK updates from GitHub Releases
- No companion app required on the controller device

## Requirements

| Role | Requirement |
| --- | --- |
| Playback device | Android 9.0 / API 28 or newer, YouTube Music, YT Music Remote |
| Controller | Any device that can run YouTube Music with Cast controls |
| Network | Both devices on the same LAN / Wi-Fi with peer-to-peer and multicast traffic allowed |

## Install

### 1. Install the APK on the playback device

**[Download MediaRemote-latest.apk](https://github.com/hglasswater-boop/media-remote-android/releases/download/debug-latest/MediaRemote-latest.apk)**

You can also use the [Releases page](https://github.com/hglasswater-boop/media-remote-android/releases).

`debug-latest` is currently a rolling pre-release generated automatically from `main`, not a stable versioned release channel.

### 2. First-time setup

1. Install and launch YouTube Music once on the playback device
2. Launch YT Music Remote
3. Grant **Notification access**
4. Grant local-network access if Android asks for it
5. Put both devices on the same LAN / Wi-Fi

YT Music Remote starts listening for Cast/DIAL discovery when the app launches.

### 3. Control playback

1. Open YouTube Music on the controller
2. Tap the Cast icon
3. Select `YT Music Remote <device name>`
4. Pick a song or playlist
5. Use the normal YouTube Music playback controls

## Permissions

| Permission / access | Why it is needed |
| --- | --- |
| Notification access | Discover and control YouTube Music's MediaSession and read playback metadata/state |
| Local network / Wi-Fi | DIAL / Lounge communication and LAN discovery |
| Multicast | Advertise/discover the receiver on the local network |
| Foreground service | Keep the receiver available reliably |
| Notifications | Show Android-required foreground-service state |
| Install unknown apps | Install a signed APK downloaded by the in-app updater |
| Internet | YouTube-related communication and GitHub Releases update checks |

## Troubleshooting

### The receiver does not appear in the Cast list

- Confirm both devices are on the same LAN / Wi-Fi
- Avoid guest Wi-Fi or networks with AP/client isolation
- Open YT Music Remote once on the playback device
- Temporarily rule out VPNs or security apps that block local traffic
- Check that your router allows multicast traffic

### The receiver connects, but playback controls do nothing

- Launch YouTube Music once on the playback device
- Confirm Notification access is enabled for YT Music Remote
- Check Android battery optimization/background restrictions

### Track metadata or position is stale

MediaSession state and YouTube Lounge state can arrive at slightly different times. If the state still does not converge after a few seconds, reconnect and verify the playback device's YouTube Music state.

If the problem persists, open an [Issue](https://github.com/hglasswater-boop/media-remote-android/issues/new/choose) with reproduction steps, Android version, YouTube Music version, and device model.

## Known limitations

- Some playlists, including `Favorite Songs`, may appear as a generic queue name on the controller
- Metadata behavior is not guaranteed to match a genuine Chromecast exactly
- Compatibility may change whenever YouTube Music changes its undocumented DIAL / Lounge behavior
- The project does not bypass DRM, spoof Google Cast certificates, or store Google account credentials

## Updating

A push to `main` triggers GitHub Actions to build a signed APK and refresh the `debug-latest` release.

The app checks for updates at launch at most once every 24 hours. You can also trigger a manual check from the app.

## Development

### Toolchain

- JDK 17
- Android SDK Platform 37 / Build Tools 37.0.0
- Android Studio or the included Gradle Wrapper

### Build

macOS / Linux:

```bash
git clone https://github.com/hglasswater-boop/media-remote-android.git
cd media-remote-android
./gradlew :app:assembleDebug
```

Windows:

```powershell
git clone https://github.com/hglasswater-boop/media-remote-android.git
cd media-remote-android
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Test and lint

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Source layout

```text
app/src/main/java/dev/mediaremote/
├── dial/      # DIAL / YouTube Lounge compatibility
├── media/     # MediaSession / YouTube Music integration
├── network/   # LAN receiver and networking
├── ui/        # Jetpack Compose UI
└── update/    # GitHub Releases updater

docs/         # Protocol research, design notes, release docs
```

## Contributing

Bug reports, compatibility findings, documentation fixes, and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Security and privacy

See [SECURITY.md](SECURITY.md) for vulnerability reporting guidance.

The app does not store Google account credentials. Because it listens on the local network, use it only on networks you trust.

## License

This project is released under the [MIT License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party attribution.

---

YT Music Remote is an independent, unofficial open-source project and is not affiliated with, endorsed by, or sponsored by Google LLC or YouTube.
