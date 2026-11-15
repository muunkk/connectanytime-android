package com.muunkk.connectanytime

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the phone itself is online over, under the VPN (and so under everything it shares). */
enum class Uplink { MobileData, WiFi, Ethernet, Other }

internal fun uplinkOf(cm: android.net.ConnectivityManager?, network: android.net.Network?): Uplink? {
    if (network == null) return null
    val caps = runCatching { cm?.getNetworkCapabilities(network) }.getOrNull() ?: return Uplink.Other
    return when {
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> Uplink.MobileData
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> Uplink.WiFi
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> Uplink.Ethernet
        else -> Uplink.Other
    }
}

data class VpnState(val status: String = "Not connected", val busy: Boolean = false, val connected: Boolean = false, val error: String? = null)

class ConnectApp : Application() {
    private lateinit var tetherPreferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener
    lateinit var store: Store
    private val widgets = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    lateinit var log: ActivityLog
    lateinit var battery: BatteryControl
        private set
    val updater by lazy { Updater(this) }
    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        log = ActivityLog(this)
        battery = BatteryControl(this)
        fun refreshTetherState() {
            if (!state.value.connected && state.value.status != VPN_STATUS_RECONNECTING)
                mutableTetherShare.value = if (store.sharing) TetherShare.VpnNotConnected else TetherShare.Off
        }
        refreshTetherState()
        tetherPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in SHARE_KEYS) refreshTetherState()
        }
        store.prefs.registerOnSharedPreferenceChangeListener(tetherPreferenceListener)
        Notices.channels(this)
        // Keeps the home screen widget in step with the VPN, sharing and the switches.
        widgets.launch { ConnectWidget.changes(this@ConnectApp).collect { ConnectWidget.refresh(this@ConnectApp) } }
    }
    companion object {
        // Service literals are pinned by VpnStatusContractTest so counter classification stays in sync.
        internal const val VPN_STATUS_RECONNECTING = "Reconnecting…"
        internal const val VPN_STATUS_CONNECTED = "Connected"
        val vpnTransitions = java.util.concurrent.atomic.AtomicLong()
        val reconnectEpisodes = java.util.concurrent.atomic.AtomicLong()
        val lastStatus = java.util.concurrent.atomic.AtomicReference("Not connected")
        internal val SHARE_KEYS = setOf("shareWithTether", "shareOverHotspot")
        internal val mutableTetherShare = MutableStateFlow<TetherShare>(TetherShare.Off)
        val tetherShare = mutableTetherShare.asStateFlow()
        internal val mutableTetherUsage = MutableStateFlow(TetherUsage())
        val tetherUsage = mutableTetherUsage.asStateFlow()
        internal val mutableUplink = MutableStateFlow<Uplink?>(null)
        val uplink = mutableUplink.asStateFlow()
        internal val mutableState = MutableStateFlow(VpnState())
        val state = mutableState.asStateFlow()
    }
}
