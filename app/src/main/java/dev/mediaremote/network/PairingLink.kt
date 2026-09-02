package dev.mediaremote.network

import android.net.Uri

data class PairingLink(
    val host: String,
    val port: Int,
    val token: String,
)

object PairingLinks {
    fun create(host: String, port: Int, token: String): String = Uri.Builder()
        .scheme("mediaremote")
        .authority("pair")
        .appendQueryParameter("host", host)
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("token", token)
        .build()
        .toString()

    fun parse(raw: String?): PairingLink? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (uri.scheme != "mediaremote" || uri.host != "pair") return null

        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: RemoteServerService.PORT
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        if (host.isBlank() || token.isBlank() || port !in 1..65535) return null
        return PairingLink(host, port, token)
    }
}
