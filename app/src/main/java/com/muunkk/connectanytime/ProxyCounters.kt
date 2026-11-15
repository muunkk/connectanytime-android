package com.muunkk.connectanytime

import android.os.Debug
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal object BatteryThreads {
    const val VPN = "oc-run"
    const val TIMER = "proxy-timer"
    const val WORKER = "proxy-worker"
    const val ACCEPT = "proxy-accept"
    const val RECORDER = "battery-rec"
    const val IO = "battery-io"
}

/** Separate totals survive the sharing card's per-link resets and contain no peer data. */
internal object ProxyCounters {
    val hotUp = AtomicLong(); val hotDown = AtomicLong()
    val usbUp = AtomicLong(); val usbDown = AtomicLong()
    val forwardingCpuNs = AtomicLong()
    @Volatile var gen = 0L
    private val task = ThreadLocal<CpuMeter>()
    fun before(meter: CpuMeter = CpuMeter()) { task.set(meter) }
    fun after() { try { task.get()?.flush() } finally { task.remove() } }
    fun traffic(link: TetherLink, up: Boolean, bytes: Int, now: Long) {
        if (gen == 0L) return
        val total = if (link == TetherLink.Hotspot) { if (up) hotUp else hotDown } else { if (up) usbUp else usbDown }
        total.addAndGet(bytes.toLong())
        task.get()?.tick(now)
    }
}

/** A task owns its CPU baseline, even when two workers read the same stream. */
internal class CpuMeter(private val cpu: () -> Long = Debug::threadCpuTimeNanos,
    private val clock: () -> Long = System::nanoTime) {
    private var gen = ProxyCounters.gen
    private var last = if (gen != 0L) cpu() else 0L
    private var flushed = if (gen != 0L) clock() else 0L
    fun tick(now: Long) {
        if (ProxyCounters.gen != 0L && now - flushed >= TimeUnit.SECONDS.toNanos(1)) {
            flush(); flushed = now
        }
    }
    fun flush() {
        synchronized(ProxyCounters) {
            val current = ProxyCounters.gen
            if (current == 0L) { gen = 0; return }
            val now = cpu()
            if (gen == current) ProxyCounters.forwardingCpuNs.addAndGet((now - last).coerceAtLeast(0))
            last = if (gen == current) maxOf(last, now) else now
            gen = current
        }
    }
}

internal class ProxyWorkers : ThreadPoolExecutor(0, 512, 30, TimeUnit.SECONDS, SynchronousQueue(),
    { runnable -> Thread(runnable, BatteryThreads.WORKER) }) {
    override fun beforeExecute(thread: Thread, runnable: Runnable) { super.beforeExecute(thread, runnable); runCatching { ProxyCounters.before() } }
    override fun afterExecute(runnable: Runnable, error: Throwable?) {
        try { runCatching { ProxyCounters.after() } } finally { super.afterExecute(runnable, error) }
    }
}
