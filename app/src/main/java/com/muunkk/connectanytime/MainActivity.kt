package com.muunkk.connectanytime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) Controls.connect(this)
    }
    private var notificationDenied by mutableStateOf(false)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationDenied = !granted
        requestVpn()
    }
    private fun requestVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare == null) Controls.connect(this) else consent.launch(prepare)
    }
    private fun connect() {
        val prefs = (application as ConnectApp).store.prefs
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationDenied = Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            requestVpn()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                val app = application as ConnectApp
                val state by ConnectApp.state.collectAsStateWithLifecycle()
                var activity by rememberSaveable { mutableStateOf(false) }
                if (activity) {
                    BackHandler { activity = false }
                    ActivityScreen(app.log, onBack = { activity = false }, onShare = { lines ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, (listOf(BuildConfig.BUILD_DESCRIPTION) + lines).joinToString("\n")), getString(R.string.share_activity)))
                    })
                } else HomeScreen(app.store, state, notificationDenied, onConnect = ::connect,
                    onDisconnect = { Controls.disconnect(this) }, onActivity = { activity = true },
                    onStayChanged = {
                        if (state.busy) startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ARM))
                    })
            }
        }
    }
}
