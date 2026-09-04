package dev.mediaremote.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mediaremote.media.MediaNotificationListener
import dev.mediaremote.media.MediaSessionBridge
import dev.mediaremote.media.MediaSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeMusicRemoteApp() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("YT Music Remote")
                        Text(
                            "YouTube Musicの再生端末",
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
            SectionCard(
                title = "この端末でYouTube Musicを再生",
                body = "操作側のYouTube Musicでキャスト先「YT Music Remote」を選び、この端末を再生端末として使います。操作側への専用アプリのインストールは不要です。",
            )
            PlaybackSetup()
        }
    }
}

@Composable
private fun PlaybackSetup() {
    val context = LocalContext.current
    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var snapshot by remember { mutableStateOf(MediaSessionBridge.snapshot(context)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("1. YouTube Musicの操作を許可", fontWeight = FontWeight.Bold)
            if (!listenerEnabled) {
                Text(
                    "通知へのアクセスを許可すると、YouTube Musicの再生状態を取得し、Cast経由の操作を反映できます。",
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
            } else {
                Text("✓ 設定済み")
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("2. YouTube Music Cast待受", fontWeight = FontWeight.Bold)
            Text(
                "アプリ起動時に自動でCast待受を開始します。操作側のYouTube Musicと同じLANに接続し、Cast一覧からこの端末を選べます。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "✓ アプリ起動時に自動開始",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
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
