@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
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
 * Sharing the VPN with a USB-tethered Mac. Android never routes tethered traffic through
 * a VPN app, so the app runs a proxy on the USB connection (see TetherProxy) and the Mac
 * points its proxy settings at it. This section turns that on and tells you what to set.
 */
@Composable
internal fun ShareSection(store: Store) {
    val context = LocalContext.current
    val share by ConnectApp.tetherShare.collectAsState()
    var on by remember { mutableStateOf(store.shareWithTether) }
    val sharing = share as? TetherShare.Sharing
    val count = if (on && sharing != null) 2 else 1

    SectionTitle(R.string.share_title)
    Group(count) { index ->
        val shapes = ListItemDefaults.segmentedShapes(index, count)
        if (index == 0) {
            SegmentedListItem(colors = segmentColors(), onClick = { on = !on; store.shareWithTether = on }, shapes = shapes,
                modifier = Modifier.testTag("share").semantics { role = Role.Switch; toggleableState = ToggleableState(on) },
                leadingContent = { Icon(painterResource(R.drawable.ic_usb), contentDescription = null) },
                trailingContent = { Switch(checked = on, onCheckedChange = null) },
                supportingContent = {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(when {
                            !on -> stringResource(R.string.share_off_help)
                            sharing != null -> stringResource(R.string.share_sharing, sharing.address, sharing.port)
                            share == TetherShare.WaitingForUsbTether -> stringResource(R.string.share_waiting)
                            else -> stringResource(R.string.share_vpn_off)
                        })
                        if (on && share == TetherShare.WaitingForUsbTether) {
                            TextButton(onClick = { openTethering(context) }, shapes = ButtonDefaults.shapes(),
                                contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.open_tethering))
                            }
                        }
                    }
                }) { Text(stringResource(R.string.share_switch)) }
        } else if (sharing != null) {
            FieldSegment(1, 2) { MacSetup(context, sharing) }
        }
    }
    AnimatedVisibility(on) { Footnote(R.string.share_footnote) }
}

@Composable
private fun MacSetup(context: Context, sharing: TetherShare.Sharing) {
    val address = "${sharing.address}:${sharing.port}"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.share_proxy_label), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The one thing to type on the Mac: large, monospace and selectable.
            Text(address, style = MaterialTheme.typography.headlineSmallEmphasized, fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("share-address"))
        }
        Text(stringResource(R.string.share_mac_steps, sharing.port), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            CopyButton(context, R.string.share_copy_address, address, tonal = true)
            CopyButton(context, R.string.share_copy_command, macCommand(sharing.address, sharing.port), tonal = false)
        }
        Text(stringResource(R.string.share_command_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
 * A Terminal one-liner that sets the Mac's web, secure web and SOCKS proxies on the
 * network service whose router is this phone (the tether address is the Mac's gateway),
 * so you don't need to know what macOS named the phone's connection.
 */
internal fun macCommand(ip: String, port: Int) =
    "ip=$ip; port=$port; s=\$(networksetup -listallnetworkservices | tail -n +2 | sed 's/^\\*//' | " +
        "while IFS= read -r n; do networksetup -getinfo \"\$n\" | grep -qx \"Router: \$ip\" && echo \"\$n\"; done | head -n 1); " +
        "if [ -n \"\$s\" ]; then for p in webproxy securewebproxy socksfirewallproxy; do sudo networksetup -set\$p \"\$s\" \"\$ip\" \"\$port\"; done; " +
        "echo \"Proxies set on \$s\"; else echo \"Couldn't find the phone's connection. Is USB tethering on?\"; fi"

/** Android's tethering screen; not a public action everywhere, so fall back to network settings. */
private fun openTethering(context: Context) {
    runCatching { context.startActivity(Intent("android.settings.TETHER_SETTINGS")) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
}
