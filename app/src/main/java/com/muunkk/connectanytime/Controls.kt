package com.muunkk.connectanytime

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

object Controls {
    fun connect(context: Context) {
        context.startForegroundService(Intent(context, ConnectVpnService::class.java).setAction(ConnectVpnService.CONNECT))
    }
    fun disconnect(context: Context) {
        val store = (context.applicationContext as ConnectApp).store
        store.disconnected = true
        context.startService(Intent(context, ConnectVpnService::class.java).setAction(ConnectVpnService.DISCONNECT))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = (context.applicationContext as ConnectApp).store
        // Credential-encrypted prefs are only available after first unlock. No
        // direct-boot receiver, and no password login from a boot or sticky start.
        if (store.stayConnected && store.hasSession && !store.disconnected && VpnService.prepare(context) == null) {
            runCatching { context.startForegroundService(Intent(context, ConnectVpnService::class.java).setAction(ConnectVpnService.AUTOMATIC)) }
                .onFailure { (context.applicationContext as ConnectApp).log.add("Android postponed restarting the VPN. Open ConnectAnytime to reconnect.") }
        }
    }
}
class ConnectTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val state = ConnectApp.state.value
        qsTile?.apply {
            this.state = if (state.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = state.status
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        val action = Runnable {
            if (ConnectApp.state.value.busy) Controls.disconnect(this)
            else if (VpnService.prepare(this) != null) openApp()
            else Controls.connect(this)
            onStartListening()
        }
        if (isLocked) unlockAndRun(action) else action.run()
    }
    // API 34+ uses PendingIntent. Older Android has only the Intent overload.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}

/** Static launcher shortcuts need an Activity to request unlock and VPN consent. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { perform() }
            override fun onDismissCancelled() { finish() }
            override fun onDismissError() { finish() }
        }) else perform()
    }
    private fun perform() {
        if (intent.action == ConnectVpnService.DISCONNECT) Controls.disconnect(this)
        else if (intent.action == ConnectVpnService.CONNECT) {
            if (VpnService.prepare(this) == null) Controls.connect(this)
            else startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
