package dev.mediaremote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mediaremote.network.PairingLinks
import dev.mediaremote.ui.YouTubeMusicRemoteApp
import dev.mediaremote.update.ManualUpdateCheckButton
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
                Box(modifier = Modifier.fillMaxSize()) {
                    YouTubeMusicRemoteApp(
                        sharedText = sharedTextState.value,
                        pairingLink = pairingLinkState.value,
                        onSharedTextConsumed = { sharedTextState.value = null },
                        onPairingLinkConsumed = { pairingLinkState.value = null },
                    )
                    ManualUpdateCheckButton(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 4.dp, end = 8.dp),
                    )
                    StartupUpdateCheck()
                }
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
