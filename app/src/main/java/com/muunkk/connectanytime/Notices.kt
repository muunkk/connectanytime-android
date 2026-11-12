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
        manager.createNotificationChannel(NotificationChannel("vpn", "VPN connection", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("session-ended", "VPN session ended", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun open(c: Context) = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun ongoing(c: Context, status: String): Notification {
        val disconnect = PendingIntent.getService(c, 1, Intent(c, ConnectVpnService::class.java).setAction(ConnectVpnService.DISCONNECT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(c, "vpn").setSmallIcon(R.drawable.ic_vpn).setContentTitle("ConnectAnytime")
            .setContentText(status).setContentIntent(open(c)).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build()).build()
    }
    fun ended(c: Context) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        c.getSystemService(NotificationManager::class.java).notify(ENDED, Notification.Builder(c, "session-ended")
            .setSmallIcon(R.drawable.ic_vpn).setContentTitle("VPN session ended").setContentText(StartRule.REFUSAL).setContentIntent(open(c)).setAutoCancel(true).build())
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
            log.add("Stay Connected started the VPN, but there's no session to rejoin. ${StartRule.REFUSAL} Further automatic starts are counted, not logged, until one goes ahead.")
            Notices.ended(c)
        } else if (now - p.getLong("refusedLogged", since) >= 1_800_000) {
            edit.putLong("refusedLogged", now)
            log.add("Still no session to rejoin: $count automatic starts refused since ${java.util.Date(since)}")
        }
        edit.commit()
        log.flush()
    }
    fun end() {
        val p = store.prefs
        val count = p.getLong("refusedCount", 0)
        if (count == 0L) return
        log.add("$count automatic starts were refused from ${java.util.Date(p.getLong("refusedSince", 0))} to ${java.util.Date()}, with no session to rejoin")
        p.edit().remove("refusedCount").remove("refusedSince").remove("refusedLogged").commit()
    }
}
