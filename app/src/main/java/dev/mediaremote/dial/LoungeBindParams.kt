package dev.mediaremote.dial

import android.net.Uri
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

internal class LoungeBindParams(
    private val deviceId: String,
    private val screenName: String,
    private val modelName: String,
) {
    @Volatile var loungeIdToken: String? = null
    @Volatile var sid: String? = null
    @Volatile var gsessionId: String? = null
    @Volatile var aid: Int = 3
        private set

    private var rid: Int = Random.nextInt(41_000, 50_000)

    @Synchronized
    fun updateFrom(message: LoungeMessage) {
        when (message.name) {
            "c" -> {
                val array = message.payload as? org.json.JSONArray
                sid = array?.optString(0)?.takeIf { it.isNotBlank() }
            }
            "S" -> gsessionId = message.payload?.toString()?.takeIf { it.isNotBlank() }
        }
        aid = maxOf(aid, message.aid)
    }

    @Synchronized
    fun initSessionQuery(): String {
        val token = requireNotNull(loungeIdToken) { "Missing lounge token" }
        return encode(
            common(token) + mapOf(
                "deviceInfo" to deviceInfo().toString(),
                "RID" to (rid++).toString(),
                "CVER" to "1",
            ),
        )
    }

    @Synchronized
    fun rpcQuery(): String {
        val token = requireNotNull(loungeIdToken) { "Missing lounge token" }
        val currentSid = requireNotNull(sid) { "Missing SID" }
        val currentGsession = requireNotNull(gsessionId) { "Missing gsessionid" }
        return encode(
            common(token) + mapOf(
                "RID" to "rpc",
                "SID" to currentSid,
                "CI" to "0",
                "AID" to aid.toString(),
                "gsessionid" to currentGsession,
                "TYPE" to "xmlhttp",
            ),
        )
    }

    @Synchronized
    fun sendMessageQuery(responseAid: Int?): String {
        val token = requireNotNull(loungeIdToken) { "Missing lounge token" }
        val currentSid = requireNotNull(sid) { "Missing SID" }
        val currentGsession = requireNotNull(gsessionId) { "Missing gsessionid" }
        responseAid?.let { aid = maxOf(aid, it) }
        val query = encode(
            common(token) + mapOf(
                "deviceInfo" to deviceInfo().toString(),
                "SID" to currentSid,
                "RID" to (rid++).toString(),
                "AID" to aid.toString(),
                "gsessionid" to currentGsession,
            ),
        )
        if (responseAid == null) aid++
        return query
    }

    private fun common(token: String): Map<String, String> = linkedMapOf(
        "device" to "LOUNGE_SCREEN",
        "id" to deviceId,
        "obfuscatedGaiaId" to "",
        "name" to screenName,
        "app" to "ytcr",
        "theme" to "m",
        "capabilities" to "dsp,mic,dpa,ntb",
        "cst" to "m",
        "mdxVersion" to "2",
        "loungeIdToken" to token,
        "VER" to "8",
        "v" to "2",
        "zx" to UUID.randomUUID().toString().replace("-", "").take(12),
        "t" to "1",
    )

    private fun deviceInfo(): JSONObject = JSONObject()
        .put("brand", "YT Music Remote")
        .put("model", modelName)
        .put("year", 0)
        // Keep the TVHTML5 identity used by the established Lounge receiver implementation.
        .put("os", "Windows")
        .put("osVersion", "10.0")
        .put("chipset", "")
        .put("clientName", "TVHTML5")
        .put("dialAdditionalDataSupportLevel", "unsupported")
        .put("mdxDialServerType", "MDX_DIAL_SERVER_TYPE_UNKNOWN")

    companion object {
        fun encode(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
            "${Uri.encode(key)}=${Uri.encode(value)}"
        }
    }
}
