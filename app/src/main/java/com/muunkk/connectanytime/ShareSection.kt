@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Sharing the VPN with a Mac on USB tethering, or an iPhone or Mac on the phone's hotspot.
 * Android never routes tethered traffic through a VPN app, so the app runs a proxy on those
 * connections (see TetherProxy) and the other device points its proxy settings at it.
 *
 * USB and hotspot have separate switches: anyone who joins the hotspot can use the proxy,
 * which a USB cable doesn't allow, so turning on one never turns on the other. Each switch
 * is followed by its setup card while that link is sharing.
 */
@Composable
internal fun ShareSection(store: Store) {
    val context = LocalContext.current
    val share by ConnectApp.tetherShare.collectAsState()
    // The status card and the widget change these too.
    val usbOn = rememberShares(store, TetherLink.Usb)
    val hotspotOn = rememberShares(store, TetherLink.Hotspot)
    val active = share as? TetherShare.Active
    val usb = (active?.usb as? TetherShare.Link.Sharing)?.takeIf { usbOn }
    val hotspot = (active?.hotspot as? TetherShare.Link.Sharing)?.takeIf { hotspotOn }
    val port = active?.port ?: 1080
    // Once a device may have been set up, the way back stays in reach: after sharing or the VPN
    // stops, its proxies point at nothing and its browsing fails until they're off.
    var shared by remember { mutableStateOf(store.sharedBefore) }
    val sharingAny = usb != null || hotspot != null
    LaunchedEffect(sharingAny) { if (sharingAny && !shared) { shared = true; store.sharedBefore = true } }
    var sharedHotspot by remember { mutableStateOf(store.sharedHotspotBefore) }
    LaunchedEffect(hotspot != null) { if (hotspot != null && !sharedHotspot) { sharedHotspot = true; store.sharedHotspotBefore = true } }

    val rows = buildList<@Composable (Int, Int) -> Unit> {
        add { i, n -> ShareSwitch(context, TetherLink.Usb, usbOn, share, i, n) { store.shareWithTether = it } }
        if (usb != null) add { i, n -> FieldSegment(i, n) { MacSetup(context, usb.address, port) } }
        add { i, n -> ShareSwitch(context, TetherLink.Hotspot, hotspotOn, share, i, n) { store.shareOverHotspot = it; if (it) store.hotspotExplained = true } }
        if (hotspot != null) add { i, n -> FieldSegment(i, n) { HotspotSetup(context, hotspot.address, port, offButton = usb == null) } }
    }

    SectionTitle(R.string.share_title)
    Group(rows.size) { index -> rows[index](index, rows.size) }
    AnimatedVisibility(shared && !sharingAny) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(if (sharedHotspot) R.string.share_stopped_help_hotspot else R.string.share_stopped_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CopyButton(context, R.string.share_copy_off, macOffCommand(1080), tonal = false)
        }
    }
    AnimatedVisibility(usbOn || hotspotOn) { Footnote(R.string.share_footnote) }
}

/** One link's switch, saying what that link is doing right now. */
@Composable
private fun ShareSwitch(context: Context, kind: TetherLink, on: Boolean, share: TetherShare, index: Int, count: Int,
                        onChange: (Boolean) -> Unit) {
    val hotspot = kind == TetherLink.Hotspot
    val link = (share as? TetherShare.Active)?.let { if (hotspot) it.hotspot else it.usb }
    SegmentedListItem(colors = segmentColors(), onClick = { onChange(!on) }, shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = Modifier.testTag(if (hotspot) "share-hotspot" else "share")
            .semantics { role = Role.Switch; toggleableState = ToggleableState(on) },
        leadingContent = { Icon(painterResource(if (hotspot) R.drawable.ic_hotspot else R.drawable.ic_usb), contentDescription = null) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        supportingContent = {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(when {
                    !on -> stringResource(if (hotspot) R.string.share_hotspot_off_help else R.string.share_off_help)
                    share == TetherShare.WaitingForVpn -> stringResource(R.string.share_waiting_vpn)
                    link is TetherShare.Link.Sharing ->
                        stringResource(if (hotspot) R.string.share_sharing_hotspot else R.string.share_sharing_usb, (share as TetherShare.Active).port)
                    link == TetherShare.Link.PortInUse -> stringResource(R.string.share_port_in_use)
                    link == TetherShare.Link.Unknown -> stringResource(R.string.share_hotspot_unknown)
                    link == TetherShare.Link.Waiting -> stringResource(if (hotspot) R.string.share_waiting_hotspot else R.string.share_waiting)
                    else -> stringResource(R.string.share_vpn_off)
                })
                if (on && link == TetherShare.Link.Waiting) {
                    TextButton(onClick = { openTethering(context) }, shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.open_tethering))
                    }
                }
            }
        }) { Text(stringResource(if (hotspot) R.string.share_hotspot_switch else R.string.share_switch)) }
}

@Composable
private fun MacSetup(context: Context, ip: String, port: Int) {
    val address = "$ip:$port"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CardLabel(R.drawable.ic_usb, R.string.share_proxy_label)
            // The one thing to type on the Mac: large, monospace and selectable.
            SelectionContainer { Big(address, Modifier.testTag("share-address")) }
        }
        Text(stringResource(R.string.share_mac_steps, port), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            CopyButton(context, R.string.share_copy_address, address, tonal = true)
            CopyButton(context, R.string.share_copy_mac_command, macCommand(ip, port), tonal = false)
            CopyButton(context, R.string.share_copy_off, macOffCommand(port), tonal = false)
        }
        Text(stringResource(R.string.share_command_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** For an iPhone on the hotspot, which you set up by typing, not pasting: its Server and Port
 *  fields side by side, as iOS asks for them. A Mac on the hotspot can still paste the command. */
@Composable
private fun HotspotSetup(context: Context, ip: String, port: Int, offButton: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardLabel(R.drawable.ic_hotspot, R.string.share_hotspot_label)
        SelectionContainer {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(R.string.share_server, ip, Modifier.testTag("share-hotspot-address"))
                Field(R.string.share_port, port.toString())
            }
        }
        Text(stringResource(R.string.share_iphone_steps, port), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.share_iphone_off), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            CopyButton(context, R.string.share_copy_mac_command, macCommand(ip, port), tonal = false)
            // Same command as on the USB card (it finds the proxy by port), so shown once.
            if (offButton) CopyButton(context, R.string.share_copy_off, macOffCommand(port), tonal = false)
        }
        Text(stringResource(R.string.share_hotspot_command_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Names the connection a card is for, with its icon, so USB and hotspot cards tell apart at a glance. */
@Composable
private fun CardLabel(icon: Int, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Field(label: Int, value: String, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Big(value, modifier)
    }
}

@Composable
private fun Big(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.headlineSmallEmphasized, fontFamily = FontFamily.Monospace, modifier = modifier)

/** Copies `text`, and says so on the button for a moment (Android 13+ also shows its own notice). */
@Composable
private fun CopyButton(context: Context, label: Int, text: String, tonal: Boolean) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val onClick = {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.getString(label), text))
        copied = true
    }
    val content: @Composable RowScope.() -> Unit = {
        Icon(painterResource(if (copied) R.drawable.ic_check_circle else R.drawable.ic_copy), contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(if (copied) R.string.share_copied else label))
    }
    val modifier = Modifier.heightIn(min = 48.dp)
    if (tonal) FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, content = content)
    else OutlinedButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, content = content)
}

/**
 * Terminal one-liners for the Mac. `ip` is an IPv4 address string and `port` an Int, so
 * neither can inject anything. Written with § for the shell's $ (Kotlin would read $ as a
 * template), and checked in zsh and bash against a fake networksetup (see MacCommandTest).
 *
 * Both pick the enabled network service to change, refuse if more than one matches rather
 * than guess, check every networksetup call, and only report success when all three worked.
 */
private const val FIND = "ip=@IP@; port=@PORT@; s=; n=0; " +
    "while IFS= read -r v; do case \"§v\" in '*'*) continue;; esac; " +
    "if networksetup @MATCH@; then s=§v; n=§((n+1)); fi; " +
    "done <<< \"§(networksetup -listallnetworkservices | tail -n +2)\"; " +
    "if [ §n -eq 0 ]; then echo \"@NONE@\"; " +
    "elif [ §n -gt 1 ]; then echo \"More than one network service matches. Change the proxies in System Settings instead.\"; " +
    "else ok=1; for p in webproxy securewebproxy socksfirewallproxy; do sudo networksetup @SET@ || { ok=0; break; }; done; " +
    "if [ §ok = 1 ]; then echo \"@DONE@ §s\"; else echo \"Couldn't change the proxies on §s.\"; fi; fi"

private fun script(ip: String, port: Int, match: String, set: String, none: String, done: String) = FIND
    .replace("@MATCH@", match).replace("@SET@", set).replace("@NONE@", none).replace("@DONE@", done)
    .replace("@IP@", ip).replace("@PORT@", port.toString()).replace('§', '$')

/** Sets the web, secure web and SOCKS proxies on the service whose router is this phone
 *  (the tether or hotspot address is the Mac's gateway), so you needn't know what macOS
 *  named it: the phone's USB service, or Wi-Fi when the Mac is on the hotspot. */
internal fun macCommand(ip: String, port: Int) = script(ip, port,
    match = "-getinfo \"§v\" | grep -qxF \"Router: §ip\"",
    set = "-set§p \"§s\" \"§ip\" \"§port\"",
    none = "Couldn't find the phone's connection. Is USB tethering or the hotspot on?",
    done = "Proxies set on")

/** Turns those proxies off again. Finds services by their SOCKS proxy being on with this
 *  port, not by the phone's address: that still works once the phone is unplugged, and
 *  after Android has moved the tether to a new address. Unlike setting them, it turns off
 *  every match, since a Mac that used both USB and the hotspot has the proxy on both. */
internal fun macOffCommand(port: Int) = ("port=$port; n=0; ok=1; d=; " +
    "while IFS= read -r v; do case \"§v\" in '*'*) continue;; esac; " +
    "if networksetup -getsocksfirewallproxy \"§v\" | grep -qxF \"Enabled: Yes\" && networksetup -getsocksfirewallproxy \"§v\" | grep -qxF \"Port: §port\"; then " +
    "n=§((n+1)); d=\"§d§{d:+, }§v\"; " +
    "for p in webproxy securewebproxy socksfirewallproxy; do sudo networksetup -set§{p}state \"§v\" off || { ok=0; break; }; done; " +
    "fi; done <<< \"§(networksetup -listallnetworkservices | tail -n +2)\"; " +
    "if [ §n -eq 0 ]; then echo \"No network service uses this proxy. Nothing to turn off.\"; " +
    "elif [ §ok = 1 ]; then echo \"Proxies turned off on §d\"; else echo \"Couldn't change the proxies on §d.\"; fi").replace('§', '$')

/** Android's tethering screen; not a public action everywhere, so fall back to network settings. */
internal fun openTethering(context: Context) {
    // Its own task: from the widget's ShortcutActivity, Settings mustn't land in that hidden task.
    runCatching { context.startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
