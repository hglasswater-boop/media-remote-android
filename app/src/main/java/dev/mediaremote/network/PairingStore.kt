package dev.mediaremote.network

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

object PairingStore {
    private const val PREFS = "media_remote"
    private const val KEY_TOKEN = "pairing_token"

    fun getOrCreateToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { return it }

        val bytes = ByteArray(18).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')

        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }
}
