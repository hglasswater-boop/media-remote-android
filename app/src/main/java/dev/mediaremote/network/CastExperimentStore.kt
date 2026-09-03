package dev.mediaremote.network

import android.content.Context
import java.security.SecureRandom

object CastExperimentStore {
    private const val PREFS = "cast_experiment"
    private const val DEVICE_ID = "device_id"
    private const val BUILD_STATUS = "build_status"
    private const val RECEIVER_METRICS = "receiver_metrics"
    private const val CLOUD_DEVICE_ID = "cloud_device_id"
    private const val PROBE_COUNT = "probe_count"
    private const val LAST_PROBE_AT = "last_probe_at"

    fun deviceId(context: Context): String = stableHex(context, DEVICE_ID, 16, uppercase = false)

    fun buildStatus(context: Context): String = stableHex(context, BUILD_STATUS, 6, uppercase = true)

    fun receiverMetrics(context: Context): String =
        stableHex(context, RECEIVER_METRICS, 8, uppercase = true)

    fun cloudDeviceId(context: Context): String =
        stableHex(context, CLOUD_DEVICE_ID, 16, uppercase = true)

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

    private fun stableHex(
        context: Context,
        key: String,
        byteCount: Int,
        uppercase: Boolean,
    ): String {
        val expectedLength = byteCount * 2
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key, null)
            ?.takeIf { it.length == expectedLength }
            ?.let { return it }

        val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
        val format = if (uppercase) "%02X" else "%02x"
        val generated = bytes.joinToString(separator = "") {
            format.format(it.toInt() and 0xff)
        }
        prefs.edit().putString(key, generated).apply()
        return generated
    }
}
