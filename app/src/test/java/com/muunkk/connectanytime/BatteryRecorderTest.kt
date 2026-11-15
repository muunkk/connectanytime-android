package com.muunkk.connectanytime

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35], shadows = [TetherProxyTest.ShadowNativeBridge::class])
class BatteryRecorderTest {
    private lateinit var app: ConnectApp
    private val disks = mutableListOf<BatteryStore>()
    private val controls = mutableListOf<BatteryControl>()
    private var wall = 1_000_000L
    private var elapsed = 100_000L
    private var plugged = false
    private val readings get() = object : BatteryReadings(app, BatteryProc(File("missing-proc"), 100)) {
        override fun wall() = wall
        override fun elapsed() = elapsed
        override fun uptime() = android.os.SystemClock.uptimeMillis()
        override fun cpu() = elapsed * 1000
        override fun read() = batteryTrace().first().copy(wallMin = wall / 60_000, elapsedMs = elapsed, uptimeMs = elapsed,
            plugged = if (plugged) 1 else 0, status = if (plugged) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING)
    }
    @Before fun init() {
        app = ApplicationProvider.getApplicationContext()
        app.battery.close()
        ConnectApp.mutableState.value = VpnState()
        app.store.measureBattery = false
        shadowOf(Looper.getMainLooper()).idle()
        File(app.filesDir, "battery").deleteRecursively()
    }
    @After fun finish() {
        app.store.measureBattery = false; ConnectApp.mutableState.value = VpnState()
        shadowOf(Looper.getMainLooper()).idle()
        controls.forEach { drain(it); it.close() }
        disks.forEach { it.close() }
        app.log.observer = null; ProxyCounters.gen = 0
    }
    private fun control(fail: Boolean = false): BatteryControl {
        val disk = BatteryStore(app, app.store, append = { file, text -> if (fail) error("full") else file.appendText(text) })
        disks += disk
        return BatteryControl(app, disk, { readings }, { wall }).also { controls += it }
    }
    private fun drain(control: BatteryControl) {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            val recorder = BatteryControl::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(control)
            if (recorder != null) {
                val handler = BatteryRecorder::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(recorder) as android.os.Handler
                shadowOf(handler.looper).idle()
            } else {
                // Stopped loopers finish their own cleanup. Driving them races quitSafely.
                Thread.getAllStackTraces().keys.filter { it.name == BatteryThreads.RECORDER }.forEach { it.join(2000) }
            }
            control.disk.awaitIdle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun busy() { ConnectApp.mutableState.value = VpnState("Connected", busy = true, connected = true) }
    @Test fun onOffOnAndVpnResumeAllocateFreshEpochs() {
        val c = control()
        c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.PausedVpnOff, c.status.value)
        busy(); drain(c)
        assertEquals(BatteryStatus.Measuring, c.status.value)
        val first = app.store.batteryRec
        ConnectApp.mutableState.value = VpnState(); drain(c)
        assertEquals(BatteryStatus.PausedVpnOff, c.status.value)
        busy(); drain(c)
        assertTrue(app.store.batteryRec > first)
        c.setEnabled(false); drain(c)
        assertEquals(BatteryStatus.Off, c.status.value); assertEquals(0L, ProxyCounters.gen)
        c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.Measuring, c.status.value)
        assertTrue(app.store.batteryRec > first + 1)
    }
    @Test fun deadlineStopsWhileChargingIndependentlyOfPeriodicSampling() {
        val c = control(); plugged = true; busy(); c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.PausedCharging, c.status.value)
        val deadline = app.store.batteryDeadline
        wall = deadline; elapsed += 7 * 86_400_000L
        val r = BatteryControl::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(c)
        val h = BatteryRecorder::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(r) as android.os.Handler
        shadowOf(h.looper).idleFor(Duration.ofDays(7))
        drain(c)
        assertFalse(app.store.measureBattery); assertTrue(c.autoStopped.value)
        assertEquals(BatteryStatus.AutoStopped, c.status.value)
    }
    @Test fun vpnOffAndProcessRecoveryCheckExpiredDeadlineBeforeStarting() {
        val c = control(); c.setEnabled(true); drain(c)
        wall = app.store.batteryDeadline + 1
        busy(); drain(c)
        assertEquals(BatteryStatus.AutoStopped, c.status.value); assertFalse(app.store.measureBattery)
        app.store.prefs.edit().putBoolean("measureBattery", true).putLong("batteryDeadline", wall - 1).apply()
        drain(c)
        assertEquals(BatteryStatus.AutoStopped, c.status.value)
    }
    @Test fun coldApplicationStartResumesWithoutActivityAndChecksExpiredPreference() {
        app.store.prefs.edit().putBoolean("measureBattery", true).putLong("batteryDeadline", System.currentTimeMillis() + 100_000).commit()
        busy()
        val recovered = ConnectApp().apply {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(this, "attach",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Context::class.java, app.baseContext))
            onCreate()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(recovered.store.batteryRec > 0)
        assertEquals(BatteryStatus.Measuring, recovered.battery.status.value)
        recovered.store.measureBattery = false; shadowOf(Looper.getMainLooper()).idle(); recovered.battery.close()
    }
    @Test fun failedFlushKeepsMeasuringOnAndRetryDoesNotFlipPreference() {
        val c = control(true); busy(); c.setEnabled(true); drain(c)
        c.disk.flush(); c.disk.awaitIdle(); drain(c)
        assertEquals(BatteryStatus.CouldNotSave, c.status.value); assertTrue(app.store.measureBattery)
        c.retrySave(); c.disk.awaitIdle(); drain(c)
        assertTrue(app.store.measureBattery)
    }
    @Test fun failedStartIsReportedAndRetryCanStartRecording() {
        var fail = true
        val disk = BatteryStore(app, app.store).also { disks += it }
        val c = BatteryControl(app, disk, { if (fail) error("unavailable") else readings }, { wall }).also { controls += it }
        busy(); c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.CouldNotSave, c.status.value)
        assertTrue(app.store.measureBattery)
        fail = false; c.retrySave(); drain(c)
        assertEquals(BatteryStatus.Measuring, c.status.value)
    }
    @Test fun clearDuringRecordingAllocatesNewRecAndResetsAutoStopped() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val rec = app.store.batteryRec
        c.clear(); drain(c)
        assertTrue(app.store.batteryRec > rec)
        assertTrue(app.store.measureBattery); assertFalse(c.autoStopped.value)
    }
    @Test fun tetherReceiverIsExportedAndUsbBluetoothNeverCountAsHotspot() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val registered = shadowOf(app).registeredReceivers.first { it.intentFilter.hasAction(TETHER_STATE_CHANGED) &&
            it.broadcastReceiver.javaClass.name.contains("BatteryRecorder") }
        assertEquals(Context.RECEIVER_EXPORTED, registered.flags)
        val intent = Intent(TETHER_STATE_CHANGED).putStringArrayListExtra("tetherArray", arrayListOf("usb0", "bt-pan"))
        app.sendBroadcast(intent); drain(c)
        c.disk.flush(); c.disk.awaitIdle()
        assertEquals(BatteryHotspot.off, c.report.value!!.let {
            c.disk.file.readLines().mapNotNull(BatteryCsv::parse).last().hotspot
        })
        app.sendBroadcast(Intent(TETHER_STATE_CHANGED).putStringArrayListExtra("tetherArray", arrayListOf("wlan0", "usb0")))
        drain(c); c.disk.flush(); c.disk.awaitIdle()
        assertEquals(BatteryHotspot.on, c.disk.file.readLines().mapNotNull(BatteryCsv::parse).last().hotspot)
    }
    @Test fun pluggedHasNoPeriodicRowsAndUnplugResumesMinuteCadence() {
        val c = control(); plugged = true; busy(); c.setEnabled(true); drain(c)
        val r = BatteryControl::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(c)
        val h = BatteryRecorder::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(r) as android.os.Handler
        shadowOf(h.looper).idleFor(Duration.ofMinutes(60)); drain(c)
        c.disk.flush(); c.disk.awaitIdle()
        val before = c.disk.file.readLines().mapNotNull(BatteryCsv::parse)
        assertTrue(before.none { it.kind == BatteryKind.periodic })
        assertEquals(BatteryHotspot.unknown, before.first().hotspot)
        plugged = false; wall += 60_000; elapsed += 60_000
        app.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED)); drain(c)
        wall += 60_000; elapsed += 60_000
        shadowOf(h.looper).idleFor(Duration.ofMinutes(1)); drain(c)
        c.disk.flush(); c.disk.awaitIdle()
        val rows = c.disk.file.readLines().mapNotNull(BatteryCsv::parse)
        assertTrue(rows.any { it.kind == BatteryKind.periodic })
        assertTrue(rows.last().pgen > before.last().pgen)
        assertEquals(BatteryStatus.Measuring, c.status.value)
    }
    @Test fun rapidToggleAndClearIgnoreOldRunTasksAndKeepEpochOrder() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val first = app.store.batteryRec
        c.setEnabled(false); c.setEnabled(true); drain(c)
        assertTrue(app.store.batteryRec > first)
        c.disk.flush(); c.disk.awaitIdle()
        val ids = c.disk.file.readLines().mapNotNull(BatteryCsv::parse).map { it.id }
        assertEquals(ids.sortedWith(compareBy({ it.first }, { it.second })), ids)
        c.clear(); assertTrue(c.clearing.value); c.clear(); drain(c)
        assertFalse(c.clearing.value)
    }
    @Test fun autoStoppedLatchTracksExternalClearAndNewEnableResetsDeadline() {
        val c = control()
        app.store.batteryAutoStopped = true; drain(c)
        assertTrue(c.autoStopped.value); assertEquals(BatteryStatus.AutoStopped, c.status.value)
        app.store.batteryAutoStopped = false; drain(c)
        assertFalse(c.autoStopped.value); assertEquals(BatteryStatus.Off, c.status.value)
        c.setEnabled(true); drain(c)
        assertEquals(wall + 7 * 86_400_000L, app.store.batteryDeadline)
    }
    @Test fun procParserUsesLastParenAndThreadKeysRetainExitedTotals() {
        fun stat(tid: Int, name: String, ticks: Int, start: Int): String {
            val fields = MutableList(20) { "0" }; fields[0] = "S"; fields[11] = ticks.toString(); fields[12] = "5"; fields[19] = start.toString()
            return "$tid ($name) " + fields.joinToString(" ")
        }
        assertEquals("a (b) c", batteryProcStat(stat(3, "a (b) c", 10, 1))!!.name)
        assertNull(batteryProcStat("broken"))
        val root = Files.createTempDirectory("battery-proc").toFile()
        try {
            File(root, "stat").writeText(stat(1, "app", 95, 1))
            val task = File(root, "task/3/stat").apply { parentFile!!.mkdirs(); writeText(stat(3, BatteryThreads.VPN, 15, 1)) }
            val timer = File(root, "task/4/stat").apply { parentFile!!.mkdirs(); writeText(stat(4, BatteryThreads.TIMER, 5, 1)) }
            val proc = BatteryProc(root, 100)
            assertEquals(BatteryProc.Cpu(1000, 200, 100), proc.read())
            task.delete(); timer.delete()
            assertEquals(BatteryProc.Cpu(1000, 200, 100), proc.read())
            task.writeText(stat(3, BatteryThreads.VPN, 5, 2)); timer.writeText(stat(4, BatteryThreads.ACCEPT, 5, 2))
            assertEquals(BatteryProc.Cpu(1000, 300, 200), proc.read())
        } finally { root.deleteRecursively() }
        assertEquals(BatteryProc.Cpu(null, 0, 0), BatteryProc(File("missing-proc"), 100).read())
    }
    @Test fun transportClassifiesEvery921FallbackAndSeedsOnlyCurrentBuildTail() {
        val c = BatteryTransportClassifier()
        c.observe("CSTP connected. DPD 60, Keepalive 20"); assertEquals(BatteryTransport.tls, c.transport)
        c.observe("Established DTLS connection"); assertEquals(BatteryTransport.dtls, c.transport)
        val tls = listOf("DTLS unavailable; using TLS only", "DTLS Dead Peer Detection detected dead peer", "DTLS handshake failed",
            "DTLS handshake timed out", "DTLS Rehandshake failed", "DTLS session resume failed")
        tls.forEach { c.observe("Established DTLS connection"); c.observe(it); assertEquals(it, BatteryTransport.tls, c.transport) }
        c.observe("Reconnecting to the server"); assertEquals(BatteryTransport.unknown, c.transport)
        c.seed(listOf("11-15 12:00:00 Established DTLS connection", "11-15 12:01:00 ${BuildConfig.BUILD_DESCRIPTION}"))
        assertEquals(BatteryTransport.unknown, c.transport)
        c.seed(listOf(BuildConfig.BUILD_DESCRIPTION, "CSTP connected", "Established DTLS connection"))
        assertEquals(BatteryTransport.dtls, c.transport)
        c.reset(); assertEquals(BatteryTransport.unknown, c.transport)
    }
    @Test fun reconnectEpisodesCountOnlyConnectedToReconnecting() {
        val service = Robolectric.buildService(ConnectVpnService::class.java).get()
        val method = ConnectVpnService::class.java.getDeclaredMethod("state", String::class.java, Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }
        ConnectApp.lastStatus.set("Connected"); ConnectApp.vpnTransitions.set(0); ConnectApp.reconnectEpisodes.set(0)
        method.invoke(service, "Reconnecting…", false, null)
        method.invoke(service, "Reconnecting…", false, null)
        method.invoke(service, "Connecting…", false, null)
        method.invoke(service, "Reconnecting…", false, null)
        assertEquals(3L, ConnectApp.vpnTransitions.get()); assertEquals(1L, ConnectApp.reconnectEpisodes.get())
    }
}
