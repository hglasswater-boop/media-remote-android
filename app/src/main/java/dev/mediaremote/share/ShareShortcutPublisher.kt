package dev.mediaremote.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.mediaremote.MainActivity
import dev.mediaremote.R

/** Publishes and promotes the Direct Share target used for YouTube Music links. */
object ShareShortcutPublisher {
    private const val SHORTCUT_ID = "send-youtube-music-to-playback"
    const val SHARE_CATEGORY = "dev.mediaremote.category.YOUTUBE_MUSIC_SHARE"

    private fun shortcut(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel("再生端末へ送る")
            .setLongLabel("YT Music Remoteで再生端末へ送る")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_send_to_playback))
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

    /**
     * Publish at app startup so the Android/Samsung Sharesheet can discover the target.
     * setDynamicShortcuts is the platform-recommended initialization path for Direct Share.
     */
    fun publish(context: Context) {
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut(context)))
        }
    }

    /**
     * A completed ACTION_SEND is equivalent to selecting this shortcut.
     * Re-push it to refresh recency, then report the actual usage so launcher prediction models
     * can promote it in the Sharesheet over time.
     */
    fun reportUsed(context: Context) {
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context))
            ShortcutManagerCompat.reportShortcutUsed(context, SHORTCUT_ID)
        }
    }
}
