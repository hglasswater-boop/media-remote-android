package dev.mediaremote.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.mediaremote.R
import dev.mediaremote.media.MediaSessionBridge
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RemoteServerService : Service() {
    private val running = AtomicBoolean(false)
    private val clientPool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText("Port $PORT • ${LocalAddress.bestIpv4Address()}")
            .setOngoing(true)
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
        startServer()
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
        clientPool.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return

        acceptThread = Thread {
            try {
                ServerSocket(PORT).use { server ->
                    serverSocket = server
                    while (running.get()) {
                        val socket = server.accept()
                        clientPool.execute { handleClient(socket) }
                    }
                }
            } catch (_: Exception) {
                if (running.get()) stopSelf()
            }
        }.apply {
            name = "MediaRemote-Accept"
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            it.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val writer = PrintWriter(it.getOutputStream(), true)

            val response = runCatching {
                val raw = reader.readLine() ?: error("Empty request")
                val request = RemoteRequest.fromJson(raw)

                if (!secureEquals(request.token, PairingStore.getOrCreateToken(this))) {
                    return@runCatching RemoteResponse(false, "Unauthorized")
                }

                if (request.command == "status") {
                    return@runCatching RemoteResponse(
                        ok = true,
                        message = "OK",
                        snapshot = MediaSessionBridge.snapshot(this),
                    )
                }

                val command = request.toMediaCommand()
                    ?: return@runCatching RemoteResponse(false, "Unknown command")

                if (!MediaSessionBridge.execute(this, command)) {
                    return@runCatching RemoteResponse(
                        false,
                        "YouTube Music MediaSession not available",
                        MediaSessionBridge.snapshot(this),
                    )
                }

                Thread.sleep(80)
                RemoteResponse(
                    ok = true,
                    message = "OK",
                    snapshot = MediaSessionBridge.snapshot(this),
                )
            }.getOrElse {
                RemoteResponse(false, it.message ?: "Bad request")
            }

            writer.println(response.toJson())
        }
    }

    private fun secureEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

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
        const val PORT = 50505
        private const val CHANNEL_ID = "media_remote_server"
        private const val NOTIFICATION_ID = 50505
    }
}
