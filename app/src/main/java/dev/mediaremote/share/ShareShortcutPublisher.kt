package dev.mediaremote.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.mediaremote.MainActivity

/** Publishes a high-priority Direct Share target for YouTube Music text links. */
object ShareShortcutPublisher {
    private const val SHORTCUT_ID = "send-youtube-music-to-playback"
    const val SHARE_CATEGORY = "dev.mediaremote.category.YOUTUBE_MUSIC_SHARE"

    fun publish(context: Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel("再生端末へ送る")
            .setLongLabel("YT Music Remoteへ送る")
            .setRank(0)
            .setLongLived(true)
            .setCategories(setOf(SHARE_CATEGORY))
            .setIntent(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                },
            )
            .build()

        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
        }
    }

    fun reportUsed(context: Context) {
        runCatching {
            ShortcutManagerCompat.reportShortcutUsed(context, SHORTCUT_ID)
        }
    }
}
