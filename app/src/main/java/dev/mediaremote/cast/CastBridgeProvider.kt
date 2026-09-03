package dev.mediaremote.cast

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import dev.mediaremote.media.YouTubeMusicLink
import dev.mediaremote.network.RemoteClient
import dev.mediaremote.network.RemoteTargetStore

/**
 * Private-ish IPC bridge used by the patched ReVanced GmsCore on the same phone.
 *
 * The provider must be exported so GmsCore can call it, but every call is guarded by Binder UID
 * package verification. The bridge never exposes the paired host token to GmsCore; it only queues
 * commands through the existing authenticated RemoteClient transport.
 */
class CastBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val appContext = context?.applicationContext
            ?: return result(false, "Context unavailable")

        if (!isAllowedCaller()) {
            Log.w(TAG, "Rejected Cast bridge caller uid=${Binder.getCallingUid()}")
            return result(false, "Caller is not an allowed ReVanced GmsCore package")
        }

        return when (method) {
            METHOD_STATUS -> {
                result(
                    ok = true,
                    message = if (RemoteTargetStore.load(appContext) != null) "paired" else "not_paired",
                ).apply {
                    putBoolean("paired", RemoteTargetStore.load(appContext) != null)
                    putInt("protocolVersion", PROTOCOL_VERSION)
                }
            }

            METHOD_PLAY_URL -> {
                val raw = extras?.getString("url").orEmpty()
                val link = YouTubeMusicLink.extract(raw)
                    ?: return result(false, "Not a YouTube Music URL")
                dispatch("playUrl", text = link.playbackUri.toString())
            }

            METHOD_RAW_CAST_MESSAGE -> {
                val namespace = extras?.getString("namespace").orEmpty()
                val payload = extras?.getString("payload").orEmpty()
                if (namespace.isBlank() || payload.isBlank()) {
                    return result(false, "Missing namespace or payload")
                }

                val actions = CastBridgeMessageParser.parse(namespace, payload)
                if (actions.isEmpty()) {
                    Log.d(TAG, "No bridge action for $namespace payload=$payload")
                    return result(true, "ignored").apply { putInt("actionCount", 0) }
                }

                var accepted = 0
                actions.forEach { action ->
                    if (dispatchAction(action).getBoolean("ok")) accepted++
                }
                result(accepted == actions.size, "queued $accepted/${actions.size}").apply {
                    putInt("actionCount", accepted)
                }
            }

            METHOD_PLAY -> dispatch("play")
            METHOD_PAUSE -> dispatch("pause")
            METHOD_STOP -> dispatch("stop")
            METHOD_NEXT -> dispatch("next")
            METHOD_PREVIOUS -> dispatch("previous")
            METHOD_SEEK_TO -> dispatch("seekTo", value = extras?.getLong("positionMs", -1L) ?: -1L)
            else -> result(false, "Unknown method: $method")
        }
    }

    private fun dispatchAction(action: CastBridgeMessageParser.Action): Bundle =
        dispatch(action.command, action.value, action.text)

    private fun dispatch(command: String, value: Long = 0L, text: String = ""): Bundle {
        val appContext = context?.applicationContext ?: return result(false, "Context unavailable")
        val target = RemoteTargetStore.load(appContext)
            ?: return result(false, "No paired playback device")

        if (command == "seekTo" && value < 0L) {
            return result(false, "Invalid seek position")
        }

        RemoteClient.send(
            host = target.host,
            token = target.token,
            command = command,
            value = value,
            text = text,
            port = target.port,
        ) { response ->
            if (response.ok) {
                Log.d(TAG, "Forwarded $command to paired host")
            } else {
                Log.w(TAG, "Host rejected $command: ${response.message}")
            }
        }

        return result(true, "queued")
    }

    private fun isAllowedCaller(): Boolean {
        val appContext = context?.applicationContext ?: return false
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true

        val packages = appContext.packageManager.getPackagesForUid(uid).orEmpty().toSet()
        return packages.any(ALLOWED_GMS_PACKAGES::contains)
    }

    private fun result(ok: Boolean, message: String): Bundle = Bundle().apply {
        putBoolean("ok", ok)
        putString("message", message)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = ".castbridge"
        const val PROTOCOL_VERSION = 1

        const val METHOD_STATUS = "status"
        const val METHOD_PLAY_URL = "play_url"
        const val METHOD_RAW_CAST_MESSAGE = "raw_cast_message"
        const val METHOD_PLAY = "play"
        const val METHOD_PAUSE = "pause"
        const val METHOD_STOP = "stop"
        const val METHOD_NEXT = "next"
        const val METHOD_PREVIOUS = "previous"
        const val METHOD_SEEK_TO = "seek_to"

        private const val TAG = "CastBridgeProvider"
        private val ALLOWED_GMS_PACKAGES = setOf(
            "app.revanced.android.gms",
            "com.mgoogle.android.gms",
        )
    }
}
