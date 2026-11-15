package com.muunkk.connectanytime

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

enum class BatteryStatus { Off, Measuring, PausedVpnOff, PausedCharging, CouldNotSave, AutoStopped }
internal data class BatteryPrefs(val enabled: Boolean, val autoStopped: Boolean, val deadline: Long,
    val sharing: Boolean, val usbSharing: Boolean, val setup: ProxySetup)
internal fun batteryPrefs(store: Store) = callbackFlow {
    fun emit() { trySend(BatteryPrefs(store.measureBattery, store.batteryAutoStopped, store.batteryDeadline,
        store.shareOverHotspot, store.shareWithTether, store.proxySetup)) }
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> emit() }
    store.prefs.registerOnSharedPreferenceChangeListener(listener)
    emit()
    awaitClose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()

/** App-owned facade. Export callbacks run on main. UI may read setup and sharing from Store.
 *  Reports retain the last snapshot while refreshing. AutoStopped follows its persisted latch,
 *  which the screen clears on leaving. Clear is asynchronous and starts a fresh epoch if enabled. */
class BatteryControl internal constructor(private val app: ConnectApp,
    internal val disk: BatteryStore = BatteryStore(app, app.store),
    private val readings: () -> BatteryReadings = { BatteryReadings(app) },
    private val now: () -> Long = System::currentTimeMillis) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(BatteryStatus.Off)
    val status: StateFlow<BatteryStatus> = mutableStatus.asStateFlow()
    val report: StateFlow<BatteryReport?> = disk.report
    private val mutableAutoStopped = MutableStateFlow(app.store.batteryAutoStopped)
    val autoStopped: StateFlow<Boolean> = mutableAutoStopped.asStateFlow()
    private val plugged = MutableStateFlow(false)
    private var recorder: BatteryRecorder? = null
    private var clearPending = false
    private var logClearRestart = false
    private val mutableClearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = mutableClearing.asStateFlow()
    private var stopping = false
    private var startFailed = false
    private var erasing = false
    init {
        scope.launch {
            combine(batteryPrefs(app.store), ConnectApp.state, disk.failed, plugged) { prefs, vpn, failed, charging ->
                State(prefs, vpn.busy, failed, charging)
            }.distinctUntilChanged().collect { update(it) }
        }
    }
    private data class State(val prefs: BatteryPrefs, val busy: Boolean, val failed: Boolean, val charging: Boolean)
    private fun update(state: State) {
        mutableAutoStopped.value = state.prefs.autoStopped
        if (state.prefs.enabled && now() >= state.prefs.deadline) { autoStop(); return }
        if (state.prefs.enabled && state.busy && !clearPending) {
            if (recorder == null && !stopping && !startFailed) {
                runCatching {
                    recorder = BatteryRecorder(app, disk, readings(), { onMain { autoStop() } }, { plugged.value = it },
                        failed = { onMain { startFailed = true; stopRecorder(); reconcile() } })
                    recorder!!.start()
                }.onFailure { startFailed = true; stopRecorder() }
            }
        } else { stopRecorder() }
        mutableStatus.value = when {
            !state.prefs.enabled -> if (state.prefs.autoStopped) BatteryStatus.AutoStopped else BatteryStatus.Off
            state.failed || startFailed -> BatteryStatus.CouldNotSave
            !state.busy -> BatteryStatus.PausedVpnOff
            state.charging -> BatteryStatus.PausedCharging
            else -> BatteryStatus.Measuring
        }
    }
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
    private fun autoStop() {
        if (!app.store.measureBattery) return
        app.store.prefs.edit().putBoolean("batteryAutoStopped", true).putBoolean("measureBattery", false).apply()
        stopRecorder()
        mutableAutoStopped.value = true; mutableStatus.value = BatteryStatus.AutoStopped
        app.log.add(app.getString(R.string.battery_log_auto_stop))
    }
    fun setEnabled(on: Boolean) = onMain {
        val changed = on != app.store.measureBattery
        if (on && changed) app.store.prefs.edit().putLong("batteryDeadline", now() + 7 * 86_400_000L)
            .putBoolean("batteryAutoStopped", false).putBoolean("measureBattery", true).apply()
        else if (!on) { app.store.measureBattery = false; startFailed = false }
        if (changed) {
            logClearRestart = false
            app.log.add(app.getString(if (on) R.string.battery_log_start else R.string.battery_log_stop))
        }
        reconcile()
    }
    private fun reconcile() {
        update(State(BatteryPrefs(app.store.measureBattery, app.store.batteryAutoStopped, app.store.batteryDeadline,
            app.store.shareOverHotspot, app.store.shareWithTether, app.store.proxySetup), ConnectApp.state.value.busy,
            disk.failed.value, plugged.value))
    }
    private fun stopRecorder() {
        val old = recorder ?: return
        recorder = null; stopping = true
        old.stop {
            stopping = false
            if (clearPending) erase() else reconcile()
        }
    }
    private fun erase() {
        if (erasing) return
        erasing = true
        disk.clear {
            clearPending = false; mutableClearing.value = false; erasing = false
            if (logClearRestart && app.store.measureBattery) app.log.add(app.getString(R.string.battery_log_start))
            logClearRestart = false
            reconcile()
        }
    }
    fun clear() = onMain {
        if (clearPending || disk.clearing.get()) return@onMain
        clearPending = true; mutableClearing.value = true
        logClearRestart = app.store.measureBattery
        if (logClearRestart) app.log.add(app.getString(R.string.battery_log_stop))
        stopRecorder()
        if (!stopping) erase()
    }
    internal fun close() { scope.cancel(); recorder?.stop(); recorder = null }
    fun retrySave() = onMain { startFailed = false; disk.retrySave(); reconcile() }
    fun refresh() { disk.refresh() }
    fun export(callback: (File?) -> Unit) { disk.export(callback) }
}
