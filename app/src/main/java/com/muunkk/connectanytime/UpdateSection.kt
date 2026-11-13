@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp

/** Whether Try again should show yet: only after [Updater.RETRY_AFTER], so a silent install
 *  isn't interrupted and a double tap on Install doesn't land on it. */
@Composable
private fun retryShown(update: UpdateState): Boolean {
    var shown by remember(update is UpdateState.Installing) { mutableStateOf(false) }
    LaunchedEffect(update is UpdateState.Installing) {
        if (update is UpdateState.Installing) { kotlinx.coroutines.delay(Updater.RETRY_AFTER); shown = true }
    }
    return shown && update is UpdateState.Installing
}

/** What installing does to the VPN right now, in one line. [restarts] is whether BootReceiver
 *  will start it again after the update (Controls.restartsAutomatically). */
@Composable
private fun installNote(vpn: VpnState, restarts: Boolean) = stringResource(when {
    vpn.busy && restarts -> R.string.update_note_rejoin
    vpn.busy -> R.string.update_note_off
    restarts -> R.string.update_note_starts
    else -> R.string.update_note_idle
})

/**
 * Under the status panel once an update is downloaded, since About is at the bottom of
 * the screen. Nothing shows while checking or downloading: that happens by itself.
 */
@Composable
internal fun UpdateBanner(update: UpdateState, vpn: VpnState, restarts: Boolean, onInstall: () -> Unit) {
    val release = (update as? UpdateState.Ready)?.release ?: (update as? UpdateState.Installing)?.release
    // Keep the last release while the banner animates out.
    var shown by remember { mutableStateOf(release) }
    if (release != null) shown = release
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(release != null,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())) {
        val r = shown ?: return@AnimatedVisibility
        val retry = retryShown(update)
        // Outlined on the page colour, so it never reads as part of the status panel above,
        // whatever colour that has.
        Surface(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("update-banner"), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_update), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(if (update is UpdateState.Installing) R.string.update_installing else R.string.update_banner, r.versionName),
                        style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(when {
                        retry -> stringResource(R.string.update_installing_help) + " " + stringResource(R.string.update_installing_slow)
                        update is UpdateState.Installing -> stringResource(R.string.update_installing_help)
                        else -> installNote(vpn, restarts)
                    },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (retry) {
                    OutlinedButton(onClick = onInstall, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.update_try_again))
                    }
                } else if (update is UpdateState.Installing) {
                    LoadingIndicator(Modifier.size(40.dp))
                } else {
                    Button(onClick = onInstall, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.update_install))
                    }
                }
            }
        }
    }
}

/** The update rows of the About group: [index] 1 is the check or progress, 2 the switch. */
@Composable
internal fun UpdateSegment(index: Int, count: Int, store: Store, update: UpdateState, vpn: VpnState, restarts: Boolean,
    onCheck: () -> Unit, onInstall: () -> Unit) {
    val context = LocalContext.current
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    if (index == count - 1) {
        var auto by remember { mutableStateOf(store.autoUpdate) }
        SegmentedListItem(colors = segmentColors(), onClick = { auto = !auto; store.autoUpdate = auto }, shapes = shapes,
            modifier = Modifier.testTag("update-auto").semantics { role = Role.Switch; toggleableState = ToggleableState(auto) },
            leadingContent = { Icon(painterResource(R.drawable.ic_sync), contentDescription = null) },
            trailingContent = { Switch(checked = auto, onCheckedChange = null) },
            supportingContent = { Text(stringResource(R.string.update_auto_help)) }) { Text(stringResource(R.string.update_auto)) }
        return
    }
    when (update) {
        is UpdateState.Downloading -> FieldSegment(index, count) { Downloading(update) }
        is UpdateState.Ready -> FieldSegment(index, count) { ReadyToInstall(context, update, vpn, restarts, onInstall) }
        is UpdateState.Installing -> FieldSegment(index, count) { Installing(update, onInstall) }
        else -> {
            val checking = update == UpdateState.Checking
            SegmentedListItem(colors = segmentColors(), onClick = onCheck, enabled = !checking, shapes = shapes,
                modifier = Modifier.testTag("update-check"),
                leadingContent = { Icon(painterResource(R.drawable.ic_update), contentDescription = null) },
                trailingContent = if (checking) { { LoadingIndicator(Modifier.size(32.dp)) } } else null,
                supportingContent = {
                    val failed = update as? UpdateState.Failed
                    Text(when (update) {
                        UpdateState.Checking -> stringResource(R.string.update_checking)
                        is UpdateState.UpToDate -> stringResource(R.string.update_latest, checkedAgo(update.checkedAt))
                        is UpdateState.Failed -> stringResource(update.reason)
                        else -> stringResource(R.string.update_source)
                    }, color = if (failed != null) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }) { Text(stringResource(R.string.check_updates)) }
        }
    }
}

@Composable
private fun checkedAgo(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.update_just_now)
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
private fun Downloading(update: UpdateState.Downloading) {
    Column(Modifier.testTag("update-downloading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.update_downloading, update.release.versionName), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Text(stringResource(R.string.update_percent, (update.progress * 100).toInt()), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearWavyProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Installing(update: UpdateState.Installing, onRetry: () -> Unit) {
    val retry = retryShown(update)
    Column(Modifier.testTag("update-installing"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(R.string.update_installing, update.release.versionName), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.update_installing_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        if (retry) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_installing_slow), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_try_again))
            }
        }
    }
}

@Composable
private fun ReadyToInstall(context: Context, update: UpdateState.Ready, vpn: VpnState, restarts: Boolean, onInstall: () -> Unit) {
    // Without "Install unknown apps" for this app, Android may stop to ask for it.
    val firstTime = remember { !context.packageManager.canRequestPackageInstalls() }
    Column(Modifier.testTag("update-ready"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.update_ready, update.release.versionName), style = MaterialTheme.typography.bodyLarge)
            update.note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Text(installNote(vpn, restarts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (firstTime) Text(stringResource(R.string.update_first_time), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onInstall, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp).testTag("update-install")) {
                Icon(painterResource(R.drawable.ic_update), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.update_install))
            }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.release.page))) } },
                shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_whats_new))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Icon(painterResource(R.drawable.ic_open_external), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
        }
    }
}
