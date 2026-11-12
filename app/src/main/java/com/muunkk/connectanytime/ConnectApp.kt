package com.muunkk.connectanytime

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnState(val status: String = "Not connected", val busy: Boolean = false, val connected: Boolean = false, val error: String? = null)

class ConnectApp : Application() {
    lateinit var store: Store
    lateinit var log: ActivityLog
    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        log = ActivityLog(this)
        Notices.channels(this)
    }
    companion object {
        internal val mutableState = MutableStateFlow(VpnState())
        val state = mutableState.asStateFlow()
    }
}
