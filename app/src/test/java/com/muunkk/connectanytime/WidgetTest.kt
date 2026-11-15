package com.muunkk.connectanytime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class WidgetTest {
    private lateinit var app: ConnectApp
    private val connected = VpnState("Connected", busy = true, connected = true)

    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.store.prefs.edit().clear().commit()
    }
    @After fun clear() {
        ConnectApp.mutableState.value = VpnState()
        ConnectApp.mutableTetherShare.value = TetherShare.Off
        ConnectApp.mutableTetherUsage.value = TetherUsage()
        ConnectApp.mutableUplink.value = null
        ConnectApp.mutableAndroidHotspot.value = AndroidHotspot.Unknown
    }

    @Test fun eachLinkSaysWhatItIsDoing() {
        val share = TetherShare.Active(1080, usb = TetherShare.Link.Sharing("192.168.42.129"), hotspot = TetherShare.Link.Sharing("10.84.137.1"))
        val usage = TetherUsage(hotspot = LinkUsage(listOf("10.84.137.9", "10.84.137.24"), 4))
        val model = widgetModel(connected, share, usage, usbOn = true, hotspotOn = true, uplink = Uplink.MobileData)
        assertEquals(WidgetLink.Ready, model.usb)
        assertEquals(WidgetLink.InUse, model.hotspot)
        assertEquals(2, model.hotspotDevices)
        assertEquals(Uplink.MobileData, model.uplink)
        // A switch that's off is off, whatever the proxy last said.
        assertEquals(WidgetLink.Off, widgetModel(connected, share, usage, usbOn = false, hotspotOn = true, uplink = null).usb)
        // Turned on with the VPN down: it starts once the VPN connects.
        val idle = widgetModel(VpnState(), TetherShare.VpnNotConnected, TetherUsage(), usbOn = true, hotspotOn = false, uplink = Uplink.WiFi)
        assertEquals(WidgetLink.Starts, idle.usb)
        assertEquals(null, idle.uplink)
        // Waiting for tethering, and the hotspot that can't be detected.
        val waiting = widgetModel(connected, TetherShare.Active(1080, TetherShare.Link.Waiting, TetherShare.Link.Unknown), TetherUsage(), true, true, null)
        assertEquals(WidgetLink.Waiting, waiting.usb)
        assertEquals(WidgetLink.Problem, waiting.hotspot)
    }

    @Test fun theModelCarriesAndroidsHotspotAsTheWatchDecided() {
        for (state in AndroidHotspot.entries)
            assertEquals(state, widgetModel(connected, TetherShare.Off, TetherUsage(), false, false, null, state).androidHotspot)
        // Not overridden by the proxy: sharing over the hotspot while the watch is neutral (the
        // settings just opened, or a restriction) stays neutral.
        val sharing = TetherShare.Active(1080, hotspot = TetherShare.Link.Sharing("10.84.137.1"))
        assertEquals(AndroidHotspot.Unknown, widgetModel(connected, sharing, TetherUsage(), false, true, null, AndroidHotspot.Unknown).androidHotspot)
    }

    /** Puts Android's hotspot in [state] the way Android does, through HotspotWatch: the widget
     *  reads the watch again before drawing, so setting the flow directly wouldn't last. */
    private fun hotspot(state: AndroidHotspot) {
        fun ap(apState: Int) = android.content.Intent(WIFI_AP_STATE_CHANGED).putExtra("wifi_state", apState)
            .putExtra("android.net.wifi.extra.WIFI_AP_MODE", AP_MODE_TETHERED)
        // In the order Android sends them: the radio first, then Tethering.
        val intents = when (state) {
            AndroidHotspot.On -> listOf(ap(AP_ENABLED), android.content.Intent(TETHER_STATE_CHANGED).putStringArrayListExtra("tetherArray", arrayListOf("wlan2")))
            AndroidHotspot.Off -> listOf(ap(AP_DISABLED))
            AndroidHotspot.Unknown -> listOf(ap(AP_DISABLING))
        }
        for (intent in intents) app.sendBroadcast(intent)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(state, ConnectApp.androidHotspot.value)
    }

    /** Every content description in the laid-out widget. */
    private fun descriptions(view: View): List<String> = listOfNotNull(view.contentDescription?.toString()) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descriptions(view.getChildAt(it)) } else emptyList()

    @Test fun theHotspotButtonSaysAndroidsHotspotStateToTalkBack() {
        val expected = mapOf(AndroidHotspot.On to R.string.widget_hotspot_settings_on, AndroidHotspot.Off to R.string.widget_hotspot_settings_off,
            AndroidHotspot.Unknown to R.string.widget_hotspot_settings)
        for ((state, text) in expected) for (width in listOf(180, 280)) {
            hotspot(state)
            val said = descriptions(laidOut(DpSize(width.dp, 110.dp)))
            assertTrue("$state at $width: $said", app.getString(text) in said)
            // Unknown claims nothing anywhere.
            if (state == AndroidHotspot.Unknown) assertTrue(said.none { "Android hotspot," in it })
        }
        // Compact at 130%+, where the settings button isn't drawn: the hotspot share button says it.
        RuntimeEnvironment.setFontScale(1.5f)
        hotspot(AndroidHotspot.On)
        val said = descriptions(laidOut(DpSize(180.dp, 110.dp)))
        assertTrue(said.toString(), app.getString(R.string.widget_hotspot_settings_on) !in said)
        val share = app.getString(R.string.widget_share_off, app.getString(R.string.share_hotspot_switch), app.getString(R.string.sharing_link_off))
        assertTrue(said.toString(), app.getString(R.string.widget_share_hotspot_state_on, share) in said)
        hotspot(AndroidHotspot.Unknown)
        assertTrue(descriptions(laidOut(DpSize(180.dp, 110.dp))).none { "Android hotspot," in it })
    }

    @Test fun theOnDotStandsOutFromTheButtonInTheBrandPalette() {
        // The dot is a shape cue first, but it must also be visible on the button: 3:1 (WCAG non-text).
        fun lum(c: androidx.compose.ui.graphics.Color) = c.luminance().toDouble()
        fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
            (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)
        for (scheme in listOf(BrandLight, BrandDark)) for (fill in listOf(scheme.secondaryContainer, scheme.surfaceVariant))
            assertTrue("${contrast(scheme.tertiary, fill)}", contrast(scheme.tertiary, fill) >= 3.0)
    }

    private fun tap(action: String) =
        org.robolectric.Robolectric.buildActivity(ShortcutActivity::class.java, android.content.Intent(app, ShortcutActivity::class.java).setAction(action)).setup()

    @Test fun theWidgetsShareButtonsFlipTheSamePrefsAsTheApp() {
        tap(Controls.SHARE_USB)
        assertEquals(true, app.store.shareWithTether)
        tap(Controls.SHARE_USB)
        assertEquals(false, app.store.shareWithTether)
    }

    @Test fun theFirstHotspotShareOpensTheAppToExplainIt() {
        val first = tap(Controls.SHARE_HOTSPOT)
        assertEquals(false, app.store.shareOverHotspot)
        val opened = org.robolectric.Shadows.shadowOf(first.get()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        // In its own task (not this activity's hidden one), asking there before turning it on.
        for (flag in listOf(android.content.Intent.FLAG_ACTIVITY_NEW_TASK, android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)) // the last two make an open MainActivity get onNewIntent
            assertTrue(opened.flags and flag != 0)
        assertTrue(opened.getBooleanExtra(Controls.EXTRA_CONFIRM_HOTSPOT, false))
        // Once it's been turned on in the app, the widget flips it directly.
        app.store.hotspotExplained = true
        tap(Controls.SHARE_HOTSPOT)
        assertEquals(true, app.store.shareOverHotspot)
        tap(Controls.SHARE_HOTSPOT)
        assertEquals(false, app.store.shareOverHotspot)
    }

    @Test fun powerOpensTheAppWhenItCantConnectYet() {
        // No NetID or password: the app opens instead of starting the VPN.
        val power = tap(Controls.TOGGLE)
        assertEquals(MainActivity::class.java.name, org.robolectric.Shadows.shadowOf(power.get()).nextStartedActivity.component?.className)
    }

    private fun laidOut(size: DpSize): View {
        val views = runBlocking { ConnectWidget().compose(app, size = size) }
        val view: View = views.apply(app, FrameLayout(app))
        val density = app.resources.displayMetrics.density
        val w = (size.width.value * density).toInt(); val h = (size.height.value * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }
    /** Every visible text the layout had to cut short ("Tap power to con…"). */
    // Walks visibility itself: isShown is always false for a view that isn't in a window.
    private fun cutShort(view: View): List<String> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is android.widget.TextView -> view.layout?.let { layout ->
            (0 until layout.lineCount).filter { layout.getEllipsisCount(it) > 0 }.map { view.text.toString() }
        }.orEmpty()
        view is android.view.ViewGroup -> (0 until view.childCount).flatMap { cutShort(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun render(name: String, size: DpSize) {
        val view = laidOut(size)
        val density = app.resources.displayMetrics.density
        val w = (size.width.value * density).toInt(); val h = (size.height.value * density).toInt()
        val bitmap = Bitmap.createBitmap(w + 48, h + 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(96, 110, 128)) // a wallpaper-ish backdrop
        canvas.translate(24f, 24f)
        view.draw(canvas)
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun rendersConnectedAndSharing() {
        app.store.shareWithTether = true; app.store.shareOverHotspot = true
        ConnectApp.mutableState.value = connected
        ConnectApp.mutableTetherShare.value = TetherShare.Active(1080, usb = TetherShare.Link.Waiting, hotspot = TetherShare.Link.Sharing("10.84.137.1"))
        ConnectApp.mutableTetherUsage.value = TetherUsage(hotspot = LinkUsage(listOf("10.84.137.9", "10.84.137.24"), 4))
        ConnectApp.mutableUplink.value = Uplink.MobileData
        render("widget-wide-sharing-light", DpSize(300.dp, 120.dp))
        render("widget-compact-sharing-light", DpSize(180.dp, 110.dp))
    }

    @Test fun fitsAt200PercentFont() {
        app.store.shareWithTether = true
        ConnectApp.mutableState.value = connected
        ConnectApp.mutableUplink.value = Uplink.WiFi
        RuntimeEnvironment.setFontScale(2f)
        render("widget-wide-200pct", DpSize(280.dp, 110.dp))
        render("widget-compact-200pct", DpSize(180.dp, 110.dp))
    }

    @Test fun noTextIsCutShortInAnyStateWidthOrFont() {
        app.store.shareWithTether = true; app.store.shareOverHotspot = true
        val active = TetherShare.Active(1080, usb = TetherShare.Link.Waiting, hotspot = TetherShare.Link.Sharing("10.84.137.1"))
        val states = listOf<Triple<String, VpnState, TetherShare>>(
            Triple("idle", VpnState(), TetherShare.VpnNotConnected),
            Triple("connecting", VpnState("Connecting…", busy = true), TetherShare.VpnNotConnected),
            Triple("reconnecting", VpnState("Reconnecting…", busy = true), TetherShare.WaitingForVpn),
            Triple("failed", VpnState(error = "Couldn't reach the VPN server."), TetherShare.VpnNotConnected),
            Triple("ended", VpnState(error = StartRule.REFUSAL), TetherShare.VpnNotConnected),
            Triple("connected", connected, active),
            Triple("problem", connected, TetherShare.Active(1080, usb = TetherShare.Link.PortInUse, hotspot = TetherShare.Link.Unknown)))
        val problems = mutableListOf<String>()
        for (font in listOf(1.0f, 1.15f, 1.2f, 1.25f, 1.3f, 1.5f, 2.0f)) {
            RuntimeEnvironment.setFontScale(font)
            for ((name, vpn, share) in states) for (uplink in listOf(Uplink.MobileData, Uplink.Other)) {
                hotspot(AndroidHotspot.entries[(name.length + uplink.ordinal) % 3])
                ConnectApp.mutableState.value = vpn
                ConnectApp.mutableTetherShare.value = share
                ConnectApp.mutableUplink.value = uplink
                ConnectApp.mutableTetherUsage.value = TetherUsage(hotspot = LinkUsage(List(12) { "10.84.137.${it + 2}" }, 20))
                for (width in listOf(180, 250, 280, 330)) {
                    val cut = cutShort(laidOut(DpSize(width.dp, 110.dp)))
                    if (cut.isNotEmpty()) problems += "$name/$uplink at ${width}dp, ${(font * 100).toInt()}%: $cut"
                }
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
        // The check itself works: squeezed below the widget's minimum, text is cut and caught.
        RuntimeEnvironment.setFontScale(1.0f)
        ConnectApp.mutableState.value = VpnState()
        assertTrue(cutShort(laidOut(DpSize(110.dp, 110.dp))).isNotEmpty())
    }

    @Test fun shortWordsFitAtDefaultAndSlightlyLargerFonts() {
        app.store.shareWithTether = true; app.store.shareOverHotspot = true
        // Sharing on with the VPN off: "Needs VPN" on both buttons.
        ConnectApp.mutableTetherShare.value = TetherShare.VpnNotConnected
        render("widget-wide-needs-vpn", DpSize(280.dp, 110.dp))
        ConnectApp.mutableState.value = VpnState(error = "Couldn't reach the VPN server.")
        render("widget-wide-failed", DpSize(280.dp, 110.dp))
        RuntimeEnvironment.setFontScale(1.15f)
        ConnectApp.mutableState.value = VpnState("Reconnecting…", busy = true)
        ConnectApp.mutableTetherShare.value = TetherShare.WaitingForVpn
        render("widget-wide-115pct", DpSize(280.dp, 110.dp))
        render("widget-compact-115pct", DpSize(180.dp, 110.dp))
    }

    @Test fun rendersNotConnected() {
        render("widget-wide-off-light", DpSize(300.dp, 120.dp))
        RuntimeEnvironment.setQualifiers("+night")
        render("widget-wide-off-dark", DpSize(300.dp, 120.dp))
        ConnectApp.mutableState.value = connected
        ConnectApp.mutableUplink.value = Uplink.WiFi
        render("widget-wide-connected-dark", DpSize(300.dp, 120.dp))
        render("widget-compact-connected-dark", DpSize(180.dp, 110.dp))
    }

    private fun rendersHotspotStates(prefix: String) {
        app.store.shareOverHotspot = false // the share button outlined (off) next to the settings button: the closest pairing
        for (state in AndroidHotspot.entries) {
            hotspot(state)
            val name = state.name.lowercase()
            ConnectApp.mutableState.value = VpnState()
            render("$prefix-wide-hotspot-$name", DpSize(280.dp, 110.dp))
            render("$prefix-compact-hotspot-$name", DpSize(180.dp, 110.dp))
            ConnectApp.mutableState.value = connected
            ConnectApp.mutableUplink.value = Uplink.MobileData
            render("$prefix-wide-hotspot-$name-connected", DpSize(280.dp, 110.dp))
            render("$prefix-compact-hotspot-$name-connected", DpSize(180.dp, 110.dp))
        }
    }

    @Test fun rendersAndroidsHotspotStates() {
        rendersHotspotStates("widget-light")
        RuntimeEnvironment.setQualifiers("+night")
        rendersHotspotStates("widget-dark")
    }

    /** Android 10: the brand palette (all blues) and square corners, where only shape tells the states apart. */
    @Config(sdk = [29])
    @Test fun rendersAndroidsHotspotStatesOnAndroid10() {
        rendersHotspotStates("widget-sdk29-light")
        RuntimeEnvironment.setQualifiers("+night")
        rendersHotspotStates("widget-sdk29-dark")
    }
}
