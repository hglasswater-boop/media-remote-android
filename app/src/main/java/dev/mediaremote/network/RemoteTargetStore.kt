package dev.mediaremote.network

import android.content.Context

data class RemoteTarget(
    val host: String,
    val port: Int,
    val token: String,
)

object RemoteTargetStore {
    private const val PREFS = "remote_target"
    private const val HOST = "host"
    private const val PORT = "port"
    private const val TOKEN = "token"

    fun load(context: Context): RemoteTarget? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(HOST, null)?.trim().orEmpty()
        val token = prefs.getString(TOKEN, null)?.trim().orEmpty()
        val port = prefs.getInt(PORT, RemoteServerService.PORT)
        if (host.isBlank() || token.isBlank() || port !in 1..65535) return null
        return RemoteTarget(host, port, token)
    }

    fun save(context: Context, target: RemoteTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST, target.host.trim())
            .putInt(PORT, target.port)
            .putString(TOKEN, target.token.trim())
            .apply()
    }
}
