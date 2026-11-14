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
