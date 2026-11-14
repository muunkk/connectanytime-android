@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import android.content.Context
import android.content.SharedPreferences
import android.text.format.Formatter
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** The pref behind each share switch. */
internal fun shareKey(link: TetherLink) = if (link == TetherLink.Usb) "shareWithTether" else "shareOverHotspot"

internal fun Store.setShares(link: TetherLink, on: Boolean) {
    if (link == TetherLink.Usb) shareWithTether = on else shareOverHotspot = on
}

/**
 * A share switch's saved value, kept in step wherever else it changes: the status card, the
 * Share section and the home screen widget all write the same pref.
 */
@Composable
internal fun rememberShares(store: Store, link: TetherLink): Boolean {
    var value by remember(store, link) { mutableStateOf(store.shares(link)) }
    DisposableEffect(store, link) {
        // Held here: SharedPreferences keeps its listeners only weakly.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == shareKey(link)) value = store.shares(link)
        }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        value = store.shares(link)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

/**
 * Under the connection status: whether the VPN is being shared, with whom, and a switch for
 * each link, so you can see at a glance that a device is really using it. Shown while the VPN
 * is up or sharing is turned on; set-up details stay in the Share section below.
 */
@Composable
internal fun SharingCard(store: Store, vpn: VpnState, modifier: Modifier = Modifier) {
    val share by ConnectApp.tetherShare.collectAsState()
    val usage by ConnectApp.tetherUsage.collectAsState()
    val uplink by ConnectApp.uplink.collectAsState()
    val usbOn = rememberShares(store, TetherLink.Usb)
    val hotspotOn = rememberShares(store, TetherLink.Hotspot)
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(vpn.connected || usbOn || hotspotOn, modifier,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())) {
        // While the VPN service isn't running nothing is published; read the switches instead.
        val shown = if (!usbOn && !hotspotOn) TetherShare.Off else share
        val active = shown as? TetherShare.Active
        val devices = (if (usbOn) usage.usb.devices else emptyList()) + (if (hotspotOn) usage.hotspot.devices else emptyList())
        val inUse = active != null && devices.isNotEmpty()
        val colors = MaterialTheme.colorScheme
        val container by animateColorAsState(if (inUse) colors.secondaryContainer else colors.surfaceContainerHigh,
            motion.slowEffectsSpec(), label = "sharingContainer")
        val content by animateColorAsState(if (inUse) colors.onSecondaryContainer else colors.onSurface,
            motion.slowEffectsSpec(), label = "sharingContent")
        Surface(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("sharing-card"), shape = MaterialTheme.shapes.extraLarge,
            color = container, contentColor = content) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val summary = when {
                    shown == TetherShare.Off -> stringResource(R.string.sharing_summary_off)
                    shown == TetherShare.VpnNotConnected -> stringResource(R.string.sharing_summary_vpn_off)
                    shown == TetherShare.WaitingForVpn -> stringResource(R.string.sharing_summary_paused)
                    devices.isNotEmpty() -> pluralStringResource(R.plurals.sharing_summary_devices, devices.distinct().size, devices.distinct().size)
                    active?.usb is TetherShare.Link.Sharing || active?.hotspot is TetherShare.Link.Sharing -> stringResource(R.string.sharing_summary_ready)
                    else -> stringResource(R.string.sharing_summary_waiting)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    // Read as one heading. Not a live region: device counts change by themselves (a
                    // device drops off a minute after it goes quiet), and that isn't worth an interruption.
                    modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
                    Badge(inUse, active != null && (active.usb is TetherShare.Link.Sharing || active.hotspot is TetherShare.Link.Sharing))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.sharing_title), style = MaterialTheme.typography.titleLargeEmphasized)
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (vpn.connected) uplink?.let { UplinkLine(it) }
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    LinkRow(TetherLink.Usb, usbOn, shown, usage.usb, 0) { store.setShares(TetherLink.Usb, it) }
                    LinkRow(TetherLink.Hotspot, hotspotOn, shown, usage.hotspot, 1) { store.setShares(TetherLink.Hotspot, it) }
                }
            }
        }
    }
}

internal fun uplinkIcon(uplink: Uplink) = when (uplink) {
    Uplink.MobileData -> R.drawable.ic_cell
    Uplink.WiFi -> R.drawable.ic_wifi
    Uplink.Ethernet -> R.drawable.ic_ethernet
    Uplink.Other -> R.drawable.ic_vpn
}
internal fun uplinkName(uplink: Uplink) = when (uplink) {
    Uplink.MobileData -> R.string.uplink_mobile
    Uplink.WiFi -> R.string.uplink_wifi
    Uplink.Ethernet -> R.string.uplink_ethernet
    Uplink.Other -> R.string.uplink_other
}

/** What the phone is online over: everything shared leaves through the VPN on that network.
 *  A plain icon and label, not a chip, since it isn't something to tap. */
@Composable
private fun UplinkLine(uplink: Uplink) {
    Row(Modifier.testTag("sharing-uplink").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(uplinkIcon(uplink)), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(uplinkName(uplink)), style = MaterialTheme.typography.labelLarge)
    }
}

/** The card's emblem: a sunny cookie while devices are on it, a soft square while ready, a circle when off. */
@Composable
private fun Badge(inUse: Boolean, sharing: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (shape, background, tint) = when {
        inUse -> Triple(MaterialShapes.Cookie7Sided.toShape(), colors.secondary, colors.onSecondary)
        sharing -> Triple(MaterialShapes.Square.toShape(), colors.secondaryContainer, colors.onSecondaryContainer)
        else -> Triple(MaterialShapes.Circle.toShape(), colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
    Box(Modifier.size(48.dp).background(background, shape), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_share), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** One link: what it's doing, who is on it, and its switch. A segmented list item like the
 *  app's other switches, so the whole row toggles with the same press shape and ripple. */
@Composable
private fun LinkRow(link: TetherLink, on: Boolean, share: TetherShare, usage: LinkUsage, index: Int, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val hotspot = link == TetherLink.Hotspot
    val state = (share as? TetherShare.Active)?.let { if (hotspot) it.hotspot else it.usb }
    val port = (share as? TetherShare.Active)?.port ?: 1080
    val sharing = on && state is TetherShare.Link.Sharing
    val status = when {
        // The hotspot's consequence, where it can be turned on without reaching the Share section.
        !on -> stringResource(if (hotspot) R.string.sharing_link_off_hotspot else R.string.sharing_link_off)
        share == TetherShare.VpnNotConnected -> stringResource(R.string.sharing_link_vpn_off)
        share == TetherShare.WaitingForVpn -> stringResource(R.string.sharing_link_paused)
        state is TetherShare.Link.Sharing -> stringResource(R.string.sharing_link_on, state.address, port)
        state == TetherShare.Link.PortInUse -> stringResource(R.string.sharing_link_port, port)
        state == TetherShare.Link.Unknown -> stringResource(R.string.sharing_link_unknown)
        else -> stringResource(if (hotspot) R.string.sharing_link_waiting_hotspot else R.string.sharing_link_waiting_usb)
    }
    val title = stringResource(if (hotspot) R.string.sharing_hotspot else R.string.sharing_usb)
    val colors = MaterialTheme.colorScheme
    SegmentedListItem(onClick = { onChange(!on) }, shapes = ListItemDefaults.segmentedShapes(index, 2),
        colors = ListItemDefaults.segmentedColors(
            containerColor = if (sharing && usage.devices.isNotEmpty()) colors.surfaceContainerLowest else colors.surfaceContainer),
        modifier = Modifier.testTag(if (hotspot) "sharing-hotspot" else "sharing-usb")
            .semantics { role = Role.Switch; toggleableState = ToggleableState(on) },
        leadingContent = {
            Icon(painterResource(if (hotspot) R.drawable.ic_hotspot else R.drawable.ic_usb), contentDescription = null,
                tint = if (sharing) colors.primary else colors.onSurfaceVariant)
        },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Announced when it changes; the traffic below changes every few seconds and isn't.
                Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                AnimatedVisibility(sharing) { Usage(context, usage) }
            }
        }) { Text(title) }
}

@Composable
private fun Usage(context: Context, usage: LinkUsage) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (usage.devices.isEmpty()) {
            Text(stringResource(R.string.sharing_no_devices), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        } else {
            val count = pluralStringResource(R.plurals.sharing_devices, usage.devices.size, usage.devices.size)
            val connections = pluralStringResource(R.plurals.sharing_connections, usage.connections, usage.connections)
            Text("$count · $connections", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text(usage.devices.joinToString(", "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant)
        }
        if (usage.down > 0 || usage.up > 0) {
            Text(stringResource(R.string.sharing_traffic, Formatter.formatShortFileSize(context, usage.down),
                Formatter.formatShortFileSize(context, usage.up)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
