package com.muunkk.connectanytime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(log: ActivityLog, onBack: () -> Unit, onShare: (List<String>) -> Unit) {
    val lines by log.state.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        // Actions live below the bar so all labels fit at 200% font scale in portrait.
        TopAppBar(title = { Text(stringResource(R.string.activity)) }, navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = lines.isNotEmpty(), onClick = { onShare(lines) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.share)) }
                TextButton(enabled = lines.isNotEmpty(), onClick = { confirmClear = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.clear)) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (lines.isEmpty()) item { Text(stringResource(R.string.no_activity)) }
                items(lines.size) { i -> SelectionContainer {
                    Text(lines[lines.lastIndex - i], style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                } }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.clear_title)) }, text = { Text(stringResource(R.string.clear_help)) },
        confirmButton = { TextButton(onClick = { log.clear(); confirmClear = false }) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } })
}
