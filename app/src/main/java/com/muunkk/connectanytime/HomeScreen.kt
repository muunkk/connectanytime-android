@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/** What the status area shows. Derived from the service's [VpnState]; no new state. */
enum class Phase { Idle, Connecting, Connected, Reconnecting, Failed }

fun VpnState.phase(): Phase = when {
    status.startsWith("Reconnecting") -> Phase.Reconnecting
    connected -> Phase.Connected
    busy -> Phase.Connecting
    error != null -> Phase.Failed
    else -> Phase.Idle
}

@Composable
fun HomeScreen(store: Store, state: VpnState, notificationDenied: Boolean, onConnect: () -> Unit,
    onDisconnect: () -> Unit, onActivity: () -> Unit, onStayChanged: () -> Unit) {
    val focus = LocalFocusManager.current
    var user by rememberSaveable { mutableStateOf(store.user) }
    var server by rememberSaveable { mutableStateOf(store.server) }
    var hasPassword by remember { mutableStateOf(store.hasPassword) }
    // Validation shown in the status area, cleared by the next tap.
    var problem by remember { mutableStateOf<Int?>(null) }
    val ready = user.isNotBlank() && (hasPassword || store.hasSession)
    val primary = {
        focus.clearFocus(); problem = null
        if (state.busy) onDisconnect()
        else if (user.isBlank()) problem = R.string.missing_user
        else if (!hasPassword && !store.hasSession) problem = R.string.missing_password
        else { store.user = user; store.server = server; onConnect() }
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer))
        },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            val status = @Composable { modifier: Modifier ->
                StatusPanel(state, store.server, ready, problem, notificationDenied, primary, modifier)
            }
            val settings = @Composable {
                Settings(store, state, user, { user = it; store.user = it }, server, { server = it; store.server = it },
                    hasPassword, { hasPassword = it; if (it) problem = null }, onActivity, onStayChanged)
            }
            if (maxWidth >= 600.dp) {
                // Side by side on tablets and wide landscape: the status stays in view.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                        status(Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) { settings() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    status(Modifier.fillMaxWidth())
                    settings()
                }
            }
        }
    }
}

// ---- Status -----------------------------------------------------------------

private data class Look(val container: Color, val content: Color, val badge: Color, val onBadge: Color)

@Composable
private fun look(phase: Phase): Look = with(MaterialTheme.colorScheme) {
    when (phase) {
        Phase.Idle -> Look(surfaceContainerHigh, onSurface, secondaryContainer, onSecondaryContainer)
        Phase.Connecting -> Look(secondaryContainer, onSecondaryContainer, secondary, onSecondary)
        Phase.Connected -> Look(primaryContainer, onPrimaryContainer, primary, onPrimary)
        Phase.Reconnecting -> Look(tertiaryContainer, onTertiaryContainer, tertiary, onTertiary)
        Phase.Failed -> Look(errorContainer, onErrorContainer, error, onError)
    }
}

@Composable
private fun StatusPanel(state: VpnState, server: String, ready: Boolean, problem: Int?, notificationDenied: Boolean,
    onPrimary: () -> Unit, modifier: Modifier) {
    val phase = state.phase()
    val target = look(phase)
    val effects = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val container by animateColorAsState(target.container, effects, label = "container")
    val content by animateColorAsState(target.content, effects, label = "content")
    val ended = state.error == StartRule.REFUSAL
    val title = stringResource(when (phase) {
        Phase.Idle -> R.string.not_connected
        Phase.Connecting -> R.string.connecting
        Phase.Connected -> R.string.connected
        Phase.Reconnecting -> R.string.reconnecting
        Phase.Failed -> if (ended) R.string.session_ended else R.string.failed_title
    })
    val detail = when (phase) {
        Phase.Idle -> stringResource(if (ready) R.string.status_idle else R.string.status_setup)
        Phase.Connecting -> stringResource(R.string.status_connecting)
        Phase.Connected -> stringResource(R.string.status_connected, server)
        Phase.Reconnecting -> stringResource(R.string.status_reconnecting)
        Phase.Failed -> if (ended) stringResource(R.string.session_ended_body) else stringResource(R.string.status_failed)
    }
    // The service's own reason when it failed; otherwise our validation message.
    val reason = problem?.let { stringResource(it) } ?: state.error?.takeIf { phase == Phase.Failed && !ended }
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = container, contentColor = content) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            StatusBadge(phase, target)
            // One announcement per change: TalkBack reads the new title and detail together,
            // not the cross-fading old and new copies.
            Column(Modifier.testTag("status").clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                heading()
                contentDescription = "$title. $detail"
            }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val motion = MaterialTheme.motionScheme
                AnimatedContent(phase to title, transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 3 })
                        .togetherWith(fadeOut(motion.fastEffectsSpec()))
                }, label = "title") { (_, text) ->
                    Text(text, style = MaterialTheme.typography.displaySmallEmphasized)
                }
                Text(detail, style = MaterialTheme.typography.bodyLarge)
            }
            AnimatedVisibility(reason != null,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())) {
                var shown by remember { mutableStateOf("") }
                if (reason != null) shown = reason
                Reason(shown, onFailedPanel = phase == Phase.Failed)
            }
            PrimaryButton(state.busy, onPrimary)
            if (notificationDenied) Text(stringResource(R.string.notification_denied), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Reason(text: String, onFailedPanel: Boolean) {
    val color = if (onFailedPanel) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.error
    Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun polygonFor(phase: Phase): RoundedPolygon = when (phase) {
    Phase.Idle -> MaterialShapes.Circle
    Phase.Connecting, Phase.Reconnecting -> MaterialShapes.Cookie6Sided
    Phase.Connected -> MaterialShapes.Cookie9Sided
    Phase.Failed -> MaterialShapes.SoftBurst
}

/** A RoundedPolygon morph drawn at [progress], scaled and centred like MaterialShapes.toShape. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path: Path = morph.toPath(progress)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        path.translate(androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2) - path.getBounds().center)
        return Outline.Generic(path)
    }
}

@Composable
private fun StatusBadge(phase: Phase, look: Look) {
    val reduced = LocalReducedMotion.current
    val busy = phase == Phase.Connecting || phase == Phase.Reconnecting
    val size = 88.dp
    if (busy && !reduced) {
        ContainedLoadingIndicator(Modifier.size(size).testTag("loading"), containerColor = look.badge,
            indicatorColor = look.onBadge)
        return
    }
    // Static states morph from the previous shape with the expressive spatial spring.
    val target = polygonFor(phase)
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spring = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        from = to; to = target
        if (reduced) progress.snapTo(1f) else { progress.snapTo(0f); progress.animateTo(1f, spring) }
    }
    val morph = remember(from, to) { Morph(from, to) }
    val badge by animateColorAsState(look.badge, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "badge")
    Box(Modifier.size(size).testTag("badge").background(badge, MorphShape(morph, progress.value)), contentAlignment = Alignment.Center) {
        Icon(painterResource(when (phase) {
            Phase.Connected -> R.drawable.ic_shield_check
            Phase.Failed -> R.drawable.ic_error
            Phase.Idle -> R.drawable.ic_shield_outline
            else -> R.drawable.ic_sync
        }), contentDescription = null, tint = look.onBadge, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun PrimaryButton(busy: Boolean, onClick: () -> Unit) {
    val height = ButtonDefaults.MediumContainerHeight
    // Round when it starts a connection, squarer while it can stop one; the press
    // morph comes from ButtonShapes.
    val corner by animateIntAsState(if (busy) 28 else 50, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "corner")
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(if (busy) colors.error else colors.primary, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "button")
    val contentColor by animateColorAsState(if (busy) colors.onError else colors.onPrimary, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "buttonContent")
    Button(onClick = onClick,
        shapes = ButtonShapes(RoundedCornerShape(corner), ButtonDefaults.shapesFor(height).pressedShape),
        modifier = Modifier.fillMaxWidth().heightIn(min = height).testTag("primary"),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = ButtonDefaults.contentPaddingFor(height)) {
        Icon(painterResource(R.drawable.ic_power), contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(stringResource(if (busy) R.string.disconnect else R.string.connect), style = ButtonDefaults.textStyleFor(height))
    }
}

// ---- Settings ---------------------------------------------------------------

@Composable
private fun Settings(store: Store, state: VpnState, user: String, onUser: (String) -> Unit, server: String, onServer: (String) -> Unit,
    hasPassword: Boolean, onHasPassword: (Boolean) -> Unit, onActivity: () -> Unit, onStayChanged: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    // Passwords deliberately never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var stay by remember { mutableStateOf(store.stayConnected) }
    var storageError by remember { mutableStateOf<Int?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val savePassword = {
        runCatching { store.savePassword(password) }.onSuccess {
            password = ""; visible = false; storageError = null; onHasPassword(true); focus.clearFocus()
        }.onFailure { storageError = R.string.save_failed }
        Unit
    }

    SectionTitle(R.string.account)
    Group(2) { index ->
        when (index) {
            0 -> FieldSegment(0, 2) {
                // Locked while busy, like the server: the saved session belongs to this NetID, and an
                // edit mid-session would make the next automatic start discard it.
                OutlinedTextField(user, onUser, enabled = !state.busy, label = { Text(stringResource(R.string.netid)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username })
            }
            else -> FieldSegment(1, 2) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(password, { password = it; storageError = null }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                        isError = storageError != null,
                        supportingText = storageError?.let { id -> { Text(stringResource(id)) } },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconToggleButton(checked = visible, onCheckedChange = { visible = it }, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                    contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) savePassword() else focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(enabled = password.isNotEmpty(), onClick = savePassword, shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
                        if (hasPassword) {
                            TextButton(onClick = { confirmRemove = true }, shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remove_password)) }
                        }
                    }
                    AnimatedVisibility(hasPassword) {
                        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.password_saved), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
    Footnote(R.string.password_help)

    SectionTitle(R.string.connection)
    Group(3) { index ->
        val shapes = ListItemDefaults.segmentedShapes(index, 3)
        when (index) {
            0 -> SegmentedListItem(colors = segmentColors(), onClick = { stay = !stay; store.stayConnected = stay; onStayChanged() }, shapes = shapes,
                // A switch to TalkBack, with the whole row as its touch target.
                modifier = Modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(stay) },
                leadingContent = { Icon(painterResource(R.drawable.ic_sync), contentDescription = null) },
                trailingContent = { Switch(checked = stay, onCheckedChange = null) },
                supportingContent = { Text(stringResource(R.string.stay_help)) }) { Text(stringResource(R.string.stay_connected)) }
            1 -> SegmentedListItem(colors = segmentColors(), onClick = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }, shapes = shapes,
                leadingContent = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                trailingContent = { Icon(painterResource(R.drawable.ic_open_external), contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.always_help)) }) { Text(stringResource(R.string.vpn_settings)) }
            else -> FieldSegment(2, 3) {
                OutlinedTextField(server, onServer, enabled = !state.busy, singleLine = true,
                    label = { Text(stringResource(R.string.server)) },
                    leadingIcon = { Icon(painterResource(if (state.busy) R.drawable.ic_lock else R.drawable.ic_server), contentDescription = null) },
                    supportingText = if (state.busy) { { Text(stringResource(R.string.server_locked)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth())
            }
        }
    }

    ShareSection(store)

    Spacer(Modifier.height(24.dp))
    Group(1) {
        SegmentedListItem(colors = segmentColors(), onClick = onActivity, shapes = ListItemDefaults.segmentedShapes(0, 1),
            leadingContent = { Icon(painterResource(R.drawable.ic_list), contentDescription = null) },
            trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
            supportingContent = { Text(stringResource(R.string.activity_help)) }) { Text(stringResource(R.string.activity)) }
    }

    SectionTitle(R.string.about)
    Group(1) {
        FieldSegment(0, 1, padded = false) {
            ListItem(headlineContent = { Text(BuildConfig.BUILD_DESCRIPTION) },
                leadingContent = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.affiliation)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        }
    }
    Spacer(Modifier.height(24.dp))

    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.remove_title)) }, text = { Text(stringResource(R.string.remove_help)) },
        confirmButton = {
            TextButton(onClick = {
                confirmRemove = false
                runCatching { store.clearPassword() }.onSuccess { onHasPassword(false); password = ""; storageError = null }
                    .onFailure { storageError = R.string.remove_failed }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remove)) }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

// Segments sit on surfaceContainer so each group reads as one block on the background.
@Composable
internal fun segmentColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@Composable
internal fun Group(count: Int, item: @Composable (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) { repeat(count) { item(it) } }
}

/** A segment of a list group that holds a text field rather than a list item. */
@Composable
internal fun FieldSegment(index: Int, count: Int, padded: Boolean = true, content: @Composable () -> Unit) {
    Surface(shape = ListItemDefaults.segmentedShapes(index, count).shape, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Box(if (padded) Modifier.padding(horizontal = 16.dp, vertical = 12.dp) else Modifier) { content() }
    }
}

@Composable
internal fun SectionTitle(id: Int) {
    Text(stringResource(id), style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp).semantics { heading() })
}

@Composable
internal fun Footnote(id: Int) {
    Text(stringResource(id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}
