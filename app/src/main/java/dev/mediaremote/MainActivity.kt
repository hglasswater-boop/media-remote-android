package dev.mediaremote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import dev.mediaremote.network.PairingLinks
import dev.mediaremote.ui.YouTubeMusicRemoteApp
import dev.mediaremote.update.StartupUpdateCheck

class MainActivity : ComponentActivity() {
    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val sharedTextState = mutableStateOf<String?>(null)
    private val pairingLinkState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        requestRuntimePermissions()

        setContent {
            MaterialTheme {
                YouTubeMusicRemoteApp(
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

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (permissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    sharedTextState.value = intent.getStringExtra(Intent.EXTRA_TEXT)
                }
            }

            Intent.ACTION_VIEW -> {
                val raw = intent.data?.toString()
                if (PairingLinks.parse(raw) != null) {
                    pairingLinkState.value = raw
                }
            }
        }
    }
}
