package com.muunkk.connectanytime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Notices {
    const val ONGOING = 1
    const val ENDED = 2
    fun channels(c: Context) {
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn", c.getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("session-ended", c.getString(R.string.session_ended), NotificationManager.IMPORTANCE_DEFAULT))
    }
    // Service status strings are internal protocol values; localize at display boundaries.
    fun status(c: Context, value: String): String = c.getString(when {
        value.startsWith("Reconnecting") -> R.string.reconnecting
        value == "Connected" -> R.string.connected
        value == "Not connected" -> R.string.not_connected
        else -> R.string.connecting
    })
    private fun open(c: Context) = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun ongoing(c: Context, status: String): Notification {
        val disconnect = PendingIntent.getService(c, 1, Intent(c, ConnectVpnService::class.java).setAction(ConnectVpnService.DISCONNECT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(c, "vpn").setSmallIcon(R.drawable.ic_vpn).setContentTitle(c.getString(R.string.app_name))
            .setContentText(status(c, status)).setContentIntent(open(c)).setOngoing(true)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.disconnect), disconnect).build()).build()
    }
    fun ended(c: Context) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        c.getSystemService(NotificationManager::class.java).notify(ENDED, Notification.Builder(c, "session-ended")
            .setSmallIcon(R.drawable.ic_vpn).setContentTitle(c.getString(R.string.session_ended)).setContentText(c.getString(R.string.refusal)).setContentIntent(open(c)).setAutoCancel(true).build())
    }
    fun clear(c: Context) { c.getSystemService(NotificationManager::class.java).cancel(ENDED) }
}

class RefusedStarts(private val c: Context, private val store: Store, private val log: ActivityLog) {
    fun note() {
        val p = store.prefs
        val now = System.currentTimeMillis()
        val count = p.getLong("refusedCount", 0) + 1
        val since = p.getLong("refusedSince", 0)
        val edit = p.edit().putLong("refusedCount", count)
        if (since == 0L) {
            edit.putLong("refusedSince", now).putLong("refusedLogged", now)
            log.add(BuildConfig.BUILD_DESCRIPTION)
            log.add(c.getString(R.string.refused_first, c.getString(R.string.refusal)))
            Notices.ended(c)
        } else if (now - p.getLong("refusedLogged", since) >= 1_800_000) {
            edit.putLong("refusedLogged", now)
            log.add(c.getString(R.string.refused_still, count, java.util.Date(since).toString()))
        }
        edit.commit()
        log.flush()
    }
    fun end() {
        val p = store.prefs
        val count = p.getLong("refusedCount", 0)
        if (count == 0L) return
        log.add(c.getString(R.string.refused_total, count, java.util.Date(p.getLong("refusedSince", 0)).toString(), java.util.Date().toString()))
        p.edit().remove("refusedCount").remove("refusedSince").remove("refusedLogged").commit()
    }
}
