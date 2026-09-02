package dev.mediaremote.network

import android.net.Uri

object PairingLinks {
    fun create(host: String, port: Int, token: String): String =
        Uri.Builder()
            .scheme("mediaremote")
            .authority("pair")
            .appendQueryParameter("host", host.trim())
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("token", token.trim())
            .build()
            .toString()

    fun parse(raw: String?): RemoteTarget? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (uri.scheme != "mediaremote" || uri.host != "pair") return null

        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        val token = uri.getQueryParameter("token")?.trim().orEmpty()

        if (host.isBlank() || token.isBlank() || port !in 1..65535) return null
        return RemoteTarget(host, port, token)
    }
}
