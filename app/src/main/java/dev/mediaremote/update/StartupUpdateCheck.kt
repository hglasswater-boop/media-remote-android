package dev.mediaremote.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.FileProvider
import dev.mediaremote.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class AvailableRelease(
    val versionName: String,
    val buildNumber: Int,
    val releaseUrl: String,
    val apkUrl: String,
)

private object ReleaseUpdateChecker {
    private const val RELEASE_API =
        "https://api.github.com/repos/hglasswater-boop/media-remote-android/releases/tags/debug-latest"
    private const val PREFS = "release_update_check"
    private const val LAST_CHECK = "last_check"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val releaseAssetPattern = Regex("^MediaRemote-(.+)-b(\\d+)-release\\.apk$")
    private val compatibilityAssetPattern = Regex("^MediaRemote-(.+)-b(\\d+)-debug\\.apk$")

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

            var releaseAsset: AvailableRelease? = null
            var compatibilityAsset: AvailableRelease? = null

            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                val apkUrl = asset.optString("browser_download_url")
                if (apkUrl.isBlank()) continue

                releaseAssetPattern.matchEntire(name)?.let { match ->
                    val build = match.groupValues[2].toIntOrNull() ?: return@let
                    val candidate = AvailableRelease(
                        versionName = match.groupValues[1],
                        buildNumber = build,
                        releaseUrl = releaseUrl,
                        apkUrl = apkUrl,
                    )
                    if (releaseAsset == null || candidate.buildNumber > releaseAsset!!.buildNumber) {
                        releaseAsset = candidate
                    }
                }

                compatibilityAssetPattern.matchEntire(name)?.let { match ->
                    val build = match.groupValues[2].toIntOrNull() ?: return@let
                    val candidate = AvailableRelease(
                        versionName = match.groupValues[1],
                        buildNumber = build,
                        releaseUrl = releaseUrl,
                        apkUrl = apkUrl,
                    )
                    if (
                        compatibilityAsset == null ||
                        candidate.buildNumber > compatibilityAsset!!.buildNumber
                    ) {
                        compatibilityAsset = candidate
                    }
                }
            }

            (releaseAsset ?: compatibilityAsset)
                ?.takeIf { it.buildNumber > BuildConfig.VERSION_CODE }
        } finally {
            connection.disconnect()
        }
    }
}

private object InAppUpdateInstaller {
    private const val MIME_APK = "application/vnd.android.package-archive"

    suspend fun downloadAndVerify(context: Context, release: AvailableRelease): File =
        withContext(Dispatchers.IO) {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updateDir.listFiles()?.forEach { existing ->
                if (existing.name != ".nomedia") existing.delete()
            }

            val partial = File(updateDir, "MediaRemote-${release.buildNumber}.apk.part")
            val apk = File(updateDir, "MediaRemote-${release.buildNumber}.apk")

            val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "YT-Music-Remote/${BuildConfig.VERSION_NAME}")
            }

            try {
                val code = connection.responseCode
                if (code !in 200..299) error("APK download HTTP $code")

                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (!partial.isFile || partial.length() <= 0L) {
                partial.delete()
                error("ダウンロードしたAPKが空です")
            }

            if (!partial.renameTo(apk)) {
                partial.copyTo(apk, overwrite = true)
                partial.delete()
            }

            runCatching { verifyPackage(context, apk, release) }
                .onFailure { apk.delete() }
                .getOrThrow()

            apk
        }

    fun openInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, apk: File, release: AvailableRelease) {
        val packageManager = context.packageManager
        // Request both representations. Android 9+ should populate signingInfo, but some Android 10
        // package archive parsers return it empty for an APK on disk while still exposing signatures.
        // The legacy field is used only as a compatibility source for the same SHA-256 comparison.
        val signingFlags =
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val downloaded = packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            signingFlags,
        ) ?: error("APKとして読み取れません")
        val installed = packageManager.getPackageInfo(
            context.packageName,
            signingFlags,
        )

        if (downloaded.packageName != context.packageName) {
            error("更新APKのpackage名が一致しません")
        }
        if (downloaded.longVersionCode != release.buildNumber.toLong()) {
            error("更新APKのbuild番号がRelease情報と一致しません")
        }
        if (downloaded.longVersionCode <= installed.longVersionCode) {
            error("現在より新しいAPKではありません")
        }

        val installedModernSigners = installed.signingInfo
            ?.let { info ->
                if (info.hasMultipleSigners()) {
                    info.apkContentsSigners
                } else {
                    info.signingCertificateHistory ?: info.apkContentsSigners
                }
            }
            .orEmpty()
        val downloadedModernSigners = downloaded.signingInfo
            ?.let { info ->
                if (info.hasMultipleSigners()) {
                    info.apkContentsSigners
                } else {
                    info.signingCertificateHistory ?: info.apkContentsSigners
                }
            }
            .orEmpty()

        val installedDigests = installedModernSigners
            .map { signature -> sha256(signature.toByteArray()) }
            .toSet()
            .ifEmpty {
                installed.signatures
                    ?.map { signature -> sha256(signature.toByteArray()) }
                    ?.toSet()
                    .orEmpty()
            }
        val downloadedDigests = downloadedModernSigners
            .map { signature -> sha256(signature.toByteArray()) }
            .toSet()
            .ifEmpty {
                downloaded.signatures
                    ?.map { signature -> sha256(signature.toByteArray()) }
                    ?.toSet()
                    .orEmpty()
            }

        if (installedDigests.isEmpty() || downloadedDigests.isEmpty()) {
            error("APK署名を確認できません")
        }
        if (installedDigests.intersect(downloadedDigests).isEmpty()) {
            error("APK署名が現在のアプリと一致しません")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(available.buildNumber) { mutableStateOf(false) }
    var errorMessage by remember(available.buildNumber) { mutableStateOf<String?>(null) }

    val startDownload: () -> Unit = {
        if (!downloading) {
            downloading = true
            errorMessage = null
            scope.launch {
                runCatching {
                    InAppUpdateInstaller.downloadAndVerify(context, available)
                }.onSuccess { apk ->
                    downloading = false
                    runCatching { InAppUpdateInstaller.openInstaller(context, apk) }
                        .onFailure { error ->
                            errorMessage = error.message ?: "インストーラを開けませんでした"
                        }
                }.onFailure { error ->
                    downloading = false
                    errorMessage = error.message ?: "APKをダウンロードできませんでした"
                }
            }
        }
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            startDownload()
        } else {
            errorMessage = "更新には「この提供元のアプリを許可」をONにしてください。"
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!downloading) onDismiss()
        },
        title = { Text("YT Music Remoteを更新できます") },
        text = {
            Column {
                Text("${available.versionName} (build ${available.buildNumber}) が利用できます。")
                Spacer(Modifier.height(8.dp))
                Text(
                    if (downloading) {
                        "署名済みAPKをダウンロード中…\n完了したらインストール画面を自動で開きます。"
                    } else {
                        "APKをアプリ内で取得し、署名を検証してからインストール画面を自動で開きます。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) {
                        startDownload()
                    } else {
                        installPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
            ) {
                Text(if (downloading) "ダウンロード中…" else "更新する")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !downloading,
                onClick = onDismiss,
            ) {
                Text("後で")
            }
        },
    )
}
