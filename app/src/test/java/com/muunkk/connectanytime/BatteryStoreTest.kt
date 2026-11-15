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
import org.robolectric.shadows.ShadowLog
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
        app.battery.close()
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
        assertEquals(0L, missing.currentAvg); assertNull(missing.voltageMv); assertEquals(-1L, missing.currentNow)
    }
    @Test fun csvPreservesZeroCurrentsAndFiltersUnsupportedCountersAndSentinels() {
        val row = batteryTrace().first().copy(currentNow = 0, currentAvg = 0, chargeUah = 0, energyNwh = 0)
        val columns = BatteryCsv.write(row).split(',')
        assertEquals(listOf("", "", "0", "0"), columns.subList(15, 19))
        val parsed = BatteryCsv.parse(BatteryCsv.write(row))!!
        assertEquals(0L, parsed.currentNow); assertEquals(0L, parsed.currentAvg)
        assertNull(parsed.chargeUah); assertNull(parsed.energyNwh)
        val missing = BatteryCsv.parse(BatteryCsv.write(row.copy(currentNow = Int.MIN_VALUE.toLong(), currentAvg = Long.MIN_VALUE)))!!
        assertNull(missing.currentNow); assertNull(missing.currentAvg)
        // Also filter sentinels in an imported CSV rather than only on write.
        val imported = columns.toMutableList().apply { this[17] = Int.MIN_VALUE.toString(); this[18] = Long.MIN_VALUE.toString() }
        assertNull(BatteryCsv.parse(imported.joinToString(","))!!.currentNow)
        assertNull(BatteryCsv.parse(imported.joinToString(","))!!.currentAvg)
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
    @Test fun summaryBuildsOnIoFromRetainedAndBufferedRowsWithoutUpdatingCachedReport() {
        app.store.measureBattery = true
        var fail = false
        val d = disk({ fail })
        val rows = batteryTrace().take(8)
        rows.take(5).forEach(d::add); d.refresh(); d.awaitIdle()
        val cached = d.report.value
        fail = true
        rows.drop(5).forEach(d::add); d.flush(); d.awaitIdle()
        var summary: BatteryReport? = null
        var threadName: String? = null
        d.summarize { summary = it; threadName = Thread.currentThread().name }
        d.awaitIdle()
        assertEquals(BatteryThreads.IO, threadName)
        assertEquals(8, summary!!.rowCount)
        assertEquals(7 * 60_000L, summary!!.countedMs)
        assertSame(cached, d.report.value)
        assertTrue(d.failed.value)
        assertEquals(3, d.bufferedRows().size)
    }
    @Test fun flushRebuildsOnlyDuringCollectionAndIncludesFailedBuffers() {
        app.store.measureBattery = true
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
    @Test fun unreadableCsvStillCompletesStopFlushAndRecoversThroughRetryAndClear() {
        app.store.measureBattery = true
        val d = disk()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { d.report.collect {} }
        try {
            batteryTrace().take(2).forEach(d::add)
            d.awaitIdle()
            d.file.mkdirs() // A directory fails both the write and the report read.
            var completions = 0
            d.flush { assertEquals(Looper.getMainLooper(), Looper.myLooper()); completions++ }
            d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, completions)
            assertTrue(d.failed.value)
            assertEquals(2, d.bufferedRows().size)
            assertTrue(d.file.delete())
            d.retrySave(); d.awaitIdle()
            assertFalse(d.failed.value)
            assertEquals(2, d.report.value!!.rowCount)
            var cleared = false
            assertTrue(d.clear { cleared = true })
            d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(cleared)
            assertEquals(0, d.report.value!!.rowCount)
        } finally { collector.cancel() }
    }
    @Test fun reportRebuildFailureKeepsLastReportAndLogsOnceWithoutReportingSaveFailure() {
        val saved = File(app.filesDir, "saved.csv")
        var breakReport = false
        val d = BatteryStore(app, app.store, append = { file, text ->
            file.appendText(text)
            if (breakReport && !text.startsWith("#")) {
                // Make only the report read fail after the append succeeds.
                check(file.renameTo(saved)); check(file.mkdir())
            }
        }).also { stores += it }
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { d.report.collect {} }
        try {
            val rows = batteryTrace().take(2)
            d.add(rows[0]); d.flush(); d.awaitIdle()
            val lastReport = d.report.value!!
            breakReport = true
            d.add(rows[1]); d.awaitIdle()
            val logsBefore = ShadowLog.getLogsForTag("BatteryStore").size
            var completed = false
            d.flush { completed = true }
            d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(completed)
            assertFalse("The append succeeded; this is only a report failure", d.failed.value)
            assertSame(lastReport, d.report.value)
            assertTrue(d.bufferedRows().isEmpty())
            assertEquals(rows, saved.readLines().mapNotNull(BatteryCsv::parse))
            val retry = BatteryStore::class.java.getDeclaredField("retry").apply { isAccessible = true }.get(d)
            assertNull(retry)
            d.refresh(); d.summarize { fail("Unreadable report must not call the summary callback") }; d.awaitIdle()
            assertFalse(d.failed.value)
            assertSame(lastReport, d.report.value)
            val logs = ShadowLog.getLogsForTag("BatteryStore").drop(logsBefore)
            assertEquals(1, logs.size)
            assertTrue(logs.single().msg.contains("FileNotFoundException"))
            assertFalse(logs.single().msg.contains(d.file.path))
            assertNull(logs.single().throwable)
            assertTrue(d.file.delete()); assertTrue(saved.renameTo(d.file))
            d.refresh(); d.awaitIdle()
            assertEquals(2, d.report.value!!.rowCount)
            assertFalse(d.failed.value)
            // Recovery resets the report failure latch for the next failure episode.
            assertTrue(d.file.renameTo(saved)); assertTrue(d.file.mkdir())
            d.refresh(); d.refresh(); d.awaitIdle()
            assertEquals(logsBefore + 2, ShadowLog.getLogsForTag("BatteryStore").size)
            assertFalse(d.failed.value)
        } finally { collector.cancel(); saved.delete() }
    }
    @Test fun firstLoadFailureIsExposedAndSuccessfulRefreshOrClearRecovers() {
        val d = disk()
        d.file.mkdirs()
        d.refresh(); d.awaitIdle()
        assertNull(d.report.value)
        assertTrue(d.loadFailed.value)
        assertFalse(d.failed.value)
        assertTrue(d.file.delete())
        d.refresh(); d.awaitIdle()
        assertFalse(d.loadFailed.value)
        assertEquals(0, d.report.value!!.rowCount)

        // Clear must also recover directly from a failed first load, without a cached report.
        val fresh = disk()
        fresh.file.mkdirs()
        fresh.refresh(); fresh.awaitIdle()
        assertNull(fresh.report.value)
        assertTrue(fresh.loadFailed.value)
        var cleared = false
        assertTrue(fresh.clear { cleared = true })
        fresh.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(cleared)
        assertFalse(fresh.loadFailed.value)
        assertFalse(fresh.failed.value)
        assertEquals(0, fresh.report.value!!.rowCount)
    }
    @Test fun finalFlushWhileOffCancelsRetriesAndResetsSaveFailureButRetainsRows() {
        app.store.measureBattery = true
        val d = disk({ true })
        val rows = batteryTrace().take(5)
        rows.forEach(d::add); d.awaitIdle()
        assertTrue(d.failed.value)
        val retryField = BatteryStore::class.java.getDeclaredField("retry").apply { isAccessible = true }
        val pending = retryField.get(d) as java.util.concurrent.ScheduledFuture<*>
        assertFalse(pending.isCancelled)
        app.store.measureBattery = false
        var completed = false
        d.flush { completed = true }
        d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(completed)
        assertFalse(d.failed.value)
        assertTrue(pending.isCancelled)
        assertNull(retryField.get(d))
        assertEquals(0, BatteryStore::class.java.getDeclaredField("failures").apply { isAccessible = true }.get(d))
        assertEquals(rows, d.bufferedRows())

        app.store.measureBattery = true
        assertFalse("Re-enabling must not restore a stale save error", d.failed.value)
        d.retrySave(); d.awaitIdle()
        assertTrue(d.failed.value)
        val next = retryField.get(d) as java.util.concurrent.ScheduledFuture<*>
        assertTrue("The next failure must start again with a one-minute backoff", next.getDelay(java.util.concurrent.TimeUnit.SECONDS) in 50..60)
    }
    @Test fun failedFlushRetainsIdsAndDedupsCompleteRowsAfterRestart() {
        app.store.measureBattery = true
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
    @Test fun exportIncludesSavedAndBufferedRowsWhenFlushFails() {
        app.store.measureBattery = true
        var fail = false
        val d = disk({ fail })
        val rows = batteryTrace().take(8)
        rows.take(5).forEach(d::add); d.awaitIdle()
        fail = true
        rows.drop(5).forEach(d::add)
        // A complete row and a partial tail may have reached disk before a write failed.
        d.awaitIdle()
        d.file.appendText(BatteryCsv.write(rows[5]) + "\n1,partial")
        var snapshot: File? = null
        var called = false
        d.export { called = true; snapshot = it }
        d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(called)
        assertTrue(d.failed.value)
        assertNotNull(snapshot)
        assertEquals(rows, snapshot!!.readLines().mapNotNull(BatteryCsv::parse))
        assertEquals(rows.drop(6), d.bufferedRows())
    }
    @Test fun exportReturnsNullWhenSnapshotCannotBeWritten() {
        val d = disk()
        d.awaitIdle()
        d.exports.writeText("blocks export directory")
        var called = false
        d.export { called = true; assertNull(it) }
        d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(called)
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
    @Test fun defaultLimitRetainsAFullSevenDayMinuteTrace() {
        val d = BatteryStore(app, app.store).also { stores += it }
        val rows = batteryTrace(7 * 24 * 60).map { it.copy(wallMin = it.wallMin + 29_000_000,
            boot = 100, rec = 1000, cpuProcMs = it.cpuProcMs!! + 100_000_000) }
        val csv = rows.joinToString("") { BatteryCsv.write(it) + "\n" }
        assertTrue("The regression trace must exceed the old two-MiB limit", csv.toByteArray().size > 2 * 1024 * 1024)
        d.file.parentFile!!.mkdirs()
        d.file.writeText(csv)
        d.flush(); d.awaitIdle()
        assertTrue(d.file.length() <= 4 * 1024 * 1024)
        assertEquals(rows, d.file.readLines().mapNotNull(BatteryCsv::parse))
    }
    @Test fun storedHeadersStaySeparateFromExportMetadataEvenAfterRotation() {
        val d = disk(max = 4096)
        d.add(batteryTrace().first()); d.flush(); d.awaitIdle()
        fun checkStored() {
            val text = d.file.readText()
            assertTrue(text.startsWith("# schema=1\n# build="))
            for (field in listOf("# exported=", "# capacity_mAh_est=", "# adb:", "# device=", "# android=")) {
                assertFalse("Stored CSV contains export metadata: $field", text.contains(field))
            }
        }
        checkStored()
        batteryTrace(100).drop(1).forEach(d::add); d.flush(); d.awaitIdle()
        assertTrue(d.file.readLines().mapNotNull(BatteryCsv::parse).first().seq > 0)
        checkStored()
        var exported: File? = null
        d.export { exported = it }; d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        val text = exported!!.readText()
        assertTrue(text.contains("# exported=10000000\n"))
        assertTrue(text.contains("# capacity_mAh_est="))
        assertTrue(text.contains("# device="))
        assertTrue(text.contains("# adb:"))
    }
    @Test fun clearDeletesBufferCsvAndLatchButKeepsExportsReadableUntilPruned() {
        val d = disk()
        batteryTrace().take(5).forEach(d::add); d.awaitIdle()
        var exported: File? = null
        d.export { exported = it }; d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        val snapshot = exported!!
        val text = snapshot.readText()
        batteryTrace().drop(5).take(2).forEach(d::add); d.awaitIdle()
        assertEquals(2, d.bufferedRows().size)
        app.store.batteryAutoStopped = true
        var callback = false
        assertTrue(d.clear { callback = true })
        d.awaitIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(callback); assertFalse(d.file.exists())
        assertTrue(d.exports.exists())
        // A share target can open the snapshot after Clear has completed.
        assertEquals(text, snapshot.inputStream().bufferedReader().use { it.readText() })
        assertTrue(d.bufferedRows().isEmpty()); assertFalse(app.store.batteryAutoStopped)
        assertEquals(0, d.report.value!!.rowCount)
        snapshot.setLastModified(6_400_000) // Exactly one hour old is still retained.
        d.refresh(); d.awaitIdle()
        assertTrue(snapshot.exists())
        snapshot.setLastModified(6_399_999)
        d.refresh(); d.awaitIdle()
        assertFalse(snapshot.exists())
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
    @Test fun constructionKeepsIoLazyAndRefreshPrunesExpiredExports() {
        val directory = File(app.cacheDir, "battery-export").apply { mkdirs() }
        val old = File(directory, "old.csv").apply { writeText("old"); setLastModified(1) }
        val recent = File(directory, "recent.csv").apply { writeText("new"); setLastModified(9_999_999) }
        val boundary = File(directory, "boundary.csv").apply { writeText("boundary"); setLastModified(6_400_000) }
        val d = disk()
        val executor = BatteryStore::class.java.getDeclaredField("io").apply { isAccessible = true }.get(d) as java.util.concurrent.ScheduledThreadPoolExecutor
        assertEquals(0, executor.poolSize)
        assertEquals(0L, executor.taskCount)
        assertTrue("Construction pruned an export", old.exists())
        assertTrue(recent.exists()); assertTrue(boundary.exists())
        val expired = File(directory, "expired.csv").apply { writeText("expired"); setLastModified(6_399_999) }
        d.refresh(); d.awaitIdle()
        assertFalse("Refresh retained an expired export", expired.exists())
        assertFalse(old.exists())
        assertTrue(recent.exists()); assertTrue(boundary.exists())
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
