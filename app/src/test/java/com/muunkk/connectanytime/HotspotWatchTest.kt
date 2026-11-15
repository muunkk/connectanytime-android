package com.muunkk.connectanytime

import android.content.Intent
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35])
class HotspotWatchTest {
    private lateinit var app: ConnectApp

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun clear() {
        app.hotspot.close()
        @Suppress("DEPRECATION")
        app.removeStickyBroadcast(Intent(TETHER_STATE_CHANGED))
        shadowOf(app.getSystemService(UserManager::class.java))
            .setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_CONFIG_TETHERING, false)
        ConnectApp.mutableAndroidHotspot.value = AndroidHotspot.Unknown
    }

    @Test fun wifiTethersProveOn() {
        for (name in listOf("wlan0", "swlan0", "ap_br_wlan2")) {
            assertEquals(name, AndroidHotspot.On,
                androidHotspot(HotspotEvidence(tether = TetherReport(listOf(name), null, 1))))
        }
    }

    @Test fun otherTethersAndMissingListsStayUnknown() {
        for (names in listOf(listOf("rndis0"), listOf("bt-pan"), listOf("eth0"),
                listOf("p2p-wlan0-0"), listOf("rndis0", "bt-pan", "eth0", "p2p-wlan0-0"), emptyList(), null)) {
            assertEquals("tethered=$names", AndroidHotspot.Unknown,
                androidHotspot(HotspotEvidence(tether = TetherReport(names, null, 1))))
        }
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence()))
    }

    @Test fun newerDisabledOrFailedApProvesOff() {
        for (state in listOf(AP_DISABLED, AP_FAILED)) {
            assertEquals("state=$state", AndroidHotspot.Off, androidHotspot(HotspotEvidence(
                tether = TetherReport(listOf("wlan0"), null, 1),
                ap = ApReport(state, AP_MODE_TETHERED, 2))))
        }
    }

    @Test fun newerTetherDecidesAfterDisabledAp() {
        val evidence = HotspotEvidence(ap = ApReport(AP_DISABLED, AP_MODE_TETHERED, 1))
        for (names in listOf(emptyList(), listOf("rndis0", "bt-pan"))) {
            assertEquals(AndroidHotspot.Off, androidHotspot(evidence.copy(
                tether = TetherReport(names, null, 2))))
        }
        // A Wi-Fi tether after the radio said off, with no ENABLING/ENABLED between: one of them is
        // old (a late callback or cached list), so neither On nor Off.
        assertEquals(AndroidHotspot.Unknown, androidHotspot(evidence.copy(
            tether = TetherReport(listOf("wlan0"), null, 2))))
        assertEquals(AndroidHotspot.Unknown, androidHotspot(evidence.copy(typed = TypedReport(true, 2))))
    }

    @Test fun startingApIsUnknownEvenWithAWifiTether() {
        // A snapshot or list from the previous run delivered while the new one starts.
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(typed = TypedReport(true, 3),
            ap = ApReport(AP_ENABLING, AP_MODE_TETHERED, 2), apStopSeq = 1)))
        assertEquals(AndroidHotspot.On, androidHotspot(HotspotEvidence(typed = TypedReport(true, 3),
            ap = ApReport(AP_ENABLED, AP_MODE_TETHERED, 2), apStopSeq = 1)))
    }

    @Test fun disablingApStaysUnknown() {
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(
            tether = TetherReport(listOf("wlan0"), null, 1),
            ap = ApReport(AP_DISABLING, AP_MODE_TETHERED, 2))))
    }

    @Test fun enablingAndEnabledApNeedWifiTether() {
        for (state in listOf(AP_ENABLING, AP_ENABLED)) {
            for (names in listOf(emptyList(), listOf("rndis0"), null)) {
                assertEquals("state=$state tethered=$names", AndroidHotspot.Unknown,
                    androidHotspot(HotspotEvidence(tether = TetherReport(names, null, 1),
                        ap = ApReport(state, AP_MODE_TETHERED, 2))))
            }
        }
    }

    @Test fun disabledApNeedsTetheredModeToProveOff() {
        val evidence = HotspotEvidence(ap = ApReport(AP_DISABLED, null, 2))
        assertEquals(AndroidHotspot.Unknown, androidHotspot(evidence))
        assertEquals(AndroidHotspot.Unknown, androidHotspot(evidence.copy(
            tether = TetherReport(emptyList(), null, 1))))
        assertEquals(AndroidHotspot.On, androidHotspot(evidence.copy(
            tether = TetherReport(listOf("wlan0"), null, 1))))
    }

    @Test fun wifiAndUnclassifiedErrorsBlockOn() {
        for (name in listOf("wlan1", "vendor_ap0")) {
            assertEquals(name, AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(
                tether = TetherReport(listOf("wlan0"), listOf(name), 1))))
        }
        assertEquals(AndroidHotspot.On, androidHotspot(HotspotEvidence(
            tether = TetherReport(listOf("wlan0"), listOf("rndis0"), 1))))
    }

    @Test fun restrictionInvalidatesOlderTethers() {
        val evidence = HotspotEvidence(tether = TetherReport(listOf("wlan0"), null, 1))
        assertEquals(AndroidHotspot.On, androidHotspot(evidence))
        assertEquals(AndroidHotspot.Unknown, androidHotspot(evidence.copy(restrictedNow = true)))
        assertEquals(AndroidHotspot.Unknown, androidHotspot(evidence.copy(restrictionSeq = 2)))
        assertEquals(AndroidHotspot.Unknown, androidHotspot(evidence.copy(restrictionSeq = 1)))
        assertEquals(AndroidHotspot.On, androidHotspot(evidence.copy(restrictionSeq = 2,
            tether = TetherReport(listOf("wlan0"), null, 3))))
    }

    @Test fun pendingHandoffStaysUnknown() {
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(
            tether = TetherReport(listOf("wlan0"), null, 1), handoffPending = true)))
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(
            ap = ApReport(AP_DISABLED, AP_MODE_TETHERED, 1), handoffPending = true)))
    }

    @Test fun androidSixteensTypedSnapshotDecidesWifiWhenItHasAnswered() {
        // Asked afresh and typed, so it beats the cached broadcast's names either way.
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(
            tether = TetherReport(listOf("wlan0"), null, 2), typed = TypedReport(false, 1))))
        assertEquals(AndroidHotspot.On, androidHotspot(HotspotEvidence(
            tether = TetherReport(emptyList(), null, 2), typed = TypedReport(true, 1))))
        // Without it (older Android, or registration failed), the broadcast decides.
        assertEquals(AndroidHotspot.On, androidHotspot(HotspotEvidence(tether = TetherReport(listOf("wlan0"), null, 1))))
        // The broadcast's error gate still applies.
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(
            tether = TetherReport(emptyList(), listOf("wlan1"), 1), typed = TypedReport(true, 2))))
    }

    @Test fun aStopAndQuickRestartDoesntBringBackThePreviousRunsInterfaces() {
        // tether On(1) -> DISABLING(2) -> DISABLED(3) -> ENABLING(4), before Tethering's teardown arrives.
        val on = TetherReport(listOf("wlan1"), null, 1)
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(tether = on, ap = ApReport(AP_DISABLING, AP_MODE_TETHERED, 2), apStopSeq = 2)))
        assertEquals(AndroidHotspot.Off, androidHotspot(HotspotEvidence(tether = on, ap = ApReport(AP_DISABLED, AP_MODE_TETHERED, 3), apStopSeq = 3)))
        assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(tether = on, ap = ApReport(AP_ENABLING, AP_MODE_TETHERED, 4), apStopSeq = 3)))
        // Tethering reports the new run: On.
        assertEquals(AndroidHotspot.On, androidHotspot(HotspotEvidence(tether = TetherReport(listOf("wlan1"), null, 5),
            ap = ApReport(AP_ENABLED, AP_MODE_TETHERED, 4), apStopSeq = 3)))
    }

    @Test fun startingWithoutAModeStaysUnknown() {
        for (state in listOf(AP_ENABLING, AP_ENABLED))
            assertEquals(AndroidHotspot.Unknown, androidHotspot(HotspotEvidence(tether = TetherReport(listOf("wlan1"), null, 2), ap = ApReport(state, null, 1))))
    }

    @Test fun theObserverWalksAStopAndQuickRestart() {
        app.sendBroadcast(tether("wlan1")); idle()
        assertEquals(AndroidHotspot.On, ConnectApp.androidHotspot.value)
        for (state in listOf(AP_DISABLING, AP_DISABLED, AP_ENABLING)) app.sendBroadcast(ap(state))
        idle()
        assertEquals(AndroidHotspot.Unknown, ConnectApp.androidHotspot.value)
        app.sendBroadcast(ap(AP_ENABLED)); app.sendBroadcast(tether("wlan1")); idle()
        assertEquals(AndroidHotspot.On, ConnectApp.androidHotspot.value)
    }

    private fun tether(vararg names: String) = Intent(TETHER_STATE_CHANGED)
        .putStringArrayListExtra("tetherArray", arrayListOf(*names))

    private fun apDisabled(mode: Int = AP_MODE_TETHERED, key: String = "android.net.wifi.extra.WIFI_AP_MODE") =
        Intent(WIFI_AP_STATE_CHANGED).putExtra("wifi_state", AP_DISABLED).putExtra(key, mode)

    private fun ap(state: Int) = Intent(WIFI_AP_STATE_CHANGED).putExtra("wifi_state", state)
        .putExtra("android.net.wifi.extra.WIFI_AP_MODE", AP_MODE_TETHERED)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun broadcast(intent: Intent) {
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun stickyWifi() {
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(tether("wlan2"))
        shadowOf(Looper.getMainLooper()).idle()
        app.hotspot.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AndroidHotspot.On, ConnectApp.androidHotspot.value)
    }

    @Test fun stickyWifiThenEmptyLiveTetherBecomesUnknown() {
        stickyWifi()
        broadcast(tether())
        assertEquals(AndroidHotspot.Unknown, ConnectApp.androidHotspot.value)
    }

    @Test fun liveDisabledApProvesOff() {
        broadcast(apDisabled())
        assertEquals(AndroidHotspot.Off, ConnectApp.androidHotspot.value)
    }

    @Test fun androidTenApModeKeyWorks() {
        broadcast(apDisabled(key = "wifi_ap_mode"))
        assertEquals(AndroidHotspot.Off, ConnectApp.androidHotspot.value)
    }

    @Test fun localOnlyApIsIgnored() {
        broadcast(apDisabled(mode = AP_MODE_LOCAL_ONLY))
        assertEquals(AndroidHotspot.Unknown, ConnectApp.androidHotspot.value)
        broadcast(tether("wlan2"))
        assertEquals(AndroidHotspot.On, ConnectApp.androidHotspot.value)
        broadcast(apDisabled(mode = AP_MODE_LOCAL_ONLY))
        assertEquals(AndroidHotspot.On, ConnectApp.androidHotspot.value)
    }

    @Test fun rereadingStickyDoesNotOutdateDisabledAp() {
        stickyWifi()
        broadcast(apDisabled())
        assertEquals(AndroidHotspot.Off, ConnectApp.androidHotspot.value)
        app.hotspot.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AndroidHotspot.Off, ConnectApp.androidHotspot.value)
    }

    @Test fun liveTetherClearsHandoff() {
        stickyWifi()
        app.hotspot.handedOff()
        assertEquals(AndroidHotspot.Unknown, ConnectApp.androidHotspot.value)
        broadcast(tether("wlan2"))
        assertEquals(AndroidHotspot.On, ConnectApp.androidHotspot.value)
    }

    @Test fun postedRefreshClearsHandoffAfterTimeout() {
        stickyWifi()
        app.hotspot.handedOff()
        assertEquals(AndroidHotspot.Unknown, ConnectApp.androidHotspot.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HotspotWatch.HANDOFF_MS - 1))
        assertEquals(AndroidHotspot.Unknown, ConnectApp.androidHotspot.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(AndroidHotspot.On, ConnectApp.androidHotspot.value)
    }

    @Test fun liftedRestrictionNeedsNewLiveTether() {
        stickyWifi()
        val users = shadowOf(app.getSystemService(UserManager::class.java))
        users.setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_CONFIG_TETHERING, true)
        broadcast(Intent(USER_RESTRICTIONS_CHANGED))
        assertEquals(AndroidHotspot.Unknown, ConnectApp.androidHotspot.value)
        users.setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_CONFIG_TETHERING, false)
        app.hotspot.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AndroidHotspot.Unknown, ConnectApp.androidHotspot.value)
        broadcast(tether("wlan2"))
        assertEquals(AndroidHotspot.On, ConnectApp.androidHotspot.value)
    }
}
