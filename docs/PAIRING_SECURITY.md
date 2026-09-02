# Pairing QR security

The pairing QR contains the local playback host address, TCP port, and a random pairing token. Treat the QR as a local secret and do not publish screenshots of it.

The token is not advertised through mDNS. mDNS only exposes service discovery metadata.

The controller stores the paired target in app-private SharedPreferences. The playback phone validates the token before accepting remote commands.
