package com.muunkk.connectanytime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VpnStatusContractTest {
    @Test fun serviceStateCallsKeepTheExactStringsUsedByBatteryCounters() {
        assertEquals("Reconnecting…", ConnectApp.VPN_STATUS_RECONNECTING)
        assertEquals("Connected", ConnectApp.VPN_STATUS_CONNECTED)
        val path = "src/main/java/com/muunkk/connectanytime/ConnectVpnService.kt"
        val source = listOf(File(path), File("app", path)).first { it.isFile }.readText()
        val reconnecting = Regex("""(?m)^\s*state\("${Regex.escape(ConnectApp.VPN_STATUS_RECONNECTING)}"\)\s*$""")
        val connected = Regex("""(?m)^\s*state\("${Regex.escape(ConnectApp.VPN_STATUS_CONNECTED)}",\s*true\)\s*$""")
        // Check every current call, so changing just one connected call also breaks this contract.
        assertEquals("Keep state(\"Reconnecting…\") in sync with battery counters", 1, reconnecting.findAll(source).count())
        assertEquals("Keep both state(\"Connected\", true) calls in sync with battery counters", 2, connected.findAll(source).count())
    }
}
