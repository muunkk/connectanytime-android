package com.muunkk.connectanytime

import kotlin.math.abs
import kotlin.math.sqrt

enum class BatteryPath { Charge, Level, Mixed }
enum class BatteryReason { TooShort, ReadingsTooUneven }
enum class BatteryWattsLabel { Measured, About }
enum class BatterySharing { InUse, Idle, Off }
enum class BatteryDifference { None, More, Less }
enum class BatteryThermal { Normal, Warm, Hot, VeryHot }

data class BatterySituation(val screenOn: Boolean, val hotspot: BatteryHotspot, val sharing: BatterySharing, val uplink: Uplink?)
data class BatteryRate(
    val hours: Double = 0.0, val percentPerHour: Double? = null, val rangePerHour: Double? = null,
    val watts: Double? = null, val wattsLabel: BatteryWattsLabel? = null, val ready: Boolean = false,
    val reason: BatteryReason? = BatteryReason.TooShort, val proxyBytesPerHour: Double? = null,
    val mobileBytesPerHour: Double? = null, val awakeShare: Double? = null
) {
    val fullToEmptyHours: Double? get() = percentPerHour?.takeIf { it >= 1 }?.let { 100 / it }
}
data class BatterySituationRate(val situation: BatterySituation, val rate: BatteryRate, val highest: Boolean = false)
data class BatteryComparison(val uplink: Uplink?, val setup: ProxySetup, val inUse: BatteryRate, val baseline: BatteryRate,
    val difference: BatteryDifference?, val pointsPerHour: Double?)
data class BatteryCpu(val totalPercent: Double? = null, val vpnPercent: Double? = null, val forwardingPercent: Double? = null,
    val otherPercent: Double? = null)
data class BatteryTransportShares(val dtls: Double = 0.0, val tls: Double = 0.0, val unknown: Double = 0.0)

/** UI snapshot. Rates are percentage points per hour, CPU is percent of one core, shares are 0–1. */
data class BatteryReport(
    val rowCount: Int = 0, val countedMs: Long = 0, val subtitleCountedMs: Long = 0,
    val firstWallMinute: Long? = null, val lastWallMinute: Long? = null, val path: BatteryPath = BatteryPath.Level,
    val capacityMah: Double? = null, val heroUplink: Uplink? = null, val hero: BatteryRate? = null,
    val comparison: BatteryComparison? = null, val all: BatteryRate = BatteryRate(), val cpu: BatteryCpu = BatteryCpu(),
    val situations: List<BatterySituationRate> = emptyList(), val otherOrShort: BatteryRate = BatteryRate(),
    val transport: BatteryTransportShares = BatteryTransportShares(), val reconnects: Long = 0,
    val reconnectsPerHour: Double? = null, val warmestThermal: BatteryThermal? = null, val droppedCoverageMs: Long = 0,
    val anySituationReady: Boolean = false, val connectedMs: Long = 0,
    val compareInUse: BatteryRate? = null, val compareBaseline: BatteryRate? = null, val compareUplink: Uplink? = null,
    // Recorded time includes short windows. Comparison rates use only qualifying windows.
    val compareInUseProgressMs: Long = 0, val compareBaselineProgressMs: Long = 0
) {
    /** The rounded duration is suitable for the subtitle after ten counted minutes. */
    val subtitleReady: Boolean get() = countedMs >= 600_000
}

internal data class BatteryInterval(val first: BatteryRow, val last: BatteryRow, val situation: BatterySituation?,
    val points: Double, val error: Double?, val energyWh: Double?, val wattsLabel: BatteryWattsLabel?) {
    val ms get() = last.elapsedMs - first.elapsedMs
}
internal data class BatteryWindow(val intervals: List<BatteryInterval>) {
    val ms get() = intervals.sumOf { it.ms }
    val situation get() = intervals.first().situation
}

/** No interval crosses a missing baseline, charger transition, process restart or reboot. */
internal object BatteryMath {
    private fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let {
        (it[(it.size - 1) / 2] + it[it.size / 2]) / 2
    }
    fun capacity(rows: List<BatteryRow>): Double? = median(rows.mapNotNull { r ->
        val points = r.points ?: return@mapNotNull null
        r.chargeUah?.takeIf { it > 0 && points > 0 }?.let { it * 100 / points }
    })?.takeIf { it > 0 && it.isFinite() }
    fun path(rows: List<BatteryRow>): BatteryPath {
        val charges = rows.mapNotNull { it.chargeUah }
        val levels = rows.mapNotNull { it.points }
        var flat = false
        var anchor: BatteryRow? = null
        for (row in rows) {
            if (row.chargeUah == null || anchor?.chargeUah != row.chargeUah) anchor = row
            val points = anchor?.points
            if (points != null && row.points != null && points - row.points!! >= 3) flat = true
        }
        return if (charges.size >= rows.size * .9 && charges.distinct().size > 1 && !flat && capacity(rows) != null)
            BatteryPath.Charge else BatteryPath.Level
    }
    fun quantum(rows: List<BatteryRow>): Double? {
        fun steps(periodic: Boolean): List<Double> {
            val candidates = if (periodic) rows.filter { it.kind == BatteryKind.periodic } else rows
            return candidates.zipWithNext().mapNotNull { (a, b) ->
                if (a.boot != b.boot || a.rec != b.rec || a.pgen != b.pgen || a.plugged != 0 || b.plugged != 0 ||
                    a.status !in 3..5 || b.status !in 3..5 || a.kind == BatteryKind.gap || b.kind == BatteryKind.gap ||
                    b.elapsedMs <= a.elapsedMs || (periodic && b.elapsedMs - a.elapsedMs !in 50_000..70_000)) return@mapNotNull null
                val x = a.chargeUah ?: return@mapNotNull null
                val y = b.chargeUah ?: return@mapNotNull null
                abs((x - y).toDouble()).takeIf { it > 0 }
            }
        }
        return steps(true).takeIf { it.size >= 10 }?.let(::median)
            ?: steps(false).takeIf { it.size >= 10 }?.let(::median)
    }
    private fun flatEnergy(rows: List<BatteryRow>): Boolean {
        var start: BatteryRow? = null
        for (r in rows) {
            if (r.energyNwh == null || start?.energyNwh != r.energyNwh)
                start = r.takeIf { it.energyNwh != null && it.chargeUah != null }
            val a = start ?: continue
            if (r.energyNwh != null && r.elapsedMs - a.elapsedMs >= 1_800_000 &&
                r.chargeUah != null && a.chargeUah != null && r.chargeUah < a.chargeUah) return true
        }
        return false
    }
    private fun rowKey(r: BatteryRow) = listOf(r.interactive, r.hotspot, r.sharingOn, r.uplink)
    fun windows(rows: List<BatteryRow>): List<BatteryWindow> {
        val path = path(rows)
        val capacity = capacity(rows)
        val error = if (path == BatteryPath.Level) 2.0 else quantum(rows)?.let { 200 * it / checkNotNull(capacity) }
        val energyFlat = flatEnergy(rows)
        val result = mutableListOf<BatteryWindow>()
        var run = mutableListOf<BatteryInterval>()
        var inUse = false
        var lowMs = 0L
        fun close() { if (run.isNotEmpty()) result += BatteryWindow(run.toList()); run = mutableListOf() }
        for ((a, b) in rows.zipWithNext()) {
            val ms = b.elapsedMs - a.elapsedMs
            val amount = if (path == BatteryPath.Charge) {
                if (a.chargeUah != null && b.chargeUah != null) (a.chargeUah - b.chargeUah) * 100.0 / checkNotNull(capacity) else null
            } else if (a.points != null && b.points != null) a.points!! - b.points!! else null
            if (a.boot != b.boot || a.rec != b.rec || a.kind == BatteryKind.gap || b.kind == BatteryKind.gap ||
                a.plugged != 0 || b.plugged != 0 || a.status !in 3..5 || b.status !in 3..5 || a.pgen != b.pgen ||
                a.vpn == BatteryVpn.off || b.vpn == BatteryVpn.off || ms !in 1..7_200_000 || amount == null || amount < 0) {
                close(); inUse = false; lowMs = 0; continue
            }
            val bytes = (b.hotUp - a.hotUp) + (b.hotDown - a.hotDown)
            val perMinute = bytes * 60_000.0 / ms
            when {
                perMinute > 50_000 -> { inUse = true; lowMs = 0 }
                perMinute < 10_000 -> { lowMs += ms; if (lowMs >= 180_000) inUse = false }
                else -> lowMs = 0
            }
            val same = a.gen == b.gen && rowKey(a) == rowKey(b)
            val event = b.gen == a.gen + 1 && b.kind == BatteryKind.event
            val situation = if (same || event) BatterySituation(a.interactive, a.hotspot,
                if (!a.sharingOn) BatterySharing.Off else if (inUse) BatterySharing.InUse else BatterySharing.Idle, a.uplink) else null
            var energy: Double? = null
            var label: BatteryWattsLabel? = null
            if (path == BatteryPath.Charge) {
                if (!energyFlat && a.energyNwh != null && b.energyNwh != null && a.energyNwh >= b.energyNwh) {
                    energy = (a.energyNwh - b.energyNwh) / 1e9; label = BatteryWattsLabel.Measured
                } else if (a.voltageMv != null && b.voltageMv != null && a.chargeUah != null && b.chargeUah != null) {
                    energy = (a.chargeUah - b.chargeUah) * (a.voltageMv + b.voltageMv) / 2.0 / 1e9
                    label = BatteryWattsLabel.About
                }
            }
            val interval = BatteryInterval(a, b, situation, amount, error, energy, label)
            if (run.isNotEmpty() && run.last().situation != situation) close()
            run += interval
        }
        close()
        return result.toList()
    }
    fun rate(windows: List<BatteryWindow>): BatteryRate {
        val intervals = windows.flatMap { it.intervals }
        val hours = intervals.sumOf { it.ms } / 3_600_000.0
        if (hours == 0.0) return BatteryRate()
        val value = intervals.sumOf { it.points } / hours
        val range = if (windows.any { it.intervals.any { i -> i.error == null } }) null
            else sqrt(windows.sumOf { val error = it.intervals.first().error!!; error * error }) / hours
        val energy = intervals.filter { it.energyWh != null }
        fun delta(first: Long?, last: Long?) = if (first != null && last != null && last >= first) last - first else null
        val mobile = intervals.map { i -> delta(i.first.mobileRx, i.last.mobileRx)?.let { rx ->
            delta(i.first.mobileTx, i.last.mobileTx)?.let { rx + it } } }
        val ready = hours >= .5 && range != null && range <= value * .25
        return BatteryRate(hours, value, range,
            energy.takeIf { it.isNotEmpty() }?.let { it.sumOf { i -> i.energyWh!! } / (it.sumOf { i -> i.ms } / 3_600_000.0) },
            if (energy.isEmpty()) null else if (energy.all { it.wattsLabel == BatteryWattsLabel.Measured }) BatteryWattsLabel.Measured else BatteryWattsLabel.About,
            ready, if (ready) null else if (hours < .5) BatteryReason.TooShort else BatteryReason.ReadingsTooUneven,
            intervals.sumOf { (it.last.hotUp - it.first.hotUp + it.last.hotDown - it.first.hotDown).coerceAtLeast(0) } / hours,
            if (mobile.any { it == null }) null else mobile.sumOf { it!! } / hours,
            intervals.sumOf { (it.last.uptimeMs - it.first.uptimeMs).coerceAtLeast(0) }.toDouble() / intervals.sumOf { it.ms })
    }
    fun report(rows: Sequence<BatteryRow>, setup: ProxySetup): BatteryReport {
        val epochs = linkedMapOf<Pair<Long, Long>, MutableList<BatteryRow>>()
        var count = 0; var first: Long? = null; var last: Long? = null
        var dropped = 0L
        var previous: BatteryRow? = null
        rows.forEach { row ->
            count++; first = first?.let { minOf(it, row.wallMin) } ?: row.wallMin
            last = last?.let { maxOf(it, row.wallMin) } ?: row.wallMin
            previous?.let { a -> if (a.boot == row.boot && a.rec == row.rec && (row.kind == BatteryKind.gap || a.kind == BatteryKind.gap))
                dropped += (row.elapsedMs - a.elapsedMs).coerceAtLeast(0) }
            previous = row
            epochs.getOrPut(row.boot to row.rec) { mutableListOf() }.add(row)
        }
        val windows = epochs.values.flatMap(::windows)
        val intervals = windows.flatMap { it.intervals }
        val warmest = intervals.asSequence().flatMap { sequenceOf(it.first, it.last) }.mapNotNull { it.thermal }.maxOrNull()
        val all = rate(windows)
        val grouped = windows.filter { it.situation != null && it.ms >= 600_000 }.groupBy { it.situation!! }.mapValues { rate(it.value) }
        fun active(key: BatterySituation) = !key.screenOn && key.hotspot == BatteryHotspot.on && key.sharing == BatterySharing.InUse
        fun baseline(key: BatterySituation) = !key.screenOn && key.hotspot == BatteryHotspot.on &&
            key.sharing == (if (setup == ProxySetup.Automatic) BatterySharing.Off else BatterySharing.Idle)
        fun side(uplink: Uplink?, matches: (BatterySituation) -> Boolean) =
            grouped.entries.firstOrNull { matches(it.key) && it.key.uplink == uplink }?.value
        fun bothReady(uplink: Uplink?) = side(uplink, ::active)?.ready == true && side(uplink, ::baseline)?.ready == true
        fun pairedHours(uplink: Uplink?) = minOf(side(uplink, ::active)?.hours ?: 0.0, side(uplink, ::baseline)?.hours ?: 0.0)
        val uplinkOrder = compareBy<BatterySituation> { bothReady(it.uplink) }.thenBy { pairedHours(it.uplink) }
        val candidates = grouped.keys.filter(::active).filter { side(it.uplink, ::baseline) != null }
        val chosen = candidates.maxWithOrNull(uplinkOrder)
        val comparison = chosen?.let { key ->
            val a = grouped.getValue(key)
            val b = checkNotNull(side(key.uplink, ::baseline))
            val d = a.percentPerHour!! - b.percentPerHour!!
            val bothReady = a.ready && b.ready
            val separated = bothReady && abs(d) > a.rangePerHour!! + b.rangePerHour!!
            val visible = separated && abs(d) >= .05
            BatteryComparison(key.uplink, setup, a, b,
                if (!bothReady) null else if (!visible) BatteryDifference.None else if (d > 0) BatteryDifference.More else BatteryDifference.Less,
                if (bothReady) abs(d) else null)
        }
        val heroKey = chosen?.takeIf { grouped.getValue(it).ready }
            ?: grouped.keys.filter { active(it) && grouped.getValue(it).ready }.maxByOrNull { grouped.getValue(it).hours }
        val retained = windows.filter { it.situation != null }.groupBy { it.situation!! }
        // Keep one uplink for both progress sides, even when only short windows have been recorded.
        val progressKeys = retained.keys.filter(::active) + retained.keys.filter(::baseline)
        fun progressTime(uplink: Uplink?) = retained.entries
            .filter { it.key.uplink == uplink && (active(it.key) || baseline(it.key)) }
            .sumOf { entry -> entry.value.sumOf { it.ms } }
        val compareUplink = (chosen ?: progressKeys.maxWithOrNull(uplinkOrder.thenBy { progressTime(it.uplink) }))?.uplink
        val compareInUse = comparison?.inUse ?: side(compareUplink, ::active)
        val compareBaseline = comparison?.baseline ?: side(compareUplink, ::baseline)
        fun progress(matches: (BatterySituation) -> Boolean) = retained.entries
            .filter { matches(it.key) && it.key.uplink == compareUplink }.sumOf { entry -> entry.value.sumOf { it.ms } }
        val top = grouped.entries.sortedByDescending { it.value.hours }.take(3)
        val highest = top.filter { it.value.ready }.takeIf { it.size >= 2 }
            ?.maxByOrNull { it.value.percentPerHour!! }?.key
        val topKeys = top.map { it.key }.toSet()
        val other = windows.filter { it.situation !in topKeys || it.ms < 600_000 }
        val elapsed = intervals.sumOf { it.ms }
        fun cpuDelta(interval: BatteryInterval, value: (BatteryRow) -> Long?): Long? {
            val first = value(interval.first) ?: return null
            val last = value(interval.last) ?: return null
            return (last - first).takeIf { last >= first }
        }
        // A split needs one denominator for every component, including the process total.
        val commonCpu = intervals.filter {
            cpuDelta(it) { r -> r.cpuProcMs } != null && cpuDelta(it) { r -> r.cpuVpnMs } != null &&
                cpuDelta(it) { r -> r.cpuFwdMs } != null
        }
        fun cpu(samples: List<BatteryInterval>, value: (BatteryRow) -> Long?): Double? {
            var sampledMs = 0L
            var deltaMs = 0L
            for (interval in samples) {
                val delta = cpuDelta(interval, value) ?: continue
                deltaMs += delta
                sampledMs += interval.ms
            }
            return if (sampledMs == 0L) null else 100.0 * deltaMs / sampledMs
        }
        val proc = cpu(commonCpu.ifEmpty { intervals }) { it.cpuProcMs }
        val vpn = cpu(commonCpu) { it.cpuVpnMs }; val fwd = cpu(commonCpu) { it.cpuFwdMs }
        val connected = intervals.filter { it.first.vpn == BatteryVpn.connected && it.last.vpn == BatteryVpn.connected }
        val connectedMs = connected.sumOf { it.ms }
        fun share(transport: BatteryTransport) = if (connectedMs == 0L) 0.0 else connected.filter {
            val same = it.first.transportGen == it.last.transportGen && it.first.transport == it.last.transport
            (if (same) it.first.transport else BatteryTransport.unknown) == transport
        }.sumOf { it.ms }.toDouble() / connectedMs
        val reconnects = intervals.sumOf { (it.last.reconnects - it.first.reconnects).coerceAtLeast(0) }
        val paths = epochs.values.map(::path).distinct()
        return BatteryReport(count, elapsed, (elapsed + 300_000) / 600_000 * 600_000, first, last,
            paths.singleOrNull() ?: BatteryPath.Mixed, median(epochs.values.mapNotNull(::capacity))?.div(1000),
            heroKey?.uplink, heroKey?.let { grouped[it]?.takeIf { rate -> rate.ready } }, comparison, all,
            BatteryCpu(proc, vpn, fwd, if (proc != null && vpn != null && fwd != null) (proc - vpn - fwd).coerceAtLeast(0.0) else null),
            top.map { BatterySituationRate(it.key, it.value, it.key == highest) }.toList(), rate(other),
            BatteryTransportShares(share(BatteryTransport.dtls), share(BatteryTransport.tls), share(BatteryTransport.unknown)),
            reconnects, if (all.hours > 0) reconnects / all.hours else null,
            warmest?.let { BatteryThermal.entries[it.coerceIn(0, 3)] }, dropped, grouped.values.any { it.ready }, connectedMs,
            compareInUse, compareBaseline, compareUplink, progress(::active), progress(::baseline))
    }
}
