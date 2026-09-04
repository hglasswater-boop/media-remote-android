package dev.mediaremote.media

import android.app.Notification
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

internal data class YouTubeMusicNotificationSession(
    val key: String,
    val token: MediaSession.Token,
    val title: String,
    val artist: String,
    val postTime: Long,
)

/**
 * Notification access is required for MediaSessionManager#getActiveSessions, but it also gives us a
 * stronger signal than that API's unordered session list: the MediaSession token attached to the
 * currently visible YouTube Music media notification.
 *
 * YouTube Music can leave more than one active MediaSession behind. Picking the first package match
 * can therefore keep reading a moving PlaybackState from an old session while its metadata remains
 * on the previous track. Cache the notification-bound token and let MediaSessionBridge prefer it.
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshCurrentSession()
    }

    override fun onListenerDisconnected() {
        currentSession = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != MediaSessionBridge.TARGET_PACKAGE) return
        notificationSession(sbn)?.let { currentSession = it }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != MediaSessionBridge.TARGET_PACKAGE) return
        if (currentSession?.key == sbn.key) {
            refreshCurrentSession()
        }
    }

    private fun refreshCurrentSession() {
        currentSession = runCatching {
            activeNotifications
                .asSequence()
                .filter { it.packageName == MediaSessionBridge.TARGET_PACKAGE }
                .mapNotNull(::notificationSession)
                .maxByOrNull { it.postTime }
        }.onFailure {
            Log.w(TAG, "Unable to inspect YouTube Music notifications", it)
        }.getOrNull()
    }

    private fun notificationSession(sbn: StatusBarNotification): YouTubeMusicNotificationSession? {
        val notification = sbn.notification ?: return null
        val token = mediaSessionToken(notification) ?: return null
        val extras = notification.extras
        return YouTubeMusicNotificationSession(
            key = sbn.key,
            token = token,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            artist = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            postTime = sbn.postTime,
        )
    }

    @Suppress("DEPRECATION")
    private fun mediaSessionToken(notification: Notification): MediaSession.Token? {
        val extras = notification.extras ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }
    }

    companion object {
        private const val TAG = "MediaNotificationListener"

        @Volatile
        private var currentSession: YouTubeMusicNotificationSession? = null

        internal fun currentYouTubeMusicSession(
            context: Context,
        ): Pair<MediaController, YouTubeMusicNotificationSession>? {
            val session = currentSession ?: return null
            val controller = runCatching { MediaController(context, session.token) }
                .getOrNull()
                ?.takeIf { it.packageName == MediaSessionBridge.TARGET_PACKAGE }
                ?: return null
            return controller to session
        }
    }
}
