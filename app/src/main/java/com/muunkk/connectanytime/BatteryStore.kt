package com.muunkk.connectanytime

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val BATTERY_AUTHORITY = BuildConfig.APPLICATION_ID + ".battery"

/** All disk operations share one queue, including exports while recording is off. */
internal class BatteryStore(private val context: Context, private val prefs: Store,
    private val now: () -> Long = System::currentTimeMillis,
    private val cpu: () -> Long = Debug::threadCpuTimeNanos,
    private val append: (File, String) -> Unit = { file, text ->
        FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush(); it.fd.sync() }
    }, private val maxBytes: Long = 4 * 1024 * 1024) {
    private val io = ScheduledThreadPoolExecutor(1) { Thread(it, BatteryThreads.IO) }.apply {
        setKeepAliveTime(30, TimeUnit.SECONDS); allowCoreThreadTimeOut(true); removeOnCancelPolicy = true
    }
    private val main = Handler(Looper.getMainLooper())
    internal val file = File(context.filesDir, "battery/samples.csv")
    internal val exports = File(context.cacheDir, "battery-export")
    private val buffer = ArrayDeque<BatteryRow>()
    private var failures = 0
    private var retry: java.util.concurrent.ScheduledFuture<*>? = null
    private val mutableReport = MutableStateFlow<BatteryReport?>(null)
    val report = mutableReport.asStateFlow()
    private val mutableLoadFailed = MutableStateFlow(false)
    /** Report reads can fail independently of saves, including before the first snapshot. */
    val loadFailed = mutableLoadFailed.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val failed = mutableFailed.asStateFlow()
    val clearing = AtomicBoolean()
    val ioCpuUs = AtomicLong()
    private fun task(block: () -> Unit) = Runnable {
        val start = cpu()
        runCatching(block).onFailure { Log.w("BatteryStore", "Battery task failed (${it.javaClass.simpleName})") }
        ioCpuUs.addAndGet(((cpu() - start) / 1000).coerceAtLeast(0))
    }
    private fun execute(block: () -> Unit) { io.execute(task(block)) }
    fun add(row: BatteryRow) = execute {
        buffer.addLast(row)
        if (buffer.size > 120) {
            val oldest = buffer.removeFirst()
            val next = buffer.removeFirst()
            buffer.addFirst(oldest.copy(rec = next.rec, seq = next.seq, kind = BatteryKind.gap))
        }
        if (buffer.size >= 5 && retry == null) flushNow()
    }
    private fun headers(export: Boolean): String = buildString {
        append("# schema=1\n# build=${BuildConfig.BUILD_DESCRIPTION}\n")
        if (export) {
            append("# device=${Build.MANUFACTURER} ${Build.MODEL}\n# android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("# capacity_mAh_est=${report.value?.capacityMah ?: ""}\n# exported=${now()}\n")
            append("# adb: README.md, battery measurement ground truth steps\n")
        }
        append(BatteryCsv.COLUMNS); append('\n')
    }
    private fun truncateTail() {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { data ->
            var end = data.length()
            while (end > 0) { data.seek(end - 1); if (data.read() == 10) break; end-- }
            if (end != data.length()) data.setLength(end)
        }
    }
    private fun lastId(): Pair<Long, Long>? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { data ->
            var end = data.length() - 1
            while (end > 0) {
                var start = end - 1
                while (start >= 0) { data.seek(start); if (data.read() == 10) break; start-- }
                data.seek(start + 1)
                BatteryCsv.parse(data.readLine().orEmpty())?.let { return it.id }
                end = start
            }
        }
        return null
    }
    private fun after(id: Pair<Long, Long>, last: Pair<Long, Long>) = id.first > last.first || (id.first == last.first && id.second > last.second)
    internal fun flushNow(): Boolean {
        val saved = try {
            file.parentFile!!.mkdirs()
            truncateTail()
            val last = lastId()
            while (buffer.isNotEmpty() && last != null && !after(buffer.first().id, last)) buffer.removeFirst()
            if (!file.exists() || file.length() == 0L) append(file, headers(export = false))
            if (buffer.isNotEmpty()) append(file, buffer.joinToString("") { BatteryCsv.write(it) + "\n" })
            buffer.clear()
            rotate()
            failures = 0; retry?.cancel(false); retry = null; mutableFailed.value = false
            true
        } catch (_: Throwable) {
            mutableFailed.value = true
            if (retry == null) {
                val minutes = minOf(30L, 1L shl minOf(failures++, 5))
                retry = io.schedule(task { retry = null; flushNow() }, minutes, TimeUnit.MINUTES)
            }
            false
        }
        if (!prefs.measureBattery) {
            // The stop flush is the last attempt for this run. Keep unsaved rows, but
            // do not keep retrying or carry its save error into the next run.
            retry?.cancel(false); retry = null; failures = 0; mutableFailed.value = false
        }
        // A report read can fail after a successful append or during write recovery.
        if (mutableReport.subscriptionCount.value > 0) rebuild()
        return saved
    }
    private fun rotate() {
        if (file.length() <= maxBytes) return
        val temp = File(file.parentFile, "samples.tmp")
        FileOutputStream(temp).use { output ->
            output.write(headers(export = false).toByteArray(Charsets.UTF_8))
            RandomAccessFile(file, "r").use { input ->
                input.seek(file.length() / 2)
                input.readLine() // The cut can be inside a row.
                val bytes = ByteArray(8192)
                while (true) { val n = input.read(bytes); if (n < 0) break; output.write(bytes, 0, n) }
            }
            output.fd.sync()
        }
        check(temp.renameTo(file))
    }
    private fun <T> rows(block: (Sequence<BatteryRow>) -> T): T {
        val pending = buffer.toList()
        if (!file.exists()) return block(pending.asSequence())
        truncateTail()
        return file.bufferedReader().use { reader ->
            var last: Pair<Long, Long>? = null
            val saved = reader.lineSequence().mapNotNull(BatteryCsv::parse).onEach { last = it.id }
            block(sequence {
                yieldAll(saved)
                yieldAll(pending.filter { last == null || after(it.id, last!!) })
            })
        }
    }
    private fun buildReport(): BatteryReport? = try {
        rows { BatteryMath.report(it, prefs.proxySetup) }.also { mutableLoadFailed.value = false }
    } catch (cause: Throwable) {
        if (!mutableLoadFailed.value) Log.w("BatteryStore", "Battery report failed (${cause.javaClass.simpleName})")
        mutableLoadFailed.value = true
        null
    }
    private fun rebuild() { buildReport()?.let { mutableReport.value = it } }
    /** Build from retained data on battery-io even when the UI report is unobserved. */
    fun summarize(callback: (BatteryReport) -> Unit) = execute { buildReport()?.let(callback) }
    private fun pruneExports() {
        val cutoff = now() - 3_600_000
        exports.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
    fun refresh() = execute { pruneExports(); rebuild() }
    fun flush(callback: (() -> Unit)? = null) = execute {
        try { flushNow() } finally { callback?.let { main.post(it) } }
    }
    fun retrySave() = execute { retry?.cancel(false); retry = null; flushNow() }
    fun export(callback: (File?) -> Unit) = execute {
        val snapshot = runCatching {
            flushNow()
            exports.mkdirs()
            pruneExports()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now()))
            var out: File
            do { out = File(exports, "battery-$stamp-${UUID.randomUUID().toString().take(4)}.csv") } while (!out.createNewFile())
            try {
                out.outputStream().bufferedWriter().use { writer ->
                    writer.write(headers(export = true))
                    rows { rows -> rows.forEach { writer.write(BatteryCsv.write(it)); writer.newLine() } }
                }
                out
            } catch (t: Throwable) { out.delete(); throw t }
        }.getOrNull()
        main.post { callback(snapshot) }
    }
    fun clear(callback: () -> Unit): Boolean {
        if (!clearing.compareAndSet(false, true)) return false
        execute {
            try {
                retry?.cancel(false); retry = null; buffer.clear()
                check(!file.exists() || file.delete())
                File(file.parentFile, "samples.tmp").delete()
                check(!exports.exists() || exports.deleteRecursively())
                prefs.batteryAutoStopped = false
                failures = 0; mutableFailed.value = false; mutableReport.value = null
                rebuild()
            } finally { clearing.set(false); main.post(callback) }
        }
        return true
    }
    internal fun awaitIdle() { io.submit {}.get(10, TimeUnit.SECONDS) }
    internal fun bufferedRows(): List<BatteryRow> = io.submit<List<BatteryRow>> { buffer.toList() }.get(10, TimeUnit.SECONDS)
    internal fun close() { retry?.cancel(false); io.shutdownNow() }
}
