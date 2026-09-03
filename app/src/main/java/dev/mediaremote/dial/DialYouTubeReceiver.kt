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
    private var loungeSession: YouTubeLoungeSession? = null
    private var httpServer: DialHttpServer? = null
    private var ssdpAdvertiser: DialSsdpAdvertiser? = null

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
        val http = DialHttpServer(
            loungeSession = lounge,
            identityUuid = identity,
            friendlyName = friendlyName,
            hostAddress = { address },
            onStatus = ::status,
        )
        if (!http.start()) {
            releaseMulticastLock()
            running.set(false)
            return false
        }

        val ssdp = DialSsdpAdvertiser(
            identityUuid = identity,
            httpPort = http.port,
            hostAddress = { address },
            onProbe = { status("YouTube MusicのDIAL検索を検出") },
        )
        if (!ssdp.start()) {
            http.stop()
            releaseMulticastLock()
            running.set(false)
            return false
        }

        loungeSession = lounge
        httpServer = http
        ssdpAdvertiser = ssdp
        lounge.start()
        status("YouTube Music Cast待受を開始")
        Log.i(TAG, "DIAL receiver started at $address:${http.port}")
        return true
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
        Log.i(TAG, message)
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toast(message: String) = status(message)

    companion object {
        private const val TAG = "DialYouTubeReceiver"
    }
}
