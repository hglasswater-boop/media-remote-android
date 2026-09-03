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
 * Phase-1 Google Cast receiver experiment.
 *
 * This intentionally implements discovery and a TCP probe endpoint only. It does not pretend
 * that Cast V2 TLS/device authentication is complete. The goal is to verify two facts on real
 * hardware before bringing in a much larger protocol stack:
 * 1. YouTube Music sees this Android phone in its Cast picker.
 * 2. Selecting it causes the sender to contact TCP/8009.
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
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    "Cast接続試行を検出 • TLS認証の手前まで到達",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            probeClients.execute { holdProbe(socket) }
        }
    }

    private fun holdProbe(socket: Socket) {
        socket.use {
            // A stock Cast sender should begin a TLS handshake here. We deliberately do not
            // fake a successful handshake in this discovery spike. Keeping the socket alive
            // briefly makes connection attempts observable without claiming protocol support.
            runCatching { Thread.sleep(PROBE_HOLD_MS) }
        }
    }

    private fun registerCastService(): Boolean {
        val manager = appContext.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        val friendlyName = "YT Music Remote ${Build.MODEL.take(18)}"
        val info = runCatching {
            NsdServiceInfo().apply {
                serviceName = friendlyName
                serviceType = CAST_SERVICE_TYPE
                port = CAST_PORT
                setAttribute("id", CastExperimentStore.deviceId(appContext))
                setAttribute("ve", "05")
                setAttribute("md", "Chromecast Ultra")
                setAttribute("fn", friendlyName)
                setAttribute("ca", "4101")
                setAttribute("st", "0")
                setAttribute("ic", "/setup/icon.png")
                setAttribute("nf", "1")
                setAttribute("rs", "")
            }
        }.getOrElse { return false }

        nsdManager = manager
        registrationListener = listener
        return runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        }.getOrDefault(false)
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

    companion object {
        const val CAST_PORT = 8009
        const val CAST_SERVICE_TYPE = "_googlecast._tcp."
        private const val PROBE_HOLD_MS = 4_000L
    }
}
