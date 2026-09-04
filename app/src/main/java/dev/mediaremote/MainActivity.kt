package dev.mediaremote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.mediaremote.network.RemoteServerService
import dev.mediaremote.ui.YouTubeMusicRemoteApp
import dev.mediaremote.update.ManualUpdateCheckButton
import dev.mediaremote.update.StartupUpdateCheck

class MainActivity : ComponentActivity() {
    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    YouTubeMusicRemoteApp()
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

    override fun onResume() {
        super.onResume()
        startCastReceiver()
    }

    private fun startCastReceiver() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RemoteServerService::class.java),
            )
        }.onFailure { error ->
            Log.w(TAG, "Could not start YouTube Music Cast receiver", error)
        }
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
