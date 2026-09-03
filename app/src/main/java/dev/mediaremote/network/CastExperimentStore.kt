package dev.mediaremote.network

import android.content.Context
import java.security.SecureRandom

object CastExperimentStore {
    private const val PREFS = "cast_experiment"
    private const val DEVICE_ID = "device_id"
    private const val PROBE_COUNT = "probe_count"
    private const val LAST_PROBE_AT = "last_probe_at"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(DEVICE_ID, null)?.takeIf { it.length == 32 }?.let { return it }

        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val generated = bytes.joinToString(separator = "") {
            "%02x".format(it.toInt() and 0xff)
        }
        prefs.edit().putString(DEVICE_ID, generated).apply()
        return generated
    }

    fun recordProbe(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(PROBE_COUNT, prefs.getInt(PROBE_COUNT, 0) + 1)
            .putLong(LAST_PROBE_AT, System.currentTimeMillis())
            .apply()
    }

    fun probeCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PROBE_COUNT, 0)

    fun lastProbeAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_PROBE_AT, 0L)
}
