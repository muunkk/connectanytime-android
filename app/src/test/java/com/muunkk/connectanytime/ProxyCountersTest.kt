package com.muunkk.connectanytime

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35])
class ProxyCountersTest {
    private val cpuDelta = ThreadLocal.withInitial { 0L }
    @Before fun reset() { ProxyCounters.gen = 0; listOf(ProxyCounters.hotUp, ProxyCounters.hotDown, ProxyCounters.usbUp, ProxyCounters.usbDown, ProxyCounters.forwardingCpuNs).forEach { it.reset() } }
    @After fun stop() { ProxyCounters.gen = 0 }
    @Test fun replacingTheThreadFactoryWithPlainThreadsStillRunsTasks() {
        ProxyCounters.gen = 1
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val pool = ProxyWorkers { error("Plain threads cannot have worker-local CPU meters") }
        pool.threadFactory = java.util.concurrent.ThreadFactory { runnable ->
            Thread(runnable).apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> errors.add(error) } }
        }
        try {
            assertEquals(42, pool.submit<Int> { 42 }.get(5, TimeUnit.SECONDS))
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(0L, ProxyCounters.forwardingCpuNs.sum())
    }
    @Test fun disabledMeterReadsNeitherClock() {
        val fail = { error("clock read while disabled") }
        val meter = CpuMeter(fail, fail)
        meter.tick(Long.MAX_VALUE); meter.flush()
        ProxyCounters.traffic(TetherLink.Hotspot, true, 100, 0)
        ProxyCounters.traffic(TetherLink.Usb, false, 200, 0)
        assertEquals(0L, ProxyCounters.hotUp.sum())
        assertEquals(0L, ProxyCounters.usbDown.sum())
    }
    @Test fun staleGenerationIsDiscardedAndDeltaIsMonotonic() {
        var cpu = 100L
        ProxyCounters.gen = 1
        val meter = CpuMeter({ cpu }, { 0 })
        cpu = 200; ProxyCounters.gen = 2; meter.flush()
        assertEquals(0L, ProxyCounters.forwardingCpuNs.sum())
        cpu = 250; meter.flush()
        assertEquals(50L, ProxyCounters.forwardingCpuNs.sum())
        cpu = 240; meter.flush()
        assertEquals(50L, ProxyCounters.forwardingCpuNs.sum())
    }
    @Test fun periodicCpuFlushDoesNotWaitForTheGlobalCounterMonitor() {
        ProxyCounters.gen = 1
        var cpu = 100L
        val meter = CpuMeter({ cpu }, { 0 })
        val worker = Executors.newSingleThreadExecutor()
        try {
            synchronized(ProxyCounters) {
                cpu = 150
                worker.submit { meter.tick(TimeUnit.SECONDS.toNanos(1)) }.get(2, TimeUnit.SECONDS)
                assertEquals(50L, ProxyCounters.forwardingCpuNs.sum())
            }
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun unmeteredTasksAndReadsNeverAllocateCpuMeters() {
        val allocations = AtomicInteger()
        val pool = ProxyWorkers { allocations.incrementAndGet(); error("allocated without a metered read") }
        try {
            repeat(20) {
                ProxyCounters.gen = 0
                pool.submit {
                    repeat(10) { ProxyCounters.traffic(TetherLink.Hotspot, true, 100, 0) }
                    assertNull((Thread.currentThread() as ProxyWorker).meter)
                }.get()
            }
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        assertEquals(0, allocations.get())
        assertEquals(0L, ProxyCounters.hotUp.sum())
    }
    @Test fun enabledTaskMeasuresCpuBeforeFirstTrafficAndWithoutTraffic() {
        val allocations = AtomicInteger()
        ProxyCounters.gen = 1
        val pool = ProxyWorkers { allocations.incrementAndGet(); CpuMeter({ cpuDelta.get()!! }, { 0 }) }
        try {
            pool.submit {
                assertNotNull((Thread.currentThread() as ProxyWorker).meter)
                cpuDelta.set(cpuDelta.get()!! + 150)
                ProxyCounters.traffic(TetherLink.Hotspot, true, 100, 0)
                cpuDelta.set(cpuDelta.get()!! + 50)
            }.get()
            pool.submit {
                assertNotNull((Thread.currentThread() as ProxyWorker).meter)
                cpuDelta.set(cpuDelta.get()!! + 150)
            }.get()
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        assertEquals(2, allocations.get())
        assertEquals(350L, ProxyCounters.forwardingCpuNs.sum())
    }
    @Test fun twoThreadHandoffUsesTaskLocalCpuBaselines() {
        ProxyCounters.gen = 1
        val started = CountDownLatch(2)
        val baselined = CountDownLatch(2)
        val pool = ProxyWorkers { CpuMeter({ cpuDelta.get()!! }, { 0 }) }
        try {
            val tasks = listOf(60L, 90L).map { delta ->
                pool.submit {
                    started.countDown(); check(started.await(5, TimeUnit.SECONDS))
                    ProxyCounters.traffic(TetherLink.Hotspot, delta == 60L, 100, 0)
                    baselined.countDown(); check(baselined.await(5, TimeUnit.SECONDS))
                    cpuDelta.set(cpuDelta.get()!! + delta)
                }
            }
            tasks.forEach { it.get() }
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        assertEquals(150L, ProxyCounters.forwardingCpuNs.sum())
    }
    @Test fun firstReadAfterEnablingInsideAnExistingTaskStartsItsCpuBaseline() {
        var cpu = 10L
        var allocations = 0
        val pool = ProxyWorkers { allocations++; CpuMeter({ cpu }, { 0 }) }
        var worker: ProxyWorker? = null
        try {
            pool.submit {
                worker = Thread.currentThread() as ProxyWorker
                ProxyCounters.traffic(TetherLink.Hotspot, true, 100, 0)
                assertEquals(0, allocations)
                cpu = 500; ProxyCounters.gen = 7
                ProxyCounters.traffic(TetherLink.Hotspot, true, 200, 0)
                assertEquals(1, allocations)
                cpu = 550
            }.get()
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        assertEquals(200L, ProxyCounters.hotUp.sum())
        assertEquals(50L, ProxyCounters.forwardingCpuNs.sum())
        assertNull(worker!!.meter)
    }
    @Test fun disablingDuringATaskStillClearsItsMeterWithoutReadingCpu() {
        var reads = 0
        val pool = ProxyWorkers { CpuMeter({ reads++; 100 }, { 0 }) }
        var worker: ProxyWorker? = null
        try {
            ProxyCounters.gen = 1
            pool.submit {
                worker = Thread.currentThread() as ProxyWorker
                ProxyCounters.traffic(TetherLink.Hotspot, true, 100, 0)
                assertNotNull(worker!!.meter)
                ProxyCounters.gen = 0
            }.get()
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        assertEquals(1, reads)
        assertNull(worker!!.meter)
        assertEquals(0L, ProxyCounters.forwardingCpuNs.sum())
    }
    @Test fun offToOnDiscardsUnmeasuredCpuAndTicksFlushOnlyEverySecond() {
        var cpu = 0L; var reads = 0
        val meter = CpuMeter({ reads++; cpu }, { 0 })
        ProxyCounters.gen = 3
        cpu = 100; meter.tick(1_000_000_000)
        cpu = 200; meter.tick(1_500_000_000)
        assertEquals(1, reads)
        meter.tick(2_000_000_000)
        assertEquals(100L, ProxyCounters.forwardingCpuNs.sum())
        meter.flush(); assertEquals(100L, ProxyCounters.forwardingCpuNs.sum())
    }
    @Test fun concurrentTrafficRetainsEveryDirectionalByteAndCpuTotal() {
        ProxyCounters.gen = 1
        val pool = ProxyWorkers { CpuMeter({ cpuDelta.get()!! }, { 0 }) }
        try {
            val tasks = (1..8).map {
                pool.submit {
                    repeat(3000) {
                        ProxyCounters.traffic(TetherLink.Hotspot, true, 1, 0)
                        ProxyCounters.traffic(TetherLink.Hotspot, false, 2, 0)
                        ProxyCounters.traffic(TetherLink.Usb, true, 3, 0)
                        ProxyCounters.traffic(TetherLink.Usb, false, 4, 0)
                        cpuDelta.set(cpuDelta.get()!! + 10)
                    }
                }
            }
            tasks.forEach { it.get() }
            pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(24_000L, ProxyCounters.hotUp.sum())
            assertEquals(48_000L, ProxyCounters.hotDown.sum())
            assertEquals(72_000L, ProxyCounters.usbUp.sum())
            assertEquals(96_000L, ProxyCounters.usbDown.sum())
            assertEquals(240_000L, ProxyCounters.forwardingCpuNs.sum())
        } finally { pool.shutdownNow() }
    }
    @Test fun namesFitLinuxCommAndExecutorKeepsOriginalBounds() {
        listOf(BatteryThreads.VPN, BatteryThreads.WORKER, BatteryThreads.ACCEPT, BatteryThreads.TIMER, BatteryThreads.IO, BatteryThreads.RECORDER)
            .forEach { assertTrue(it.length <= 15) }
        val pool = ProxyWorkers()
        try {
            assertEquals(0, pool.corePoolSize); assertEquals(512, pool.maximumPoolSize)
            assertEquals(BatteryThreads.WORKER, pool.submit<String> { Thread.currentThread().name }.get())
        } finally { pool.shutdownNow() }
    }
}
