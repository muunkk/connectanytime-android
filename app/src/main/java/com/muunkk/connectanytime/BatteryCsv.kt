package com.muunkk.connectanytime

internal enum class BatteryKind { periodic, event, gap, start, stop }
enum class BatteryTransport { unknown, tls, dtls }
enum class BatteryHotspot { unknown, off, on }
internal enum class BatteryVpn { off, connecting, connected, reconnecting }

/** Nullable readings preserve unsupported values without mistaking them for zero. */
internal data class BatteryRow(
    val v: Int = 1, val boot: Long = 0, val rec: Long = 0, val seq: Long = 0,
    val gen: Long = 0, val pgen: Long = 0, val transportGen: Long = 0, val kind: BatteryKind = BatteryKind.periodic,
    val wallMin: Long = 0, val elapsedMs: Long = 0, val uptimeMs: Long = 0,
    val level: Int? = null, val scale: Int? = null, val status: Int? = null, val plugged: Int? = null,
    val chargeUah: Long? = null, val energyNwh: Long? = null, val currentNow: Long? = null, val currentAvg: Long? = null,
    val voltageMv: Int? = null, val tempDc: Int? = null, val interactive: Boolean = false, val doze: Boolean = false,
    val powerSave: Boolean = false, val thermal: Int? = null, val vpn: BatteryVpn = BatteryVpn.off,
    val transport: BatteryTransport = BatteryTransport.unknown, val hotspot: BatteryHotspot = BatteryHotspot.unknown,
    val sharingOn: Boolean = false, val hotUp: Long = 0, val hotDown: Long = 0, val usbUp: Long = 0, val usbDown: Long = 0,
    val mobileRx: Long? = null, val mobileTx: Long? = null, val uplink: Uplink? = null, val reconnects: Long = 0,
    val cpuProcMs: Long? = null, val cpuVpnMs: Long? = null, val cpuFwdMs: Long? = null,
    val samplerCpuUs: Long = 0, val ioCpuUs: Long = 0
) {
    val points get() = if (level != null && scale != null && scale > 0) level * 100.0 / scale else null
    val id get() = rec to seq
}

/** Version one contains only scalar measurements and fixed enums, so no CSV quoting is needed. */
internal object BatteryCsv {
    const val COLUMNS = "v,boot,rec,seq,gen,pgen,transport_gen,kind,wall_min,elapsed_ms,uptime_ms,level,scale,status,plugged,charge_uAh,energy_nWh,current_now_raw,current_avg_raw,voltage_mV,temp_dC,interactive,doze,power_save,thermal,vpn,transport,hotspot,sharing_on,hot_up,hot_down,usb_up,usb_down,mobile_rx,mobile_tx,uplink,reconnects,cpu_proc_ms,cpu_vpn_ms,cpu_fwd_ms,sampler_cpu_us,io_cpu_us"
    fun supported(value: Long?, zeroUnsupported: Boolean = false): Long? = value?.takeUnless {
        it == Long.MIN_VALUE || it == Int.MIN_VALUE.toLong() || (zeroUnsupported && it == 0L)
    }
    fun write(r: BatteryRow): String = with(r) {
        listOf(v, boot, rec, seq, gen, pgen, transportGen, kind, wallMin, elapsedMs, uptimeMs, level, scale, status, plugged,
            supported(chargeUah, true), supported(energyNwh, true), supported(currentNow), supported(currentAvg),
            voltageMv, tempDc, interactive, doze, powerSave, thermal, vpn, transport, hotspot, sharingOn,
            hotUp, hotDown, usbUp, usbDown, mobileRx?.takeUnless { it == -1L }, mobileTx?.takeUnless { it == -1L }, uplink, reconnects,
            cpuProcMs, cpuVpnMs, cpuFwdMs, samplerCpuUs, ioCpuUs).joinToString(",") {
            if (it == null || it == Int.MIN_VALUE || it == Long.MIN_VALUE) "" else it.toString()
        }
    }
    fun parse(line: String): BatteryRow? {
        if (line.startsWith('#') || line == COLUMNS) return null
        val p = line.split(',')
        if (p.size != 42) return null
        return runCatching {
            fun n(i: Int) = p[i].toLongOrNull()?.let { supported(it) }
            fun b(i: Int) = p[i].toBooleanStrict()
            BatteryRow(p[0].toInt().also { require(it == 1) }, p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(),
                p[5].toLong(), p[6].toLong(), BatteryKind.valueOf(p[7]), p[8].toLong(), p[9].toLong(), p[10].toLong(),
                n(11)?.toInt(), n(12)?.toInt(), n(13)?.toInt(), n(14)?.toInt(), supported(n(15), true), supported(n(16), true),
                n(17), n(18), n(19)?.toInt(), n(20)?.toInt(), b(21), b(22), b(23), n(24)?.toInt(),
                BatteryVpn.valueOf(p[25]), BatteryTransport.valueOf(p[26]), BatteryHotspot.valueOf(p[27]), b(28), p[29].toLong(),
                p[30].toLong(), p[31].toLong(), p[32].toLong(), n(33)?.takeUnless { it == -1L }, n(34)?.takeUnless { it == -1L }, p[35].takeIf(String::isNotEmpty)?.let(Uplink::valueOf),
                p[36].toLong(), n(37), n(38), n(39), p[40].toLong(), p[41].toLong())
        }.getOrNull()
    }
}
