package com.muunkk.connectanytime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens with Robolectric's native graphics and saves PNGs under
 * app/build/screenshots/ for review. Uses the fallback brand colors so the images do
 * not depend on Robolectric's stand-in wallpaper palette.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{index}: dark={0}")
        fun modes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val compose = createComposeRule()
    private lateinit var app: ConnectApp
    private val mode get() = if (dark) "dark" else "light"

    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.store.prefs.edit().clear().commit()
        app.log.clear()
    }
    // captureToImage() waits for a frame-commit callback Robolectric never delivers, so
    // draw the newest window (the activity, or a dialog above it) into a bitmap instead.
    private fun save(name: String) {
        compose.waitForIdle()
        val view = newestWindow()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        File(dir, "$name-$mode.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Suppress("UNCHECKED_CAST")
    private fun newestWindow(): View {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
        return views.last()
    }
    private fun home(state: VpnState) {
        compose.setContent { ConnectTheme(dark = dark, dynamicColor = false) { HomeScreen(app.store, state, false, {}, {}, {}, {}) } }
        compose.waitForIdle()
    }
    private fun account() {
        app.store.user = "abc123"
        app.store.prefs.edit().putString("password", "presence-only").commit()
    }

    @Test fun notConnected() { home(VpnState()); save("home-not-connected") }
    @Test fun ready() { account(); home(VpnState()); save("home-ready") }
    @Test fun connecting() {
        account(); home(VpnState("Connecting… approve Duo on your phone", busy = true))
        save("home-connecting")
    }
    @Test fun connected() { account(); home(VpnState("Connected", busy = true, connected = true)); save("home-connected") }
    @Test fun reconnecting() { account(); home(VpnState("Reconnecting…", busy = true)); save("home-reconnecting") }
    @Test fun failed() {
        account(); home(VpnState(error = "Couldn't reach the VPN server."))
        save("home-failed")
    }
    @Test fun sessionEnded() { account(); home(VpnState(error = StartRule.REFUSAL)); save("home-session-ended") }
    @Test fun settings() {
        account(); home(VpnState("Connected", busy = true, connected = true))
        compose.onNodeWithText(BuildConfig.BUILD_DESCRIPTION).performScrollTo()
        save("home-settings")
    }
    @Test fun removePasswordDialog() {
        account(); home(VpnState())
        compose.onNodeWithText("Remove saved password").performScrollTo().performClick()
        save("home-remove-password-dialog")
    }
    @Test fun activity() {
        listOf(BuildConfig.BUILD_DESCRIPTION, "Connecting to vpn.shanghai.nyu.edu (manual)", "Connected",
            "Network changed", "Reconnecting…", "Back up").forEach(app.log::add)
        compose.setContent { ConnectTheme(dark = dark, dynamicColor = false) { ActivityScreen(app.log, {}, {}) } }
        save("activity")
    }
    @Test @Config(qualifiers = "w891dp-h411dp-xhdpi") fun largeTextLandscape() {
        account()
        compose.setContent { ConnectTheme(dark = dark, dynamicColor = false) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                HomeScreen(app.store, VpnState("Connected", busy = true, connected = true), false, {}, {}, {}, {})
            }
        } }
        save("home-landscape-200pct")
    }
    @Test fun sharing() {
        account(); app.store.shareWithTether = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Sharing("192.168.42.129"))
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithText("Only apps that use", substring = true).performScrollTo()
            save("home-share-sharing")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun sharingHotspot() {
        account(); app.store.shareOverHotspot = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, hotspot = TetherShare.Link.Sharing("10.84.137.1"))
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithText("Only apps that use", substring = true).performScrollTo()
            compose.onNodeWithTag("share-address").assertDoesNotExist()
            compose.onNodeWithText("leave Authentication off, then tap Save", substring = true).assertExists()
            compose.onNodeWithText("Anyone who joins the hotspot can use the VPN", substring = true).assertExists()
            // Alone on the hotspot, its card keeps the Mac's turn-off command.
            compose.onNodeWithText("Copy command to turn off").assertExists()
            save("home-share-hotspot")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun sharingBoth() {
        account(); app.store.shareWithTether = true; app.store.shareOverHotspot = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Sharing("192.168.42.129"), hotspot = TetherShare.Link.Sharing("10.84.137.1"))
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithTag("share-hotspot-address").performScrollTo()
            compose.onNodeWithTag("share-address").assertTextEquals("192.168.42.129:1080")
            compose.onNodeWithTag("share-hotspot-address").assertTextEquals("10.84.137.1")
            compose.onNodeWithText("Sharing over USB, port 1080.").assertExists()
            // The turn-off command is the same for both, so it's shown once.
            compose.onAllNodesWithText("Copy command to turn off").assertCountEquals(1)
            save("home-share-both")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun hotspotOnlyWhenTurnedOn() {
        // USB sharing from 0.1.1 doesn't extend to the hotspot: its own switch stays off.
        account(); app.store.shareWithTether = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Waiting, hotspot = TetherShare.Link.Off)
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithTag("share-hotspot").performScrollTo().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Off))
            compose.onNodeWithText("Anyone who joins the hotspot can use it.", substring = true).assertExists()
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun shareWaiting() {
        account(); app.store.shareWithTether = true; app.store.shareOverHotspot = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Waiting, hotspot = TetherShare.Link.Waiting)
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithTag("share-hotspot").performScrollTo()
            save("home-share-waiting")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun shareHotspotUnknown() {
        account(); app.store.shareWithTether = true; app.store.shareOverHotspot = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Sharing("192.168.42.129"), hotspot = TetherShare.Link.Unknown)
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithTag("share-hotspot").performScrollTo()
            compose.onNodeWithText("which Wi‑Fi is its hotspot", substring = true).assertExists()
            save("home-share-hotspot-unknown")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun sharePortInUse() {
        account(); app.store.shareWithTether = true; app.store.shareOverHotspot = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Sharing("192.168.42.129"), hotspot = TetherShare.Link.PortInUse)
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithTag("share-hotspot").performScrollTo()
            compose.onNodeWithText("using port 1080", substring = true).assertExists()
            save("home-share-port-in-use")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun sharingCardInUse() {
        account(); app.store.shareWithTether = true; app.store.shareOverHotspot = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Sharing("192.168.42.129"), hotspot = TetherShare.Link.Sharing("10.84.137.1"))
        ConnectApp.mutableTetherUsage.value = TetherUsage(
            usb = LinkUsage(listOf("192.168.42.10"), 3, 1_200_000, 48_000),
            hotspot = LinkUsage(listOf("10.84.137.9", "10.84.137.24"), 11, 52_400_000, 2_100_000))
        ConnectApp.mutableUplink.value = Uplink.MobileData
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithText("3 devices are using the VPN").assertExists()
            compose.onNodeWithText("2 devices · 11 connections").assertExists()
            compose.onNodeWithText("10.84.137.9, 10.84.137.24").assertExists()
            compose.onNodeWithText("On at 10.84.137.1:1080").assertExists()
            compose.onNodeWithText("Via mobile data").assertExists()
            save("home-sharing-card-in-use")
        } finally {
            ConnectApp.mutableTetherShare.value = TetherShare.Off; ConnectApp.mutableTetherUsage.value = TetherUsage()
            ConnectApp.mutableUplink.value = null
        }
    }
    @Test fun sharingCardReadyAndWaiting() {
        account(); app.store.shareWithTether = true; app.store.shareOverHotspot = true
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Waiting, hotspot = TetherShare.Link.Sharing("10.84.137.1"))
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithText("Ready. No devices are using it yet.").assertExists()
            compose.onNodeWithText("Waiting for USB tethering").assertExists()
            compose.onNodeWithText("No devices yet").assertExists()
            save("home-sharing-card-ready")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun sharingCardQuickToggleTurnsOnTheSameSwitchAsBelow() {
        account()
        home(VpnState("Connected", busy = true, connected = true))
        compose.onNodeWithText("Not sharing. Turn on a connection below", substring = true).assertExists()
        save("home-sharing-card-off")
        compose.onNodeWithTag("sharing-hotspot").performClick()
        compose.waitForIdle()
        assertTrue(app.store.shareOverHotspot)
        // The Share section's own switch follows.
        compose.onNodeWithTag("share-hotspot").performScrollTo().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.On))
        app.store.shareOverHotspot = false
    }
    @Test fun sharingCardHiddenWhenIdleAndOff() {
        account(); home(VpnState())
        compose.onNodeWithTag("sharing-card").assertDoesNotExist()
    }
    @Test fun shareStopped() {
        account(); app.store.sharedBefore = true
        home(VpnState())
        compose.onNodeWithText("Copy command to turn off").performScrollTo()
        save("home-share-stopped")
    }
    private val release = Release("0.1.2", 3, "https://github.com/x", 16_400_000, "a".repeat(64), "https://github.com/x")
    private fun homeWith(state: VpnState, update: UpdateState) {
        compose.setContent { ConnectTheme(dark = dark, dynamicColor = false) {
            HomeScreen(app.store, state, false, {}, {}, {}, {}, update)
        } }
        compose.waitForIdle()
    }
    @Test fun updateReady() {
        account(); homeWith(VpnState("Connected", busy = true, connected = true), UpdateState.Ready(release))
        save("home-update-ready")
        compose.onNodeWithTag("update-auto").performScrollTo()
        save("home-update-ready-about")
    }
    @Test fun updateDownloading() {
        account(); homeWith(VpnState(), UpdateState.Downloading(release, 0.62f))
        compose.onNodeWithTag("update-auto").performScrollTo()
        save("home-update-downloading")
    }
    @Test fun updateUpToDate() {
        account(); homeWith(VpnState(), UpdateState.UpToDate(System.currentTimeMillis() - 3 * 60_000))
        compose.onNodeWithTag("update-auto").performScrollTo()
        save("home-update-up-to-date")
    }
    @Test fun activityEmpty() {
        compose.setContent { ConnectTheme(dark = dark, dynamicColor = false) { ActivityScreen(app.log, {}, {}) } }
        save("activity-empty")
    }
}
