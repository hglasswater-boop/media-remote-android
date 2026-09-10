package dev.mediaremote.dial

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.mediaremote.network.LocalAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stock YouTube / YouTube Music receiver path based on DIAL + YouTube Lounge.
 *
 * Unlike the experimental _googlecast._tcp receiver, DIAL does not require a Google Cast device
 * certificate. YouTube Music discovers the Android playback phone as a DIAL receiver, launches the
 * `YouTube` app endpoint with theme=m and pairingCode, then controls it through Lounge RPC.
 */
class DialYouTubeReceiver(context: Context) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var loungeSession: YouTubeLoungeSession? = null
    @Volatile private var httpServer: DialHttpServer? = null
    @Volatile private var ssdpAdvertiser: DialSsdpAdvertiser? = null
    @Volatile private var publishedAddress: String? = null
    @Volatile private var networkWasLost = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkMonitor: ScheduledExecutorService? = null
    private var networkMonitorFuture: ScheduledFuture<*>? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val address = LocalAddress.bestIpv4Address()
        if (!isUsableAddress(address)) {
            running.set(false)
            toast("DIAL Castを開始できません • Wi-Fi/LANアドレスなし")
            return false
        }

        acquireMulticastLock()
        val friendlyName = "YT Music Remote ${Build.MODEL.take(20)}"
        val identity = DialIdentityStore.deviceUuid(appContext)
        val bootId = DialIdentityStore.nextSsdpBootId(appContext)
        val lounge = YouTubeLoungeSession(appContext, friendlyName, ::status)
        loungeSession = lounge

        // Match the established yt-cast-receiver startup order: establish the YouTube Lounge
        // session first, and only then publish the DIAL endpoint. Once a device is visible in the
        // sender's Cast list it is therefore already able to register the DIAL pairingCode.
        lounge.start {
            if (!running.get()) return@start
            startDialEndpoints(
                address = address,
                friendlyName = friendlyName,
                identity = identity,
                bootId = bootId,
                lounge = lounge,
            )
        }
        Log.i(TAG, "Lounge bootstrap started for $address")
        return true
    }

    private fun startDialEndpoints(
        address: String,
        friendlyName: String,
        identity: String,
        bootId: Long,
        lounge: YouTubeLoungeSession,
    ) {
        if (!running.get() || httpServer != null || ssdpAdvertiser != null) return

        val http = DialHttpServer(
            loungeSession = lounge,
            identityUuid = identity,
            friendlyName = friendlyName,
            // The DIAL description and Application-URL are read after discovery. Resolve the
            // address at response time so a DHCP change does not leave the sender with a dead URL.
            hostAddress = { currentAddressOr(address) },
            onStatus = ::status,
        )
        if (!http.start()) {
            toast("DIAL HTTP待受を開始できません")
            return
        }

        val ssdp = DialSsdpAdvertiser(
            identityUuid = identity,
            bootId = bootId,
            httpPort = http.port,
            // DHCP / Wi-Fi roaming can change the playback phone's IPv4 address while the
            // foreground service remains alive. DIAL LOCATION must always point at the current
            // address, otherwise the sender discovers a stale receiver and cannot connect.
            hostAddress = { currentAddressOr(address) },
            onProbe = ::probeDetected,
        )
        if (!ssdp.start()) {
            http.stop()
            toast("DIAL SSDP公開を開始できません")
            return
        }

        if (!running.get()) {
            ssdp.stop()
            http.stop()
            return
        }
        httpServer = http
        ssdpAdvertiser = ssdp
        publishedAddress = address
        startNetworkMonitor(identity = identity, bootId = bootId, fallbackAddress = address)
        status("YouTube Music Cast待受中")
        Log.i(TAG, "DIAL receiver published at $address:${http.port} bootId=$bootId")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        unregisterNetworkCallback()
        networkWasLost = false
        networkMonitorFuture?.cancel(true)
        networkMonitorFuture = null
        networkMonitor?.shutdownNow()
        networkMonitor = null
        ssdpAdvertiser?.stop()
        ssdpAdvertiser = null
        httpServer?.stop()
        httpServer = null
        publishedAddress = null
        loungeSession?.stop()
        loungeSession = null
        releaseMulticastLock()
    }

    private fun startNetworkMonitor(
        identity: String,
        bootId: Long,
        fallbackAddress: String,
    ) {
        val monitor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "YTMusicRemote-DIAL-Network").apply { isDaemon = true }
        }
        networkMonitor = monitor
        registerNetworkCallback(identity, bootId, fallbackAddress)
        networkMonitorFuture = monitor.scheduleWithFixedDelay(
            {
                runCatching {
                    refreshSsdpAdvertisement(identity, bootId, fallbackAddress)
                }.onFailure { error ->
                    if (running.get()) Log.w(TAG, "DIAL network refresh failed", error)
                }
            },
            NETWORK_CHECK_INITIAL_DELAY_SECONDS,
            NETWORK_CHECK_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun refreshSsdpAdvertisement(
        identity: String,
        bootId: Long,
        fallbackAddress: String,
        force: Boolean = false,
    ) {
        if (!running.get()) return
        val currentAddress = LocalAddress.bestIpv4Address()
        if (!isUsableAddress(currentAddress) || (!force && currentAddress == publishedAddress)) return

        val http = httpServer ?: return
        // Rejoin the multicast group when Wi-Fi moved to another interface. The HTTP server is
        // already bound to all interfaces, so its port can be retained during this refresh.
        ssdpAdvertiser?.stop()
        ssdpAdvertiser = null
        val ssdp = DialSsdpAdvertiser(
            identityUuid = identity,
            bootId = bootId,
            httpPort = http.port,
            hostAddress = { currentAddressOr(fallbackAddress) },
            onProbe = ::probeDetected,
        )
        if (!ssdp.start()) {
            Log.w(TAG, "DIAL SSDP restart failed for address $currentAddress")
            return
        }
        if (!running.get()) {
            ssdp.stop()
            return
        }
        ssdpAdvertiser = ssdp
        publishedAddress = currentAddress
        status("ネットワーク変更を検知 • Cast待受を更新")
        loungeSession?.requestReconnect("ネットワーク変更 • Lounge再接続")
        Log.i(TAG, "DIAL receiver network address updated to $currentAddress:${http.port}")
    }

    private fun registerNetworkCallback(
        identity: String,
        bootId: Long,
        fallbackAddress: String,
    ) {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (!running.get()) return
                networkWasLost = true
                status("ネットワーク切断を検知")
            }

            override fun onAvailable(network: Network) {
                if (!running.get() || !networkWasLost) return
                networkWasLost = false
                mainHandler.postDelayed(
                    {
                        if (!running.get()) return@postDelayed
                        runCatching {
                            refreshSsdpAdvertisement(
                                identity = identity,
                                bootId = bootId,
                                fallbackAddress = fallbackAddress,
                                force = true,
                            )
                            loungeSession?.requestReconnect("ネットワーク復旧 • Lounge再接続")
                        }.onFailure { error ->
                            if (running.get()) Log.w(TAG, "Network recovery failed", error)
                        }
                    },
                    NETWORK_RECOVERY_DELAY_MS,
                )
            }
        }

        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess {
                connectivityManager = manager
                networkCallback = callback
            }
            .onFailure { error ->
                Log.w(TAG, "Could not register network callback", error)
            }
    }

    private fun unregisterNetworkCallback() {
        val manager = connectivityManager
        val callback = networkCallback
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
        connectivityManager = null
        networkCallback = null
    }

    private fun currentAddressOr(fallbackAddress: String): String =
        LocalAddress.bestIpv4Address().takeIf(::isUsableAddress) ?: fallbackAddress

    private fun isUsableAddress(address: String): Boolean =
        address.isNotBlank() &&
            address != "Unavailable" &&
            address != "0.0.0.0" &&
            !address.startsWith("127.")

    private fun probeDetected() {
        // Cast-sheet discovery is normal background traffic. Keep it in logcat without showing a
        // user-visible toast every time YouTube Music sends an M-SEARCH probe.
        Log.d(TAG, "YouTube Music DIAL probe detected")
        loungeSession?.requestStateResync("DIAL probe")
    }

    private fun acquireMulticastLock() {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi.createMulticastLock("YTMusicRemote-DIAL").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        multicastLock = null
    }

    private fun status(message: String) {
        // Lounge and DIAL status callbacks include normal protocol chatter and can be emitted many
        // times during a healthy Cast session. Keep those diagnostics in logcat only.
        Log.i(TAG, message)
    }

    private fun toast(message: String) {
        Log.w(TAG, message)
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "DialYouTubeReceiver"
        private const val NETWORK_CHECK_INITIAL_DELAY_SECONDS = 5L
        private const val NETWORK_CHECK_INTERVAL_SECONDS = 5L
        private const val NETWORK_RECOVERY_DELAY_MS = 1_500L
    }
}
