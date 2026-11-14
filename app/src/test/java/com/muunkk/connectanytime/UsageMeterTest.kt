package com.muunkk.connectanytime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class UsageMeterTest {
    private val s = TimeUnit.SECONDS.toNanos(1)
    private val both = setOf(TetherLink.Usb, TetherLink.Hotspot)

    @Test fun countsDevicesConnectionsAndTrafficPerLink() {
        val meter = UsageMeter()
        meter.opened(TetherLink.Hotspot, "10.84.137.24", 0)
        meter.opened(TetherLink.Hotspot, "10.84.137.9", 0)
        meter.opened(TetherLink.Hotspot, "10.84.137.9", 0)
        meter.traffic(TetherLink.Hotspot, "10.84.137.9", up = false, bytes = 5000, now = 1 * s)
        meter.traffic(TetherLink.Hotspot, "10.84.137.9", up = true, bytes = 300, now = 1 * s)
        meter.opened(TetherLink.Usb, "192.168.42.10", 0)
        val now = meter.snapshot(2 * s, both)
        // Numeric order: .9 before .24.
        assertEquals(LinkUsage(listOf("10.84.137.9", "10.84.137.24"), 3, 5000, 300), now.hotspot)
        assertEquals(LinkUsage(listOf("192.168.42.10"), 1, 0, 0), now.usb)
    }

    @Test fun aDeviceStaysListedForAMinuteAfterItsLastConnection() {
        val meter = UsageMeter()
        meter.opened(TetherLink.Usb, "192.168.42.10", 0)
        meter.closed(TetherLink.Usb, "192.168.42.10", 10 * s)
        assertEquals(LinkUsage(listOf("192.168.42.10"), 0, 0, 0), meter.snapshot(60 * s, both).usb)
        assertEquals(emptyList<String>(), meter.snapshot(71 * s, both).usb.devices)
        // An open connection keeps it listed however quiet it is.
        meter.opened(TetherLink.Usb, "192.168.42.11", 0)
        assertEquals(listOf("192.168.42.11"), meter.snapshot(600 * s, both).usb.devices)
    }

    @Test fun aLinkThatStopsSharingStartsOver() {
        val meter = UsageMeter()
        meter.opened(TetherLink.Hotspot, "10.84.137.9", 0)
        meter.traffic(TetherLink.Hotspot, "10.84.137.9", up = false, bytes = 900, now = s)
        assertEquals(LinkUsage(), meter.snapshot(2 * s, setOf(TetherLink.Usb)).hotspot)
        // A session closing after the reset doesn't go negative or come back.
        meter.closed(TetherLink.Hotspot, "10.84.137.9", 3 * s)
        assertEquals(LinkUsage(), meter.snapshot(4 * s, both).hotspot)
    }
}
