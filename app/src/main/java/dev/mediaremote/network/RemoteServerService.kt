package dev.mediaremote.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.mediaremote.BuildConfig
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
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText("同じLANから接続できます • ${LocalAddress.bestIpv4Address()}:$PORT")
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
        registerNsdService()
    }

    override fun onDestroy() {
        unregisterNsdService()
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
            name = "YTMusicRemote-Accept"
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
                    return@runCatching RemoteResponse(false, "ペアリングキーが一致しません")
                }

                if (request.command == "status") {
                    return@runCatching RemoteResponse(
                        ok = true,
                        message = "再生端末に接続しました",
                        snapshot = MediaSessionBridge.snapshot(this),
                    )
                }

                val command = request.toMediaCommand()
                    ?: return@runCatching RemoteResponse(false, "未対応の操作です")

                if (!MediaSessionBridge.execute(this, command)) {
                    return@runCatching RemoteResponse(
                        false,
                        when (request.command) {
                            "playUrl" -> "YouTube Musicでこのリンクを開けませんでした"
                            "playSearch" -> "YouTube Musicへ検索を送れませんでした"
                            else -> "YouTube Musicの再生セッションが見つかりません"
                        },
                        MediaSessionBridge.snapshot(this),
                    )
                }

                Thread.sleep(120)
                RemoteResponse(
                    ok = true,
                    message = when (request.command) {
                        "playUrl" -> "YouTube Musicへ送信しました"
                        "playSearch" -> "YouTube Musicへ検索を送信しました"
                        "play" -> "再生"
                        "pause" -> "一時停止"
                        "next" -> "次の曲"
                        "previous" -> "前の曲"
                        "seekBy" -> "再生位置を変更しました"
                        else -> "OK"
                    },
                    snapshot = MediaSessionBridge.snapshot(this),
                )
            }.getOrElse {
                RemoteResponse(false, it.message ?: "リモート操作に失敗しました")
            }

            writer.println(response.toJson())
        }
    }

    private fun registerNsdService() {
        val manager = getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        val info = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX-${Build.MODEL.take(24)}"
            serviceType = SERVICE_TYPE
            port = PORT
            setAttribute("version", BuildConfig.VERSION_NAME)
            setAttribute("build", BuildConfig.VERSION_CODE.toString())
            setAttribute("target", "youtube-music")
        }
        nsdManager = manager
        registrationListener = listener
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterNsdService() {
        val manager = nsdManager
        val listener = registrationListener
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
        registrationListener = null
        nsdManager = null
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
        const val SERVICE_TYPE = "_mediaremote._tcp."
        const val SERVICE_NAME_PREFIX = "YTMusicRemote"
        private const val CHANNEL_ID = "youtube_music_remote_server"
        private const val NOTIFICATION_ID = 50505
    }
}
