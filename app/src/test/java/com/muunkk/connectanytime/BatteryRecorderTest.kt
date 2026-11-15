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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35], shadows = [TetherProxyTest.ShadowNativeBridge::class])
class BatteryRecorderTest {
    private lateinit var app: ConnectApp
    private val disks = mutableListOf<BatteryStore>()
    private val controls = mutableListOf<BatteryControl>()
    private var wall = 1_000_000L
    private var elapsed = 100_000L
    private var plugged = false
    private var missingPlugged = false
    private var readingFailures = 0
    private val readings get() = object : BatteryReadings(app, BatteryProc(File("missing-proc"), 100)) {
        override fun wall() = wall
        override fun elapsed() = elapsed
        override fun uptime() = android.os.SystemClock.uptimeMillis()
        override fun cpu() = elapsed * 1000
        override fun read(): BatteryRow {
            if (readingFailures > 0) { readingFailures--; error("transient battery read") }
            return batteryTrace().first().copy(wallMin = wall / 60_000, elapsedMs = elapsed, uptimeMs = elapsed,
            plugged = if (missingPlugged) null else if (plugged) 1 else 0,
            status = if (plugged) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING)
        }
    }
    @Before fun init() {
        app = ApplicationProvider.getApplicationContext()
        app.battery.close()
        Thread.getAllStackTraces().keys.filter { it.name == BatteryThreads.RECORDER }.forEach { it.join(2000) }
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
    @Test fun vpnResumeReadsStickyPowerBeforePublishingStatusAndIgnoresStoppedSamples() {
        fun power(on: Boolean) {
            plugged = on
            @Suppress("DEPRECATION")
            app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_PLUGGED, if (on) BatteryManager.BATTERY_PLUGGED_USB else 0))
            shadowOf(Looper.getMainLooper()).idle()
        }
        val c = control()
        val statuses = mutableListOf<BatteryStatus>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { c.status.collect { statuses += it } }
        try {
            power(true); busy(); c.setEnabled(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(BatteryStatus.PausedCharging, c.status.value)
            drain(c)
            assertFalse(statuses.contains(BatteryStatus.Measuring))
            ConnectApp.mutableState.value = VpnState(); drain(c)
            val cachedPower = BatteryControl::class.java.getDeclaredField("plugged").apply { isAccessible = true }.get(c) as MutableStateFlow<*>
            assertEquals(false, cachedPower.value)
            power(false)
            statuses.clear()
            busy(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(BatteryStatus.Measuring, c.status.value)
            drain(c)
            assertFalse(statuses.contains(BatteryStatus.PausedCharging))
            ConnectApp.mutableState.value = VpnState(); drain(c)
            power(true)
            statuses.clear()
            busy(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(BatteryStatus.PausedCharging, c.status.value)
            drain(c)
            assertFalse(statuses.contains(BatteryStatus.Measuring))
        } finally { collector.cancel(); power(false) }
    }
    @Test fun csvReadFailureDuringStopStillAllowsRetryRestartAndClear() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val first = app.store.batteryRec
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { c.report.collect {} }
        try {
            c.disk.flush(); c.disk.awaitIdle()
            assertTrue(c.disk.file.delete()); assertTrue(c.disk.file.mkdir())
            ConnectApp.mutableState.value = VpnState(); drain(c)
            assertTrue(c.disk.failed.value)
            assertTrue(c.disk.file.delete())
            c.retrySave(); drain(c)
            busy(); drain(c)
            assertTrue(app.store.batteryRec > first)
            assertEquals(BatteryStatus.Measuring, c.status.value)
            c.clear(); drain(c)
            assertFalse(c.clearing.value)
            assertEquals(BatteryStatus.Measuring, c.status.value)
        } finally { collector.cancel() }
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
    @Test fun queuedRecorderExpiryCannotStopRestartedMeasurement() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val old = BatteryControl::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(c)
        val expired = BatteryRecorder::class.java.getDeclaredField("expired").apply { isAccessible = true }
            .get(old) as Function0<*>
        wall = app.store.batteryDeadline
        // The worker has noticed expiry, but main is still occupied when the user restarts.
        Executors.newSingleThreadExecutor().let { worker ->
            try { worker.submit { expired.invoke() }.get(5, TimeUnit.SECONDS) }
            finally { worker.shutdownNow() }
        }
        c.setEnabled(false)
        c.setEnabled(true)
        val newDeadline = app.store.batteryDeadline
        drain(c)
        assertTrue(app.store.measureBattery)
        assertEquals(newDeadline, app.store.batteryDeadline)
        assertEquals(BatteryStatus.Measuring, c.status.value)
        assertFalse(c.autoStopped.value)
        val line = app.getString(R.string.battery_log_auto_stop)
        assertTrue(app.log.state.value.none { it.endsWith(line) })
    }
    @Test fun recorderExpiryRechecksPersistedDeadlineForTheSameRecorder() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val oldDeadline = app.store.batteryDeadline
        val r = BatteryControl::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(c)
        val expired = BatteryRecorder::class.java.getDeclaredField("expired").apply { isAccessible = true }
            .get(r) as Function0<*>
        wall = oldDeadline
        Executors.newSingleThreadExecutor().let { worker ->
            try { worker.submit { expired.invoke() }.get(5, TimeUnit.SECONDS) }
            finally { worker.shutdownNow() }
        }
        app.store.batteryDeadline = wall + 60_000
        drain(c)
        assertTrue(app.store.measureBattery)
        assertFalse(c.autoStopped.value)
        assertEquals(BatteryStatus.Measuring, c.status.value)
    }
    @Test fun deadlineStopsWithoutAnyVpnOrPreferenceChange() {
        val c = control(); c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.PausedVpnOff, c.status.value)
        wall = app.store.batteryDeadline - 1
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofDays(7).minusMillis(1))
        assertTrue(app.store.measureBattery)
        wall++
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        drain(c)
        assertFalse(app.store.measureBattery)
        assertTrue(c.autoStopped.value)
        assertEquals(BatteryStatus.AutoStopped, c.status.value)
    }
    @Test fun deadlineTimerRearmsFromCurrentWallTimeAndCancelsOnClose() {
        val c = control(); c.setEnabled(true); drain(c)
        val deadline = app.store.batteryDeadline
        // A backward wall-clock change must not let the uptime timer stop early.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofDays(7))
        assertTrue(app.store.measureBattery)
        wall = deadline
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofDays(7))
        drain(c)
        assertEquals(BatteryStatus.AutoStopped, c.status.value)
        c.setEnabled(true); drain(c)
        c.close()
        wall = app.store.batteryDeadline
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofDays(7))
        assertTrue(app.store.measureBattery)
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
        drain(recovered.battery)
        assertTrue(recovered.store.batteryRec > 0)
        assertEquals(BatteryStatus.Measuring, recovered.battery.status.value)
        recovered.store.measureBattery = false; shadowOf(Looper.getMainLooper()).idle(); recovered.battery.close()
    }
    @Test fun failedFlushKeepsMeasuringOnAndRetryDoesNotFlipPreference() {
        val c = control(true); busy(); c.setEnabled(true); drain(c)
        c.disk.flush(); c.disk.awaitIdle(); drain(c)
        assertEquals(BatteryStatus.CouldNotSave, c.status.value); assertTrue(app.store.measureBattery)
        assertEquals(BatteryProblem.SaveFailed, c.problem.value)
        c.retrySave(); c.disk.awaitIdle(); drain(c)
        assertTrue(app.store.measureBattery)
        assertEquals(BatteryProblem.SaveFailed, c.problem.value)
        c.setEnabled(false); drain(c)
        assertNull(c.problem.value)
    }
    @Test fun stopClearsSaveFailureBeforeMeasurementRestartsAndKeepsUnsavedRows() {
        val c = control(true); busy(); c.setEnabled(true); drain(c)
        c.disk.flush(); c.disk.awaitIdle(); drain(c)
        assertEquals(BatteryStatus.CouldNotSave, c.status.value)
        c.setEnabled(false); drain(c)
        val retained = c.disk.bufferedRows()
        assertTrue(retained.isNotEmpty())
        assertFalse(c.disk.failed.value)
        c.setEnabled(true)
        assertEquals(BatteryStatus.Measuring, c.status.value)
        assertNull(c.problem.value)
        // New rows can trigger a fresh failed flush; only the old failure was reset.
        drain(c)
        assertTrue(c.disk.bufferedRows().containsAll(retained))
    }
    @Test fun loadFailureIsAvailableThroughControlAndClearWorksWithoutReport() {
        val c = control()
        c.disk.file.mkdirs()
        c.refresh(); c.disk.awaitIdle()
        assertNull(c.report.value)
        assertTrue(c.loadFailed.value)
        c.clear(); drain(c)
        assertFalse(c.loadFailed.value)
        assertEquals(0, c.report.value!!.rowCount)
        assertFalse(c.clearing.value)
    }
    @Test fun cachedReportReadFailureIsAvailableThroughControlAndRefreshOrClearRecovers() {
        val c = control()
        assertSame(c.disk.readFailed, c.readFailed)
        assertSame(c.readFailed, c.loadFailed)
        val row = batteryTrace().first()
        c.disk.add(row); c.disk.flush(); c.disk.awaitIdle()
        c.refresh(); c.disk.awaitIdle()
        val cached = c.report.value!!
        assertEquals(1, cached.rowCount)
        assertFalse(c.readFailed.value)

        val saved = c.disk.file.readText()
        assertTrue(c.disk.file.delete()); assertTrue(c.disk.file.mkdir())
        c.refresh(); c.disk.awaitIdle()
        assertTrue(c.readFailed.value)
        assertSame(cached, c.report.value)
        assertFalse(c.disk.failed.value)
        assertEquals(BatteryStatus.Off, c.status.value)

        assertTrue(c.disk.file.delete()); c.disk.file.writeText(saved + BatteryCsv.write(row.copy(seq = 1)) + "\n")
        c.refresh(); c.disk.awaitIdle()
        assertFalse(c.readFailed.value)
        assertEquals(2, c.report.value!!.rowCount)

        assertTrue(c.disk.file.delete()); assertTrue(c.disk.file.mkdir())
        c.refresh(); c.disk.awaitIdle()
        assertTrue(c.readFailed.value)
        c.clear(); drain(c)
        assertFalse(c.readFailed.value)
        assertEquals(0, c.report.value!!.rowCount)
        assertFalse(c.clearing.value)
    }
    @Test fun turningOffWhileVpnPausedAlsoEndsSaveRetries() {
        val c = control(true); busy(); c.setEnabled(true); drain(c)
        ConnectApp.mutableState.value = VpnState(); drain(c)
        assertTrue(c.disk.failed.value)
        c.setEnabled(false); drain(c)
        val retained = c.disk.bufferedRows()
        assertTrue(retained.isNotEmpty())
        assertFalse(c.disk.failed.value)
        c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.PausedVpnOff, c.status.value)
        assertNull(c.problem.value)
        assertEquals(retained, c.disk.bufferedRows())
    }
    @Test fun failedStartLogsOnlyCauseTypeOnceAndRetryCanStartRecording() {
        var fail = true
        val disk = BatteryStore(app, app.store).also { disks += it }
        val c = BatteryControl(app, disk, { if (fail) error("unavailable /private/user/account") else readings }, { wall })
            .also { controls += it }
        val line = app.getString(R.string.battery_log_start_failed, "IllegalStateException")
        val before = app.log.state.value.count { it.endsWith(line) }
        busy(); c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.CouldNotSave, c.status.value)
        assertEquals(BatteryProblem.StartFailed, c.problem.value)
        assertTrue(app.store.measureBattery)
        assertEquals(before + 1, app.log.state.value.count { it.endsWith(line) })
        assertTrue(app.log.state.value.none { it.contains("/private/user/account") })
        app.store.shareWithTether = !app.store.shareWithTether; drain(c)
        ConnectApp.mutableState.value = VpnState(); drain(c)
        busy(); drain(c)
        assertEquals(before + 1, app.log.state.value.count { it.endsWith(line) })
        fail = false; c.retrySave(); drain(c)
        assertEquals(BatteryStatus.Measuring, c.status.value)
        assertNull(c.problem.value)
        assertEquals(before + 1, app.log.state.value.count { it.endsWith(line) })
    }
    @Test fun asynchronousStartFailureLogsCauseOnceAndRetryRestartsRecorder() {
        var fail = true
        val source = readings
        val disk = BatteryStore(app, app.store).also { disks += it }
        val c = BatteryControl(app, disk, {
            object : BatteryReadings(app, BatteryProc(File("missing-proc"), 100)) {
                override fun wall() = wall
                override fun elapsed(): Long = if (fail) throw SecurityException("private identifier") else elapsed
                override fun read() = source.read()
            }
        }, { wall }).also { controls += it }
        val line = app.getString(R.string.battery_log_start_failed, "SecurityException")
        val before = app.log.state.value.count { it.endsWith(line) }
        busy(); c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.CouldNotSave, c.status.value)
        assertEquals(BatteryProblem.StartFailed, c.problem.value)
        assertTrue(app.store.measureBattery)
        assertEquals(0L, ProxyCounters.gen)
        assertEquals(before + 1, app.log.state.value.count { it.endsWith(line) })
        assertTrue(app.log.state.value.none { it.contains("private identifier") })
        app.store.shareOverHotspot = !app.store.shareOverHotspot; drain(c)
        assertEquals(before + 1, app.log.state.value.count { it.endsWith(line) })
        fail = false; c.retrySave(); drain(c)
        assertEquals(BatteryStatus.Measuring, c.status.value)
        assertNull(c.problem.value)
        assertTrue(ProxyCounters.gen > 0)
        assertEquals(before + 1, app.log.state.value.count { it.endsWith(line) })
    }
    @Test fun startProblemTakesPrecedenceAndFallsBackToDiskProblemAfterRetry() {
        var failStart = true
        var failSave = true
        val disk = BatteryStore(app, app.store, append = { file, text ->
            if (failSave) error("full") else file.appendText(text)
        }).also { disks += it }
        val c = BatteryControl(app, disk, { if (failStart) error("unavailable") else readings }, { wall })
            .also { controls += it }
        assertNull(c.problem.value)
        busy(); c.setEnabled(true); drain(c)
        disk.flush(); disk.awaitIdle(); drain(c)
        assertTrue(disk.failed.value)
        assertEquals(BatteryProblem.StartFailed, c.problem.value)
        failStart = false; c.retrySave(); drain(c)
        assertEquals(BatteryStatus.CouldNotSave, c.status.value)
        assertEquals(BatteryProblem.SaveFailed, c.problem.value)
        failSave = false; c.retrySave(); drain(c)
        assertEquals(BatteryStatus.Measuring, c.status.value)
        assertNull(c.problem.value)
    }
    @Test fun clearDuringRecordingAllocatesNewRecAndResetsAutoStopped() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val rec = app.store.batteryRec
        c.clear(); drain(c)
        assertTrue(app.store.batteryRec > rec)
        assertTrue(app.store.measureBattery); assertFalse(c.autoStopped.value)
    }
    @Test fun usbSharingPreferenceLeavesSituationGenerationUnchanged() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val before = saved(c).last()
        app.store.shareWithTether = !app.store.shareWithTether; drain(c)
        wall += 60_000; elapsed += 60_000
        shadowOf(handler(c).looper).idleFor(Duration.ofMinutes(1)); drain(c)
        assertEquals(before.gen, saved(c).last().gen)
        app.store.shareOverHotspot = !app.store.shareOverHotspot; drain(c)
        assertEquals(before.gen + 1, saved(c).last().gen)
    }
    @Test fun tetherReceiverIsExportedAndUsbBluetoothNeverCountAsHotspot() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val registered = shadowOf(app).registeredReceivers.first { it.intentFilter.hasAction(TETHER_STATE_CHANGED) &&
            it.broadcastReceiver.javaClass.name.contains("BatteryRecorder") }
        assertEquals(Context.RECEIVER_EXPORTED, registered.flags)
        val intent = Intent(TETHER_STATE_CHANGED).putStringArrayListExtra("tetherArray", arrayListOf("usb0", "bt-pan"))
        app.sendBroadcast(intent); drain(c)
        c.disk.flush(); c.disk.awaitIdle()
        assertEquals(BatteryHotspot.off, c.disk.file.readLines().mapNotNull(BatteryCsv::parse).last().hotspot)
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
    private fun handler(c: BatteryControl): android.os.Handler {
        val recorder = BatteryControl::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(c)
        return BatteryRecorder::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(recorder) as android.os.Handler
    }
    private fun saved(c: BatteryControl): List<BatteryRow> {
        c.disk.flush(); c.disk.awaitIdle()
        return c.disk.file.readLines().mapNotNull(BatteryCsv::parse)
    }
    @Test fun handshakeBeforeConnectedSurvivesOtherConnectingProjections() {
        val c = control()
        ConnectApp.mutableState.value = VpnState("Connecting…", busy = true)
        c.setEnabled(true); drain(c)
        app.log.add("CSTP connected. DPD 60, Keepalive 20"); drain(c)
        app.store.shareOverHotspot = !app.store.shareOverHotspot; drain(c)
        busy(); drain(c)
        assertEquals(BatteryTransport.tls, saved(c).last().transport)
    }
    @Test fun reconnectResetsOnceAndKeepsHandshakeUntilConnected() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        app.log.add("Established DTLS connection"); drain(c)
        ConnectApp.reconnectEpisodes.incrementAndGet()
        ConnectApp.mutableState.value = VpnState("Reconnecting…", busy = true); drain(c)
        assertEquals(BatteryTransport.unknown, saved(c).last().transport)
        app.log.add("CSTP connected"); drain(c)
        app.store.shareOverHotspot = !app.store.shareOverHotspot; drain(c)
        busy(); drain(c)
        assertEquals(BatteryTransport.tls, saved(c).last().transport)
    }
    @Test fun eventsEveryThirtySecondsDoNotDelayThePeriodicCadence() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val start = elapsed
        repeat(6) { index ->
            wall += 30_000; elapsed += 30_000
            shadowOf(handler(c).looper).idleFor(Duration.ofSeconds(30)); drain(c)
            app.sendBroadcast(Intent(if (index % 2 == 0) Intent.ACTION_SCREEN_OFF else Intent.ACTION_SCREEN_ON))
            drain(c)
        }
        val rows = saved(c)
        assertEquals(listOf(start + 60_000, start + 120_000, start + 180_000),
            rows.filter { it.kind == BatteryKind.periodic }.map { it.elapsedMs })
        assertTrue(rows.count { it.kind == BatteryKind.event } >= 6)
    }
    @Test fun pendingClearRetainsPausedStatusUntilEraseCompletes() {
        val c = control(); c.setEnabled(true); drain(c)
        assertEquals(BatteryStatus.PausedVpnOff, c.status.value)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        c.disk.summarize { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            c.clear()
            busy(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(c.clearing.value)
            assertEquals(0L, ProxyCounters.gen)
            assertEquals(BatteryStatus.PausedVpnOff, c.status.value)
        } finally { release.countDown(); drain(c) }
        assertFalse(c.clearing.value)
        assertEquals(BatteryStatus.Measuring, c.status.value)
        assertTrue(ProxyCounters.gen > 0)
    }
    @Test fun readingsPreserveZeroCurrentsButFilterCounterZerosAndUnsupportedSentinels() {
        val bm = shadowOf(app.getSystemService(BatteryManager::class.java))
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 0)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, 0)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 0)
        bm.setLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, 0)
        val source = BatteryReadings(app, BatteryProc(File("missing-proc"), 100))
        val row = source.read()
        assertEquals(0L, row.currentNow); assertEquals(0L, row.currentAvg)
        assertNull(row.chargeUah); assertNull(row.energyNwh)
        val csv = BatteryCsv.parse(BatteryCsv.write(row))!!
        assertEquals(0L, csv.currentNow); assertEquals(0L, csv.currentAvg)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, Int.MIN_VALUE)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, Int.MIN_VALUE)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, Int.MIN_VALUE)
        bm.setLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, Long.MIN_VALUE)
        val missing = source.read()
        assertNull(missing.currentNow); assertNull(missing.currentAvg)
        assertNull(missing.chargeUah); assertNull(missing.energyNwh)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -20)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, 15)
        assertEquals(-20L, source.read().currentNow); assertEquals(15L, source.read().currentAvg)
    }
    @Test fun periodicSamplingRecoversAfterTransientReadingFailure() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        val h = handler(c)
        readingFailures = 1
        shadowOf(h.looper).idleFor(Duration.ofMinutes(1)); drain(c)
        assertEquals(0, readingFailures)
        wall += 120_000; elapsed += 120_000
        shadowOf(h.looper).idleFor(Duration.ofMinutes(1)); drain(c)
        assertTrue(saved(c).any { it.kind == BatteryKind.periodic })
    }
    @Test fun missingPluggedExtraKeepsPeriodicSamplingEnabled() {
        missingPlugged = true
        val c = control(); busy(); c.setEnabled(true); drain(c)
        repeat(2) {
            wall += 60_000; elapsed += 60_000
            shadowOf(handler(c).looper).idleFor(Duration.ofMinutes(1)); drain(c)
        }
        val periodic = saved(c).filter { it.kind == BatteryKind.periodic }
        assertEquals(2, periodic.size)
        assertTrue(periodic.all { it.plugged == null })
        assertEquals(BatteryStatus.Measuring, c.status.value)
    }
    @Test fun sixHourSummaryUsesRetainedRowsAndBufferWithoutRefreshingUiReport() {
        val c = control()
        batteryTrace(rec = 0).forEach(c.disk::add)
        c.disk.flush(); c.disk.refresh(); c.disk.awaitIdle()
        val cached = c.report.value
        assertEquals(3_600_000L, cached!!.countedMs)
        busy(); c.setEnabled(true); drain(c)
        repeat(4) {
            wall += 90 * 60_000; elapsed += 90 * 60_000
            shadowOf(handler(c).looper).idleFor(Duration.ofMinutes(1)); drain(c)
        }
        assertEquals(elapsed, c.disk.bufferedRows().last().elapsedMs)
        assertSame(cached, c.report.value)
        val summary = app.resources.getQuantityString(R.plurals.battery_log_summary, 420, 420L)
        assertTrue(app.log.state.value.any { it.endsWith(summary) })
    }
    @Test fun measurementLogsFollowUserToggleAndClearWhileVpnPausesStayQuiet() {
        val c = control()
        fun count(id: Int) = app.log.state.value.count { it.endsWith(app.getString(id)) }
        val starts = count(R.string.battery_log_start)
        val stops = count(R.string.battery_log_stop)
        c.setEnabled(true); c.setEnabled(true); drain(c)
        assertEquals(starts + 1, count(R.string.battery_log_start))
        repeat(2) {
            busy(); drain(c)
            ConnectApp.mutableState.value = VpnState(); drain(c)
        }
        busy(); drain(c)
        assertEquals(starts + 1, count(R.string.battery_log_start))
        assertEquals(stops, count(R.string.battery_log_stop))
        c.clear(); drain(c)
        assertEquals(starts + 2, count(R.string.battery_log_start))
        assertEquals(stops + 1, count(R.string.battery_log_stop))
        c.setEnabled(false); c.setEnabled(false); drain(c)
        assertEquals(stops + 2, count(R.string.battery_log_stop))
    }
    @Test fun autoStopLogsItsCauseOnceWithoutGenericRecorderStop() {
        val c = control(); busy(); c.setEnabled(true); drain(c)
        fun count(id: Int) = app.log.state.value.count { it.endsWith(app.getString(id)) }
        val stops = count(R.string.battery_log_stop)
        val autoStops = count(R.string.battery_log_auto_stop)
        wall = app.store.batteryDeadline
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); drain(c)
        assertEquals(stops, count(R.string.battery_log_stop))
        assertEquals(autoStops + 1, count(R.string.battery_log_auto_stop))
    }
    @Test fun sampleRearmsWallDeadlineAfterDeepSleepEvenWhileCharging() {
        val c = control(); plugged = true; busy(); c.setEnabled(true); drain(c)
        wall = app.store.batteryDeadline - 1000
        elapsed += 7 * 86_400_000L - 1000
        // An awake event follows deep sleep; uptime has barely moved.
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); drain(c)
        wall += 1000; elapsed += 1000
        shadowOf(handler(c).looper).idleFor(Duration.ofSeconds(1)); drain(c)
        assertFalse(app.store.measureBattery)
        assertEquals(BatteryStatus.AutoStopped, c.status.value)
    }
    @Test fun failedUnplugReadStillRetriesAndResumesPeriodicSampling() {
        val c = control(); plugged = true; busy(); c.setEnabled(true); drain(c)
        readingFailures = 1; plugged = false
        app.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED)); drain(c)
        assertEquals(0, readingFailures)
        shadowOf(handler(c).looper).idleFor(Duration.ofMinutes(1)); drain(c)
        assertTrue(saved(c).any { it.kind == BatteryKind.periodic })
    }
    @Test fun recorderSeedsOutsideLogLockWithObserverInstalledAndNewerLineWinning() {
        ConnectApp.mutableState.value = VpnState("Connecting…", busy = true)
        app.store.batteryDeadline = wall + 60_000
        val d = BatteryStore(app, app.store).also { disks += it }
        val r = BatteryRecorder(app, d, readings, {}, {})
        val pool = Executors.newSingleThreadExecutor()
        var scanned = false
        var heldLogMonitor = true
        var installedObserver = false
        @Suppress("UNCHECKED_CAST")
        val state = ActivityLog::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(app.log) as MutableStateFlow<List<String>>
        val snapshot = object : AbstractList<String>() {
            override val size = state.value.size + 1
            override fun get(index: Int): String {
                if (!scanned) {
                    scanned = true
                    heldLogMonitor = Thread.holdsLock(app.log)
                    installedObserver = app.log.observer != null
                    pool.submit { app.log.add("Established DTLS connection") }.get(2, TimeUnit.SECONDS)
                }
                return "11-15 12:00:00 CSTP connected"
            }
        }
        state.value = snapshot
        try {
            r.start()
            val h = BatteryRecorder::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(r) as android.os.Handler
            shadowOf(h.looper).idle()
            assertTrue(scanned)
            assertFalse("Seeding held ActivityLog's monitor", heldLogMonitor)
            assertTrue(installedObserver)
            d.flush(); d.awaitIdle()
            assertEquals(BatteryTransport.dtls, d.file.readLines().mapNotNull(BatteryCsv::parse).first().transport)
        } finally {
            r.stop()
            Thread.getAllStackTraces().keys.filter { it.name == BatteryThreads.RECORDER }.forEach { it.join(2000) }
            pool.shutdownNow()
        }
    }
    @Test fun startupSeedsHandshakeWhileConnecting() {
        app.log.add(BuildConfig.BUILD_DESCRIPTION)
        app.log.add("CSTP connected")
        ConnectApp.mutableState.value = VpnState("Connecting…", busy = true)
        val c = control(); c.setEnabled(true); drain(c)
        busy(); drain(c)
        assertEquals(BatteryTransport.tls, saved(c).last().transport)
    }
    @Test fun allocationRunsOnRecorderBeforeCountersAndSamplesStart() {
        val d = BatteryStore(app, app.store).also { disks += it }
        var allocations = 0
        var sampled = false
        val source = readings
        val r = BatteryRecorder(app, d, object : BatteryReadings(app, BatteryProc(File("missing-proc"), 100)) {
            override fun wall() = wall
            override fun read(): BatteryRow {
                assertEquals(1, allocations)
                assertEquals(app.store.batteryRec, ProxyCounters.gen)
                sampled = true
                return source.read()
            }
        }, {}, {}, allocateRec = {
            assertEquals(BatteryThreads.RECORDER, Thread.currentThread().name)
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(0L, ProxyCounters.gen)
            allocations++
            app.store.nextBatteryRec()
        })
        busy(); app.store.batteryDeadline = wall + 60_000
        try {
            r.start()
            assertEquals(0, allocations)
            assertEquals(0L, ProxyCounters.gen)
            val h = BatteryRecorder::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(r) as android.os.Handler
            shadowOf(h.looper).idle()
            assertEquals(1, allocations)
            assertTrue(sampled)
            d.flush(); d.awaitIdle()
            assertTrue(d.file.readLines().mapNotNull(BatteryCsv::parse).all { it.rec == app.store.batteryRec && it.rec > 0 })
        } finally {
            r.stop()
            Thread.getAllStackTraces().keys.filter { it.name == BatteryThreads.RECORDER }.forEach { it.join(2000) }
        }
    }
    @Test fun workerAllocationFailureIsReportedWithoutStartingCountersOrSampling() {
        val d = BatteryStore(app, app.store).also { disks += it }
        val failure = IllegalStateException("commit failed")
        var observed: Throwable? = null
        var sampled = false
        val r = BatteryRecorder(app, d, object : BatteryReadings(app, BatteryProc(File("missing-proc"), 100)) {
            override fun wall() = wall
            override fun read(): BatteryRow { sampled = true; return batteryTrace().first() }
        }, {}, {}, allocateRec = { throw failure }, failed = { observed = it })
        busy(); app.store.batteryDeadline = wall + 60_000
        try {
            r.start()
            val h = BatteryRecorder::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(r) as android.os.Handler
            shadowOf(h.looper).idle()
            assertSame(failure, observed)
            assertFalse(sampled)
            assertEquals(0L, ProxyCounters.gen)
        } finally {
            r.stop()
            Thread.getAllStackTraces().keys.filter { it.name == BatteryThreads.RECORDER }.forEach { it.join(2000) }
        }
        assertFalse(sampled) // A stop before allocation cannot write a stop sample either.
    }
    @Test fun initialReadingFailureStillInstallsObservationAndRetries() {
        readingFailures = 2 // The start and initial projection both fail.
        val c = control(); busy(); c.setEnabled(true); drain(c)
        assertEquals(0, readingFailures)
        app.log.add("CSTP connected"); drain(c)
        shadowOf(handler(c).looper).idleFor(Duration.ofMinutes(1)); drain(c)
        assertTrue(saved(c).any { it.kind == BatteryKind.periodic && it.transport == BatteryTransport.tls })
    }
    @Test fun seedingScansNewestTailAndLiveObservationsWinEvenWithoutAClassChange() {
        val c = BatteryTransportClassifier()
        val accessed = mutableListOf<Int>()
        val lines = object : AbstractList<String>() {
            override val size = 1000
            override fun get(index: Int): String {
                accessed += index
                return when (index) {
                    999 -> "11-15 12:00:00 unrelated"
                    998 -> "11-15 12:00:00 ${BuildConfig.BUILD_DESCRIPTION}"
                    else -> error("Read beyond newest build marker")
                }
            }
        }
        c.seed(lines)
        assertEquals(listOf(999, 998), accessed)
        assertEquals(BatteryTransport.unknown, c.transport)
        val version = c.seedVersion()
        c.observe("Reconnecting to the server") // Still unknown; this must invalidate old seed data too.
        c.seed(listOf("Established DTLS connection"), version)
        assertEquals(BatteryTransport.unknown, c.transport)
        val next = c.seedVersion()
        c.observe("CSTP connected")
        c.seed(listOf("Established DTLS connection"), next)
        assertEquals(BatteryTransport.tls, c.transport)
        c.connection(busy = false, episodes = 0)
        assertEquals(BatteryTransport.unknown, c.transport)
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
            File(task.parentFile, "comm").writeText(BatteryThreads.VPN + "\n")
            File(timer.parentFile, "comm").writeText(BatteryThreads.TIMER + "\n")
            // Unselected and unreadable names must exclude their CPU totals.
            File(root, "task/5/stat").apply { parentFile!!.mkdirs(); writeText(stat(5, BatteryThreads.VPN, 100, 1)) }
            File(root, "task/5/comm").writeText("unrelated\n")
            File(root, "task/6/stat").apply { parentFile!!.mkdirs(); writeText(stat(6, BatteryThreads.TIMER, 100, 1)) }
            val proc = BatteryProc(root, 100, elapsed = { 100_000L })
            assertEquals(BatteryProc.Cpu(1000, 200, 100), proc.read())
            task.delete(); timer.delete()
            assertEquals(BatteryProc.Cpu(1000, 200, 100), proc.read())
            task.writeText(stat(3, BatteryThreads.VPN, 5, 10_001)); timer.writeText(stat(4, BatteryThreads.ACCEPT, 5, 10_001))
            File(timer.parentFile, "comm").writeText(BatteryThreads.ACCEPT + "\n")
            assertEquals(BatteryProc.Cpu(1000, 300, 200), proc.read())
        } finally { root.deleteRecursively() }
        assertEquals(BatteryProc.Cpu(null, 0, 0), BatteryProc(File("missing-proc"), 100).read())
    }
    @Test fun procBaselinesPreexistingThreadsAfterInitialStatOrCommReadFailure() {
        for (name in listOf(BatteryThreads.VPN, BatteryThreads.TIMER, BatteryThreads.ACCEPT)) {
            for (missing in listOf("stat", "comm")) {
                val root = Files.createTempDirectory("battery-proc-late").toFile()
                fun stat(tid: Int, name: String, ticks: Int): String {
                    val fields = MutableList(20) { "0" }
                    fields[0] = "S"; fields[11] = ticks.toString(); fields[19] = "1"
                    return "$tid ($name) " + fields.joinToString(" ")
                }
                fun update(process: Int, thread: Int) {
                    File(root, "stat").writeText(stat(1, "app", process))
                    File(root, "task/3/stat").apply { parentFile!!.mkdirs(); writeText(stat(3, name, thread)) }
                    File(root, "task/3/comm").writeText("$name\n")
                }
                var fail = true
                val proc = BatteryProc(root, 100, elapsed = { 100_000L }) { file ->
                    if (fail && file == File(root, "task/3/$missing")) error("transient read")
                    file.readText()
                }
                try {
                    update(1000, 900)
                    val first = proc.read()
                    assertEquals(BatteryProc.Cpu(10_000, 0, 0), first)
                    fail = false
                    update(1010, 905)
                    val recovered = proc.read()
                    assertEquals("$name after failed $missing read", BatteryProc.Cpu(10_100, 0, 0), recovered)
                    update(1020, 910)
                    val next = proc.read()
                    assertEquals(BatteryProc.Cpu(10_200, if (name == BatteryThreads.VPN) 50 else 0,
                        if (name == BatteryThreads.VPN) 0 else 50), next)
                    assertTrue(next.vpn + next.forwarding <= next.process!! - first.process!!)
                    File(root, "task/3").deleteRecursively()
                    assertEquals(next, proc.read()) // Exited threads retain only the counted CPU.
                } finally { root.deleteRecursively() }
            }
        }
    }
    @Test fun procCountsThreadBornAfterRecordingFromZeroEvenWhenItsFirstReadFails() {
        for (name in listOf(BatteryThreads.VPN, BatteryThreads.TIMER, BatteryThreads.ACCEPT)) {
            val root = Files.createTempDirectory("battery-proc-new").toFile()
            fun stat(tid: Int, name: String, ticks: Int, start: Int): String {
                val fields = MutableList(20) { "0" }
                fields[0] = "S"; fields[11] = ticks.toString(); fields[19] = start.toString()
                return "$tid ($name) " + fields.joinToString(" ")
            }
            var now = 100_000L
            val proc = BatteryProc(root, 100, elapsed = { now })
            try {
                File(root, "stat").writeText(stat(1, "app", 1000, 1))
                assertEquals(BatteryProc.Cpu(10_000, 0, 0), proc.read())
                now += 1000
                val task = File(root, "task/3/stat").apply {
                    parentFile!!.mkdirs(); writeText(stat(3, name, 10, 10_001))
                }
                // It was born after the initial scan, but comm is temporarily unreadable.
                assertEquals(BatteryProc.Cpu(10_000, 0, 0), proc.read())
                now += 1000
                File(task.parentFile, "comm").writeText("$name\n")
                File(root, "stat").writeText(stat(1, "app", 1020, 1))
                task.writeText(stat(3, name, 15, 10_001))
                assertEquals(BatteryProc.Cpu(10_200, if (name == BatteryThreads.VPN) 150 else 0,
                    if (name == BatteryThreads.VPN) 0 else 150), proc.read())
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun procNamesAreReadOncePerTidStartIncludingWorkersAndOtherThreads() {
        fun stat(tid: Int, name: String, ticks: Int, start: Int): String {
            val fields = MutableList(20) { "0" }
            fields[0] = "S"; fields[11] = ticks.toString(); fields[19] = start.toString()
            return "$tid ($name) " + fields.joinToString(" ")
        }
        val root = Files.createTempDirectory("battery-proc-cache").toFile()
        val reads = mutableMapOf<String, Int>()
        fun task(tid: Int, name: String, ticks: Int, start: Int) {
            File(root, "task/$tid").mkdirs()
            File(root, "task/$tid/stat").writeText(stat(tid, name, ticks, start))
            File(root, "task/$tid/comm").writeText("$name\n")
        }
        try {
            File(root, "stat").writeText(stat(1, "app", 100, 1))
            task(3, BatteryThreads.WORKER, 500, 1)
            task(4, BatteryThreads.VPN, 10, 1)
            task(5, "unrelated", 900, 1)
            task(7, BatteryThreads.VPN, 10, 1)
            File(root, "task/7/comm").delete()
            val proc = BatteryProc(root, 100, elapsed = { 100_000L }) { file ->
                val name = file.relativeTo(root).path
                reads[name] = (reads[name] ?: 0) + 1
                file.readText()
            }
            assertEquals(BatteryProc.Cpu(1000, 100, 0), proc.read())
            task(4, BatteryThreads.VPN, 20, 1)
            assertEquals(BatteryProc.Cpu(1000, 200, 0), proc.read())
            for (tid in 3..5) assertEquals(1, reads["task/$tid/comm"])
            repeat(8) { assertEquals(BatteryProc.Cpu(1000, 200, 0), proc.read()) }
            assertEquals("Known workers must skip stat on the next nine samples", 1, reads["task/3/stat"])
            assertEquals(10, reads["task/4/stat"])
            assertEquals(10, reads["stat"])
            task(3, BatteryThreads.ACCEPT, 5, 10_001) // A reused worker TID needs a fresh classification.
            assertEquals(BatteryProc.Cpu(1000, 200, 50), proc.read())
            assertEquals("Revalidate a cached worker TID every ten samples", 2, reads["task/3/stat"])
            assertEquals(2, reads["task/3/comm"])
            assertEquals(1, reads["task/4/comm"])
            task(4, BatteryThreads.WORKER, 500, 10_001)
            assertEquals(BatteryProc.Cpu(1000, 200, 50), proc.read())
            assertEquals(2, reads["task/4/comm"])
            task(6, BatteryThreads.TIMER, 3, 10_001) // New TIDs also read comm once.
            assertEquals(BatteryProc.Cpu(1000, 200, 80), proc.read())
            assertEquals(1, reads["task/6/comm"])
            task(7, BatteryThreads.VPN, 15, 1) // A previously unreadable name can recover.
            assertEquals(BatteryProc.Cpu(1000, 200, 80), proc.read())
            task(7, BatteryThreads.VPN, 20, 1)
            assertEquals(BatteryProc.Cpu(1000, 250, 80), proc.read())
            File(root, "task").deleteRecursively()
            assertEquals(BatteryProc.Cpu(1000, 250, 80), proc.read())
        } finally { root.deleteRecursively() }
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
        ConnectApp.lastStatus.set(ConnectApp.VPN_STATUS_CONNECTED); ConnectApp.vpnTransitions.set(0); ConnectApp.reconnectEpisodes.set(0)
        method.invoke(service, ConnectApp.VPN_STATUS_RECONNECTING, false, null)
        method.invoke(service, ConnectApp.VPN_STATUS_RECONNECTING, false, null)
        method.invoke(service, "Connecting…", false, null)
        method.invoke(service, ConnectApp.VPN_STATUS_RECONNECTING, false, null)
        assertEquals(3L, ConnectApp.vpnTransitions.get()); assertEquals(1L, ConnectApp.reconnectEpisodes.get())
    }
}
