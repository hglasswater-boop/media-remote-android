package dev.mediaremote.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mediaremote.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class AvailableRelease(
    val versionName: String,
    val buildNumber: Int,
    val releaseUrl: String,
)

private object ReleaseUpdateChecker {
    private const val RELEASE_API =
        "https://api.github.com/repos/hglasswater-boop/media-remote-android/releases/tags/debug-latest"
    private const val PREFS = "release_update_check"
    private const val LAST_CHECK = "last_check"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val assetPattern = Regex("^MediaRemote-(.+)-b(\\d+)-(?:release|debug)\\.apk$")

    fun isDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    suspend fun check(): AvailableRelease? = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "YT-Music-Remote/${BuildConfig.VERSION_NAME}")
        }

        try {
            val code = connection.responseCode
            if (code == 404) return@withContext null
            if (code !in 200..299) error("GitHub HTTP $code")

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val releaseUrl = json.optString("html_url")
            val assets = json.getJSONArray("assets")

            var newest: AvailableRelease? = null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                val match = assetPattern.matchEntire(name) ?: continue
                val build = match.groupValues[2].toIntOrNull() ?: continue
                val candidate = AvailableRelease(
                    versionName = match.groupValues[1],
                    buildNumber = build,
                    releaseUrl = releaseUrl,
                )
                if (newest == null || candidate.buildNumber > newest.buildNumber) {
                    newest = candidate
                }
            }

            newest?.takeIf { it.buildNumber > BuildConfig.VERSION_CODE }
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
fun StartupUpdateCheck() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<AvailableRelease?>(null) }

    LaunchedEffect(Unit) {
        if (!ReleaseUpdateChecker.isDue(context)) return@LaunchedEffect
        runCatching { ReleaseUpdateChecker.check() }
            .onSuccess { found ->
                ReleaseUpdateChecker.markChecked(context)
                release = found
            }
    }

    release?.let { available ->
        UpdateAvailableDialog(
            available = available,
            onDismiss = { release = null },
            onOpen = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.releaseUrl)))
                release = null
            },
        )
    }
}

@Composable
fun ManualUpdateCheckButton(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AvailableRelease?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    TextButton(
        onClick = {
            if (checking) return@TextButton
            checking = true
            message = null
            scope.launch {
                runCatching { ReleaseUpdateChecker.check() }
                    .onSuccess { found ->
                        ReleaseUpdateChecker.markChecked(context)
                        if (found != null) {
                            release = found
                        } else {
                            message = "最新版です\n${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
                        }
                    }
                    .onFailure { error ->
                        message = "更新を確認できませんでした\n${error.message ?: "通信エラー"}"
                    }
                checking = false
            }
        },
        enabled = !checking,
        modifier = modifier,
    ) {
        Text(if (checking) "確認中…" else "更新を確認")
    }

    release?.let { available ->
        UpdateAvailableDialog(
            available = available,
            onDismiss = { release = null },
            onOpen = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.releaseUrl)))
                release = null
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("アップデート") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun UpdateAvailableDialog(
    available: AvailableRelease,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YT Music Remoteを更新できます") },
        text = {
            Column {
                Text("${available.versionName} (build ${available.buildNumber}) が利用できます。")
                Spacer(Modifier.height(8.dp))
                Text(
                    "GitHub Releasesで更新内容と署名済みAPKを確認できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpen) {
                Text("更新ページを開く")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("後で")
            }
        },
    )
}
