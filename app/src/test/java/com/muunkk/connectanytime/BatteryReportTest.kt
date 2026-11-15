package com.muunkk.connectanytime

import org.junit.Assert.*
import org.junit.Test

internal fun batteryTrace(minutes: Int = 60, loss: Long = 100_000, rec: Long = 1,
    sharing: Boolean = true, uplink: Uplink? = Uplink.MobileData, inUse: Boolean = true): List<BatteryRow> = (0..minutes).map { m ->
    val charge = 5_000_000 - loss * m / minutes
    BatteryRow(rec = rec, seq = m.toLong(), wallMin = 1000L + m, elapsedMs = m * 60_000L, uptimeMs = m * 60_000L,
        level = charge.toInt(), scale = 5_000_000, status = 3, plugged = 0, chargeUah = charge,
        voltageMv = 4000, energyNwh = 20_000_000_000L - (5_000_000 - charge) * 4000,
        vpn = BatteryVpn.connected, hotspot = BatteryHotspot.on, sharingOn = sharing, uplink = uplink,
        hotDown = if (inUse) m * 100_000L else 0, mobileRx = m * 200_000L, mobileTx = m * 10_000L,
        cpuProcMs = m * 6000L, cpuVpnMs = m * 2000L, cpuFwdMs = m * 3000L)
}

class BatteryReportTest {
    private fun report(rows: List<BatteryRow>, setup: ProxySetup = ProxySetup.Automatic) = BatteryMath.report(rows.asSequence(), setup)
    @Test fun chargePathHasExactTwoPercentPerHourAndMeasuredWatts() {
        val r = report(batteryTrace())
        assertEquals(2.0, r.all.percentPerHour!!, 1e-10)
        assertEquals(5000.0, r.capacityMah!!, 1e-10)
        assertEquals(.4, r.all.watts!!, 1e-10)
        assertEquals(BatteryWattsLabel.Measured, r.all.wattsLabel)
        assertTrue(r.hero!!.ready)
        assertEquals(50.0, r.hero.fullToEmptyHours!!, 1e-10)
        assertEquals(10.0, r.cpu.totalPercent!!, 1e-10)
        assertEquals(1.0, r.all.awakeShare!!, 1e-10)
        assertEquals(3_600_000L, r.countedMs)
        assertEquals(1000L, r.firstWallMinute)
        assertEquals(1060L, r.lastWallMinute)
    }
    @Test fun rawFourPointTwoFiveAndMultiCoreCpuAreUnrounded() {
        val r = report(batteryTrace(loss = 212_500).map { it.copy(cpuProcMs = it.elapsedMs * 2, cpuVpnMs = it.elapsedMs * 3 / 2) })
        assertEquals(4.25, r.all.percentPerHour!!, 1e-10)
        assertEquals(200.0, r.cpu.totalPercent!!, 1e-10)
        assertEquals(150.0, r.cpu.vpnPercent!!, 1e-10)
        assertEquals(45.0, r.cpu.otherPercent!!, 1e-10)
    }
    @Test fun levelPathNeedsNoCapacityAndHasNoWatts() {
        val r = report(batteryTrace(loss = 1_000_000).map { it.copy(chargeUah = null, energyNwh = null) })
        assertEquals(BatteryPath.Level, r.path)
        assertEquals(20.0, r.all.percentPerHour!!, 1e-10)
        assertEquals(2.0, r.all.rangePerHour!!, 1e-10)
        assertNull(r.all.watts); assertNull(r.capacityMah); assertTrue(r.all.ready)
    }
    @Test fun flatChargeCounterFallsBackAndZeroLevelNeverProvidesCapacity() {
        val rows = batteryTrace(loss = 500_000).map { it.copy(chargeUah = 5_000_000) }
        assertEquals(BatteryPath.Level, report(rows).path)
        assertNull(BatteryMath.capacity(listOf(rows[0].copy(level = 0))))
        assertEquals(5_000_000.0, BatteryMath.capacity(listOf(rows[0], rows[0].copy(level = 0)))!!, 0.0)
    }
    @Test fun missingCounterThresholdSelectsLevel() {
        val rows = batteryTrace(loss = 1_000_000).mapIndexed { i, r -> if (i < 7) r.copy(chargeUah = null) else r }
        assertEquals(BatteryPath.Level, report(rows).path)
    }
    @Test fun invalidIntervalsExcludeBootRecPowerGapTimeAndCounterIncrease() {
        val a = batteryTrace().first()
        val b = batteryTrace().last()
        val bad = listOf(b.copy(boot = 1), b.copy(rec = 2), b.copy(pgen = 2), b.copy(kind = BatteryKind.gap),
            b.copy(plugged = 1), b.copy(status = 2), b.copy(elapsedMs = 0), b.copy(elapsedMs = 7_200_001),
            b.copy(chargeUah = 5_100_000), b.copy(vpn = BatteryVpn.off))
        bad.forEach { assertEquals(it.toString(), 0, report(listOf(a, it)).countedMs) }
        assertEquals(0, report(listOf(a.copy(kind = BatteryKind.gap), b)).countedMs)
    }
    @Test fun unpluggedNotChargingAndFullStatusesCountWhileChargingAndUnknownDoNot() {
        for (status in listOf(3, 4, 5)) {
            val rows = batteryTrace().map { it.copy(status = status) }
            val r = report(rows)
            assertEquals("Unplugged status $status", 3_600_000L, r.countedMs)
            assertTrue(r.all.ready)
            assertEquals(0L, report(rows.map { it.copy(plugged = 1) }).countedMs)
        }
        for (status in listOf(null, 1, 2)) {
            assertEquals(0L, report(batteryTrace().map { it.copy(status = status) }).countedMs)
        }
        val transitions = batteryTrace().mapIndexed { index, row -> row.copy(status = 3 + index % 3) }
        assertEquals(3_600_000L, report(transitions).countedMs)
    }
    @Test fun plugFlickerWithNetDecreaseAndConflatedVpnFlipsCannotLookStable() {
        val rows = batteryTrace().take(2)
        assertTrue(BatteryMath.windows(listOf(rows[0], rows[1].copy(pgen = 2))).isEmpty())
        val mixed = BatteryMath.windows(listOf(rows[0], rows[1].copy(gen = 2)))
        assertNull(mixed.single().situation)
        assertEquals(60_000L, mixed.single().ms)
    }
    @Test fun aToBToAIsMixedButOneTransitionEventUsesOldKey() {
        val rows = batteryTrace().take(2)
        assertNull(BatteryMath.windows(listOf(rows[0], rows[1].copy(gen = 2))).single().situation)
        val event = BatteryMath.windows(listOf(rows[0], rows[1].copy(gen = 1, interactive = true, kind = BatteryKind.event)))
        assertFalse(event.single().situation!!.screenOn)
        assertNull(BatteryMath.windows(listOf(rows[0], rows[1].copy(interactive = true))).single().situation)
    }
    @Test fun hysteresisUsesElapsedTimeWithIrregularSampling() {
        val a = batteryTrace().first()
        fun at(ms: Long, bytes: Long) = a.copy(elapsedMs = ms, uptimeMs = ms, hotDown = bytes, chargeUah = 5_000_000 - ms / 100)
        val windows = BatteryMath.windows(listOf(at(0, 0), at(60_000, 100_000), at(130_000, 100_000), at(230_000, 100_000), at(250_000, 100_000)))
        assertEquals(BatterySharing.InUse, windows[0].situation!!.sharing)
        assertEquals(230_000L, windows[0].ms)
        assertEquals(BatterySharing.Idle, windows[1].situation!!.sharing)
    }
    @Test fun shortWindowsAndMixedGoToOtherAndTopThreeAreByTime() {
        val rows = batteryTrace(8)
        val r = report(rows)
        assertTrue(r.situations.isEmpty()); assertEquals(8 / 60.0, r.otherOrShort.hours, 1e-10)
        val all = (1L..5L).flatMap { rec -> batteryTrace((rec * 20).toInt(), rec = rec).map { it.copy(interactive = rec % 2 == 0L, uplink = Uplink.entries[(rec % 4).toInt()]) } }
        assertEquals(3, report(all).situations.size)
        assertTrue(report(all).otherOrShort.hours > 0)
    }
    @Test fun quantumUnavailableForSingleStepAndShortReasonTakesPriority() {
        val rows = batteryTrace()
        val r = report(listOf(rows.first(), rows.last()))
        assertNull(r.all.rangePerHour); assertEquals(BatteryReason.ReadingsTooUneven, r.all.reason)
        assertEquals(BatteryReason.TooShort, report(rows.take(10)).all.reason)
    }
    @Test fun sleepHeavyElapsedDenominatorAndAwakeShare() {
        val r = report(batteryTrace().map { it.copy(uptimeMs = it.elapsedMs / 10) })
        assertEquals(.1, r.all.awakeShare!!, 1e-10)
        assertEquals(2.0, r.all.percentPerHour!!, 1e-10)
    }
    @Test fun rootSumSquareRangeForOneVersusFourWindows() {
        val rows = batteryTrace()
        val one = report(rows)
        val four = report(rows.mapIndexed { i, r -> r.copy(interactive = (i / 15) % 2 == 1, gen = (i / 15).toLong(), kind = if (i % 15 == 0 && i > 0) BatteryKind.event else BatteryKind.periodic) })
        assertEquals(one.all.percentPerHour!!, four.all.percentPerHour!!, 1e-10)
        assertEquals(one.all.rangePerHour!! * 2, four.all.rangePerHour!!, 1e-10)
    }
    @Test fun periodicQuantumIgnoresEventHeavyPrefixAndUsesFallbackWhenNeeded() {
        val stable = batteryTrace()
        val noisy = (1..100).map { i -> stable.first().copy(elapsedMs = i.toLong(), chargeUah = 6_000_000L - i) }
        assertEquals(BatteryMath.quantum(stable)!!, BatteryMath.quantum(noisy + stable)!!, 0.0)
        val events = stable.map { it.copy(kind = BatteryKind.event) }
        assertEquals(BatteryMath.quantum(stable)!!, BatteryMath.quantum(events)!!, 0.0)
    }
    @Test fun wattsFallbackHandlesIncreasingMissingFlatAndMixedEnergy() {
        val rows = batteryTrace()
        val increasing = report(rows.map { it.copy(energyNwh = 1_000_000 + it.elapsedMs) })
        val missing = report(rows.map { it.copy(energyNwh = null) })
        val flat = report(rows.map { it.copy(energyNwh = 20_000_000_000) })
        val mixed = report(rows.mapIndexed { i, r -> if (i > 30) r.copy(energyNwh = null) else r })
        listOf(increasing, missing, flat, mixed).forEach {
            assertEquals(.4, it.all.watts!!, 1e-10); assertEquals(BatteryWattsLabel.About, it.all.wattsLabel)
        }
    }
    @Test fun flatEnergyAnchorsOnFirstAvailableChargeReading() {
        val rows = batteryTrace(loss = 120_000).mapIndexed { index, row ->
            row.copy(energyNwh = 20_000_000_000, chargeUah = if (index == 0) null else row.chargeUah)
        }
        val r = report(rows)
        assertEquals(BatteryPath.Charge, r.path)
        assertEquals(.48, r.all.watts!!, 1e-10)
        assertEquals(BatteryWattsLabel.About, r.all.wattsLabel)
    }
    @Test fun wattsTimeWeightedAcrossIrregularIntervalsAndMissingVoltage() {
        val rows = batteryTrace().filterIndexed { i, _ -> i % 7 == 0 || i == 60 }
        assertEquals(.4, report(rows).all.watts!!, 1e-10)
        assertNull(report(rows.map { it.copy(energyNwh = null, voltageMv = null) }).all.watts)
    }
    private fun comparison(aLoss: Long, bLoss: Long, setup: ProxySetup = ProxySetup.Automatic) = report(
        batteryTrace(loss = aLoss) + batteryTrace(loss = bLoss, rec = 2, sharing = setup == ProxySetup.Manual, inUse = false), setup).comparison!!
    @Test fun comparisonMoreLessOverlapAndRoundedZero() {
        assertEquals(BatteryDifference.More, comparison(400_000, 100_000).difference)
        assertEquals(BatteryDifference.Less, comparison(100_000, 400_000).difference)
        assertEquals(BatteryDifference.None, comparison(100_000, 100_000).difference)
        assertEquals(6.0, comparison(100_000, 400_000).pointsPerHour!!, 1e-10)
        assertEquals(BatteryDifference.More, comparison(400_000, 100_000, ProxySetup.Manual).difference)
    }
    @Test fun smallSeparatedDifferenceThatRoundsToZeroShowsNone() {
        fun dense(rows: List<BatteryRow>) = rows.map { it.copy(elapsedMs = it.elapsedMs / 10, uptimeMs = it.uptimeMs / 10) }
        val r = report(dense(batteryTrace(600, 102_000)) + dense(batteryTrace(600, 100_000, rec = 2, sharing = false, inUse = false)))
        assertTrue(r.comparison!!.inUse.ready); assertTrue(r.comparison.baseline.ready)
        assertEquals(.04, r.comparison.pointsPerHour!!, 1e-10)
        assertEquals(BatteryDifference.None, r.comparison.difference)
    }
    @Test fun comparisonSelectsUplinkWithMostHoursOnBothSidesAndHeroMatches() {
        val rows = batteryTrace() + batteryTrace(rec = 2, sharing = false, inUse = false) +
            batteryTrace(120, 400_000, rec = 3, uplink = Uplink.WiFi) + batteryTrace(120, 100_000, rec = 4, sharing = false, uplink = Uplink.WiFi, inUse = false)
        val r = report(rows)
        assertEquals(Uplink.WiFi, r.comparison!!.uplink); assertEquals(Uplink.WiFi, r.heroUplink)
        assertEquals(BatteryDifference.More, r.comparison.difference)
        assertNull(report(batteryTrace(10) + batteryTrace(10, rec = 2, sharing = false, inUse = false)).comparison?.difference)
    }
    @Test fun shortActiveEpochCannotChangeReadyComparisonSides() {
        val ready = batteryTrace() + batteryTrace(rec = 2, sharing = false, inUse = false)
        val r = report(ready + batteryTrace(5, loss = 500_000, rec = 3))
        assertTrue(r.comparison!!.inUse.ready)
        assertTrue(r.comparison.baseline.ready)
        assertEquals(r.comparison.inUse, r.compareInUse)
        assertEquals(r.comparison.baseline, r.compareBaseline)
        assertEquals(r.comparison.uplink, r.compareUplink)
        assertEquals(r.comparison.inUse, r.hero)
        assertEquals(3_900_000L, r.compareInUseProgressMs)
        assertEquals(3_600_000L, r.compareBaselineProgressMs)
    }
    @Test fun readyComparisonPairWinsOverLongerUnevenRecordings() {
        val r = report(batteryTrace() + batteryTrace(rec = 2, sharing = false, inUse = false) +
            batteryTrace(120, loss = 0, rec = 3, uplink = Uplink.WiFi) +
            batteryTrace(120, loss = 0, rec = 4, uplink = Uplink.WiFi, sharing = false, inUse = false))
        assertEquals(Uplink.MobileData, r.comparison!!.uplink)
        assertTrue(r.comparison.inUse.ready)
        assertTrue(r.comparison.baseline.ready)
        assertEquals(Uplink.MobileData, r.compareUplink)
        assertEquals(Uplink.MobileData, r.heroUplink)
    }
    @Test fun comparisonProgressRetainsBothUnreadySidesOutsideTheTopThree() {
        val dominant = (1L..3L).flatMap { rec ->
            batteryTrace(120, rec = rec, uplink = Uplink.entries[(rec - 1).toInt()]).map { it.copy(interactive = true) }
        }
        val r = report(dominant + batteryTrace(20, rec = 4) +
            batteryTrace(15, rec = 5, sharing = false, inUse = false))
        assertEquals(3, r.situations.size)
        assertTrue(r.situations.all { it.situation.screenOn })
        assertEquals(Uplink.MobileData, r.compareUplink)
        assertEquals(20 / 60.0, r.compareInUse!!.hours, 1e-10)
        assertEquals(15 / 60.0, r.compareBaseline!!.hours, 1e-10)
        assertFalse(r.compareInUse.ready); assertFalse(r.compareBaseline.ready)
        assertNull(r.hero)
    }
    @Test fun shortComparisonProgressNeverPairsDifferentUplinks() {
        val r = report(batteryTrace(8) + batteryTrace(rec = 2, uplink = Uplink.WiFi, sharing = false, inUse = false))
        assertEquals(Uplink.WiFi, r.compareUplink)
        assertNull(r.compareInUse)
        assertEquals(0L, r.compareInUseProgressMs)
        assertEquals(3_600_000L, r.compareBaselineProgressMs)
        assertEquals(1.0, r.compareBaseline!!.hours, 0.0)
        assertNull(r.comparison)
        val baselineOnly = report(batteryTrace(5, sharing = false, inUse = false))
        assertNull(baselineOnly.compareInUse)
        assertNull(baselineOnly.compareBaseline)
        assertEquals(0L, baselineOnly.compareInUseProgressMs)
        assertEquals(300_000L, baselineOnly.compareBaselineProgressMs)
        assertEquals(Uplink.MobileData, baselineOnly.compareUplink)
        assertNull(BatteryReport().compareInUse); assertNull(BatteryReport().compareBaseline)
        assertEquals(0L, BatteryReport().compareInUseProgressMs)
        assertEquals(0L, BatteryReport().compareBaselineProgressMs)
    }
    @Test fun comparisonSidesUseSetupSpecificBaselineAndTheComparisonUplink() {
        for (setup in ProxySetup.entries) {
            val r = report(batteryTrace(20, rec = 1, uplink = Uplink.WiFi) +
                batteryTrace(rec = 2, uplink = Uplink.WiFi, sharing = setup == ProxySetup.Manual, inUse = false) +
                batteryTrace(rec = 3) +
                batteryTrace(5, rec = 4, sharing = setup == ProxySetup.Manual, inUse = false), setup)
            assertEquals(Uplink.WiFi, r.comparison!!.uplink)
            assertEquals(Uplink.MobileData, r.heroUplink)
            assertTrue(r.hero!!.ready)
            assertEquals(1.0, r.hero.hours, 0.0)
            assertEquals(r.comparison.uplink, r.compareUplink)
            assertEquals(r.comparison.inUse, r.compareInUse)
            assertEquals(r.comparison.baseline, r.compareBaseline)
            assertEquals(20 / 60.0, r.compareInUse!!.hours, 1e-10)
            assertEquals(1.0, r.compareBaseline!!.hours, 1e-10)
            assertEquals(1_200_000L, r.compareInUseProgressMs)
            assertEquals(3_600_000L, r.compareBaselineProgressMs)
        }
        val unknown = report(batteryTrace(uplink = null) +
            batteryTrace(rec = 2, sharing = false, inUse = false))
        assertNotNull(unknown.hero)
        assertNull(unknown.compareUplink)
        assertEquals(1.0, unknown.compareInUse!!.hours, 0.0)
        assertNull(unknown.compareBaseline)
    }
    @Test fun heroUsesReadyInUseFallbackWhenComparisonActiveSideIsUnready() {
        val r = report(batteryTrace(20, rec = 1, uplink = Uplink.WiFi) +
            batteryTrace(rec = 2, uplink = Uplink.WiFi, sharing = false, inUse = false) +
            batteryTrace(rec = 3))
        assertEquals(Uplink.WiFi, r.comparison!!.uplink)
        assertFalse(r.comparison.inUse.ready)
        assertEquals(Uplink.MobileData, r.heroUplink)
        assertTrue(r.hero!!.ready)
    }
    @Test fun progressRatesExcludeShortWindowsAndKeepTheirDurationsSeparate() {
        val r = report(batteryTrace(rec = 1) + batteryTrace(5, loss = 500_000, rec = 2) +
            batteryTrace(5, rec = 3, sharing = false, inUse = false))
        assertNull(r.comparison)
        assertEquals(Uplink.MobileData, r.compareUplink)
        assertEquals(1.0, r.compareInUse!!.hours, 0.0)
        assertTrue(r.compareInUse.ready)
        assertEquals(r.hero, r.compareInUse)
        assertNull(r.compareBaseline)
        assertEquals(3_900_000L, r.compareInUseProgressMs)
        assertEquals(300_000L, r.compareBaselineProgressMs)
    }
    @Test fun nullUplinkComparisonKeepsBothSidesAndProgressTogether() {
        val r = report(batteryTrace(uplink = null) + batteryTrace(rec = 2, uplink = null, sharing = false, inUse = false))
        assertNotNull(r.comparison)
        assertNull(r.comparison!!.uplink)
        assertNull(r.compareUplink)
        assertEquals(r.comparison.inUse, r.compareInUse)
        assertEquals(r.comparison.baseline, r.compareBaseline)
        assertEquals(r.comparison.inUse, r.hero)
        assertEquals(3_600_000L, r.compareInUseProgressMs)
        assertEquals(3_600_000L, r.compareBaselineProgressMs)
    }
    @Test fun heroWithoutComparisonFallsBackToMostReadyHours() {
        val r = report(batteryTrace() + batteryTrace(120, rec = 2, uplink = Uplink.WiFi))
        assertNull(r.comparison)
        assertEquals(Uplink.WiFi, r.heroUplink)
        assertEquals(2.0, r.hero!!.hours, 0.0)
    }
    @Test fun unreadyComparisonFallsBackToReadyInUseWithMostHours() {
        val r = report(batteryTrace(20) + batteryTrace(rec = 2, sharing = false, inUse = false) +
            batteryTrace(rec = 3, uplink = Uplink.WiFi) + batteryTrace(120, rec = 4, uplink = Uplink.Ethernet))
        assertEquals(Uplink.MobileData, r.comparison!!.uplink)
        assertFalse(r.comparison.inUse.ready)
        assertEquals(Uplink.Ethernet, r.heroUplink)
        assertTrue(r.hero!!.ready)
        assertEquals(2.0, r.hero.hours, 0.0)
    }
    @Test fun progressUplinkTiesUseRetainedTimeRegardlessOfInsertionOrder() {
        val shorter = batteryTrace(5)
        val longer = batteryTrace(8, rec = 2, uplink = Uplink.WiFi)
        for (rows in listOf(shorter + longer, longer + shorter)) {
            val r = report(rows)
            assertNull(r.comparison)
            assertEquals(Uplink.WiFi, r.compareUplink)
            assertEquals(480_000L, r.compareInUseProgressMs)
            assertEquals(0L, r.compareBaselineProgressMs)
        }
    }
    @Test fun cpuUsesOnlyIntervalsWithBothCountersAndTheirElapsedTime() {
        val rows = batteryTrace(10).filter { it.seq in listOf(0L, 1L, 3L, 6L, 10L) }.map {
            it.copy(cpuProcMs = when (it.seq) { 0L -> 0; 1L -> 6000; 3L -> null; 6L -> 90_000; else -> 210_000 },
                cpuVpnMs = if (it.seq == 0L) null else it.elapsedMs / 5,
                cpuFwdMs = it.elapsedMs / 20)
        }
        val cpu = report(rows).cpu
        assertEquals(42.0, cpu.totalPercent!!, 1e-10)
        assertEquals(20.0, cpu.vpnPercent!!, 1e-10)
        assertEquals(5.0, cpu.forwardingPercent!!, 1e-10)
        assertEquals(17.0, cpu.otherPercent!!, 1e-10)
        val missing = report(rows.map { it.copy(cpuProcMs = null) }).cpu
        assertNull(missing.totalPercent); assertNull(missing.otherPercent)
        assertEquals(20.0, missing.vpnPercent!!, 1e-10)
        val reset = report(rows.take(2).mapIndexed { index, row -> row.copy(cpuProcMs = 10L - index) }).cpu
        assertNull(reset.totalPercent)
    }
    @Test fun warmestThermalUsesOnlyValidIntervalEndpoints() {
        val valid = batteryTrace().map { it.copy(thermal = 1) }
        val excluded = listOf(
            batteryTrace(rec = 2).map { it.copy(thermal = 3, plugged = 1) },
            batteryTrace(rec = 3).map { it.copy(thermal = 3, vpn = BatteryVpn.off) },
            batteryTrace(rec = 4).map { it.copy(thermal = 3, kind = BatteryKind.gap) },
            listOf(batteryTrace(rec = 5).first().copy(thermal = 3)))
        for (rows in excluded) {
            assertEquals(BatteryThermal.Warm, report(valid + rows).warmestThermal)
            assertNull(report(rows).warmestThermal)
        }
        assertEquals(BatteryThermal.Hot, report(valid.mapIndexed { index, row ->
            row.copy(thermal = if (index == 0 || index == 60) 2 else 1)
        }).warmestThermal)
    }
    @Test fun otherCpuIsFlooredOnlyForDisplayAndLevelIncreaseIsExcluded() {
        val r = report(batteryTrace().map { it.copy(cpuProcMs = 0) })
        assertEquals(0.0, r.cpu.otherPercent!!, 0.0)
        assertTrue(r.cpu.vpnPercent!! > 0)
        val rows = batteryTrace().take(2).map { it.copy(chargeUah = null) }
        assertEquals(0L, report(listOf(rows[0], rows[1].copy(level = rows[0].level!! + 1))).countedMs)
    }
    @Test fun epochGaugeStepsAndCapacityAreIndependent() {
        val a = batteryTrace()
        val b = batteryTrace(rec = 2).map { it.copy(chargeUah = it.chargeUah!! * 2) }
        val single = report(a)
        val both = report(a + b)
        assertEquals(single.all.rangePerHour!! / kotlin.math.sqrt(2.0), both.all.rangePerHour!!, 1e-10)
    }
    @Test fun transportSharesAndReconnectRateAreTimeWeighted() {
        val r = report(batteryTrace().mapIndexed { i, r -> r.copy(transport = if (i <= 30) BatteryTransport.dtls else BatteryTransport.tls,
            transportGen = if (i <= 30) 0 else 1, reconnects = if (i == 60) 2 else 0) })
        assertEquals(.5, r.transport.dtls, 1e-10)
        assertEquals(3_600_000L, r.connectedMs)
        assertEquals(1.0, r.transport.dtls + r.transport.tls + r.transport.unknown, 1e-10)
        assertEquals(2L, r.reconnects); assertEquals(2.0, r.reconnectsPerHour!!, 0.0)
    }
    @Test fun connectedDurationExposesTheTransportShareDenominator() {
        assertEquals(0L, BatteryReport().connectedMs)
        val noConnection = report(batteryTrace().map { it.copy(vpn = BatteryVpn.connecting) })
        assertEquals(3_600_000L, noConnection.countedMs)
        assertEquals(0L, noConnection.connectedMs)
        assertEquals(BatteryTransportShares(), noConnection.transport)
        val partial = report(batteryTrace().mapIndexed { index, row -> row.copy(
            vpn = if (index <= 30) BatteryVpn.connected else BatteryVpn.connecting,
            transport = BatteryTransport.dtls) })
        assertEquals(3_600_000L, partial.countedMs)
        assertEquals(1_800_000L, partial.connectedMs)
        assertEquals(1.0, partial.transport.dtls, 0.0)
    }
    @Test fun countedSubtitleIsReadyOnlyAfterTenMeasuredMinutes() {
        assertFalse(BatteryReport().subtitleReady)
        listOf(1, 4, 5, 9).forEach { minutes ->
            val r = report(batteryTrace(minutes))
            assertEquals(minutes * 60_000L, r.countedMs)
            assertFalse("Subtitle was ready at $minutes minutes", r.subtitleReady)
        }
        val tenMinutes = report(batteryTrace(10))
        assertTrue(tenMinutes.subtitleReady)
        assertEquals(600_000L, tenMinutes.subtitleCountedMs)
        assertEquals(1_200_000L, report(batteryTrace(15)).subtitleCountedMs)
        val onlyStoredTime = report(batteryTrace(10).map { it.copy(plugged = 1) })
        assertFalse(onlyStoredTime.subtitleReady)
    }
    @Test fun epochsAggregateTheirOwnCapacityAndGapReportsDroppedCoverage() {
        val r = report(batteryTrace() + batteryTrace(rec = 2).map { it.copy(chargeUah = it.chargeUah!! * 2) })
        assertEquals(2.0, r.all.percentPerHour!!, 1e-10)
        assertEquals(2.0, r.all.hours, 0.0)
        val gap = report(batteryTrace().mapIndexed { i, r -> if (i == 10) r.copy(kind = BatteryKind.gap) else r })
        assertEquals(120_000L, gap.droppedCoverageMs)
    }
}
