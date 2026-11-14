package com.muunkk.connectanytime

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** One link as the widget shows it. */
internal enum class WidgetLink { Off, Starts, Waiting, Ready, InUse, Problem }

/** Everything the widget shows. Pure, so it can be tested without a launcher. */
internal data class WidgetModel(
    val phase: Phase, val ended: Boolean, val uplink: Uplink?,
    val usbOn: Boolean, val usb: WidgetLink, val usbDevices: Int,
    val hotspotOn: Boolean, val hotspot: WidgetLink, val hotspotDevices: Int,
)

internal fun widgetModel(state: VpnState, share: TetherShare, usage: TetherUsage, usbOn: Boolean, hotspotOn: Boolean, uplink: Uplink?): WidgetModel {
    fun link(kind: TetherLink, on: Boolean): WidgetLink {
        if (!on) return WidgetLink.Off
        val link = (share as? TetherShare.Active)?.let { if (kind == TetherLink.Hotspot) it.hotspot else it.usb }
        return when {
            share == TetherShare.VpnNotConnected || share == TetherShare.Off -> WidgetLink.Starts
            share == TetherShare.WaitingForVpn -> WidgetLink.Waiting
            link is TetherShare.Link.Sharing -> if (usage[kind].devices.isNotEmpty()) WidgetLink.InUse else WidgetLink.Ready
            link == TetherShare.Link.Waiting -> WidgetLink.Waiting
            else -> WidgetLink.Problem
        }
    }
    val usb = link(TetherLink.Usb, usbOn)
    val hotspot = link(TetherLink.Hotspot, hotspotOn)
    return WidgetModel(state.phase(), state.error == StartRule.REFUSAL, if (state.connected) uplink else null,
        usbOn, usb, if (usb == WidgetLink.InUse) usage.usb.devices.size else 0,
        hotspotOn, hotspot, if (hotspot == WidgetLink.InUse) usage.hotspot.devices.size else 0)
}

/**
 * The home screen widget: the VPN's state and a power button, a button each for sharing over
 * USB and over the hotspot, and one for Android's hotspot settings. Android doesn't let apps
 * turn the hotspot itself on or off, so that button opens the screen where you can.
 *
 * Every button goes through [ShortcutActivity], which unlocks the phone first wherever the
 * widget is shown, decides at the moment of the tap (so a widget that hasn't redrawn yet can't
 * do the opposite), and opens the app when it needs VPN permission, a password, or (the
 * first time) to show what hotspot sharing means.
 */
class ConnectWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as ConnectApp
        val initial = current(context)
        provideContent {
            // Followed while this drawing session lasts: Glance doesn't rerun provideGlance for an
            // update during a session, so a value read once above would go stale.
            val model by remember { changes(app) }.collectAsState(initial)
            // Android 12+ follows the wallpaper like the app does; older versions get the app's brand colors.
            GlanceTheme(colors = if (Build.VERSION.SDK_INT >= 31) GlanceTheme.colors else BrandColors) { Content(model) }
        }
    }

    companion object {
        /** Glance reports these buckets, not the real size: three square buttons from 180 dp,
         *  buttons with words from 280 dp (a 4 × 2 on most launchers). */
        internal val COMPACT = DpSize(180.dp, 110.dp)
        internal val WIDE = DpSize(280.dp, 110.dp)
        private val BrandColors = ColorProviders(light = BrandLight, dark = BrandDark)

        internal fun current(context: Context): WidgetModel {
            val store = (context.applicationContext as ConnectApp).store
            return widgetModel(ConnectApp.state.value, ConnectApp.tetherShare.value, ConnectApp.tetherUsage.value,
                store.shareWithTether, store.shareOverHotspot, ConnectApp.uplink.value)
        }

        /** What the widget shows, as one value. Byte counts don't reach it, so traffic doesn't redraw. */
        @OptIn(FlowPreview::class)
        internal fun changes(app: ConnectApp): Flow<WidgetModel> =
            combine(ConnectApp.state, ConnectApp.tetherShare, ConnectApp.tetherUsage, ConnectApp.uplink, sharePrefs(app.store)) { state, share, usage, uplink, prefs ->
                widgetModel(state, share, usage, prefs.first, prefs.second, uplink)
            }.distinctUntilChanged().debounce(300)

        private fun sharePrefs(store: Store): Flow<Pair<Boolean, Boolean>> = callbackFlow {
            fun send() = trySend(store.shareWithTether to store.shareOverHotspot)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in ConnectApp.SHARE_KEYS) send()
            }
            store.prefs.registerOnSharedPreferenceChangeListener(listener)
            send()
            awaitClose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        /** Starts a drawing session when none is running (a running one follows [changes] itself). */
        suspend fun refresh(context: Context) { runCatching { ConnectWidget().updateAll(context) } }
    }
}

class ConnectWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ConnectWidget()
}

private fun shortcut(context: Context, action: String): Action = actionStartActivity(
    Intent(context, ShortcutActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private const val TARGET = 44 // dp: what fits two rows in the shortest 2-row widget (110 dp)

@Composable
private fun Content(model: WidgetModel) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    val connected = model.phase == Phase.Connected
    val wide = LocalSize.current.width >= ConnectWidget.WIDE.width
    val fontScale = context.resources.configuration.fontScale
    // Any enlarged font: the buttons lose their words (their text area holds about 9 characters)
    // and titles shorten. Large fonts also drop the status's second line, and on the compact
    // widget the power button moves down so the title gets the full width.
    val enlarged = fontScale > 1.0f
    val large = fontScale >= 1.3f
    val background = if (connected) colors.primaryContainer else colors.widgetBackground
    val powerBelow = !wide && large
    Scaffold(backgroundColor = background, horizontalPadding = 12.dp, modifier = GlanceModifier.padding(vertical = 8.dp)) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Status(model, short = !wide || enlarged, large, context, GlanceModifier.defaultWeight())
                if (!powerBelow) { Spacer(GlanceModifier.width(8.dp)); Power(model, context, GlanceModifier.size(TARGET.dp)) }
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (powerBelow) { Power(model, context, GlanceModifier.defaultWeight().height(TARGET.dp)); Spacer(GlanceModifier.width(6.dp)) }
                val words = wide && !enlarged
                LinkButton(context, TetherLink.Usb, model.usbOn, model.usb, model.usbDevices, words, background, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                LinkButton(context, TetherLink.Hotspot, model.hotspotOn, model.hotspot, model.hotspotDevices, words, background, GlanceModifier.defaultWeight())
                if (!powerBelow) {
                    Spacer(GlanceModifier.width(6.dp))
                    HotspotSettings(context, connected, if (wide) GlanceModifier.size(TARGET.dp) else GlanceModifier.defaultWeight().height(TARGET.dp))
                }
            }
        }
    }
}

@Composable
private fun Status(model: WidgetModel, short: Boolean, large: Boolean, context: Context, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    val connected = model.phase == Phase.Connected
    val onColor = if (connected) colors.onPrimaryContainer else colors.onSurface
    val title = context.getString(when (model.phase) {
        Phase.Idle -> if (short) R.string.widget_short_off else R.string.not_connected
        Phase.Connecting -> if (short) R.string.widget_short_connecting else R.string.connecting
        Phase.Connected -> if (short) R.string.widget_short_connected else R.string.connected
        Phase.Reconnecting -> if (short) R.string.widget_short_reconnecting else R.string.reconnecting
        Phase.Failed -> when {
            model.ended -> if (short) R.string.widget_short_ended else R.string.session_ended
            else -> if (short) R.string.widget_short_failed else R.string.failed_title
        }
    })
    val detail = when {
        connected && model.uplink != null -> context.getString(uplinkName(model.uplink))
        connected -> context.getString(R.string.widget_vpn_on)
        model.phase == Phase.Connecting || model.phase == Phase.Reconnecting -> context.getString(R.string.widget_working)
        model.phase == Phase.Failed -> context.getString(R.string.widget_try_again)
        else -> context.getString(R.string.widget_vpn_off)
    }
    // Tapping the status opens the app, as most widgets do.
    Column(modifier.clickable(shortcut(context, Controls.OPEN_APP)).semantics { contentDescription = "$title. " + context.getString(R.string.widget_open_app) }) {
        Text(title, maxLines = 1, style = TextStyle(color = onColor, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        if (!large) Row(verticalAlignment = Alignment.CenterVertically) {
            if (connected && model.uplink != null) {
                Image(ImageProvider(uplinkIcon(model.uplink)), contentDescription = null,
                    colorFilter = ColorFilter.tint(onColor), modifier = GlanceModifier.size(14.dp))
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(detail, maxLines = 1, style = TextStyle(color = onColor, fontSize = 12.sp))
        }
    }
}

/** Connect or Disconnect: drawn for the state now, decided by ShortcutActivity when tapped. */
@Composable
private fun Power(model: WidgetModel, context: Context, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    val busy = model.phase == Phase.Connected || model.phase == Phase.Connecting || model.phase == Phase.Reconnecting
    Box(modifier.cornerRadius((TARGET / 2).dp).background(if (busy) colors.error else colors.primary)
        .clickable(shortcut(context, Controls.TOGGLE))
        .semantics { contentDescription = context.getString(if (busy) R.string.disconnect else R.string.connect) },
        contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_power), contentDescription = null,
            colorFilter = ColorFilter.tint(if (busy) colors.onError else colors.onPrimary), modifier = GlanceModifier.size(22.dp))
    }
}

private fun linkStatus(context: Context, state: WidgetLink, devices: Int) = when (state) {
    WidgetLink.Off -> context.getString(R.string.sharing_link_off)
    WidgetLink.Starts -> context.getString(R.string.widget_link_starts)
    WidgetLink.Waiting -> context.getString(R.string.widget_link_waiting)
    WidgetLink.Ready -> context.getString(R.string.widget_link_ready)
    WidgetLink.InUse -> context.getString(R.string.widget_link_using, devices)
    WidgetLink.Problem -> context.getString(R.string.widget_link_problem)
}

/**
 * A share button. On: filled. Off: outlined, so on and off differ by shape as well as colour.
 * Wide, it names the link and says what it's doing; compact, the icon and how many are using it.
 */
@Composable
private fun LinkButton(context: Context, link: TetherLink, on: Boolean, state: WidgetLink, devices: Int, words: Boolean,
                       background: ColorProvider, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    val hotspot = link == TetherLink.Hotspot
    val content = if (on) colors.onPrimary else colors.onSurfaceVariant
    val name = context.getString(if (hotspot) R.string.share_hotspot_switch else R.string.share_switch)
    val status = linkStatus(context, state, devices)
    val description = context.getString(if (on) R.string.widget_share_on else R.string.widget_share_off, name, status)
    // The outline is the outer box's colour showing round a 1.5 dp inset.
    Box(modifier.height(TARGET.dp).cornerRadius((TARGET / 2).dp).background(if (on) colors.primary else colors.outline)
        .clickable(shortcut(context, if (hotspot) Controls.SHARE_HOTSPOT else Controls.SHARE_USB))
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Row(GlanceModifier.fillMaxSize().padding(if (on) 0.dp else 1.5.dp), verticalAlignment = Alignment.CenterVertically) {
            // Off: the widget's own background inside the outline, so it reads as outlined on either background.
            Row(GlanceModifier.fillMaxSize().cornerRadius((TARGET / 2).dp).background(if (on) colors.primary else background)
                .padding(horizontal = if (words) 10.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalAlignment = if (words) Alignment.Start else Alignment.CenterHorizontally) {
                Image(ImageProvider(if (hotspot) R.drawable.ic_hotspot else R.drawable.ic_usb), contentDescription = null,
                    colorFilter = ColorFilter.tint(content), modifier = GlanceModifier.size(20.dp))
                if (words) {
                    Spacer(GlanceModifier.width(6.dp))
                    Column {
                        Text(context.getString(if (hotspot) R.string.widget_hotspot else R.string.widget_usb), maxLines = 1,
                            style = TextStyle(color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        Text(status, maxLines = 1, style = TextStyle(color = content, fontSize = 11.sp))
                    }
                } else if (state == WidgetLink.InUse) {
                    Spacer(GlanceModifier.width(4.dp))
                    Text(devices.toString(), maxLines = 1, style = TextStyle(color = content, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/** Android's hotspot screen (apps can't switch the hotspot itself): the hotspot sign with an
 *  "opens elsewhere" arrow, so it doesn't read as this app's settings. */
@Composable
private fun HotspotSettings(context: Context, connected: Boolean, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    // secondaryContainer all but vanishes on the connected widget's primaryContainer.
    val fill = if (connected) colors.surface else colors.secondaryContainer
    val ink = if (connected) colors.onSurface else colors.onSecondaryContainer
    Box(modifier.cornerRadius((TARGET / 2).dp).background(fill)
        .clickable(shortcut(context, Controls.OPEN_TETHERING))
        .semantics { contentDescription = context.getString(R.string.widget_hotspot_settings) },
        contentAlignment = Alignment.Center) {
        Box(GlanceModifier.size(26.dp)) {
            Image(ImageProvider(R.drawable.ic_hotspot), contentDescription = null, colorFilter = ColorFilter.tint(ink),
                modifier = GlanceModifier.size(20.dp))
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                Image(ImageProvider(R.drawable.ic_open_external), contentDescription = null,
                    colorFilter = ColorFilter.tint(ink), modifier = GlanceModifier.size(12.dp))
            }
        }
    }
}
