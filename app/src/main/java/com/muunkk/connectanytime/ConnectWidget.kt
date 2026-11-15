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
    /** Android's own hotspot (HotspotWatch), drawn on the hotspot-settings button. */
    val androidHotspot: AndroidHotspot = AndroidHotspot.Unknown,
)

internal fun widgetModel(state: VpnState, share: TetherShare, usage: TetherUsage, usbOn: Boolean, hotspotOn: Boolean, uplink: Uplink?,
                         androidHotspot: AndroidHotspot = AndroidHotspot.Unknown): WidgetModel {
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
        hotspotOn, hotspot, if (hotspot == WidgetLink.InUse) usage.hotspot.devices.size else 0,
        // Carried as HotspotWatch decided it; nothing here overrides its Unknown (a restriction, or
        // the hotspot settings just opened) with what the proxy last saw.
        androidHotspot)
}

/**
 * The home screen widget: the VPN's state and a power button, a button each for sharing over
 * USB and over the hotspot, and one for Android's hotspot settings. Android doesn't let apps
 * turn the hotspot itself on or off, so that button opens the screen where you can. It also
 * shows whether the hotspot is on, as [HotspotWatch] last heard from Android: a dot when on, a
 * slashed sign when off, and the plain sign when that isn't known. Off is often not known (see
 * HotspotWatch), so plain is the usual look with the hotspot off; it never claims a state it
 * hasn't seen.
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
        app.hotspot.refresh() // what Android holds now, in case broadcasts were held while the app was idle
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
                store.shareWithTether, store.shareOverHotspot, ConnectApp.uplink.value, ConnectApp.androidHotspot.value)
        }

        /** What the widget shows, as one value. Byte counts don't reach it, so traffic doesn't redraw. */
        @OptIn(FlowPreview::class)
        internal fun changes(app: ConnectApp): Flow<WidgetModel> =
            // combine takes five typed flows, so the switches and Android's hotspot come as one.
            combine(ConnectApp.state, ConnectApp.tetherShare, ConnectApp.tetherUsage, ConnectApp.uplink,
                combine(sharePrefs(app.store), ConnectApp.androidHotspot, ::Pair)) { state, share, usage, uplink, (prefs, hotspot) ->
                widgetModel(state, share, usage, prefs.first, prefs.second, uplink, hotspot)
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
    /** The periodic and launcher updates: read the hotspot afresh, as a running drawing session
     *  doesn't call provideGlance again. */
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        runCatching { (context.applicationContext as ConnectApp).hotspot.refresh() }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
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
    // What fits, checked for every state, width and font in WidgetTest:
    // - wide: the buttons' name and status at 100%; just the status (the icon names the link)
    //   up to 120%; just the icon above that. From 130% titles also shorten and the second line goes;
    // - compact: short titles and second lines at 100%, no second line above that, and from
    //   130% the power button moves into the button row so the title gets the full width.
    val enlarged = fontScale > 1.0f
    val large = fontScale >= 1.3f
    val background = if (connected) colors.primaryContainer else colors.widgetBackground
    val powerBelow = !wide && large
    val words = when {
        !wide || fontScale > 1.2f -> Words.None // from 121%, "9+ using" no longer fits the 280 dp layout
        enlarged -> Words.Status
        else -> Words.Both
    }
    Scaffold(backgroundColor = background, horizontalPadding = 12.dp, modifier = GlanceModifier.padding(vertical = 8.dp)) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Status(model, short = !wide || large, detail = if (wide) !large else !enlarged, context, GlanceModifier.defaultWeight())
                if (!powerBelow) { Spacer(GlanceModifier.width(8.dp)); Power(model, context, GlanceModifier.size(TARGET.dp)) }
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (powerBelow) { Power(model, context, GlanceModifier.defaultWeight().height(TARGET.dp), inRow = true); Spacer(GlanceModifier.width(6.dp)) }
                LinkButton(context, TetherLink.Usb, model.usbOn, model.usb, model.usbDevices, words, !large, background, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                LinkButton(context, TetherLink.Hotspot, model.hotspotOn, model.hotspot, model.hotspotDevices, words, !large, background, GlanceModifier.defaultWeight(),
                    // The settings button isn't drawn here, so its state goes to TalkBack on the share button, whose "Waiting" it explains.
                    androidHotspot = if (powerBelow) model.androidHotspot else AndroidHotspot.Unknown)
                if (!powerBelow) {
                    Spacer(GlanceModifier.width(6.dp))
                    HotspotSettings(context, connected, model.androidHotspot, if (wide) GlanceModifier.size(TARGET.dp) else GlanceModifier.defaultWeight().height(TARGET.dp))
                }
            }
        }
    }
}

@Composable
private fun Status(model: WidgetModel, short: Boolean, detail: Boolean, context: Context, modifier: GlanceModifier) {
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
    val second = when {
        connected && model.uplink != null -> context.getString(if (short) uplinkShortName(model.uplink) else uplinkName(model.uplink))
        connected -> context.getString(R.string.widget_vpn_on)
        model.phase == Phase.Connecting || model.phase == Phase.Reconnecting -> context.getString(R.string.widget_working)
        model.phase == Phase.Failed -> context.getString(if (short) R.string.widget_short_try_again else R.string.widget_try_again)
        else -> context.getString(if (short) R.string.widget_short_tap_power else R.string.widget_vpn_off)
    }
    // Tapping the status opens the app, as most widgets do.
    Column(modifier.clickable(shortcut(context, Controls.OPEN_APP)).semantics { contentDescription = "$title. " + context.getString(R.string.widget_open_app) }) {
        Text(title, maxLines = 1, style = TextStyle(color = onColor, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        if (detail) Row(verticalAlignment = Alignment.CenterVertically) {
            if (connected && model.uplink != null) {
                Image(ImageProvider(uplinkIcon(model.uplink)), contentDescription = null,
                    colorFilter = ColorFilter.tint(onColor), modifier = GlanceModifier.size(14.dp))
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(second, maxLines = 1, style = TextStyle(color = onColor, fontSize = 12.sp))
        }
    }
}

/** Connect or Disconnect: drawn for the state now, decided by ShortcutActivity when tapped. */
@Composable
private fun Power(model: WidgetModel, context: Context, modifier: GlanceModifier, inRow: Boolean = false) {
    val colors = GlanceTheme.colors
    val busy = model.phase == Phase.Connected || model.phase == Phase.Connecting || model.phase == Phase.Reconnecting
    // In the button row (compact, large font), idle power takes tertiary so it isn't mistaken
    // for a share button that's on.
    val fill = when { busy -> colors.error; inRow -> colors.tertiary; else -> colors.primary }
    val ink = when { busy -> colors.onError; inRow -> colors.onTertiary; else -> colors.onPrimary }
    Box(modifier.cornerRadius((TARGET / 2).dp).background(fill)
        .clickable(shortcut(context, Controls.TOGGLE))
        .semantics { contentDescription = context.getString(if (busy) R.string.disconnect else R.string.connect) },
        contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_power), contentDescription = null,
            colorFilter = ColorFilter.tint(ink), modifier = GlanceModifier.size(22.dp))
    }
}

private fun linkStatus(context: Context, state: WidgetLink, devices: Int) = when (state) {
    WidgetLink.Off -> context.getString(R.string.sharing_link_off)
    WidgetLink.Starts -> context.getString(R.string.widget_link_starts)
    WidgetLink.Waiting -> context.getString(R.string.widget_link_waiting)
    WidgetLink.Ready -> context.getString(R.string.widget_link_ready)
    WidgetLink.InUse -> if (devices > 9) context.getString(R.string.widget_link_using_many) else context.getString(R.string.widget_link_using, devices)
    WidgetLink.Problem -> context.getString(R.string.widget_link_problem)
}

/** What a share button says besides its icon. */
private enum class Words { Both, Status, None }

/**
 * A share button. On: filled. Off: outlined, so on and off differ by shape as well as colour.
 * It says as much as fits (see Content): name and status, the status alone, or the icon with
 * how many are using it.
 */
@Composable
private fun LinkButton(context: Context, link: TetherLink, on: Boolean, state: WidgetLink, devices: Int, words: Words, count: Boolean,
                       background: ColorProvider, modifier: GlanceModifier, androidHotspot: AndroidHotspot = AndroidHotspot.Unknown) {
    val colors = GlanceTheme.colors
    val hotspot = link == TetherLink.Hotspot
    val content = if (on) colors.onPrimary else colors.onSurfaceVariant
    val name = context.getString(if (hotspot) R.string.share_hotspot_switch else R.string.share_switch)
    val status = linkStatus(context, state, devices)
    val described = context.getString(if (on) R.string.widget_share_on else R.string.widget_share_off, name, status)
    val description = when (androidHotspot) {
        AndroidHotspot.On -> context.getString(R.string.widget_share_hotspot_state_on, described)
        AndroidHotspot.Off -> context.getString(R.string.widget_share_hotspot_state_off, described)
        AndroidHotspot.Unknown -> described
    }
    // The outline is the outer box's colour showing round a 1.5 dp inset.
    Box(modifier.height(TARGET.dp).cornerRadius((TARGET / 2).dp).background(if (on) colors.primary else colors.outline)
        .clickable(shortcut(context, if (hotspot) Controls.SHARE_HOTSPOT else Controls.SHARE_USB))
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Row(GlanceModifier.fillMaxSize().padding(if (on) 0.dp else 1.5.dp), verticalAlignment = Alignment.CenterVertically) {
            // Off: the widget's own background inside the outline, so it reads as outlined on either background.
            Row(GlanceModifier.fillMaxSize().cornerRadius((TARGET / 2).dp).background(if (on) colors.primary else background)
                .padding(horizontal = if (words != Words.None) 10.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalAlignment = if (words != Words.None) Alignment.Start else Alignment.CenterHorizontally) {
                Image(ImageProvider(if (hotspot) R.drawable.ic_hotspot else R.drawable.ic_usb), contentDescription = null,
                    colorFilter = ColorFilter.tint(content), modifier = GlanceModifier.size(20.dp))
                if (words == Words.Both) {
                    Spacer(GlanceModifier.width(6.dp))
                    Column {
                        Text(context.getString(if (hotspot) R.string.widget_hotspot else R.string.widget_usb), maxLines = 1,
                            style = TextStyle(color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        Text(status, maxLines = 1, style = TextStyle(color = content, fontSize = 11.sp))
                    }
                } else if (words == Words.Status) {
                    // The icon names the link; the status (Waiting, Error…) is what's worth reading.
                    Spacer(GlanceModifier.width(6.dp))
                    Text(status, maxLines = 1, style = TextStyle(color = content, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                } else if (count && state == WidgetLink.InUse) {
                    Spacer(GlanceModifier.width(4.dp))
                    Text(if (devices > 9) "9+" else devices.toString(), maxLines = 1, style = TextStyle(color = content, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/**
 * Android's hotspot screen (apps can't switch the hotspot itself): the hotspot sign with an
 * "opens elsewhere" arrow, so it doesn't read as this app's settings.
 *
 * Android's hotspot state is in the sign only: a dot when on, a slash when off, the plain sign
 * when not known. The container never changes with it: filled and outlined already mean this
 * app's sharing on and off next to it, and in the brand palette (Android 10–11) tertiary is
 * nearly the share buttons' blue, so a fill would read as "sharing". A dot, a slash and nothing
 * differ by shape, which holds in any palette and with the square corners of Android 10–11.
 */
@Composable
private fun HotspotSettings(context: Context, connected: Boolean, state: AndroidHotspot, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    // secondaryContainer all but vanishes on the connected widget's primaryContainer.
    val fill = if (connected) colors.surfaceVariant else colors.secondaryContainer
    val ink = if (connected) colors.onSurfaceVariant else colors.onSecondaryContainer
    val description = context.getString(when (state) {
        AndroidHotspot.On -> R.string.widget_hotspot_settings_on
        AndroidHotspot.Off -> R.string.widget_hotspot_settings_off
        AndroidHotspot.Unknown -> R.string.widget_hotspot_settings
    })
    Box(modifier.cornerRadius((TARGET / 2).dp).background(fill)
        .clickable(shortcut(context, Controls.OPEN_TETHERING))
        .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Box(GlanceModifier.size(26.dp)) {
            Image(ImageProvider(if (state == AndroidHotspot.Off) R.drawable.ic_hotspot_off else R.drawable.ic_hotspot), contentDescription = null,
                colorFilter = ColorFilter.tint(ink), modifier = GlanceModifier.size(20.dp))
            if (state == AndroidHotspot.On) Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                // A 7 dp dot ringed in the button's own fill, so it reads as a badge on either background.
                Box(GlanceModifier.size(10.dp), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.ic_dot), contentDescription = null, colorFilter = ColorFilter.tint(fill), modifier = GlanceModifier.size(10.dp))
                    Image(ImageProvider(R.drawable.ic_dot), contentDescription = null, colorFilter = ColorFilter.tint(colors.tertiary), modifier = GlanceModifier.size(7.dp))
                }
            }
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                // A plate in the button's fill under the arrow, so the off sign's slash doesn't show through it.
                Box(GlanceModifier.size(13.dp).cornerRadius(2.dp).background(fill), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.ic_open_external), contentDescription = null,
                        colorFilter = ColorFilter.tint(ink), modifier = GlanceModifier.size(12.dp))
                }
            }
        }
    }
}
