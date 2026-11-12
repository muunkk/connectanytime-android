package com.muunkk.connectanytime

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: Store, state: VpnState, notificationDenied: Boolean, onConnect: () -> Unit,
    onDisconnect: () -> Unit, onActivity: () -> Unit, onStayChanged: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var user by rememberSaveable { mutableStateOf(store.user) }
    var server by rememberSaveable { mutableStateOf(store.server) }
    // Passwords deliberately never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var hasPassword by remember { mutableStateOf(store.hasPassword) }
    var stay by remember { mutableStateOf(store.stayConnected) }
    var error by remember { mutableStateOf<Int?>(null) }
    val savePassword = {
        runCatching { store.savePassword(password) }.onSuccess {
            password = ""; visible = false; hasPassword = true; error = null; focus.clearFocus()
        }.onFailure { error = R.string.save_failed }
        Unit
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val status = when {
                        state.status.startsWith("Reconnecting") -> R.string.reconnecting
                        state.connected -> R.string.connected
                        state.busy -> R.string.connecting
                        else -> R.string.not_connected
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_vpn), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(status), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (state.busy && !state.connected) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val message = error?.let { stringResource(it) } ?: state.error?.let { if (it == StartRule.REFUSAL) stringResource(R.string.refusal) else it }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (notificationDenied) Text(stringResource(R.string.notification_denied), style = MaterialTheme.typography.bodySmall)
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                        focus.clearFocus(); error = null
                        if (state.busy) onDisconnect()
                        else if (user.isBlank()) error = R.string.missing_user
                        else if (!hasPassword && !store.hasSession) error = R.string.missing_password
                        else { store.user = user; store.server = server; onConnect() }
                    }) { Text(stringResource(if (state.busy) R.string.disconnect else R.string.connect)) }
                }
            }
            SectionTitle(R.string.account)
            OutlinedTextField(user, { user = it; store.user = it }, label = { Text(stringResource(R.string.netid)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconToggleButton(checked = visible, onCheckedChange = { visible = it }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_visibility), contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password))
                } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) savePassword() else focus.clearFocus() }), modifier = Modifier.fillMaxWidth())
            if (hasPassword) Text(stringResource(R.string.password_saved), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OutlinedButton(enabled = password.isNotEmpty(), onClick = savePassword, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
            if (hasPassword) TextButton(onClick = {
                runCatching { store.clearPassword() }.onSuccess { hasPassword = false; password = ""; error = null }
                    .onFailure { error = R.string.remove_failed }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remove_password)) }
            Text(stringResource(R.string.password_help), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            val stayLabel = stringResource(R.string.stay_connected)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stayLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = stay, onCheckedChange = { stay = it; store.stayConnected = it; onStayChanged() },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = stayLabel })
            }
            Text(stringResource(R.string.stay_help))
            Text(stringResource(R.string.always_help), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vpn_settings)) }
            HorizontalDivider()
            SectionTitle(R.string.server)
            OutlinedTextField(server, { server = it; store.server = it }, enabled = !state.busy, singleLine = true,
                label = { Text(stringResource(R.string.server)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onActivity, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.activity)) }
            SectionTitle(R.string.about)
            Text(BuildConfig.BUILD_DESCRIPTION, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.affiliation), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(id: Int) {
    Text(stringResource(id), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}
