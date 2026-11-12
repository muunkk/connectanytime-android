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
        ConnectApp.mutableTetherShare.value = TetherShare.Sharing("192.168.42.129", 1080)
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithText("Only Mac apps", substring = true).performScrollTo()
            save("home-share-sharing")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun shareWaiting() {
        account(); app.store.shareWithTether = true
        ConnectApp.mutableTetherShare.value = TetherShare.WaitingForUsbTether
        try {
            home(VpnState("Connected", busy = true, connected = true))
            compose.onNodeWithTag("share").performScrollTo()
            save("home-share-waiting")
        } finally { ConnectApp.mutableTetherShare.value = TetherShare.Off }
    }
    @Test fun activityEmpty() {
        compose.setContent { ConnectTheme(dark = dark, dynamicColor = false) { ActivityScreen(app.log, {}, {}) } }
        save("activity-empty")
    }
}
