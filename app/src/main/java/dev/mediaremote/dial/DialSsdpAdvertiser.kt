package dev.mediaremote.dial

import android.os.Build
import android.util.Log
import dev.mediaremote.BuildConfig
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class DialSsdpAdvertiser(
    private val identityUuid: String,
    private val httpPort: Int,
    private val hostAddress: () -> String,
    private val onProbe: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var socket: MulticastSocket? = null
    private var listenerThread: Thread? = null
    private var announceFuture: ScheduledFuture<*>? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val multicastAddress = InetAddress.getByName(MULTICAST_ADDRESS)
        val multicastSocket = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                soTimeout = 1_500
                selectWifiInterface()?.let { networkInterface ->
                    runCatching {
                        joinGroup(InetSocketAddress(multicastAddress, SSDP_PORT), networkInterface)
                    }.onFailure { Log.w(TAG, "joinGroup with explicit interface failed", it) }
                } ?: runCatching { joinGroup(multicastAddress) }
            }
        }.getOrElse {
            running.set(false)
            Log.e(TAG, "Could not bind SSDP", it)
            return false
        }
        socket = multicastSocket

        listenerThread = Thread { listen(multicastSocket) }.apply {
            name = "YTMusicRemote-DIAL-SSDP"
            start()
        }

        // NOTIFY is not strictly required because senders actively M-SEARCH, but it helps route
        // caches notice the receiver quickly after the playback service starts.
        announceFuture = scheduler.scheduleAtFixedRate(
            { sendAlive(multicastSocket) },
            0,
            15,
            TimeUnit.MINUTES,
        )
        return true
    }

    fun stop() {
        running.set(false)
        announceFuture?.cancel(true)
        announceFuture = null
        sendByebye()
        runCatching { socket?.close() }
        socket = null
        listenerThread?.interrupt()
        listenerThread = null
        scheduler.shutdownNow()
    }

    private fun listen(multicastSocket: MulticastSocket) {
        val buffer = ByteArray(16 * 1024)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                multicastSocket.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (error: Exception) {
                if (running.get()) Log.w(TAG, "SSDP receive failed", error)
                break
            }

            val request = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
            val searchTarget = dialSearchTarget(request) ?: continue
            onProbe()
            if (searchTarget.equals("ssdp:all", ignoreCase = true)) {
                // A generic search should expose both DIAL identities. YouTube clients have used
                // both device:dial:1 and service:dial:1 across versions.
                sendSearchResponse(multicastSocket, packet.address, packet.port, DIAL_DEVICE)
                sendSearchResponse(multicastSocket, packet.address, packet.port, DIAL_SERVICE)
            } else {
                sendSearchResponse(multicastSocket, packet.address, packet.port, searchTarget)
            }
        }
    }

    private fun dialSearchTarget(request: String): String? {
        val lines = request.replace("\r", "").split('\n')
        if (lines.firstOrNull()?.trim()?.uppercase() != "M-SEARCH * HTTP/1.1") return null
        val st = lines.firstNotNullOfOrNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@firstNotNullOfOrNull null
            val key = line.substring(0, separator).trim()
            if (!key.equals("ST", ignoreCase = true)) return@firstNotNullOfOrNull null
            line.substring(separator + 1).trim()
        } ?: return null
        return when {
            st.equals(DIAL_SERVICE, ignoreCase = true) -> DIAL_SERVICE
            st.equals(DIAL_DEVICE, ignoreCase = true) -> DIAL_DEVICE
            st.equals("ssdp:all", ignoreCase = true) -> "ssdp:all"
            else -> null
        }
    }

    private fun sendSearchResponse(
        socket: MulticastSocket,
        address: InetAddress,
        port: Int,
        searchTarget: String,
    ) {
        val payload = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("EXT:\r\n")
            append("LOCATION: ${descriptionUrl()}\r\n")
            append("SERVER: Android/${Build.VERSION.RELEASE} UPnP/1.1 YTMusicRemote/${BuildConfig.VERSION_NAME}\r\n")
            append("ST: $searchTarget\r\n")
            append("USN: ${usn(searchTarget)}\r\n")
            append("BOOTID.UPNP.ORG: 1\r\n")
            append("CONFIGID.UPNP.ORG: 1\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        runCatching {
            socket.send(DatagramPacket(payload, payload.size, address, port))
        }.onFailure { Log.w(TAG, "SSDP response failed", it) }
    }

    private fun sendAlive(socket: MulticastSocket) {
        if (!running.get()) return
        val payload = notifyPayload("ssdp:alive").toByteArray(Charsets.US_ASCII)
        runCatching {
            val address = InetAddress.getByName(MULTICAST_ADDRESS)
            socket.send(DatagramPacket(payload, payload.size, address, SSDP_PORT))
        }.onFailure { Log.w(TAG, "SSDP alive failed", it) }
    }

    private fun sendByebye() {
        val current = socket ?: return
        val payload = notifyPayload("ssdp:byebye").toByteArray(Charsets.US_ASCII)
        runCatching {
            val address = InetAddress.getByName(MULTICAST_ADDRESS)
            current.send(DatagramPacket(payload, payload.size, address, SSDP_PORT))
        }
    }

    private fun notifyPayload(nts: String): String = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: $MULTICAST_ADDRESS:$SSDP_PORT\r\n")
        append("NT: $DIAL_SERVICE\r\n")
        append("NTS: $nts\r\n")
        append("USN: ${usn(DIAL_SERVICE)}\r\n")
        if (nts == "ssdp:alive") {
            append("LOCATION: ${descriptionUrl()}\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("SERVER: Android/${Build.VERSION.RELEASE} UPnP/1.1 YTMusicRemote/${BuildConfig.VERSION_NAME}\r\n")
            append("BOOTID.UPNP.ORG: 1\r\n")
            append("CONFIGID.UPNP.ORG: 1\r\n")
        }
        append("\r\n")
    }

    private fun descriptionUrl(): String = "http://${hostAddress()}:$httpPort/dd.xml"

    private fun usn(searchTarget: String): String = "uuid:$identityUuid::$searchTarget"

    private fun selectWifiInterface(): NetworkInterface? {
        val targetAddress = hostAddress()
        return Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { iface ->
            iface.isUp && !iface.isLoopback && Collections.list(iface.inetAddresses)
                .any { it.hostAddress == targetAddress }
        }
    }

    companion object {
        private const val TAG = "DialSsdpAdvertiser"
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DIAL_SERVICE = "urn:dial-multiscreen-org:service:dial:1"
        private const val DIAL_DEVICE = "urn:dial-multiscreen-org:device:dial:1"
    }
}
