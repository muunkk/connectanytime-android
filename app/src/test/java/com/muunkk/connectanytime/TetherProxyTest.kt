package com.muunkk.connectanytime

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35], shadows = [TetherProxyTest.ShadowNativeBridge::class])
class TetherProxyTest {
    @org.robolectric.annotation.Implements(NativeBridge::class)
    class ShadowNativeBridge {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation
            fun __staticInitializer__() = Unit
        }
    }

    @Test fun readsAndroidsActiveTethers() {
        assertNull(activeTethers(null))
        assertNull(activeTethers(android.content.Intent(TETHER_STATE_CHANGED)))
        assertEquals(setOf("wlan1", "rndis0"), activeTethers(android.content.Intent(TETHER_STATE_CHANGED)
            .putStringArrayListExtra("tetherArray", arrayListOf("wlan1", "rndis0"))
            .putStringArrayListExtra("availableArray", arrayListOf("wlan2"))))
    }
    @Test fun followsAndroidsTetherBroadcastAndStopsListening() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        app.store.shareWithTether = false
        val service = Robolectric.buildService(ConnectVpnService::class.java).get()
        fun tethered(proxy: TetherProxy) = TetherProxy::class.java.getDeclaredField("tethered")
            .apply { isAccessible = true }.get(proxy)
        // Already hotspotting when sharing starts: the sticky broadcast is read at once.
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(android.content.Intent(TETHER_STATE_CHANGED).putStringArrayListExtra("tetherArray", arrayListOf("wlan1")))
        val proxy = TetherProxy(service, app, 0)
        try {
            assertEquals(setOf("wlan1"), tethered(proxy))
            // Hotspot turned off, USB on.
            app.sendBroadcast(android.content.Intent(TETHER_STATE_CHANGED).putStringArrayListExtra("tetherArray", arrayListOf("rndis0")))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(setOf("rndis0"), tethered(proxy))
        } finally { proxy.close() }
        // Closed: unregistered, so later broadcasts can't reach it.
        app.sendBroadcast(android.content.Intent(TETHER_STATE_CHANGED).putStringArrayListExtra("tetherArray", arrayListOf("wlan2")))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(setOf("rndis0"), tethered(proxy))
        @Suppress("DEPRECATION") app.removeStickyBroadcast(android.content.Intent(TETHER_STATE_CHANGED))
    }
    private fun awaitShare(predicate: (TetherShare) -> Boolean): TetherShare {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val now = ConnectApp.tetherShare.value
            if (predicate(now)) return now
            Thread.sleep(20)
        }
        return ConnectApp.tetherShare.value
    }
    @Test fun eachLinkReportsItsOwnStateAndTheHotspotIsNeverGuessed() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        app.store.shareWithTether = true; app.store.shareOverHotspot = true
        @Suppress("DEPRECATION") app.removeStickyBroadcast(android.content.Intent(TETHER_STATE_CHANGED))
        val service = Robolectric.buildService(ConnectVpnService::class.java).get()
        // No list from Android yet (as before any tethering since boot) and no hotspot-like
        // interface up: both wait, rather than calling the hotspot undetectable.
        var proxy = TetherProxy(service, app, 0)
        try {
            proxy.state(connected = true, reconnecting = false)
            assertEquals(TetherShare.Active(0, TetherShare.Link.Waiting, TetherShare.Link.Waiting),
                awaitShare { it is TetherShare.Active })
        } finally { proxy.close() }
        // Android lists a hotspot that this machine doesn't have: waiting, not sharing anything else.
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(android.content.Intent(TETHER_STATE_CHANGED).putStringArrayListExtra("tetherArray", arrayListOf("wlan9")))
        proxy = TetherProxy(service, app, 0)
        try {
            proxy.state(connected = true, reconnecting = false)
            assertEquals(TetherShare.Active(0, TetherShare.Link.Waiting, TetherShare.Link.Waiting),
                awaitShare { it is TetherShare.Active && it.hotspot == TetherShare.Link.Waiting })
            // Hotspot sharing turned off: reported off, USB unaffected.
            app.store.shareOverHotspot = false
            assertEquals(TetherShare.Active(0, TetherShare.Link.Waiting, TetherShare.Link.Off),
                awaitShare { it is TetherShare.Active && it.hotspot == TetherShare.Link.Off })
            app.store.shareWithTether = false
            assertEquals(TetherShare.Off, awaitShare { it == TetherShare.Off })
        } finally {
            proxy.close()
            @Suppress("DEPRECATION") app.removeStickyBroadcast(android.content.Intent(TETHER_STATE_CHANGED))
            app.store.shareWithTether = false; app.store.shareOverHotspot = false
        }
    }
    @Test fun reconnectRefusesTcpOnAutomaticAndResumesTheSameBinding() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        app.store.shareWithTether = true; app.store.shareOverHotspot = false
        app.store.proxySetup = ProxySetup.Automatic
        val service = Robolectric.buildService(ConnectVpnService::class.java).get()
        val address = java.net.InetAddress.getByName("127.0.0.1")
        val port = java.net.ServerSocket(0, 1, address).use { it.localPort }
        val binding = TetherProxy.Binding("usb0", TetherLink.Usb, address, 24)
        var present = true
        val proxy = TetherProxy(service, app, port) { if (present) setOf(binding) else emptySet() }
        val reconcile = TetherProxy::class.java.getDeclaredMethod("reconcile").apply { isAccessible = true }
        fun scan(connected: Boolean, reconnecting: Boolean) {
            proxy.state(connected, reconnecting)
            reconcile.invoke(proxy)
        }
        fun accepts() { java.net.Socket().use { it.connect(java.net.InetSocketAddress(address, port), 1000) } }
        fun refuses() {
            assertThrows(java.net.ConnectException::class.java) { accepts() }
        }
        try {
            scan(false, true); refuses() // Never opens before first Connected.
            scan(true, false); accepts()
            val started = service.getString(R.string.tether_started, address.hostAddress, port)
            val stopped = service.getString(R.string.tether_stopped)
            val starts = app.log.state.value.count { it.endsWith(started) }
            val stops = app.log.state.value.count { it.endsWith(stopped) }
            // A registered session owns its sockets independently of the accepting socket.
            val pairServer = java.net.ServerSocket(0, 1, address)
            val peer = java.net.Socket(address, pairServer.localPort)
            val accepted = pairServer.accept()
            pairServer.close()
            val sessionType = TetherProxy::class.java.declaredClasses.single { it.simpleName == "Session" }
            val session = sessionType.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(proxy, binding, accepted)
            @Suppress("UNCHECKED_CAST")
            val sessions = TetherProxy::class.java.getDeclaredField("sessions").apply { isAccessible = true }
                .get(proxy) as MutableSet<Any>
            sessions.add(session)
            scan(false, true); refuses()
            assertFalse(accepted.isClosed)
            scan(true, false); accepts()
            assertFalse(accepted.isClosed)
            assertEquals(starts, app.log.state.value.count { it.endsWith(started) })
            assertEquals(stops, app.log.state.value.count { it.endsWith(stopped) })
            // Something else takes the port while paused: the resume reports it, then recovers.
            scan(false, true); refuses()
            java.net.ServerSocket().use { other ->
                other.bind(java.net.InetSocketAddress(address, port))
                scan(true, false)
                assertEquals(TetherShare.Link.PortInUse, (ConnectApp.tetherShare.value as TetherShare.Active).usb)
            }
            scan(true, false); accepts()
            assertTrue(ConnectApp.tetherShare.value.let { it is TetherShare.Active && it.usb is TetherShare.Link.Sharing })
            present = false
            scan(true, false); refuses()
            assertTrue(accepted.isClosed)
            peer.close()
        } finally { proxy.close(); app.store.shareWithTether = false; app.store.proxySetup = ProxySetup.Manual }
        refuses()
    }
    @Test fun manualKeepsTakingClientsWhileTheVpnReconnects() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        app.store.shareWithTether = true; app.store.shareOverHotspot = false
        app.store.proxySetup = ProxySetup.Manual
        val service = Robolectric.buildService(ConnectVpnService::class.java).get()
        val address = java.net.InetAddress.getByName("127.0.0.1")
        val port = java.net.ServerSocket(0, 1, address).use { it.localPort }
        val binding = TetherProxy.Binding("usb0", TetherLink.Usb, address, 24)
        val proxy = TetherProxy(service, app, port) { setOf(binding) }
        val reconcile = TetherProxy::class.java.getDeclaredMethod("reconcile").apply { isAccessible = true }
        fun scan(connected: Boolean, reconnecting: Boolean) { proxy.state(connected, reconnecting); reconcile.invoke(proxy) }
        fun accepts() { java.net.Socket().use { it.connect(java.net.InetSocketAddress(address, port), 1000) } }
        try {
            scan(false, true)
            assertThrows(java.net.ConnectException::class.java) { accepts() } // still never before first Connected
            scan(true, false); accepts()
            // As before Automatic existed: a short reconnect keeps the port open.
            scan(false, true); accepts()
            // Switching to Automatic mid-reconnect closes it on the next scan.
            app.store.proxySetup = ProxySetup.Automatic
            scan(false, true)
            assertThrows(java.net.ConnectException::class.java) { accepts() }
            // And back to Manual: the port opens again, still mid-reconnect.
            app.store.proxySetup = ProxySetup.Manual
            scan(false, true); accepts()
        } finally { proxy.close(); app.store.shareWithTether = false; app.store.proxySetup = ProxySetup.Manual }
    }
    @Test fun sessionServesPacOverLoopbackWithoutAVpn() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        app.store.shareWithTether = true
        app.store.directRanges = parseDirectRanges("192.168.68.0/22")!!
        val service = Robolectric.buildService(ConnectVpnService::class.java).get()
        val proxy = TetherProxy(service, app, 1080) { emptySet() }
        proxy.state(connected = true, reconnecting = false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (method in listOf("GET", "HEAD")) {
                java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                    java.net.Socket(server.inetAddress, server.localPort).use { client ->
                        val accepted = server.accept()
                        val binding = TetherProxy.Binding("usb0", TetherLink.Usb, java.net.InetAddress.getByName("10.84.137.1"), 24)
                        val type = TetherProxy::class.java.declaredClasses.single { it.simpleName == "Session" }
                        val session = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(proxy, binding, accepted)
                        val run = type.getDeclaredMethod("run").apply { isAccessible = true }
                        val done = pool.submit { run.invoke(session) }
                        client.soTimeout = 3000
                        client.getOutputStream().write("$method /proxy.pac?v=1 HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray())
                        val response = client.getInputStream().bufferedReader().readText()
                        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
                        val body = response.substringAfter("\r\n\r\n")
                        assertEquals(if (method == "GET") pacFile("10.84.137.1", 1080,
                            Ipv4Range.from(binding.address, 24)!!, app.store.directRanges) else "", body)
                        done.get(3, TimeUnit.SECONDS)
                    }
                }
            }
        } finally { proxy.close(); pool.shutdownNow(); app.store.shareWithTether = false }
    }
    @Test fun proxySettingsDefaultPersistAndRejectInvalidStoredRanges() {
        val store = ApplicationProvider.getApplicationContext<ConnectApp>().store
        store.prefs.edit().remove("proxySetup").remove("directRanges").commit()
        assertEquals(ProxySetup.Manual, store.proxySetup)
        assertEquals("192.168.68.0/22", store.directRanges.single().toString())
        store.proxySetup = ProxySetup.Automatic
        store.directRanges = parseDirectRanges("10.1.0.0/16")!!
        assertEquals(ProxySetup.Automatic, store.proxySetup)
        assertEquals("10.1.0.0/16", store.directRanges.single().toString())
        store.prefs.edit().putString("proxySetup", "bad").putString("directRanges", "bad").commit()
        assertEquals(ProxySetup.Manual, store.proxySetup)
        assertEquals("192.168.68.0/22", store.directRanges.single().toString())
        store.prefs.edit().remove("proxySetup").remove("directRanges").commit()
    }
    @Test fun closeWaitsForAnInFlightRescan() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        app.store.shareWithTether = false
        val service = Robolectric.buildService(ConnectVpnService::class.java).get()
        val proxy = TetherProxy(service, app, 0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        // Hold reconciliation at the map traversal that used to race close().
        val map = object : LinkedHashMap<Any, Any>() {
            override val keys: MutableSet<Any> get() {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.keys
            }
        }
        try {
            synchronized(proxy) {
                TetherProxy::class.java.getDeclaredField("listeners").apply { isAccessible = true }.set(proxy, map)
            }
            val reconcile = TetherProxy::class.java.getDeclaredMethod("reconcile").apply { isAccessible = true }
            val scan = pool.submit { reconcile.invoke(proxy) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stop = pool.submit { closing.countDown(); proxy.close() }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.TimeoutException::class.java) { stop.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            scan.get(3, TimeUnit.SECONDS)
            stop.get(3, TimeUnit.SECONDS)
            proxy.close() // idempotent
        } finally {
            release.countDown()
            proxy.close()
            pool.shutdownNow()
        }
    }
}
