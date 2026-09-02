package dev.mediaremote

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.mediaremote.network.LocalAddress
import dev.mediaremote.network.PairingStore
import dev.mediaremote.network.RemoteClient
import dev.mediaremote.network.RemoteServerService

class MainActivity : ComponentActivity() {
    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (permissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(permissions.toTypedArray())
        }

        setContent {
            MaterialTheme {
                MediaRemoteApp()
            }
        }
    }
}

private enum class Role {
    Host,
    Remote,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaRemoteApp() {
    var role by remember { mutableStateOf(Role.Host) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MediaRemote") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                Role.Remote -> RemoteScreen()
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

    Text(
        "この端末で YouTube Music を再生し、別端末から操作します。",
        style = MaterialTheme.typography.bodyLarge,
    )

    InfoCard("接続先", "$address:${RemoteServerService.PORT}")
    InfoCard("ペアリングキー", token)

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

    Button(
        onClick = { snapshot = MediaSessionBridge.snapshot(context) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("YouTube Music を再検出")
    }

    NowPlaying(snapshot)
}

@Composable
private fun RemoteScreen() {
    var host by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf("未接続") }
    var snapshot by remember { mutableStateOf(MediaSnapshot(false)) }

    fun send(command: String, value: Long = 0L) {
        resultMessage = "送信中…"
        RemoteClient.send(host, token, command, value) { response ->
            resultMessage = response.message
            response.snapshot?.let { snapshot = it }
        }
    }

    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("再生端末のIP") },
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
        onClick = { send("status") },
        enabled = host.isNotBlank() && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("接続 / 状態取得")
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
