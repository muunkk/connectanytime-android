package com.muunkk.connectanytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class BatteryProcStat(val tid: Long, val name: String, val start: Long, val ticks: Long)
private val BATTERY_CPU_THREAD_NAMES = setOf(BatteryThreads.VPN, BatteryThreads.TIMER, BatteryThreads.ACCEPT)
internal fun batteryProcStat(line: String): BatteryProcStat? = runCatching {
    val open = line.indexOf('('); val close = line.lastIndexOf(')')
    require(open > 0 && close > open)
    val fields = line.substring(close + 1).trim().split(Regex("\\s+"))
    BatteryProcStat(line.substring(0, open).trim().toLong(), line.substring(open + 1, close), fields[19].toLong(),
        fields[11].toLong() + fields[12].toLong())
}.getOrNull()

/** Keep exited threads' last totals. TID reuse has a different start tick. */
internal class BatteryProc(private val root: File = File("/proc/self"),
    private val ticks: Long = Os.sysconf(OsConstants._SC_CLK_TCK),
    private val readText: (File) -> String = { it.readText() }) {
    private val seen = mutableMapOf<Pair<Long, Long>, BatteryProcStat>()
    private val names = mutableMapOf<Long, Pair<Long, String>>()
    private var samples = 0
    data class Cpu(val process: Long?, val vpn: Long, val forwarding: Long)
    fun read(): Cpu {
        fun read(file: File) = runCatching { batteryProcStat(readText(file)) }.getOrNull()
        // Worker CPU is metered separately. Periodically check for TID reuse without
        // reopening every worker stat on every event and minute sample.
        val revalidateWorkers = samples++ % 10 == 0
        val live = mutableSetOf<Long>()
        root.resolve("task").listFiles()?.forEach { dir ->
            val tid = dir.name.toLongOrNull() ?: return@forEach
            live += tid
            if (!revalidateWorkers && names[tid]?.second == BatteryThreads.WORKER) return@forEach
            val stat = read(dir.resolve("stat")) ?: return@forEach
            val cached = names[stat.tid]?.takeIf { it.first == stat.start } ?: run {
                val name = runCatching { readText(dir.resolve("comm")).trimEnd() }.getOrNull() ?: return@forEach
                (stat.start to name).also { names[stat.tid] = it }
            }
            if (cached.second !in BATTERY_CPU_THREAD_NAMES || stat.name !in BATTERY_CPU_THREAD_NAMES) return@forEach
            val key = stat.tid to stat.start
            if (stat.ticks >= (seen[key]?.ticks ?: 0)) seen[key] = stat
        }
        names.keys.retainAll(live)
        fun ms(t: Long) = if (ticks > 0) t * 1000 / ticks else 0
        return Cpu(read(root.resolve("stat"))?.let { ms(it.ticks) },
            ms(seen.values.filter { it.name == BatteryThreads.VPN }.sumOf { it.ticks }),
            ms(seen.values.filter { it.name != BatteryThreads.VPN }.sumOf { it.ticks }))
    }
}

/** Native log strings are fixed prefixes. No addresses or other log content reach the CSV. */
internal class BatteryTransportClassifier(private var reconnects: Long = 0) {
    @Volatile var transport = BatteryTransport.unknown
        private set
    val changes = AtomicLong()
    private var observations = 0L
    @Synchronized fun reset() { observations++; set(BatteryTransport.unknown) }
    @Synchronized fun connection(busy: Boolean, episodes: Long) {
        if (!busy || episodes != reconnects) reset()
        reconnects = episodes
    }
    private fun set(next: BatteryTransport) {
        if (next != transport) { transport = next; changes.incrementAndGet() }
    }
    @Synchronized fun observe(line: String) {
        classify(line)?.let { observations++; set(it) }
    }
    @Synchronized fun seedVersion() = observations
    fun seed(lines: List<String>, version: Long = seedVersion()) {
        var latest = BatteryTransport.unknown
        for (index in lines.indices.reversed()) {
            val line = lines[index]
            val raw = if (LOG_TIMESTAMP.matchesAt(line, 0)) line.substring(15) else line
            if (raw.startsWith(BuildConfig.BUILD_DESCRIPTION)) break
            val classified = classify(raw)
            if (classified != null) { latest = classified; break }
        }
        synchronized(this) {
            // A live classification after the snapshot always wins over its older tail.
            if (observations == version) set(latest)
        }
    }
    companion object {
        private val LOG_TIMESTAMP = Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} ")
        fun classify(line: String): BatteryTransport? = when {
            line.startsWith("CSTP connected") -> BatteryTransport.tls
            line.startsWith("Established DTLS connection") -> BatteryTransport.dtls
            line.startsWith("DTLS unavailable; using TLS only") || line.startsWith("DTLS Dead Peer Detection detected dead peer") ||
                line.startsWith("DTLS handshake failed") || line.startsWith("DTLS handshake timed out") ||
                line.startsWith("DTLS Rehandshake failed") || line.startsWith("DTLS session resume failed") -> BatteryTransport.tls
            line.startsWith("Reconnecting to the server") -> BatteryTransport.unknown
            else -> null
        }
    }
}

/** Injectable readings keep device quirks and clocks out of lifecycle and calculation tests. */
internal open class BatteryReadings(private val context: Context, private val proc: BatteryProc = BatteryProc()) {
    open fun wall() = System.currentTimeMillis()
    open fun elapsed() = SystemClock.elapsedRealtime()
    open fun uptime() = SystemClock.uptimeMillis()
    open fun cpu() = Debug.threadCpuTimeNanos()
    open fun read(): BatteryRow {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(BatteryManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        fun extra(name: String): Int? = sticky?.takeIf { it.hasExtra(name) }?.getIntExtra(name, Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }
        fun prop(id: Int) = BatteryCsv.supported(bm.getIntProperty(id).toLong(),
            zeroUnsupported = id != BatteryManager.BATTERY_PROPERTY_CURRENT_NOW && id != BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val cpu = proc.read()
        return BatteryRow(boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong(),
            wallMin = wall() / 60_000, elapsedMs = elapsed(), uptimeMs = uptime(),
            level = extra(BatteryManager.EXTRA_LEVEL), scale = extra(BatteryManager.EXTRA_SCALE),
            status = extra(BatteryManager.EXTRA_STATUS), plugged = extra(BatteryManager.EXTRA_PLUGGED),
            chargeUah = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            energyNwh = BatteryCsv.supported(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER), true),
            currentNow = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), currentAvg = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            voltageMv = extra(BatteryManager.EXTRA_VOLTAGE), tempDc = extra(BatteryManager.EXTRA_TEMPERATURE),
            interactive = pm.isInteractive, doze = pm.isDeviceIdleMode, powerSave = pm.isPowerSaveMode, thermal = pm.currentThermalStatus,
            hotUp = ProxyCounters.hotUp.sum(), hotDown = ProxyCounters.hotDown.sum(), usbUp = ProxyCounters.usbUp.sum(), usbDown = ProxyCounters.usbDown.sum(),
            mobileRx = TrafficStats.getMobileRxBytes().takeUnless { it == TrafficStats.UNSUPPORTED.toLong() },
            mobileTx = TrafficStats.getMobileTxBytes().takeUnless { it == TrafficStats.UNSUPPORTED.toLong() },
            cpuProcMs = cpu.process, cpuVpnMs = cpu.vpn, cpuFwdMs = cpu.forwarding + ProxyCounters.forwardingCpuNs.sum() / 1_000_000)
    }
}

internal class BatteryRecorder(private val app: ConnectApp, private val disk: BatteryStore,
    private val readings: BatteryReadings, private val expired: () -> Unit, private val charging: (Boolean) -> Unit,
    private val allocateRec: () -> Long = app.store::nextBatteryRec, private val failed: (Throwable) -> Unit = {}) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var scope: CoroutineScope? = null
    @Volatile private var rec = 0L
    private var seq = 0L
    private var gen = 0L
    private var pgen = 0L
    private var previous: BatteryRow? = null
    private var transitions = ConnectApp.vpnTransitions.get()
    private var transportChanges = 0L
    private val prefChanges = AtomicLong()
    private var seenPrefs = 0L
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shareOverHotspot" && running.get()) { prefChanges.incrementAndGet(); event() }
    }
    private val transport = BatteryTransportClassifier(ConnectApp.reconnectEpisodes.get())
    private fun updateTransport() = transport.connection(ConnectApp.state.value.busy, ConnectApp.reconnectEpisodes.get())
    private val observer: (String) -> Unit = { line ->
        synchronized(transport) { updateTransport(); transport.observe(line) }
    }
    private val pending = AtomicBoolean()
    private val running = AtomicBoolean()
    private var samplerNs = 0L
    private var summaryAt = 0L
    private val tetherLock = Any()
    private var heardBroadcast = false
    private var tethered: Set<String>? = null
    private var eventsRegistered = false
    private var tetherRegistered = false
    private val tether = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running.get()) return
            synchronized(tetherLock) { tethered = activeTethers(intent); heardBroadcast = true }
            gen++; event()
        }
    }
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running.get()) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> pgen++
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> gen++
            }
            event()
        }
    }
    private fun post(block: () -> Unit) {
        val token = rec
        handler?.post { if (running.get() && ProxyCounters.gen == token) runCatching(block) }
    }
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (readings.wall() >= app.store.batteryDeadline) { running.set(false); expired(); return }
        val worker = HandlerThread(BatteryThreads.RECORDER).apply { start() }
        thread = worker; handler = Handler(worker.looper)
        scope = CoroutineScope(SupervisorJob() + handler!!.asCoroutineDispatcher())
        handler!!.post {
            if (running.get()) runCatching { initialize() }.onFailure(failed)
        }
    }
    private fun initialize() {
        rec = allocateRec()
        synchronized(ProxyCounters) {
            if (!running.get()) return
            ProxyCounters.gen = rec
        }
        val (version, snapshot) = synchronized(app.log) {
            app.log.observer = observer
            transport.seedVersion() to app.log.state.value
        }
        transport.seed(snapshot, version)
        updateTransport()
        app.store.prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val filter = IntentFilter(TETHER_STATE_CHANGED)
        val sticky = runCatching {
            val result = if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(tether, filter, null, handler, Context.RECEIVER_EXPORTED)
                else app.registerReceiver(tether, filter, null, handler)
            tetherRegistered = true
            result
        }.getOrNull()
        synchronized(tetherLock) { if (!heardBroadcast) tethered = activeTethers(sticky) }
        val eventFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(events, eventFilter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(events, eventFilter, null, handler)
        eventsRegistered = true
        runCatching { sample(BatteryKind.start) }
        summaryAt = readings.elapsed()
        scope!!.launch {
            var lastUplink = ConnectApp.uplink.value
            combine(ConnectApp.state.map { it.status }, ConnectApp.uplink, batteryPrefs(app.store),
                app.log.state.map { synchronized(app.log) { transport.changes.get() } }) { status, uplink, prefs, changes ->
                Projection(status, uplink, prefs.sharing, changes)
            }.distinctUntilChanged().collect { projection ->
                if (!running.get()) return@collect
                if (lastUplink != projection.uplink) { gen++; lastUplink = projection.uplink }
                updateTransport()
                event()
            }
        }
    }

    private data class Projection(val status: String, val uplink: Uplink?, val sharing: Boolean, val transportChanges: Long)
    private fun event() {
        if (!running.get() || !pending.compareAndSet(false, true)) return
        post { pending.set(false); sample(BatteryKind.event) }
    }
    private var periodicScheduled = false
    private val periodic = Runnable {
        periodicScheduled = false
        if (running.get() && ProxyCounters.gen == rec) runCatching { sample(BatteryKind.periodic) }
    }
    private val expiry = Runnable {
        if (running.get() && ProxyCounters.gen == rec) runCatching {
            if (readings.wall() >= app.store.batteryDeadline) expired() else armExpiry()
        }
    }
    private fun armExpiry() {
        handler?.removeCallbacks(expiry)
        // Handler uses uptime, which pauses in deep sleep; re-arm from wall time on every sample.
        handler?.postAtTime(expiry, readings.uptime() + (app.store.batteryDeadline - readings.wall()).coerceAtLeast(0))
    }
    private fun sample(kind: BatteryKind) {
        var periodicEnabled = true // Retry unreadable battery state, including a failed unplug event.
        try {
            if (kind != BatteryKind.stop && readings.wall() >= app.store.batteryDeadline) { expired(); return }
            updateTransport()
            val raw = readings.read()
            periodicEnabled = raw.plugged == null || raw.plugged == 0
            val last = previous
            if (last != null && (last.status != raw.status || last.plugged != raw.plugged)) pgen++
            val vpnChanges = ConnectApp.vpnTransitions.get()
            gen += (vpnChanges - transitions).coerceAtLeast(0); transitions = vpnChanges
            val prefs = prefChanges.get()
            gen += (prefs - seenPrefs).coerceAtLeast(0); seenPrefs = prefs
            val changes = transport.changes.get()
            gen += (changes - transportChanges).coerceAtLeast(0); transportChanges = changes
            val state = ConnectApp.state.value
            val vpn = when {
                state.connected -> BatteryVpn.connected
                state.status == "Reconnecting…" -> BatteryVpn.reconnecting
                state.busy -> BatteryVpn.connecting
                else -> BatteryVpn.off
            }
            val hotspot = synchronized(tetherLock) { when {
                tethered == null -> BatteryHotspot.unknown
                tethered!!.any(::wifiTetherName) -> BatteryHotspot.on
                else -> BatteryHotspot.off
            } }
            samplerNs = maxOf(samplerNs, readings.cpu())
            val rowKind = if (kind == BatteryKind.periodic && !periodicEnabled) BatteryKind.event else kind
            val row = raw.copy(rec = rec, seq = ++seq, gen = gen, pgen = pgen, transportGen = changes, kind = rowKind,
                vpn = vpn, transport = transport.transport, hotspot = hotspot, sharingOn = app.store.shareOverHotspot,
                uplink = ConnectApp.uplink.value, reconnects = ConnectApp.reconnectEpisodes.get(), samplerCpuUs = samplerNs / 1000,
                ioCpuUs = disk.ioCpuUs.get())
            previous = row
            disk.add(row)
            charging(raw.plugged != null && raw.plugged != 0)
            if (kind != BatteryKind.stop && readings.elapsed() - summaryAt >= 6 * 3_600_000) {
                summaryAt = readings.elapsed()
                disk.summarize { report ->
                    val minutes = report.countedMs / 60_000
                    app.log.add(app.resources.getQuantityString(R.plurals.battery_log_summary, minutes.toInt(), minutes))
                }
            }
        } finally {
            val active = kind != BatteryKind.stop && running.get() && ProxyCounters.gen == rec
            if (!active || !periodicEnabled) {
                handler?.removeCallbacks(periodic)
                periodicScheduled = false
            } else if (!periodicScheduled) {
                // Event rows must not postpone an already scheduled periodic sample.
                periodicScheduled = handler?.postDelayed(periodic, 60_000) == true
            }
            if (active) {
                armExpiry()
            }
        }
    }
    fun stop(callback: (() -> Unit)? = null) {
        if (!running.compareAndSet(true, false)) { callback?.invoke(); return }
        synchronized(ProxyCounters) { if (ProxyCounters.gen == rec) ProxyCounters.gen = 0 }
        val h = handler ?: run { callback?.invoke(); return }
        val worker = thread
        h.removeCallbacksAndMessages(null)
        scope?.cancel()
        h.post {
            runCatching { if (rec != 0L) sample(BatteryKind.stop) }
            runCatching { if (app.log.observer === observer) app.log.observer = null }
            runCatching { app.store.prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
            runCatching { if (eventsRegistered) app.unregisterReceiver(events) }
            runCatching { if (tetherRegistered) app.unregisterReceiver(tether) }
            runCatching { disk.flush(callback) }
            worker?.quitSafely()
        }
    }
}
