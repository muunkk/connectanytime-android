package com.muunkk.connectanytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import androidx.annotation.RequiresApi

/** Whether Android's own hotspot is on, as the widget shows it. Unknown draws the neutral button. */
internal enum class AndroidHotspot { On, Off, Unknown }

/** `WifiManager.WIFI_AP_STATE_CHANGED_ACTION` and its extras (all `@SystemApi`, so by value).
 *  Sticky with no permission on Android 10–12; on later Wi-Fi module versions a plain broadcast that
 *  needs ACCESS_WIFI_STATE, so it is only heard while the app runs. */
internal const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
internal const val AP_DISABLING = 10
internal const val AP_DISABLED = 11
internal const val AP_ENABLING = 12
internal const val AP_ENABLED = 13
internal const val AP_FAILED = 14
/** `WifiManager.IFACE_IP_MODE_TETHERED` / `_LOCAL_ONLY`. */
internal const val AP_MODE_TETHERED = 1
internal const val AP_MODE_LOCAL_ONLY = 2
/** `UserManager.ACTION_USER_RESTRICTIONS_CHANGED` (`@SystemApi`). */
internal const val USER_RESTRICTIONS_CHANGED = "android.os.action.USER_RESTRICTIONS_CHANGED"

/** What Tethering lists (TETHER_STATE_CHANGED's tetherArray and erroredArray). null: not said. */
internal data class TetherReport(val tethered: List<String>?, val errored: List<String>?, val seq: Long)
/** Android 16's typed callback: whether any tethered interface is TETHERING_WIFI. */
internal data class TypedReport(val wifi: Boolean, val seq: Long)
/** The last tethered-hotspot AP state heard; [mode] null when Android didn't say which hotspot. */
internal data class ApReport(val state: Int, val mode: Int?, val seq: Long)

/** Everything known about the hotspot, each report stamped from one counter so "newer" is exact. */
internal data class HotspotEvidence(
    val tether: TetherReport? = null,
    val typed: TypedReport? = null,
    val ap: ApReport? = null,
    /** DISALLOW_CONFIG_TETHERING now: Android stops tethering without announcing it. */
    val restrictedNow: Boolean = false,
    /** When a restriction was last seen; tether reports from before it may describe tethering it stopped. */
    val restrictionSeq: Long? = null,
    /** The widget just opened Android's hotspot settings, and Android hasn't reported since. */
    val handoffPending: Boolean = false,
    /** When the tethered hotspot last went (or started going) off. Tethering reports from before
     *  it describe the previous run of the hotspot, even after [ap] moves on to ENABLING. */
    val apStopSeq: Long? = null,
)

/** Names Android tethers on that aren't its Wi-Fi hotspot: USB, Bluetooth, Ethernet, Wi-Fi Direct. */
private val OTHER_TETHER = Regex("(?:rndis|ncm|usb)[0-9]+|bt-pan[0-9]*|eth[0-9]+|p2p[0-9]+|p2p-.+")
private fun otherTetherName(name: String) = OTHER_TETHER.matches(name)

/**
 * Whether Android's hotspot is on. Pure, first match wins, and Unknown whenever the evidence
 * doesn't prove an answer:
 * - Tethering's lists prove **On** only. They list IP serving, not the radio: on Android 10 the
 *   station `wlan0` is tracked as an available Wi-Fi tether interface, and an AP that has just
 *   started isn't tethered yet, so an empty list never means Off.
 * - **Off** needs the tethered hotspot's own AP state (DISABLED or FAILED).
 */
internal fun androidHotspot(e: HotspotEvidence): AndroidHotspot {
    if (e.restrictedNow || e.handoffPending) return AndroidHotspot.Unknown
    val since = e.restrictionSeq ?: Long.MIN_VALUE
    val tether = e.tether?.takeIf { it.seq > since }
    val typed = e.typed?.takeIf { it.seq > since }
    // A Wi-Fi interface (or one we can't name) that failed to tether: the radio may well be on.
    if (tether?.errored.orEmpty().any { wifiTetherName(it) || !otherTetherName(it) }) return AndroidHotspot.Unknown
    val ap = e.ap
    // Stopping or starting: tethering reports can't be trusted either way until it settles.
    if (ap?.state == AP_DISABLING || ap?.state == AP_ENABLING) return AndroidHotspot.Unknown
    // On, but Android didn't say which hotspot: it may be a local-only one.
    if (ap != null && ap.mode == null && ap.state == AP_ENABLED) return AndroidHotspot.Unknown
    val apOff = ap != null && ap.mode == AP_MODE_TETHERED && (ap.state == AP_DISABLED || ap.state == AP_FAILED)
    // Android 16's callback, when it has answered, is the word on Wi-Fi tethering: it is asked
    // afresh and typed, where the broadcast is a cached list of names.
    val wifi: Pair<Long, Boolean?>? = typed?.let { it.seq to it.wifi } ?: tether?.let { it.seq to it.tethered?.any(::wifiTetherName) }
    // The radio going off is newer than the tethering report: a disabled AP serves nobody.
    if (apOff && ap!!.seq > (wifi?.first ?: Long.MIN_VALUE)) return AndroidHotspot.Off
    // A Wi-Fi tether heard after the radio said off, with no ENABLING/ENABLED between: one of
    // them is old (a late callback, a cached list), and which one can't be told.
    if (apOff && wifi?.second == true) return AndroidHotspot.Unknown
    // On only from a report made since the hotspot last stopped, so a stop and a quick restart
    // doesn't bring back the previous run's interfaces; and here the radio is ENABLED or unheard.
    if (wifi?.second == true && wifi.first > (e.apStopSeq ?: Long.MIN_VALUE)) return AndroidHotspot.On
    // Off earlier, and nothing since shows the hotspot tethered (turning it on again would have
    // replaced this AP report with ENABLING).
    if (apOff && wifi?.second == false) return AndroidHotspot.Off
    return AndroidHotspot.Unknown
}

/**
 * Follows Android's hotspot for the widget, for the life of the app's process. Nothing polls:
 * one receiver (and on Android 16 one tethering callback) that the system calls when tethering,
 * the hotspot or a tethering restriction changes. Only interface names and states are read;
 * no SSID, clients or addresses, and nothing is stored or logged.
 *
 * What it knows lives in memory only, on purpose: after the process restarts, Off isn't known
 * again until the hotspot is heard going off (Android 12L+ doesn't keep that broadcast), so the
 * widget shows its neutral button rather than an Off that may have changed meanwhile.
 */
internal class HotspotWatch(private val context: Context, private val publish: (AndroidHotspot) -> Unit,
    typedSource: TetherTypeSource? = if (Build.VERSION.SDK_INT >= 36) AndroidTetherTypes(context) else null) {
    private val lock = Any()
    private var seq = 0L
    private var evidence = HotspotEvidence()
    private var handoffSeq: Long? = null
    private var handoffAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val typed = typedSource?.let { Typed(it) }
    private val receiver = object : BroadcastReceiver() {
        // The sticky handed over at registration is cached, not news.
        override fun onReceive(context: Context, intent: Intent) = heard(intent, live = !isInitialStickyBroadcast)
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(TETHER_STATE_CHANGED); addAction(WIFI_AP_STATE_CHANGED); addAction(USER_RESTRICTIONS_CHANGED)
        }
        // Protected broadcasts only the system sends; the senders (Tethering, Wi-Fi modules) aren't
        // the system UID that NOT_EXPORTED admits, as in TetherProxy.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }
        refresh()
    }

    /** Reads what Android holds now: the sticky broadcasts, the tethering restriction and, on
     *  Android 16, the tethering callback (registered once; it stays live). Called at app start and
     *  before the widget draws. */
    fun refresh() {
        // Tether first, AP second: on Android 10–12, where both are sticky, the radio's state counts as the newer.
        readSticky(TETHER_STATE_CHANGED); readSticky(WIFI_AP_STATE_CHANGED)
        typed?.ensure()
        synchronized(lock) {
            readRestriction()
            if (handoffSeq != null && SystemClock.elapsedRealtime() - handoffAt >= HANDOFF_MS) handoffSeq = null
            recompute()
        }
    }

    /** The widget opened Android's hotspot settings: show neutral until Android reports again,
     *  and look again in [HANDOFF_MS] in case nothing changed there. */
    fun handedOff() {
        synchronized(lock) { handoffSeq = ++seq; handoffAt = SystemClock.elapsedRealtime(); recompute() }
        main.postDelayed({ refresh() }, HANDOFF_MS)
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
        typed?.unregister()
        main.removeCallbacksAndMessages(null)
    }

    private fun readSticky(action: String) {
        val before = synchronized(lock) { seq }
        // A null receiver only returns the sticky intent; nothing is registered.
        val sticky = runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(null, IntentFilter(action), Context.RECEIVER_EXPORTED)
            else context.registerReceiver(null, IntentFilter(action))
        }.getOrNull() ?: return
        heard(sticky, live = false, before)
    }

    /** [readAt]: for a sticky read, the counter before reading, so a broadcast heard meanwhile isn't overwritten. */
    private fun heard(intent: Intent, live: Boolean, readAt: Long? = null) = synchronized(lock) {
        when (intent.action) {
            TETHER_STATE_CHANGED -> {
                val tethered = intent.getStringArrayListExtra("tetherArray")?.toList()
                val errored = intent.getStringArrayListExtra("erroredArray")?.toList()
                val old = evidence.tether
                if (readAt != null && old != null && old.seq > readAt) return@synchronized
                // The same sticky read again is not news: keep its stamp, so a re-read can't make an
                // old report newer than a restriction or the AP going off.
                if (!live && old != null && old.tethered == tethered && old.errored == errored) return@synchronized recompute()
                val stamp = ++seq
                evidence = evidence.copy(tether = TetherReport(tethered, errored, stamp))
                if (live) clearHandoff(stamp)
            }
            WIFI_AP_STATE_CHANGED -> {
                val state = intent.getIntExtra("wifi_state", -1)
                if (state !in AP_DISABLING..AP_FAILED) return@synchronized
                val mode = when {
                    intent.hasExtra("android.net.wifi.extra.WIFI_AP_MODE") -> intent.getIntExtra("android.net.wifi.extra.WIFI_AP_MODE", -1)
                    intent.hasExtra("wifi_ap_mode") -> intent.getIntExtra("wifi_ap_mode", -1) // Android 10's key
                    else -> null
                }
                // A local-only hotspot (Quick Share, Android Auto) isn't the user's hotspot.
                if (mode == AP_MODE_LOCAL_ONLY) return@synchronized
                val old = evidence.ap
                if (readAt != null && old != null && old.seq > readAt) return@synchronized
                if (!live && old != null && old.state == state && old.mode == mode) return@synchronized recompute()
                val stamp = ++seq
                evidence = evidence.copy(ap = ApReport(state, mode, stamp),
                    apStopSeq = if (state == AP_DISABLING || state == AP_DISABLED || state == AP_FAILED) stamp else evidence.apStopSeq)
                if (live) clearHandoff(stamp)
            }
            // A restriction may have come and gone before this arrives (Android also merges these
            // broadcasts), so earlier tethering reports are treated as from before one either way.
            USER_RESTRICTIONS_CHANGED -> readRestriction(changed = live)
            else -> return@synchronized
        }
        recompute()
    }

    /** [askedAt]: the counter when this registration was made. A snapshot taken then but delivered
     *  after the hotspot stopped or tethering was restricted describes the time before, so it's dropped. */
    private fun typedReport(wifi: Boolean, snapshot: Boolean, askedAt: Long) = synchronized(lock) {
        if (snapshot && maxOf(evidence.apStopSeq ?: Long.MIN_VALUE, evidence.restrictionSeq ?: Long.MIN_VALUE) > askedAt) return@synchronized
        val stamp = ++seq
        evidence = evidence.copy(typed = TypedReport(wifi, stamp))
        if (!snapshot) clearHandoff(stamp)
        recompute()
    }

    private fun typedLost() = synchronized(lock) { evidence = evidence.copy(typed = null); recompute() }

    private fun clearHandoff(stamp: Long) { if ((handoffSeq ?: return) < stamp) handoffSeq = null }

    /** Under [lock]: DISALLOW_CONFIG_TETHERING, or (Android 13+) DISALLOW_WIFI_TETHERING; read when
     *  they may have changed (their broadcast, a refresh). */
    private fun readRestriction(changed: Boolean = false) {
        val restricted = runCatching {
            val users = context.getSystemService(UserManager::class.java) ?: return@runCatching false
            users.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING) ||
                (Build.VERSION.SDK_INT >= 33 && users.hasUserRestriction(UserManager.DISALLOW_WIFI_TETHERING))
        }.getOrDefault(true)
        evidence = evidence.copy(restrictedNow = restricted, restrictionSeq = if (restricted || changed) ++seq else evidence.restrictionSeq)
    }

    /** Under [lock]. */
    private fun recompute() {
        evidence = evidence.copy(handoffPending = handoffSeq != null)
        publish(androidHotspot(evidence))
    }

    /** Follows a [TetherTypeSource] (Android 16's tethering callback): one registration at a time,
     *  and nothing from a replaced or failed one gets through. */
    private inner class Typed(private val source: TetherTypeSource) {
        /** Identifies the live registration; null when none. */
        @Volatile private var current: Any? = null
        private var handle: Any? = null
        /** Registers unless already registered: a live callback follows changes by itself. */
        @Synchronized fun ensure() { if (current == null) register() }
        @Synchronized fun register() {
            unregister()
            val askedAt = synchronized(lock) { seq }
            val registration = Any()
            var first = true
            current = registration
            runCatching {
                handle = source.register { wifi ->
                    // Under the same monitor as register(), so a replacement (or its failure clearing
                    // the typed report) can't interleave with this one publishing. Lock order: this, then lock.
                    synchronized(this) {
                        if (current !== registration) return@synchronized // from a registration since replaced
                        typedReport(wifi, snapshot = first, askedAt)
                        first = false
                    }
                }
            }.onFailure { current = null; handle = null; typedLost() }
        }
        @Synchronized fun unregister() {
            current = null
            val old = handle ?: return
            handle = null
            runCatching { source.unregister(old) }
        }
    }

    companion object {
        const val HANDOFF_MS = 15_000L
    }
}

/** Where Android 16's typed tethering state comes from; a seam so the registration lifecycle can be tested. */
internal interface TetherTypeSource {
    /** Calls [onChange] with whether any tethered interface is Wi-Fi: first with a snapshot, then on
     *  each change. Returns a handle for [unregister]; throws if registration fails. */
    fun register(onChange: (wifi: Boolean) -> Unit): Any
    fun unregister(handle: Any)
}

/** Android 16's public `TetheringManager` callback: typed (no name guessing) and answered right
 *  after registering, so it knows On even before any broadcast since boot. */
@RequiresApi(36)
private class AndroidTetherTypes(private val context: Context) : TetherTypeSource {
    override fun register(onChange: (wifi: Boolean) -> Unit): Any {
        val manager = checkNotNull(context.getSystemService(TetheringManager::class.java))
        val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) =
                onChange(interfaces.any { it.type == TetheringManager.TETHERING_WIFI })
        }
        manager.registerTetheringEventCallback(context.mainExecutor, callback)
        return callback
    }
    override fun unregister(handle: Any) {
        context.getSystemService(TetheringManager::class.java)?.unregisterTetheringEventCallback(handle as TetheringManager.TetheringEventCallback)
    }
}
