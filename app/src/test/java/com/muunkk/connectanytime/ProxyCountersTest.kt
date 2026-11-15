package com.muunkk.connectanytime

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35])
class ProxyCountersTest {
    @Before fun reset() { ProxyCounters.gen = 0; ProxyCounters.forwardingCpuNs.set(0) }
    @After fun stop() { ProxyCounters.gen = 0 }
    @Test fun disabledMeterReadsNeitherClock() {
        val fail = { error("clock read while disabled") }
        val meter = CpuMeter(fail, fail)
        meter.tick(Long.MAX_VALUE); meter.flush()
        ProxyCounters.before(); ProxyCounters.after()
    }
    @Test fun staleGenerationIsDiscardedAndDeltaIsMonotonic() {
        var cpu = 100L
        ProxyCounters.gen = 1
        val meter = CpuMeter({ cpu }, { 0 })
        cpu = 200; ProxyCounters.gen = 2; meter.flush()
        assertEquals(0L, ProxyCounters.forwardingCpuNs.get())
        cpu = 250; meter.flush()
        assertEquals(50L, ProxyCounters.forwardingCpuNs.get())
        cpu = 240; meter.flush()
        assertEquals(50L, ProxyCounters.forwardingCpuNs.get())
    }
    @Test fun twoThreadHandoffUsesTaskLocalCpuBaselines() {
        ProxyCounters.gen = 1
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                var cpu = 100L
                ProxyCounters.before(CpuMeter({ cpu }, { 0 }))
                ProxyCounters.traffic(TetherLink.Hotspot, true, 100, 1_000_000_000)
                cpu = 160; ProxyCounters.after()
            }
            val second = pool.submit {
                var cpu = 500L
                ProxyCounters.before(CpuMeter({ cpu }, { 0 }))
                ProxyCounters.traffic(TetherLink.Hotspot, false, 200, 1_000_000_000)
                cpu = 590; ProxyCounters.after()
            }
            first.get(); second.get()
            assertEquals(150L, ProxyCounters.forwardingCpuNs.get())
        } finally { pool.shutdownNow() }
    }
    @Test fun offToOnDiscardsUnmeasuredCpuAndTicksFlushOnlyEverySecond() {
        var cpu = 0L; var reads = 0
        val meter = CpuMeter({ reads++; cpu }, { 0 })
        ProxyCounters.gen = 3
        cpu = 100; meter.tick(1_000_000_000)
        cpu = 200; meter.tick(1_500_000_000)
        assertEquals(1, reads)
        meter.tick(2_000_000_000)
        assertEquals(100L, ProxyCounters.forwardingCpuNs.get())
        meter.flush(); assertEquals(100L, ProxyCounters.forwardingCpuNs.get())
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
