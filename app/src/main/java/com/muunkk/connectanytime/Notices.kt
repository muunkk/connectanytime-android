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
    const val UPDATED = 3
    const val CONFIRM_INSTALL = 4
    fun channels(c: Context) {
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn", c.getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("session-ended", c.getString(R.string.session_ended), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("updates", c.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_LOW))
        // Waiting on you, so it makes a sound, unlike the "updated" note.
        manager.createNotificationChannel(NotificationChannel("update-confirm", c.getString(R.string.update_confirm_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }
    // Service status strings are internal protocol values; localize at display boundaries.
    fun status(c: Context, value: String): String = c.getString(when {
        value.startsWith("Reconnecting") -> R.string.reconnecting
        value == "Connected" -> R.string.connected
        value == "Not connected" -> R.string.not_connected
        else -> R.string.connecting
    })
    // The line under the status in the ongoing notification.
    private fun detail(c: Context, value: String): String = when {
        value.startsWith("Reconnecting") -> c.getString(R.string.status_reconnecting)
        value == "Connected" -> c.getString(R.string.status_connected, (c.applicationContext as ConnectApp).store.server)
        else -> c.getString(R.string.status_connecting)
    }
    private fun open(c: Context) = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun ongoing(c: Context, status: String): Notification {
        val disconnect = PendingIntent.getService(c, 1, Intent(c, ConnectVpnService::class.java).setAction(ConnectVpnService.DISCONNECT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Android already shows the app name in the header; lead with the state.
        return Notification.Builder(c, "vpn").setSmallIcon(R.drawable.ic_vpn).setContentTitle(status(c, status))
            .setContentText(detail(c, status)).setContentIntent(open(c)).setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).setShowWhen(false).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.disconnect), disconnect).build()).build()
    }
    fun ended(c: Context) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = c.getString(R.string.session_ended_body) // the title already says it ended
        c.getSystemService(NotificationManager::class.java).notify(ENDED, Notification.Builder(c, "session-ended")
            .setSmallIcon(R.drawable.ic_vpn).setContentTitle(c.getString(R.string.session_ended)).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open(c)).setAutoCancel(true).build())
    }
    /** After an update this app installed: Android doesn't reopen the app, so say it's done. */
    fun updated(c: Context) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        c.getSystemService(NotificationManager::class.java).notify(UPDATED, Notification.Builder(c, "updates")
            .setSmallIcon(R.drawable.ic_vpn).setContentTitle(c.getString(R.string.updated_title, BuildConfig.VERSION_NAME))
            .setContentText(c.getString(R.string.updated_body)).setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open(c)).setAutoCancel(true).build())
    }
    /** Android's install confirmation arrived while the app wasn't open. Tapping this opens
     *  that confirmation directly (it was made by the system for this session), so it works
     *  even if this process has ended since. */
    fun confirmInstall(c: Context, version: String, confirm: Intent, session: Int) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = c.getSystemService(NotificationManager::class.java)
        // Turned off for the app or this channel: nothing would show, so don't count it as
        // posted, or the app would think it was tapped and not open the confirmation itself.
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel("update-confirm")?.importance == NotificationManager.IMPORTANCE_NONE) return
        val tap = PendingIntent.getActivity(c, 100 + (session and 0xffff), confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(CONFIRM_INSTALL, Notification.Builder(c, "update-confirm")
            .setSmallIcon(R.drawable.ic_vpn).setContentTitle(c.getString(R.string.update_confirm_title, version))
            .setContentText(c.getString(R.string.update_confirm_body)).setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(tap).setAutoCancel(true).build())
        confirmInstallPosted = true
    }
    /** Whether [confirmInstall] posted in this process (see Updater.takeConfirmation). */
    @Volatile var confirmInstallPosted = false
    fun confirmInstallShowing(c: Context) =
        c.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == CONFIRM_INSTALL }
    fun clearConfirmInstall(c: Context) {
        confirmInstallPosted = false
        c.getSystemService(NotificationManager::class.java).cancel(CONFIRM_INSTALL)
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
