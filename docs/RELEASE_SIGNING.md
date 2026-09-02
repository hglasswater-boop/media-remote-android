# Release signing

MediaRemote release APKs are signed by GitHub Actions with a fixed Android signing key.

## Repository secrets

Configure these GitHub Actions repository secrets:

- `KEYSTORE_BASE64`: base64-encoded release keystore
- `KEYSTORE_PASSWORD`: keystore password
- `KEY_ALIAS`: signing key alias
- `KEY_PASSWORD`: signing key password

The workflow decodes the keystore only into the GitHub-hosted runner's temporary directory.
The key file and passwords must never be committed to this repository.

## Expected signing certificate

SHA-256 certificate fingerprint:

`C5:44:B7:C2:28:74:03:1A:7E:67:56:DD:6C:3C:78:93:68:B8:E9:3D:50:3A:A8:69:16:97:E2:16:D5:27:9D:92`

The release workflow runs `apksigner verify --print-certs` and aborts if the APK is not signed with this certificate.

## Key backup

Keep an offline backup of the keystore and its passwords. If this key is lost, APKs signed with a replacement key cannot update existing installations outside a supported signing-key migration mechanism.
