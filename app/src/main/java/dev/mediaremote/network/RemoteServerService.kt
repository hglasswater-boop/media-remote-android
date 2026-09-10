package dev.mediaremote.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.mediaremote.R
import dev.mediaremote.dial.DialYouTubeReceiver

/** Foreground owner for the only supported path: YouTube Music Cast via DIAL/Lounge. */
class RemoteServerService : Service() {
    private var dialReceiver: DialYouTubeReceiver? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val restartIntent = Intent(this, RemoteServerService::class.java)
            .setAction(ACTION_RESTART)
        val restartPendingIntent = PendingIntent.getService(
            this,
            RESTART_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText("YouTube Music Cast待受中")
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_popup_sync,
                "Cast待受を再起動",
                restartPendingIntent,
            )
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) {
            restartReceiver()
        }
        return START_STICKY
    }

    private fun startReceiver() {
        DialYouTubeReceiver(this).also { receiver ->
            val started = runCatching { receiver.start() }
                .onFailure { error ->
                    Log.e(TAG, "Could not start DIAL receiver", error)
                    receiver.stop()
                }
                .getOrDefault(false)
            if (started) dialReceiver = receiver else stopSelf()
        }
    }

    private fun restartReceiver() {
        Log.i(TAG, "Restarting DIAL receiver on request")
        dialReceiver?.stop()
        dialReceiver = null
        startReceiver()
    }

    override fun onDestroy() {
        dialReceiver?.stop()
        dialReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.server_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "RemoteServerService"
        private const val CHANNEL_ID = "youtube_music_remote_server"
        private const val NOTIFICATION_ID = 50505
        private const val RESTART_REQUEST_CODE = 50506
        const val ACTION_RESTART = "dev.mediaremote.action.RESTART_CAST"
    }
}
