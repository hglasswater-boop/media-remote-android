package dev.mediaremote

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.mediaremote.media.MediaSessionBridge
import dev.mediaremote.media.MediaSnapshot
import dev.mediaremote.network.DiscoveredHost
import dev.mediaremote.network.LocalAddress
import dev.mediaremote.network.NsdHostDiscovery
import dev.mediaremote.network.PairingLinks
import dev.mediaremote.network.PairingStore
import dev.mediaremote.network.RemoteClient
import dev.mediaremote.network.RemoteServerService
import dev.mediaremote.network.RemoteTarget
import dev.mediaremote.network.RemoteTargetStore
import dev.mediaremote.ui.QrCode
import dev.mediaremote.update.StartupUpdateCheck

class MainActivity : ComponentActivity() {
    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val sharedTextState = mutableStateOf<String?>(null)
    private val pairingLinkState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (permissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(permissions.toTypedArray())
        }

        setContent {
            MaterialTheme {
                MediaRemoteApp(
                    sharedText = sharedTextState.value,
                    pairingLink = pairingLinkState.value,
                    onSharedTextConsumed = { sharedTextState.value = null },
                    onPairingLinkConsumed = { pairingLinkState.value = null },
                )
                StartupUpdateCheck()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    sharedTextState.value = intent.getStringExtra(Intent.EXTRA_TEXT)
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data?.toString()
                if (PairingLinks.parse(data) != null) pairingLinkState.value = data
            }
        }
    }
}

private enum class Role {
    Host,
    Remote,
}

private const val DOWNLOAD_URL =
    "https://github.com/hglasswater-boop/media-remote-android/releases/download/debug-latest/MediaRemote-latest.apk"
private const val YOUTUBE_MUSIC_PLAYLISTS_URL = "https://music.youtube.com/library/playlists"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaRemoteApp(
    sharedText: String?,
    pairingLink: String?,
    onSharedTextConsumed: () -> Unit,
    onPairingLinkConsumed: () -> Unit,
) {
    var role by remember { mutableStateOf(Role.Host) }

    LaunchedEffect(sharedText, pairingLink) {
        if (!sharedText.isNullOrBlank() || !pairingLink.isNullOrBlank()) {
            role = Role.Remote
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MediaRemote") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = role == Role.Host,
                    onClick = { role = Role.Host },
                    label = { Text("再生端末") },
                )
                FilterChip(
                    selected = role == Role.Remote,
                    onClick = { role = Role.Remote },
                    label = { Text("操作端末") },
                )
            }

            when (role) {
                Role.Host -> HostScreen()
                Role.Remote -> RemoteScreen(
                    sharedText = sharedText,
                    pairingLink = pairingLink,
                    onSharedTextConsumed = onSharedTextConsumed,
                    onPairingLinkConsumed = onPairingLinkConsumed,
                )
            }
        }
    }
}

@Composable
private fun HostScreen() {
    val context = LocalContext.current
    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var snapshot by remember { mutableStateOf(MediaSessionBridge.snapshot(context)) }
    val token = remember { PairingStore.getOrCreateToken(context) }
    val address = remember { LocalAddress.bestIpv4Address() }
    val pairingLink = remember(address, token) {
        PairingLinks.create(address, RemoteServerService.PORT, token)
    }

    Text(
        "この端末で YouTube Music を再生し、別端末から操作します。",
        style = MaterialTheme.typography.bodyLarge,
    )

    InfoCard("接続先", "$address:${RemoteServerService.PORT}")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("操作端末をペアリング", fontWeight = FontWeight.Bold)
            QrCode(pairingLink)
            Text(
                "操作端末で読み取ると、IP・Port・ペアリングキーを自動登録します。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("MediaRemoteが未インストールの場合", fontWeight = FontWeight.Bold)
            QrCode(DOWNLOAD_URL)
            Text(
                "このQRから最新の署名済みAPKをダウンロードできます。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (!listenerEnabled) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("メディア操作権限が必要", fontWeight = FontWeight.Bold)
                Text("通知へのアクセスを許可すると、YouTube Music の MediaSession を取得できます。")
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                ) {
                    Text("通知へのアクセスを開く")
                }
                Button(
                    onClick = {
                        listenerEnabled = isNotificationListenerEnabled(context)
                        snapshot = MediaSessionBridge.snapshot(context)
                    },
                ) {
                    Text("許可状態を再確認")
                }
            }
        }
    }

    Button(
        onClick = {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteServerService::class.java),
            )
            snapshot = MediaSessionBridge.snapshot(context)
        },
        enabled = listenerEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("LANリモートを開始")
    }

    Text(
        "開始すると、この端末は同じLAN上のMediaRemoteから自動検出されます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = { snapshot = MediaSessionBridge.snapshot(context) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("YouTube Music を再検出")
    }

    NowPlaying(snapshot)
}

@Composable
private fun RemoteScreen(
    sharedText: String?,
    pairingLink: String?,
    onSharedTextConsumed: () -> Unit,
    onPairingLinkConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val savedTarget = remember { RemoteTargetStore.load(context) }
    var host by remember { mutableStateOf(savedTarget?.host.orEmpty()) }
    var port by remember { mutableIntStateOf(savedTarget?.port ?: RemoteServerService.PORT) }
    var token by remember { mutableStateOf(savedTarget?.token.orEmpty()) }
    var selectionText by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf(if (savedTarget == null) "未接続" else "ペアリング済み") }
    var snapshot by remember { mutableStateOf(MediaSnapshot(false)) }
    var discoveredHosts by remember { mutableStateOf(emptyList<DiscoveredHost>()) }
    val discovery = remember(context) { NsdHostDiscovery(context) }

    DisposableEffect(discovery) {
        discovery.start { hosts -> discoveredHosts = hosts }
        onDispose { discovery.stop() }
    }

    fun saveTargetIfValid() {
        if (host.isNotBlank() && token.isNotBlank() && port in 1..65535) {
            RemoteTargetStore.save(context, RemoteTarget(host, port, token))
        }
    }

    fun send(command: String, value: Long = 0L, text: String = "") {
        if (host.isBlank() || token.isBlank()) {
            resultMessage = "先に再生端末をペアリングしてください"
            return
        }
        saveTargetIfValid()
        resultMessage = "送信中…"
        RemoteClient.send(
            host = host,
            token = token,
            command = command,
            value = value,
            text = text,
            port = port,
        ) { response ->
            resultMessage = response.message
            response.snapshot?.let { snapshot = it }
        }
    }

    LaunchedEffect(pairingLink) {
        val pair = PairingLinks.parse(pairingLink)
        if (pair != null) {
            host = pair.host
            port = pair.port
            token = pair.token
            RemoteTargetStore.save(context, RemoteTarget(host, port, token))
            resultMessage = "再生端末をペアリングしました"
            onPairingLinkConsumed()
            send("status")
        }
    }

    LaunchedEffect(sharedText) {
        val incoming = sharedText?.trim().orEmpty()
        if (incoming.isBlank()) return@LaunchedEffect
        val youtubeUrl = extractYouTubeUrl(incoming)
        selectionText = youtubeUrl ?: incoming
        onSharedTextConsumed()
        if (youtubeUrl != null && host.isNotBlank() && token.isNotBlank()) {
            resultMessage = "共有されたプレイリストを再生端末へ転送中…"
            send("playUrl", text = youtubeUrl)
        }
    }

    Text("再生端末を自動検出", fontWeight = FontWeight.Bold)
    if (discoveredHosts.isEmpty()) {
        Text(
            "同じLAN上のMediaRemoteを探索中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        discoveredHosts.forEach { discovered ->
            DiscoveredHostButton(
                discovered = discovered,
                selected = host == discovered.address && port == discovered.port,
                onClick = {
                    host = discovered.address
                    port = discovered.port
                    resultMessage = "${discovered.serviceName} を選択"
                },
            )
        }
    }

    OutlinedTextField(
        value = host,
        onValueChange = {
            host = it
            port = RemoteServerService.PORT
        },
        label = { Text("再生端末のIP（手動指定も可）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        "Port $port",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = token,
        onValueChange = { token = it.trim() },
        label = { Text("ペアリングキー") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = { send("status") },
        enabled = host.isNotBlank() && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("接続 / 状態取得")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("選曲 / プレイリスト", fontWeight = FontWeight.Bold)
            Text(
                "同じGoogleアカウントのYouTube Musicでプレイリストを選び、共有先にMediaRemoteを指定すると、そのまま再生端末へ転送します。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_MUSIC_PLAYLISTS_URL)).apply {
                        setPackage(MediaSessionBridge.TARGET_PACKAGE)
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_MUSIC_PLAYLISTS_URL))) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("YouTube Musicのプレイリストを開く")
            }
            OutlinedTextField(
                value = selectionText,
                onValueChange = { selectionText = it },
                label = { Text("曲名 または YouTube Music URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { send("playSearch", text = selectionText) },
                    enabled = selectionText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("曲名で再生")
                }
                Button(
                    onClick = { send("playUrl", text = selectionText) },
                    enabled = extractYouTubeUrl(selectionText) != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("URLを再生")
                }
            }
        }
    }

    Text(resultMessage)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { send("previous") }, enabled = snapshot.available) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
        }
        Button(onClick = { send("play") }, enabled = snapshot.available) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
        }
        Button(onClick = { send("pause") }, enabled = snapshot.available) {
            Icon(Icons.Default.Pause, contentDescription = "Pause")
        }
        Button(onClick = { send("next") }, enabled = snapshot.available) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        Button(onClick = { send("seekBy", -10_000L) }, enabled = snapshot.available) {
            Icon(Icons.Default.FastRewind, contentDescription = "Back 10 seconds")
            Text("10秒")
        }
        Button(onClick = { send("seekBy", 10_000L) }, enabled = snapshot.available) {
            Icon(Icons.Default.FastForward, contentDescription = "Forward 10 seconds")
            Text("10秒")
        }
    }

    Spacer(Modifier.height(4.dp))
    NowPlaying(snapshot)
}

private fun extractYouTubeUrl(text: String): String? =
    Regex("https?://(?:(?:music\\.)?youtube\\.com|youtu\\.be)/[^\\s]+", RegexOption.IGNORE_CASE)
        .find(text)
        ?.value

@Composable
private fun DiscoveredHostButton(
    discovered: DiscoveredHost,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                if (selected) "✓ ${discovered.serviceName}" else discovered.serviceName,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${discovered.address}:${discovered.port}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun NowPlaying(snapshot: MediaSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Now Playing", fontWeight = FontWeight.Bold)
            if (!snapshot.available) {
                Text("YouTube Music のアクティブな MediaSession が見つかりません")
            } else {
                Text(snapshot.title.ifBlank { "タイトル不明" })
                Text(snapshot.artist.ifBlank { "アーティスト不明" })
                val state = if (snapshot.playing) "再生中" else "停止中"
                Text("$state • ${formatTime(snapshot.positionMs)} / ${formatTime(snapshot.durationMs)}")
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    return manager.isNotificationListenerAccessGranted(
        android.content.ComponentName(context, dev.mediaremote.media.MediaNotificationListener::class.java),
    )
}
