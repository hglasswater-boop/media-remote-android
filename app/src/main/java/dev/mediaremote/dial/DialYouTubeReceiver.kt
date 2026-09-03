package dev.mediaremote.dial

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.mediaremote.network.LocalAddress
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

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val address = LocalAddress.bestIpv4Address()
        if (
            address.isBlank() ||
            address == "Unavailable" ||
            address == "0.0.0.0" ||
            address.startsWith("127.")
        ) {
            running.set(false)
            toast("DIAL Castを開始できません • Wi-Fi/LANアドレスなし")
            return false
        }

        acquireMulticastLock()
        val friendlyName = "YT Music Remote ${Build.MODEL.take(20)}"
        val identity = DialIdentityStore.deviceUuid(appContext)
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
        lounge: YouTubeLoungeSession,
    ) {
        if (!running.get() || httpServer != null || ssdpAdvertiser != null) return

        val http = DialHttpServer(
            loungeSession = lounge,
            identityUuid = identity,
            friendlyName = friendlyName,
            hostAddress = { address },
            onStatus = ::status,
        )
        if (!http.start()) {
            toast("DIAL HTTP待受を開始できません")
            return
        }

        val ssdp = DialSsdpAdvertiser(
            identityUuid = identity,
            httpPort = http.port,
            hostAddress = { address },
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
        status("YouTube Music Cast待受中")
        Log.i(TAG, "DIAL receiver published at $address:${http.port}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        ssdpAdvertiser?.stop()
        ssdpAdvertiser = null
        httpServer?.stop()
        httpServer = null
        loungeSession?.stop()
        loungeSession = null
        releaseMulticastLock()
    }

    private fun probeDetected() {
        // Cast-sheet discovery is normal background traffic. Keep it in logcat without showing a
        // user-visible toast every time YouTube Music sends an M-SEARCH probe.
        Log.d(TAG, "YouTube Music DIAL probe detected")
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
    }
}
