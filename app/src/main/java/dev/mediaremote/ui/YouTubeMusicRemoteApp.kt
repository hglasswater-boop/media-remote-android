package dev.mediaremote.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.TextButton
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.mediaremote.media.MediaNotificationListener
import dev.mediaremote.media.MediaSessionBridge
import dev.mediaremote.media.MediaSnapshot
import dev.mediaremote.media.YouTubeMusicContentType
import dev.mediaremote.media.YouTubeMusicLink
import dev.mediaremote.network.DiscoveredHost
import dev.mediaremote.network.LocalAddress
import dev.mediaremote.network.NsdHostDiscovery
import dev.mediaremote.network.PairingLinks
import dev.mediaremote.network.PairingStore
import dev.mediaremote.network.RemoteClient
import dev.mediaremote.network.RemoteServerService
import dev.mediaremote.network.RemoteTarget
import dev.mediaremote.network.RemoteTargetStore

private enum class DeviceRole {
    Playback,
    Controller,
}

private const val DOWNLOAD_URL =
    "https://github.com/hglasswater-boop/media-remote-android/releases/download/debug-latest/MediaRemote-latest.apk"
private const val YOUTUBE_MUSIC_LIBRARY_URL = "https://music.youtube.com/library/playlists"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeMusicRemoteApp(
    sharedText: String?,
    pairingLink: String?,
    onSharedTextConsumed: () -> Unit,
    onPairingLinkConsumed: () -> Unit,
) {
    var role by remember { mutableStateOf(DeviceRole.Controller) }

    LaunchedEffect(sharedText, pairingLink) {
        if (!sharedText.isNullOrBlank() || !pairingLink.isNullOrBlank()) {
            role = DeviceRole.Controller
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("YT Music Remote")
                        Text(
                            "YouTube Music 専用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
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
                    selected = role == DeviceRole.Controller,
                    onClick = { role = DeviceRole.Controller },
                    label = { Text("操作する端末") },
                )
                FilterChip(
                    selected = role == DeviceRole.Playback,
                    onClick = { role = DeviceRole.Playback },
                    label = { Text("再生する端末") },
                )
            }

            when (role) {
                DeviceRole.Playback -> PlaybackPhoneScreen()
                DeviceRole.Controller -> ControllerPhoneScreen(
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
private fun PlaybackPhoneScreen() {
    val context = LocalContext.current
    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var snapshot by remember { mutableStateOf(MediaSessionBridge.snapshot(context)) }
    var serverStarted by remember { mutableStateOf(false) }
    val token = remember { PairingStore.getOrCreateToken(context) }
    val address = remember { LocalAddress.bestIpv4Address() }
    val pairingLink = remember(address, token) {
        PairingLinks.create(address, RemoteServerService.PORT, token)
    }

    SectionCard(
        title = "この端末でYouTube Musicを再生",
        body = "YouTube Musicを起動しておけば、別のAndroid端末から曲・プレイリスト・再生操作を送れます。",
    )

    if (!listenerEnabled) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("1. YouTube Musicの操作を許可", fontWeight = FontWeight.Bold)
                Text(
                    "通知へのアクセスを許可すると、YouTube Musicの再生状態とMediaSessionを操作できます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("通知へのアクセスを設定")
                }
                OutlinedButton(
                    onClick = {
                        listenerEnabled = isNotificationListenerEnabled(context)
                        snapshot = MediaSessionBridge.snapshot(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("設定済みか確認")
                }
            }
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("1. YouTube Musicの操作を許可", fontWeight = FontWeight.Bold)
                Text("✓ 設定済み")
                Button(
                    onClick = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, RemoteServerService::class.java),
                        )
                        serverStarted = true
                        snapshot = MediaSessionBridge.snapshot(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (serverStarted) "リモート受付中" else "2. リモート受付を開始")
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("3. 操作端末とペアリング", fontWeight = FontWeight.Bold)
            QrCode(pairingLink)
            Text(
                "操作端末の YT Music Remote で「ペアリングQRを読み取る」を押してください。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "$address:${RemoteServerService.PORT}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("操作端末にアプリがない場合", fontWeight = FontWeight.Bold)
            QrCode(DOWNLOAD_URL)
            Text(
                "このQRから最新の署名済みAPKを直接ダウンロードできます。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    OutlinedButton(
        onClick = { snapshot = MediaSessionBridge.snapshot(context) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("YouTube Musicの状態を更新")
    }

    NowPlaying(snapshot)
}

@Composable
private fun ControllerPhoneScreen(
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
    var searchText by remember { mutableStateOf("") }
    var resultMessage by remember {
        mutableStateOf(if (savedTarget == null) "再生端末をペアリングしてください" else "再生端末とペアリング済み")
    }
    var snapshot by remember { mutableStateOf(MediaSnapshot(false)) }
    var showAdvanced by remember { mutableStateOf(false) }
    var discoveredHosts by remember { mutableStateOf(emptyList<DiscoveredHost>()) }
    val discovery = remember(context) { NsdHostDiscovery(context) }
    val scannerOptions = remember {
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
    }
    val qrScanner = remember(context, scannerOptions) {
        GmsBarcodeScanning.getClient(context, scannerOptions)
    }

    DisposableEffect(discovery) {
        discovery.start { hosts -> discoveredHosts = hosts }
        onDispose { discovery.stop() }
    }

    fun currentTarget(): RemoteTarget? {
        if (host.isBlank() || token.isBlank() || port !in 1..65535) return null
        return RemoteTarget(host.trim(), port, token.trim())
    }

    fun applyTarget(target: RemoteTarget, message: String) {
        host = target.host
        port = target.port
        token = target.token
        RemoteTargetStore.save(context, target)
        resultMessage = message
        RemoteClient.send(
            host = target.host,
            token = target.token,
            command = "status",
            port = target.port,
        ) { response ->
            resultMessage = if (response.ok) "再生端末に接続しました" else response.message
            response.snapshot?.let { snapshot = it }
        }
    }

    fun send(command: String, value: Long = 0L, text: String = "") {
        val target = currentTarget()
        if (target == null) {
            resultMessage = "先に再生端末をペアリングしてください"
            return
        }

        RemoteTargetStore.save(context, target)
        resultMessage = "送信中…"
        RemoteClient.send(
            host = target.host,
            token = target.token,
            command = command,
            value = value,
            text = text,
            port = target.port,
        ) { response ->
            resultMessage = response.message
            response.snapshot?.let { snapshot = it }
        }
    }

    fun scanPairingQr() {
        resultMessage = "QRを読み取っています…"
        qrScanner.startScan()
            .addOnSuccessListener { barcode ->
                val target = PairingLinks.parse(barcode.rawValue)
                if (target == null) {
                    resultMessage = "MediaRemoteのペアリングQRではありません"
                } else {
                    applyTarget(target, "ペアリング情報を読み取りました")
                }
            }
            .addOnCanceledListener {
                resultMessage = "QR読み取りをキャンセルしました"
            }
            .addOnFailureListener { error ->
                resultMessage = error.message ?: "QRを読み取れませんでした"
            }
    }

    LaunchedEffect(pairingLink) {
        val target = PairingLinks.parse(pairingLink)
        if (target != null) {
            applyTarget(target, "ペアリング情報を受け取りました")
            onPairingLinkConsumed()
        }
    }

    LaunchedEffect(sharedText) {
        val incoming = sharedText?.trim().orEmpty()
        if (incoming.isBlank()) return@LaunchedEffect

        val link = YouTubeMusicLink.extract(incoming)
        onSharedTextConsumed()

        if (link == null) {
            searchText = incoming
            resultMessage = "YouTube Musicの共有リンクではありません。曲名検索として入力しました"
            return@LaunchedEffect
        }

        searchText = link.url
        val target = currentTarget()
        if (target == null) {
            resultMessage = "リンクを受け取りました。先に再生端末をペアリングしてください"
            return@LaunchedEffect
        }

        resultMessage = when (link.type) {
            YouTubeMusicContentType.Playlist -> "プレイリストを再生端末へ送信中…"
            YouTubeMusicContentType.AlbumOrMix -> "アルバム / ミックスを再生端末へ送信中…"
            YouTubeMusicContentType.Song -> "曲を再生端末へ送信中…"
            YouTubeMusicContentType.Unknown -> "YouTube Musicリンクを再生端末へ送信中…"
        }
        send("playUrl", text = link.url)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("再生端末", fontWeight = FontWeight.Bold)
            if (currentTarget() == null) {
                Text(
                    "再生端末に表示されたペアリングQRを一度だけ読み取ります。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { scanPairingQr() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ペアリングQRを読み取る")
                }
            } else {
                Text("✓ ペアリング済み")
                Text(
                    resultMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { send("status") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("接続確認")
                    }
                    OutlinedButton(
                        onClick = { scanPairingQr() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("再ペアリング")
                    }
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("YouTube Musicで選ぶ", fontWeight = FontWeight.Bold)
            Text(
                "YouTube Musicで曲・アルバム・プレイリストを選び、［共有］→［YT Music Remote］。選んだ内容をそのまま再生端末へ飛ばします。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { openYouTubeMusic(context) },
                enabled = currentTarget() != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("YouTube Musicを開いて選ぶ")
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("直接検索", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("曲名 / アーティスト / YouTube Music URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { send("playSearch", text = searchText) },
                    enabled = currentTarget() != null && searchText.isNotBlank() && YouTubeMusicLink.extract(searchText) == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("検索して再生")
                }
                Button(
                    onClick = {
                        YouTubeMusicLink.extract(searchText)?.let { send("playUrl", text = it.url) }
                    },
                    enabled = currentTarget() != null && YouTubeMusicLink.extract(searchText) != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("リンクを送る")
                }
            }
        }
    }

    NowPlaying(snapshot)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { send("previous") }, enabled = snapshot.available) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "前の曲")
        }
        Button(onClick = { send("play") }, enabled = snapshot.available) {
            Icon(Icons.Default.PlayArrow, contentDescription = "再生")
        }
        Button(onClick = { send("pause") }, enabled = snapshot.available) {
            Icon(Icons.Default.Pause, contentDescription = "一時停止")
        }
        Button(onClick = { send("next") }, enabled = snapshot.available) {
            Icon(Icons.Default.SkipNext, contentDescription = "次の曲")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        OutlinedButton(onClick = { send("seekBy", -10_000L) }, enabled = snapshot.available) {
            Icon(Icons.Default.FastRewind, contentDescription = "10秒戻る")
            Text("10秒")
        }
        OutlinedButton(onClick = { send("seekBy", 10_000L) }, enabled = snapshot.available) {
            Icon(Icons.Default.FastForward, contentDescription = "10秒進む")
            Text("10秒")
        }
    }

    TextButton(onClick = { showAdvanced = !showAdvanced }) {
        Text(if (showAdvanced) "詳細設定を閉じる" else "詳細設定")
    }

    if (showAdvanced) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("接続詳細", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("再生端末IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port = it }
                    },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("ペアリングキー") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        currentTarget()?.let {
                            RemoteTargetStore.save(context, it)
                            resultMessage = "接続設定を保存しました"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("接続設定を保存")
                }

                if (discoveredHosts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("LANで見つかった再生端末", fontWeight = FontWeight.Bold)
                    Text(
                        "自動検出ではペアリングキーを送らないため、初回接続はQR推奨です。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    discoveredHosts.forEach { discovered ->
                        DiscoveredHostButton(
                            discovered = discovered,
                            selected = host == discovered.address && port == discovered.port,
                            onClick = {
                                host = discovered.address
                                port = discovered.port
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

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
private fun NowPlaying(snapshot: MediaSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("再生中", fontWeight = FontWeight.Bold)
            if (!snapshot.available) {
                Text("YouTube Musicの再生セッションを待っています")
            } else {
                Text(
                    snapshot.title.ifBlank { "タイトル不明" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(snapshot.artist.ifBlank { "アーティスト不明" })
                if (snapshot.album.isNotBlank()) {
                    Text(
                        snapshot.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val state = if (snapshot.playing) "再生中" else "一時停止"
                Text(
                    "$state  ${formatTime(snapshot.positionMs)} / ${formatTime(snapshot.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun openYouTubeMusic(context: Context) {
    val uri = Uri.parse(YOUTUBE_MUSIC_LIBRARY_URL)
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage(MediaSessionBridge.TARGET_PACKAGE)
    }
    runCatching { context.startActivity(appIntent) }
        .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    return manager.isNotificationListenerAccessGranted(
        android.content.ComponentName(context, MediaNotificationListener::class.java),
    )
}
