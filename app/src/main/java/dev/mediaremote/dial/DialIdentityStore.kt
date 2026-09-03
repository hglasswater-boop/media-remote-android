package dev.mediaremote.dial

import android.content.Context
import java.util.UUID

internal object DialIdentityStore {
    private const val PREFS = "dial_youtube_receiver"
    private const val DEVICE_UUID = "device_uuid"
    private const val LOUNGE_DEVICE_ID = "lounge_device_id"
    private const val SCREEN_ID = "screen_id_music"
    private const val PID = "dial_pid"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceUuid(context: Context): String = stableUuid(context, DEVICE_UUID)

    fun loungeDeviceId(context: Context): String = stableUuid(context, LOUNGE_DEVICE_ID)

    fun pid(context: Context): String = stableUuid(context, PID)

    fun screenId(context: Context): String? =
        prefs(context).getString(SCREEN_ID, null)?.takeIf { it.isNotBlank() }

    fun saveScreenId(context: Context, screenId: String) {
        prefs(context).edit().putString(SCREEN_ID, screenId.trim()).apply()
    }

    fun clearScreenId(context: Context) {
        prefs(context).edit().remove(SCREEN_ID).apply()
    }

    private fun stableUuid(context: Context, key: String): String {
        val preferences = prefs(context)
        preferences.getString(key, null)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?.let { return it }

        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(key, generated).apply()
        return generated
    }
}
