package com.muunkk.connectanytime

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnState(val status: String = "Not connected", val busy: Boolean = false, val connected: Boolean = false, val error: String? = null)

class ConnectApp : Application() {
    private lateinit var tetherPreferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener
    lateinit var store: Store
    lateinit var log: ActivityLog
    val updater by lazy { Updater(this) }
    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        log = ActivityLog(this)
        fun refreshTetherState() {
            if (!state.value.connected && state.value.status != "Reconnecting…")
                mutableTetherShare.value = if (store.shareWithTether) TetherShare.VpnNotConnected else TetherShare.Off
        }
        refreshTetherState()
        tetherPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "shareWithTether") refreshTetherState()
        }
        store.prefs.registerOnSharedPreferenceChangeListener(tetherPreferenceListener)
        Notices.channels(this)
    }
    companion object {
        internal val mutableTetherShare = MutableStateFlow<TetherShare>(TetherShare.Off)
        val tetherShare = mutableTetherShare.asStateFlow()
        internal val mutableState = MutableStateFlow(VpnState())
        val state = mutableState.asStateFlow()
    }
}
