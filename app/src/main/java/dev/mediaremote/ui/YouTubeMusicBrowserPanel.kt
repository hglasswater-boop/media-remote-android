package dev.mediaremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mediaremote.media.YouTubeMusicBrowser

private data class BrowseLevel(
    val mediaId: String,
    val title: String,
)

@Composable
fun YouTubeMusicBrowserPanel(
    onPlayMediaId: (String) -> Unit,
) {
    val context = LocalContext.current
    val browser = remember(context) { YouTubeMusicBrowser(context) }
    var connectionState by remember {
        mutableStateOf<YouTubeMusicBrowser.ConnectionState>(
            YouTubeMusicBrowser.ConnectionState.Disconnected,
        )
    }
    var levels by remember { mutableStateOf(emptyList<BrowseLevel>()) }
    var items by remember { mutableStateOf(emptyList<YouTubeMusicBrowser.Item>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(mediaId: String, title: String, push: Boolean) {
        loading = true
        error = null
        browser.loadChildren(mediaId) { result ->
            loading = false
            result.onSuccess { children ->
                items = children
                levels = when {
                    push -> levels + BrowseLevel(mediaId, title)
                    levels.isEmpty() -> listOf(BrowseLevel(mediaId, title))
                    else -> levels.dropLast(1) + BrowseLevel(mediaId, title)
                }
            }.onFailure {
                error = it.message ?: "YouTube Musicの一覧を取得できませんでした"
            }
        }
    }

    DisposableEffect(browser) {
        browser.connect { state ->
            connectionState = state
            if (state is YouTubeMusicBrowser.ConnectionState.Connected) {
                load(state.rootId, "YouTube Music", push = false)
            }
        }
        onDispose { browser.disconnect() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("YouTube Music ライブラリ", fontWeight = FontWeight.Bold)
            Text(
                "同じGoogleアカウントのYouTube MusicをMediaRemoteから直接ブラウズします。",
                style = MaterialTheme.typography.bodySmall,
            )

            when (val state = connectionState) {
                YouTubeMusicBrowser.ConnectionState.Disconnected -> Text("未接続")
                YouTubeMusicBrowser.ConnectionState.Connecting -> Text("YouTube Musicへ接続中…")
                is YouTubeMusicBrowser.ConnectionState.Failed -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "この端末のYouTube Musicが外部MediaBrowserを許可しない場合は、下の共有方式を利用できます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is YouTubeMusicBrowser.ConnectionState.Connected -> {
                    val current = levels.lastOrNull()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (levels.size > 1) {
                            OutlinedButton(
                                onClick = {
                                    val parent = levels[levels.lastIndex - 1]
                                    levels = levels.dropLast(1)
                                    load(parent.mediaId, parent.title, push = false)
                                },
                            ) {
                                Text("戻る")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val target = current ?: BrowseLevel(state.rootId, "YouTube Music")
                                load(target.mediaId, target.title, push = false)
                            },
                        ) {
                            Text("更新")
                        }
                    }

                    Text(
                        current?.title ?: "YouTube Music",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    val firstPlayable = items.firstOrNull { it.playable }
                    if (firstPlayable != null && levels.size > 1) {
                        Button(
                            onClick = { onPlayMediaId(firstPlayable.mediaId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("この一覧を再生")
                        }
                    }

                    if (loading) Text("読み込み中…")
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }

                    items.forEach { item ->
                        when {
                            item.browsable -> {
                                OutlinedButton(
                                    onClick = {
                                        load(
                                            item.mediaId,
                                            item.title.ifBlank { "名称なし" },
                                            push = true,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(item.title.ifBlank { "名称なし" })
                                        if (item.subtitle.isNotBlank()) {
                                            Text(
                                                item.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                            item.playable -> {
                                Button(
                                    onClick = { onPlayMediaId(item.mediaId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(item.title.ifBlank { "名称なし" })
                                        if (item.subtitle.isNotBlank()) {
                                            Text(
                                                item.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
