@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Manual or Automatic setup for the devices that share the VPN.
 *
 * Manual (the default) is the server and port: everything that follows the proxy goes through
 * the VPN. Automatic hands the device a proxy auto-config file from this phone (PacFile.kt):
 * the tailnet, names without dots, .local, the home network and the link's own network go
 * direct, and the rest goes through the VPN, or direct when the proxy can't be reached.
 */
@Composable
internal fun rememberProxySetup(store: Store): ProxySetup {
    var value by remember(store) { mutableStateOf(store.proxySetup) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "proxySetup") value = store.proxySetup
        }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

/** The Manual / Automatic choice as a connected button group, with what each means. */
@Composable
internal fun SetupChoice(setup: ProxySetup, home: List<Ipv4Range>, onSetup: (ProxySetup) -> Unit, onHome: (List<Ipv4Range>) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardLabel(R.drawable.ic_settings, R.string.setup_label)
        // Side by side as a connected group; stacked at large font sizes, where "Automatic"
        // would otherwise break mid-word.
        val stacked = LocalDensity.current.fontScale >= 1.3f
        val options = listOf(ProxySetup.Manual to R.string.setup_manual, ProxySetup.Automatic to R.string.setup_automatic)
        val buttons: @Composable (Modifier, Int) -> Unit = { modifier, index ->
            val (option, label) = options[index]
            ToggleButton(checked = setup == option, onCheckedChange = { if (it) onSetup(option) },
                shapes = when {
                    stacked -> ToggleButtonDefaults.shapes()
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    else -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                },
                // The unselected one needs its own fill: the default matches this card's background.
                colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton }.testTag("setup-${option.name.lowercase()}")) {
                if (setup == option) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                    Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                }
                Text(stringResource(label))
            }
        }
        if (stacked) Column(Modifier.fillMaxWidth().selectableGroup().testTag("setup-choice"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.indices.forEach { buttons(Modifier.fillMaxWidth(), it) }
        } else Row(Modifier.fillMaxWidth().selectableGroup().testTag("setup-choice"),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.indices.forEach { buttons(Modifier.weight(1f), it) }
        }
        Text(stringResource(if (setup == ProxySetup.Automatic) R.string.setup_automatic_help else R.string.setup_manual_help),
            style = MaterialTheme.typography.bodyMedium)
        AnimatedVisibility(setup == ProxySetup.Automatic) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (home.isEmpty()) stringResource(R.string.setup_direct_no_home) else stringResource(R.string.setup_direct, home.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("setup-direct"))
                if (!editing) TextButton(onClick = { editing = true }, shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_edit_home)) }
                AnimatedVisibility(editing) { HomeRangesEditor(home, onDone = { editing = false }, onSave = { onHome(it); editing = false }) }
            }
        }
    }
}

/** Edits the home network ranges in place (a field in a dialog would cover the card it explains).
 *  Save stays off until the text parses. */
@Composable
private fun HomeRangesEditor(home: List<Ipv4Range>, onDone: () -> Unit, onSave: (List<Ipv4Range>) -> Unit) {
    var text by rememberSaveable { mutableStateOf(home.joinToString(", ")) }
    val parsed = parseDirectRanges(text)
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setup_home_help), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.setup_home_title)) },
            isError = parsed == null, singleLine = true,
            supportingText = { Text(stringResource(if (parsed == null) R.string.setup_home_invalid else R.string.setup_home_example)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { parsed?.let(onSave) }),
            modifier = Modifier.fillMaxWidth().testTag("setup-home-field"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("setup-home-save")) { Text(stringResource(R.string.save)) }
            TextButton(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

/**
 * Automatic setup for one link: the URL to enter, with the steps for an iPhone on the hotspot or
 * a Mac on either link, and a warning when the link's own network overlaps a home range.
 */
@Composable
internal fun AutoSetup(context: Context, link: TetherLink, sharing: TetherShare.Link.Sharing, port: Int, home: List<Ipv4Range>, offButton: Boolean) {
    val hotspot = link == TetherLink.Hotspot
    val url = pacUrl(sharing.address, port)
    val own = Ipv4Range.parse("${sharing.address}/${sharing.prefix}")
    val overlap = own?.let { o -> home.firstOrNull { it.overlaps(o) } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardLabel(if (hotspot) R.drawable.ic_hotspot else R.drawable.ic_usb, if (hotspot) R.string.share_hotspot_label else R.string.share_proxy_label)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.setup_url), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Smaller than the Manual card's address: a URL this long would otherwise wrap.
            SelectionContainer {
                Text(url, style = MaterialTheme.typography.titleSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.testTag(if (hotspot) "setup-url-hotspot" else "setup-url-usb"))
            }
        }
        // Next to the URL: it changes what the device can reach.
        if (overlap != null && own != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("setup-overlap")) {
            Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.setup_overlap, own.toString(), overlap.toString()), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(if (hotspot) R.string.setup_iphone_steps else R.string.setup_mac_steps), style = MaterialTheme.typography.bodyMedium)
        if (hotspot) Text(stringResource(R.string.setup_iphone_note), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            CopyButton(context, R.string.setup_copy_url, url, tonal = true)
            CopyButton(context, R.string.share_copy_mac_command, macAutoCommand(sharing.address, port), tonal = false)
            if (offButton) CopyButton(context, R.string.share_copy_off, macOffCommand(port), tonal = false)
        }
        Text(stringResource(if (hotspot) R.string.share_hotspot_command_help else R.string.setup_command_help_mac),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
