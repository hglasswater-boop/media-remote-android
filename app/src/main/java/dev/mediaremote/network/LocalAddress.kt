package dev.mediaremote.network

import java.net.NetworkInterface
import java.util.Collections

object LocalAddress {
    fun bestIpv4Address(): String {
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            return "Unavailable"
        }

        return interfaces
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .firstOrNull { address ->
                !address.isLoopbackAddress &&
                    address.hostAddress?.contains(':') == false &&
                    address.isSiteLocalAddress
            }
            ?.hostAddress
            ?: "Unavailable"
    }
}
