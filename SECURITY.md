# Security Policy

## Supported versions

YT Music Remote is currently developed as a rolling pre-release. Security fixes are applied to the latest code on `main` and the latest signed APK published through the `debug-latest` release.

Older builds are not maintained as separate supported branches.

## Reporting a vulnerability

Please avoid publishing exploit details, credentials, tokens, private network information, or other sensitive data in a public Issue.

If GitHub private vulnerability reporting is available in the repository's **Security** tab, use that channel. If it is not available, open a public Issue with only a minimal, non-sensitive description that a security problem exists, and wait before sharing reproduction details publicly.

Helpful information includes:

- affected YT Music Remote version / build
- Android version and device model
- whether the issue requires local-network access, physical access, or prior permissions
- impact and realistic attack scenario
- minimal reproduction steps with all secrets removed

## Security boundaries

YT Music Remote is designed to:

- communicate on the local network for receiver discovery and control
- interact with the local YouTube Music MediaSession through Android APIs
- check GitHub Releases for application updates
- install a user-approved signed APK update

It is not designed to store Google account passwords, cookies, or authentication tokens.

Because the app exposes receiver functionality on the LAN, use it only on networks you trust. Guest or hostile networks should be treated as untrusted.

## Out of scope

Please do not use this project to request or contribute:

- DRM bypasses
- Google Cast certificate spoofing
- credential theft or session-token extraction
- mechanisms intended to evade access controls

Compatibility research that does not bypass access controls is welcome when documented responsibly.
