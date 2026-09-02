package dev.mediaremote.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

internal data class DiscoveredHost(
    val serviceName: String,
    val address: String,
    val port: Int,
)

internal class NsdHostDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private val hosts = linkedMapOf<String, DiscoveredHost>()

    private var discoveryActive = false
    private var callback: ((List<DiscoveredHost>) -> Unit)? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            discoveryActive = true
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.startsWith(RemoteServerService.SERVICE_TYPE.removeSuffix("."))) return
            pending.offer(serviceInfo)
            startNextResolveIfNeeded()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            synchronized(hosts) {
                hosts.remove(serviceInfo.serviceName)
            }
            publish()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryActive = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryActive = false
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discoveryActive = false
        }
    }

    fun start(onChanged: (List<DiscoveredHost>) -> Unit) {
        callback = onChanged
        acquireMulticastLock()
        runCatching {
            nsdManager.discoverServices(
                RemoteServerService.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }
    }

    fun stop() {
        callback = null
        pending.clear()
        synchronized(hosts) { hosts.clear() }
        if (discoveryActive) {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
        discoveryActive = false
        resolving.set(false)
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("MediaRemote-NSD").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startNextResolveIfNeeded() {
        if (resolving.compareAndSet(false, true)) {
            resolveNext()
        }
    }

    private fun resolveNext() {
        val service = pending.poll()
        if (service == null) {
            resolving.set(false)
            if (pending.isNotEmpty()) startNextResolveIfNeeded()
            return
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = resolvedAddress(serviceInfo)
                if (!address.isNullOrBlank() && serviceInfo.port > 0) {
                    synchronized(hosts) {
                        hosts[serviceInfo.serviceName] = DiscoveredHost(
                            serviceName = serviceInfo.serviceName,
                            address = address,
                            port = serviceInfo.port,
                        )
                    }
                    publish()
                }
                resolveNext()
            }
        }

        runCatching { nsdManager.resolveService(service, resolveListener) }
            .onFailure { resolveNext() }
    }

    private fun resolvedAddress(serviceInfo: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= 34) {
            serviceInfo.hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            serviceInfo.host?.hostAddress
        }

    private fun publish() {
        val snapshot = synchronized(hosts) { hosts.values.sortedBy { it.serviceName }.toList() }
        mainHandler.post { callback?.invoke(snapshot) }
    }
}
