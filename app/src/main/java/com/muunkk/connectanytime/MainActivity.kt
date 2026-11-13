@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
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
    override fun onStart() {
        super.onStart()
        val updater = (application as ConnectApp).updater
        updater.foreground = true
        updater.checkIfDue()
    }
    override fun onStop() {
        (application as ConnectApp).updater.foreground = false
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                val app = application as ConnectApp
                val state by ConnectApp.state.collectAsStateWithLifecycle()
                val update by app.updater.state.collectAsStateWithLifecycle()
                // Android's install confirmation, shown from here: a broadcast receiver may not
                // open a screen, and this only runs while the app is in front.
                val confirm by app.updater.confirmation.collectAsStateWithLifecycle()
                LaunchedEffect(confirm) {
                    app.updater.takeConfirmation()?.let { runCatching { startActivity(it) } }
                }
                var activity by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = activity) { activity = false }
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                // Activity slides in from the end edge and back out, with the theme's motion.
                AnimatedContent(activity, Modifier.background(MaterialTheme.colorScheme.surface), transitionSpec = {
                    val direction = (if (targetState) 1 else -1) * (if (rtl) -1 else 1)
                    (slideInHorizontally(spatial) { direction * it / 4 } + fadeIn(effects))
                        .togetherWith(slideOutHorizontally(spatial) { -direction * it / 4 } + fadeOut(effects))
                }, label = "screen") { showActivity ->
                    if (showActivity) {
                        ActivityScreen(app.log, onBack = { activity = false }, onShare = { lines ->
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, (listOf(BuildConfig.BUILD_DESCRIPTION) + lines).joinToString("\n")), getString(R.string.share_activity)))
                        })
                    } else HomeScreen(app.store, state, notificationDenied, onConnect = ::connect,
                        onDisconnect = { Controls.disconnect(this@MainActivity) }, onActivity = { activity = true },
                        onStayChanged = {
                            if (state.busy) startService(Intent(this@MainActivity, ConnectVpnService::class.java).setAction(ConnectVpnService.ARM))
                        }, update = update, onCheckUpdate = app.updater::check, onInstallUpdate = app.updater::install)
                }
            }
        }
    }
}
