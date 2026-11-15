package com.muunkk.connectanytime

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35])
class BatteryStoreTest {
    private lateinit var app: ConnectApp
    private val stores = mutableListOf<BatteryStore>()
    @Before fun init() {
        app = ApplicationProvider.getApplicationContext()
        app.store.measureBattery = false
        File(app.filesDir, "battery").deleteRecursively(); File(app.cacheDir, "battery-export").deleteRecursively()
    }
    @After fun close() { stores.forEach { it.close() } }
    private fun disk(fail: (() -> Boolean)? = null, max: Long = 2 * 1024 * 1024): BatteryStore =
        BatteryStore(app, app.store, now = { 10_000_000 }, cpu = { 1000 }, append = { f, text ->
            if (fail?.invoke() == true) error("disk full")
            f.appendText(text)
        }, maxBytes = max).also { stores += it }
    @Test fun schemaRoundTripCommentsPartialAndSentinels() {
        val row = batteryTrace().first().copy(currentNow = -1, samplerCpuUs = 12, ioCpuUs = 34)
        assertEquals(42, BatteryCsv.COLUMNS.split(',').size)
        assertEquals(row, BatteryCsv.parse(BatteryCsv.write(row)))
        assertNull(BatteryCsv.parse("# schema=1")); assertNull(BatteryCsv.parse("1,2,"))
        assertNull(BatteryCsv.parse(BatteryCsv.COLUMNS))
        val missing = BatteryCsv.parse(BatteryCsv.write(row.copy(chargeUah = Int.MIN_VALUE.toLong(), energyNwh = Long.MIN_VALUE,
            mobileRx = -1, currentAvg = 0, voltageMv = Int.MIN_VALUE)))!!
        assertNull(missing.chargeUah); assertNull(missing.energyNwh); assertNull(missing.mobileRx)
        assertNull(missing.currentAvg); assertNull(missing.voltageMv); assertEquals(-1L, missing.currentNow)
    }
    @Test fun storeWorksWhileOffAndExportIsExclusiveFlushedAndMainThread() {
        val d = disk()
        batteryTrace().take(3).forEach(d::add)
        var first: File? = null; var second: File? = null
        d.export { assertEquals(Looper.getMainLooper(), Looper.myLooper()); first = it }
        d.export { second = it }
        d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(first); assertNotNull(second); assertNotEquals(first, second)
        assertEquals(3, first!!.readLines().mapNotNull(BatteryCsv::parse).size)
        assertTrue(first!!.readText().startsWith("# schema=1\n# build="))
        d.refresh(); d.awaitIdle()
        assertEquals(3, d.report.value!!.rowCount)
    }
    @Test fun unobservedFlushesKeepLastReportUntilExplicitRefresh() {
        val d = disk()
        val rows = batteryTrace().take(10)
        rows.take(5).forEach(d::add); d.awaitIdle()
        assertNull(d.report.value)
        d.refresh(); d.awaitIdle()
        val first = d.report.value!!
        assertEquals(5, first.rowCount)
        rows.drop(5).forEach(d::add); d.awaitIdle()
        assertSame(first, d.report.value)
        d.refresh(); d.awaitIdle()
        assertEquals(10, d.report.value!!.rowCount)
    }
    @Test fun flushRebuildsOnlyDuringCollectionAndIncludesFailedBuffers() {
        var fail = false
        val d = disk({ fail })
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { d.report.collect {} }
        try {
            batteryTrace().take(5).forEach(d::add); d.awaitIdle()
            assertEquals(5, d.report.value!!.rowCount)
            fail = true
            batteryTrace(rec = 2).take(5).forEach(d::add); d.awaitIdle()
            assertTrue(d.failed.value)
            assertEquals(10, d.report.value!!.rowCount)
        } finally { collector.cancel() }
        val last = d.report.value
        batteryTrace(rec = 3).take(5).forEach(d::add); d.retrySave(); d.awaitIdle()
        assertSame(last, d.report.value)
        d.refresh(); d.awaitIdle()
        assertEquals(15, d.report.value!!.rowCount)
    }
    @Test fun failedFlushRetainsIdsAndDedupsCompleteRowsAfterRestart() {
        var fail = true
        val d = disk({ fail })
        val rows = batteryTrace().take(5)
        rows.forEach(d::add); d.awaitIdle()
        assertTrue(d.failed.value); assertEquals(5, d.bufferedRows().size)
        // A previous failed write reached disk before close/sync reported its error.
        d.file.writeText("# schema=1\n" + rows.take(3).joinToString("") { BatteryCsv.write(it) + "\n" } + "1,partial")
        fail = false
        batteryTrace(rec = 2).take(2).forEach(d::add)
        d.retrySave(); d.awaitIdle()
        val saved = d.file.readLines().mapNotNull(BatteryCsv::parse)
        assertEquals(7, saved.size); assertEquals(7, saved.map { it.id }.distinct().size)
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 2L, 2L), saved.map { it.rec })
        assertFalse(d.failed.value)
    }
    @Test fun rebuildSkipsEvenAParseableRowUntilItsNewlineIsPresent() {
        val d = disk()
        d.file.parentFile!!.mkdirs()
        val rows = batteryTrace().take(2)
        d.file.writeText(BatteryCsv.write(rows[0]) + "\n" + BatteryCsv.write(rows[1]))
        d.refresh(); d.awaitIdle()
        assertEquals(1, d.report.value!!.rowCount)
        assertEquals(BatteryCsv.write(rows[0]) + "\n", d.file.readText())
    }
    @Test fun bufferBoundKeepsGapAndRecoveryWritesIt() {
        var fail = true
        val d = disk({ fail })
        batteryTrace(200).forEach(d::add); d.awaitIdle()
        assertEquals(120, d.bufferedRows().size)
        assertEquals(BatteryKind.gap, d.bufferedRows().first().kind)
        fail = false; d.retrySave(); d.awaitIdle()
        assertEquals(120, d.file.readLines().mapNotNull(BatteryCsv::parse).size)
        d.refresh(); d.awaitIdle()
        assertTrue(d.report.value!!.droppedCoverageMs > 0)
    }
    @Test fun rotationKeepsNewestHalfWithinRealByteLimit() {
        val d = disk(max = 4096)
        batteryTrace(100).forEach(d::add); d.flush(); d.awaitIdle()
        assertTrue(d.file.length() <= 4096)
        val rows = d.file.readLines().mapNotNull(BatteryCsv::parse)
        assertTrue(rows.isNotEmpty()); assertEquals(100L, rows.last().seq)
        assertTrue(rows.first().seq > 0)
        assertTrue(d.file.readText().endsWith("\n"))
    }
    @Test fun clearDeletesBufferCsvExportsAndLatchAndIgnoresConcurrentClear() {
        val d = disk()
        batteryTrace().take(5).forEach(d::add); d.awaitIdle()
        d.exports.mkdirs(); File(d.exports, "old.csv").writeText("old")
        app.store.batteryAutoStopped = true
        var callback = false
        assertTrue(d.clear { callback = true })
        d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(callback); assertFalse(d.file.exists()); assertFalse(d.exports.exists())
        assertTrue(d.bufferedRows().isEmpty()); assertFalse(app.store.batteryAutoStopped)
        assertEquals(0, d.report.value!!.rowCount)
    }
    @Test fun exportDeletesOnlyOldSnapshotsAndProviderRestrictsPaths() {
        val d = disk()
        d.exports.mkdirs()
        val old = File(d.exports, "old.csv").apply { writeText("old"); setLastModified(1) }
        val recent = File(d.exports, "recent.csv").apply { writeText("new"); setLastModified(9_999_999) }
        var exported: File? = null
        d.export { exported = it }; d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(old.exists()); assertTrue(recent.exists())
        val uri = FileProvider.getUriForFile(app, BATTERY_AUTHORITY, exported!!)
        assertEquals(BATTERY_AUTHORITY, uri.authority)
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, BATTERY_AUTHORITY, File(app.filesDir, "activity.txt")) }
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, BATTERY_AUTHORITY, File(app.cacheDir, "private.csv")) }
        val info = app.packageManager.resolveContentProvider(BATTERY_AUTHORITY, 0)!!
        assertFalse(info.exported); assertTrue(info.grantUriPermissions)
    }
    @Test fun observerCanThrowThrowableAndLogStillStoresAndReturns() {
        app.log.observer = { throw AssertionError("observer") }
        app.log.add("CSTP connected")
        assertTrue(app.log.state.value.last().endsWith("CSTP connected"))
        app.log.observer = null
    }
    @Test fun preferencesPersistAndRecordingIdsAreMonotonic() {
        assertFalse(app.store.measureBattery)
        app.store.batteryDeadline = 999; app.store.batteryAutoStopped = true
        val a = app.store.nextBatteryRec(); val b = Store(app).nextBatteryRec()
        assertEquals(a + 1, b); assertEquals(b, Store(app).batteryRec)
        assertEquals(999L, Store(app).batteryDeadline); assertTrue(Store(app).batteryAutoStopped)
    }
}
