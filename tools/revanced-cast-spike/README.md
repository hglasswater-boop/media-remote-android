# ReVanced CastContext spike

This spike tests the smallest sender-side change needed to expose YT Music Remote in YouTube Music's native Cast picker without pretending to be a Google-authenticated Chromecast.

## Hypothesis

ReVanced GmsCore already contains a `CastMediaRouteProvider` that discovers `_googlecast._tcp.` services and publishes them as Android MediaRouter routes. YT Music Remote already advertises that service on the playback phone.

The current GmsCore support patch in `anddea/revanced-patches` returns early from the YouTube / YouTube Music method fingerprinted by the string `Error fetching CastContext.`. That prevents the app from obtaining a working CastContext when running against GmsCore.

The first experiment therefore changes exactly one behavior: **do not early-return `castContextFetchFingerprint`**. All other GmsCore support transformations remain unchanged.

## Upstream pin

- repository: `anddea/revanced-patches`
- commit: `6e8c20a33fe54e67500c4fd959227d33b2fc4ef3`

The workflow clones that exact commit, applies `anddea-cast-context.patch`, and builds the Android patch bundle with Java 21 using the same Gradle task as upstream CI.

## Success criteria

1. Patch bundle builds successfully.
2. YouTube Music patched with this bundle + ReVanced GmsCore launches normally.
3. Playback phone runs YT Music Remote 0.5.2+ and starts remote reception.
4. YouTube Music Cast picker shows `YT Music Remote <device>`.

No receiver control is expected yet. If the route becomes visible, the next spike implements / instruments `CastMediaRouteController.onSelect()` and `onControlRequest()` in GmsCore so we can inspect the exact intents YouTube Music emits when a route is selected and playback begins.

## Non-goals

- No Google Cast device certificate extraction.
- No stock Google Play Services certificate bypass.
- No YouTube Music APK redistribution.
- No changes to the production `main` branch until the experiment is proven on-device.
