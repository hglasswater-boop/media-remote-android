# Contributing to YT Music Remote

Thanks for helping improve YT Music Remote. Bug reports, device compatibility findings, documentation fixes, protocol research, tests, and code contributions are welcome.

## Before opening an Issue

Please check existing Issues first. For bugs, include as much of the following as possible:

- Android version on the playback device
- Playback device model
- YouTube Music version
- YT Music Remote version / build number
- Network setup if discovery is involved
- Exact reproduction steps
- Expected behavior
- Actual behavior
- Relevant logs with personal information removed

Do not post Google account credentials, cookies, access tokens, private LAN credentials, or other secrets.

## Development setup

Requirements:

- JDK 17
- Android SDK Platform 37
- Android Build Tools 37.0.0
- Git

Clone the repository:

```bash
git clone https://github.com/hglasswater-boop/media-remote-android.git
cd media-remote-android
```

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Run the checks used by contributors before opening a PR:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Source layout

```text
app/src/main/java/dev/mediaremote/
├── dial/      # DIAL / YouTube Lounge compatibility
├── media/     # Android MediaSession integration
├── network/   # Receiver network services
├── ui/        # Jetpack Compose UI
└── update/    # GitHub Releases updater
```

Protocol investigation and design notes live in `docs/`.

## Pull requests

1. Create a focused branch from `main`
2. Keep unrelated refactors out of the same PR
3. Add or update tests when behavior changes
4. Update documentation when setup, permissions, behavior, or compatibility changes
5. Run the test/lint/build command above
6. Explain what changed, why it changed, and how it was verified

Small, reviewable PRs are easier to merge than large mixed changes.

## Compatibility-sensitive code

The receiver depends on undocumented DIAL / YouTube Lounge behavior. When changing protocol-facing code:

- Preserve captured evidence or a reproducible observation in `docs/` when practical
- Avoid assuming behavior from a single device or YouTube Music version
- Prefer tolerant parsing for fields that may be omitted or reordered
- Keep protocol compatibility logic separate from Android UI logic
- Document known differences from genuine Chromecast behavior

## Privacy and safety

Do not add code that requires storing Google passwords, authentication cookies, or other account secrets.

Do not add DRM bypasses, Google Cast certificate spoofing, or Accessibility-driven automation intended to imitate user input in YouTube Music.

Any new network listener should default to the smallest practical exposure and must be documented.

## Third-party code

If you add or substantially adapt third-party code or protocol research, update `THIRD_PARTY_NOTICES.md` and preserve the required license notices.

## Commit and PR style

There is no mandatory commit-message convention, but concise prefixes are encouraged:

- `feat:` new behavior
- `fix:` bug fix
- `docs:` documentation
- `test:` tests
- `refactor:` internal restructuring
- `ci:` workflow changes

## Questions

If you are unsure whether a change fits the project, open an Issue with the proposed behavior and technical approach before investing in a large implementation.
