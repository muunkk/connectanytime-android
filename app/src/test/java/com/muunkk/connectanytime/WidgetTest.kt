package com.muunkk.connectanytime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertEquals(MainActivity::class.java.name, org.robolectric.Shadows.shadowOf(first.get()).nextStartedActivity.component?.className)
        // Once the hotspot has been shared, the widget flips it directly.
        app.store.sharedHotspotBefore = true
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

    private fun render(name: String, size: DpSize) {
        val views = runBlocking { ConnectWidget().compose(app, size = size) }
        val parent = FrameLayout(app)
        val view: View = views.apply(app, parent)
        val density = app.resources.displayMetrics.density
        val w = (size.width.value * density).toInt(); val h = (size.height.value * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
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

    @Test fun rendersNotConnected() {
        render("widget-wide-off-light", DpSize(300.dp, 120.dp))
        RuntimeEnvironment.setQualifiers("+night")
        render("widget-wide-off-dark", DpSize(300.dp, 120.dp))
        ConnectApp.mutableState.value = connected
        ConnectApp.mutableUplink.value = Uplink.WiFi
        render("widget-wide-connected-dark", DpSize(300.dp, 120.dp))
        render("widget-compact-connected-dark", DpSize(180.dp, 110.dp))
    }
}
