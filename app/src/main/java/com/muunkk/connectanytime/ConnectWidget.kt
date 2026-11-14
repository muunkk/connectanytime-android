package com.muunkk.connectanytime

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ButtonDefaults
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.awaitClose

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
 * The home screen widget: the VPN's state with Connect or Disconnect, a switch for sharing
 * over USB and over the hotspot, and a shortcut to Android's hotspot settings. Android
 * doesn't let apps turn the hotspot itself on or off, so that button opens the screen where
 * you can.
 *
 * Connect and Disconnect go through [ShortcutActivity], which unlocks the phone if needed and
 * opens the app when it needs VPN permission or a password first.
 */
class ConnectWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = current(context)
        provideContent {
            // Android 12+ follows the wallpaper like the app does; older versions get the app's brand colors.
            GlanceTheme(colors = if (Build.VERSION.SDK_INT >= 31) GlanceTheme.colors else BrandColors) { Content(model) }
        }
    }

    companion object {
        internal val SMALL = DpSize(120.dp, 110.dp)
        internal val WIDE = DpSize(280.dp, 110.dp)
        private val BrandColors = ColorProviders(light = BrandLight, dark = BrandDark)

        internal fun current(context: Context): WidgetModel {
            val store = (context.applicationContext as ConnectApp).store
            return widgetModel(ConnectApp.state.value, ConnectApp.tetherShare.value, ConnectApp.tetherUsage.value,
                store.shareWithTether, store.shareOverHotspot, ConnectApp.uplink.value)
        }

        /** What the widget shows, as one value: the app updates the widget when this changes. */
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

        suspend fun refresh(context: Context) { runCatching { ConnectWidget().updateAll(context) } }
    }
}

class ConnectWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ConnectWidget()
}

/** A share switch on the widget: flips the same pref as the app's switches. */
class ToggleShareAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val link = if (parameters[LINK] == TetherLink.Hotspot.name) TetherLink.Hotspot else TetherLink.Usb
        val store = (context.applicationContext as ConnectApp).store
        store.setShares(link, !store.shares(link))
        ConnectWidget.refresh(context)
    }
    companion object { internal val LINK = ActionParameters.Key<String>("link") }
}

private fun shortcut(context: Context, action: String) =
    Intent(context, ShortcutActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun Content(model: WidgetModel) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    val connected = model.phase == Phase.Connected
    val width = LocalSize.current.width
    val wide = width >= ConnectWidget.WIDE.width
    Scaffold(backgroundColor = if (connected) colors.primaryContainer else colors.widgetBackground, horizontalPadding = 12.dp,
        modifier = GlanceModifier.padding(vertical = 10.dp)) {
        Column(GlanceModifier.fillMaxSize()) {
            // Wide: the power button beside the status, chips with words below. Narrow: the
            // status alone on top, then one row of round-ended buttons with power first.
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Status(model, GlanceModifier.defaultWeight())
                if (wide) { Spacer(GlanceModifier.width(8.dp)); Power(model, context, GlanceModifier.size(44.dp)) }
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!wide) { Power(model, context, GlanceModifier.size(40.dp)); Spacer(GlanceModifier.width(6.dp)) }
                LinkChip(context, TetherLink.Usb, model.usbOn, model.usb, model.usbDevices, wide, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                LinkChip(context, TetherLink.Hotspot, model.hotspotOn, model.hotspot, model.hotspotDevices, wide, GlanceModifier.defaultWeight())
                // Too cramped below about 4 buttons' width; the app's Share section links there too.
                if (wide || width >= 180.dp) { Spacer(GlanceModifier.width(6.dp)); HotspotSettings(context) }
            }
        }
    }
}

@Composable
private fun Status(model: WidgetModel, modifier: GlanceModifier) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    val connected = model.phase == Phase.Connected
    val onColor = if (connected) colors.onPrimaryContainer else colors.onSurface
    val title = context.getString(when (model.phase) {
        Phase.Idle -> R.string.not_connected
        Phase.Connecting -> R.string.connecting
        Phase.Connected -> R.string.connected
        Phase.Reconnecting -> R.string.reconnecting
        Phase.Failed -> if (model.ended) R.string.session_ended else R.string.failed_title
    })
    val detail = when {
        connected && model.uplink != null -> context.getString(uplinkName(model.uplink))
        connected -> context.getString(R.string.widget_vpn_on)
        model.phase == Phase.Connecting || model.phase == Phase.Reconnecting -> context.getString(R.string.widget_working)
        model.phase == Phase.Failed -> context.getString(R.string.widget_try_again)
        else -> context.getString(R.string.widget_vpn_off)
    }
    Column(modifier) {
        Text(title, maxLines = 1, style = TextStyle(color = onColor, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connected && model.uplink != null) {
                Image(ImageProvider(uplinkIcon(model.uplink)), contentDescription = null,
                    colorFilter = ColorFilter.tint(onColor), modifier = GlanceModifier.size(14.dp))
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(detail, maxLines = 1, style = TextStyle(color = onColor, fontSize = 12.sp))
        }
    }
}

/** Connect or Disconnect, decided when tapped (ShortcutActivity), drawn for the state now. */
@Composable
private fun Power(model: WidgetModel, context: Context, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    val busy = model.phase == Phase.Connected || model.phase == Phase.Connecting || model.phase == Phase.Reconnecting
    CircleIconButton(ImageProvider(R.drawable.ic_power), contentDescription = context.getString(if (busy) R.string.disconnect else R.string.connect),
        onClick = actionStartActivity(shortcut(context, Controls.TOGGLE)),
        backgroundColor = if (busy) colors.error else colors.primary, contentColor = if (busy) colors.onError else colors.onPrimary,
        modifier = modifier)
}

private fun linkStatus(context: Context, state: WidgetLink, devices: Int) = when (state) {
    WidgetLink.Off -> context.getString(R.string.sharing_link_off)
    WidgetLink.Starts -> context.getString(R.string.widget_link_starts)
    WidgetLink.Waiting -> context.getString(R.string.widget_link_waiting)
    WidgetLink.Ready -> context.getString(R.string.widget_link_ready)
    WidgetLink.InUse -> context.resources.getQuantityString(R.plurals.sharing_devices, devices, devices)
    WidgetLink.Problem -> context.getString(R.string.widget_link_problem)
}

private fun toggle(link: TetherLink) = actionRunCallback<ToggleShareAction>(actionParametersOf(ToggleShareAction.LINK to link.name))

/** A share switch as a chip: filled while that sharing is on. Wide, it names the link and says
 *  what it's doing; narrow, the icon and a device count. */
@Composable
private fun LinkChip(context: Context, link: TetherLink, on: Boolean, state: WidgetLink, devices: Int, wide: Boolean, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    val hotspot = link == TetherLink.Hotspot
    val background = if (on) colors.primary else colors.surface
    val content = if (on) colors.onPrimary else colors.onSurfaceVariant
    val name = context.getString(if (hotspot) R.string.share_hotspot_switch else R.string.share_switch)
    val status = linkStatus(context, state, devices)
    Row(modifier.height(40.dp).background(background).cornerRadius(20.dp).padding(horizontal = if (wide) 10.dp else 0.dp)
        .clickable(toggle(link)).semantics { contentDescription = "$name: $status" },
        verticalAlignment = Alignment.CenterVertically, horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
        Image(ImageProvider(if (hotspot) R.drawable.ic_hotspot else R.drawable.ic_usb), contentDescription = null,
            colorFilter = ColorFilter.tint(content), modifier = GlanceModifier.size(20.dp))
        if (wide) {
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

/** Android's hotspot screen: apps can't switch the hotspot itself on or off. */
@Composable
private fun HotspotSettings(context: Context) {
    val colors = GlanceTheme.colors
    Row(GlanceModifier.size(40.dp).cornerRadius(20.dp).background(colors.secondaryContainer)
        .clickable(actionStartActivity(shortcut(context, Controls.OPEN_TETHERING)))
        .semantics { contentDescription = context.getString(R.string.widget_hotspot_settings) },
        verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(ImageProvider(R.drawable.ic_settings), contentDescription = null, colorFilter = ColorFilter.tint(colors.onSecondaryContainer),
            modifier = GlanceModifier.size(20.dp))
    }
}
