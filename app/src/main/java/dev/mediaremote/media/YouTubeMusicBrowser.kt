package dev.mediaremote.media

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser

/**
 * Reads YouTube Music's Android Auto media hierarchy through its exported
 * MediaBrowserService. No Google OAuth token is stored by MediaRemote; YouTube
 * Music itself remains responsible for the signed-in account and library.
 */
class YouTubeMusicBrowser(context: Context) {
    data class Item(
        val mediaId: String,
        val title: String,
        val subtitle: String,
        val browsable: Boolean,
        val playable: Boolean,
    )

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data object Connecting : ConnectionState
        data class Connected(val rootId: String) : ConnectionState
        data class Failed(val message: String) : ConnectionState
    }

    private val appContext = context.applicationContext
    private var stateCallback: ((ConnectionState) -> Unit)? = null

    private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val root = runCatching { browser.root }.getOrNull()
            if (root.isNullOrBlank()) {
                stateCallback?.invoke(ConnectionState.Failed("YouTube Music returned an empty media root"))
            } else {
                stateCallback?.invoke(ConnectionState.Connected(root))
            }
        }

        override fun onConnectionSuspended() {
            stateCallback?.invoke(ConnectionState.Disconnected)
        }

        override fun onConnectionFailed() {
            stateCallback?.invoke(
                ConnectionState.Failed("YouTube Music did not allow MediaBrowser access"),
            )
        }
    }

    private val browser = MediaBrowser(
        appContext,
        ComponentName(MediaSessionBridge.TARGET_PACKAGE, BROWSER_SERVICE),
        connectionCallback,
        null,
    )

    fun connect(callback: (ConnectionState) -> Unit) {
        stateCallback = callback
        if (browser.isConnected) {
            callback(ConnectionState.Connected(browser.root))
            return
        }
        callback(ConnectionState.Connecting)
        runCatching { browser.connect() }
            .onFailure { callback(ConnectionState.Failed(it.message ?: "MediaBrowser connection failed")) }
    }

    fun loadChildren(
        parentId: String,
        callback: (Result<List<Item>>) -> Unit,
    ) {
        if (!browser.isConnected) {
            callback(Result.failure(IllegalStateException("YouTube Music browser is not connected")))
            return
        }

        val subscription = object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(
                parentId: String,
                children: MutableList<MediaBrowser.MediaItem>,
            ) {
                runCatching { browser.unsubscribe(parentId, this) }
                callback(
                    Result.success(
                        children.mapNotNull { item ->
                            val mediaId = item.mediaId?.takeIf { it.isNotBlank() }
                                ?: return@mapNotNull null
                            Item(
                                mediaId = mediaId,
                                title = item.description.title?.toString().orEmpty(),
                                subtitle = item.description.subtitle?.toString().orEmpty(),
                                browsable = item.isBrowsable,
                                playable = item.isPlayable,
                            )
                        },
                    ),
                )
            }

            override fun onError(parentId: String) {
                runCatching { browser.unsubscribe(parentId, this) }
                callback(Result.failure(IllegalStateException("Unable to browse $parentId")))
            }
        }

        runCatching { browser.subscribe(parentId, subscription) }
            .onFailure { callback(Result.failure(it)) }
    }

    fun disconnect() {
        stateCallback = null
        if (browser.isConnected) runCatching { browser.disconnect() }
    }

    companion object {
        const val BROWSER_SERVICE =
            "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService"
    }
}
