package dev.mediaremote.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental Google Cast receiver discovery.
 *
 * Phase 1 proved that YouTube Music can discover the service and probe TCP/8009. This phase
 * mirrors the TXT/service identity of a real Chromecast more closely so we can test whether the
 * sender promotes the device into its Cast picker before implementing the much larger TLS and
 * Cast V2 device-authentication stack.
 */
class CastDiscoveryExperiment(context: Context) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val probeClients = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var probeSocket: ServerSocket? = null
    private var probeThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val server = runCatching { ServerSocket(CAST_PORT) }.getOrElse {
            running.set(false)
            toast("Cast実験を開始できません • TCP/$CAST_PORT 使用中")
            return false
        }
        probeSocket = server
        probeThread = Thread { acceptProbes(server) }.apply {
            name = "YTMusicRemote-CastProbe"
            start()
        }

        val registered = registerCastService()
        if (!registered) {
            stop()
            return false
        }
        return true
    }

    fun stop() {
        unregisterCastService()
        running.set(false)
        runCatching { probeSocket?.close() }
        probeSocket = null
        probeThread?.interrupt()
        probeThread = null
        probeClients.shutdownNow()
    }

    private fun acceptProbes(server: ServerSocket) {
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            CastExperimentStore.recordProbe(appContext)
            toast("Cast接続試行を検出 • 一覧表示前の適格性チェック到達")
            probeClients.execute { holdProbe(socket) }
        }
    }

    private fun holdProbe(socket: Socket) {
        socket.use {
            // A stock Cast sender should begin a TLS handshake here. We deliberately do not
            // fake a successful handshake yet. The next phase starts here if discovery identity
            // alone is still insufficient to make the receiver visible in the Cast picker.
            runCatching { Thread.sleep(PROBE_HOLD_MS) }
        }
    }

    private fun registerCastService(): Boolean {
        val manager = appContext.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                toast("Cast端末として公開しました • ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                toast("Cast公開に失敗しました • NSD $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        val deviceId = CastExperimentStore.deviceId(appContext)
        val friendlyName = "YT Music Remote ${Build.MODEL.take(18)}"
        val info = runCatching {
            NsdServiceInfo().apply {
                // Real Chromecast advertisements use a protocol-ish instance name and expose the
                // human-readable name separately in `fn`. Some senders appear to care about this.
                serviceName = "Chromecast-$deviceId"
                serviceType = CAST_SERVICE_TYPE
                port = CAST_PORT
                setAttribute("id", deviceId)
                setAttribute("cd", CastExperimentStore.cloudDeviceId(appContext))
                setAttribute("rm", CastExperimentStore.receiverMetrics(appContext))
                setAttribute("ve", "05")
                setAttribute("md", "Chromecast")
                setAttribute("ic", "/setup/icon.png")
                setAttribute("fn", friendlyName)
                setAttribute("ca", "4101")
                setAttribute("st", "0")
                setAttribute("bs", CastExperimentStore.buildStatus(appContext))
                setAttribute("nf", "1")
                setAttribute("rs", "")
            }
        }.getOrElse {
            toast("Cast広告情報を作れませんでした")
            return false
        }

        nsdManager = manager
        registrationListener = listener
        return runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        }.getOrElse {
            toast("Cast公開を開始できませんでした")
            false
        }
    }

    private fun unregisterCastService() {
        val manager = nsdManager
        val listener = registrationListener
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
        registrationListener = null
        nsdManager = null
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val CAST_PORT = 8009
        const val CAST_SERVICE_TYPE = "_googlecast._tcp."
        private const val PROBE_HOLD_MS = 4_000L
    }
}
