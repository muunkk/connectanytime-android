package com.muunkk.connectanytime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) Controls.connect(this)
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val app = application as ConnectApp
                    val state by ConnectApp.state.collectAsStateWithLifecycle()
                    var activity by remember { mutableStateOf(false) }
                    if (activity) {
                        BackHandler { activity = false }
                        ActivityScreen(app.log, onBack = { activity = false })
                    } else Home(app.store, state, onConnect = {
                        val prepare = VpnService.prepare(this)
                        if (prepare == null) Controls.connect(this) else consent.launch(prepare)
                    }, onActivity = { activity = true })
                }
            }
        }
    }

    @Composable private fun Home(store: Store, state: VpnState, onConnect: () -> Unit, onActivity: () -> Unit) {
        var user by remember { mutableStateOf(store.user) }
        var server by remember { mutableStateOf(store.server) }
        var password by remember { mutableStateOf("") }
        var hasPassword by remember { mutableStateOf(store.hasPassword) }
        var stay by remember { mutableStateOf(store.stayConnected) }
        var error by remember { mutableStateOf<String?>(null) }
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("ConnectAnytime", style = MaterialTheme.typography.headlineLarge)
            Text(state.status, style = MaterialTheme.typography.titleMedium)
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                error = null
                if (state.busy) Controls.disconnect(this@MainActivity)
                else if (user.isBlank()) error = "Enter your NetID first."
                else if (!hasPassword && !store.hasSession) error = "Save your password first."
                else { store.user = user; store.server = server; onConnect() }
            }) { Text(if (state.busy) "Disconnect" else "Connect") }
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            Text("NYU account", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(user, { user = it; store.user = it }, label = { Text("NetID") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(if (hasPassword) "Password saved" else "Password") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = password.isNotEmpty(), onClick = {
                runCatching { store.savePassword(password) }.onSuccess { password = ""; hasPassword = true; error = null }
                    .onFailure { error = "Couldn't save the password on this device." }
            }) { Text("Save") }
            Text("Your password is encrypted with a key kept on this Android device. It is only sent to the VPN server. Approve Duo on your phone; the app answers “push” for you.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Stay Connected", modifier = Modifier.weight(1f))
                Switch(checked = stay, onCheckedChange = {
                    stay = it; store.stayConnected = it
                    if (state.busy) startService(Intent(this@MainActivity, ConnectVpnService::class.java).setAction(ConnectVpnService.ARM))
                })
            }
            Text("After you connect, the VPN keeps your session through network changes and can come back if Android stops it. It rejoins without Duo. Once the server ends the session, tap Connect to log in again. Disconnect pauses this until your next Connect.")
            Text("For Android to keep the VPN running, turn on Always-on VPN for ConnectAnytime in VPN settings. Android controls this setting. “Block connections without VPN” also blocks traffic when the app is stopped, and may hold up Duo until you turn it off.")
            TextButton(onClick = { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) { Text("Open Android VPN settings") }
            HorizontalDivider()
            Text("Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(server, { server = it; store.server = it }, singleLine = true, label = { Text("Server") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onActivity) { Text("Activity") }
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(BuildConfig.BUILD_DESCRIPTION, style = MaterialTheme.typography.bodySmall)
            Text("Not affiliated with Cisco or NYU.", style = MaterialTheme.typography.bodySmall)
        }
    }
    @Composable private fun ActivityScreen(log: ActivityLog, onBack: () -> Unit) {
        val lines by log.state.collectAsStateWithLifecycle()
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Text("Activity", style = MaterialTheme.typography.headlineLarge)
            Row {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(enabled = lines.isNotEmpty(), onClick = {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, (listOf(BuildConfig.BUILD_DESCRIPTION) + lines).joinToString("\n")), "Share activity"))
                }) { Text("Share") }
                TextButton(enabled = lines.isNotEmpty(), onClick = { log.clear() }) { Text("Clear") }
            }
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lines.isEmpty()) item { Text("No activity yet") }
                items(lines.size) { i -> SelectionContainer { Text(lines[lines.lastIndex - i], style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}
