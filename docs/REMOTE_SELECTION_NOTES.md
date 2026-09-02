# Remote selection notes

MediaRemote uses Android's supported media-control surfaces first:

- `MediaController.TransportControls.playFromSearch()` for text selection when YouTube Music advertises `ACTION_PLAY_FROM_SEARCH`.
- `MediaController.TransportControls.playFromUri()` for shared YouTube Music URLs when YouTube Music advertises `ACTION_PLAY_FROM_URI`.
- Android media/search or ACTION_VIEW intents as a fallback.

YouTube Music may choose to open a search or playlist surface without immediately starting playback on some versions. The app avoids Accessibility-based UI automation because that would be brittle and unnecessarily invasive.
