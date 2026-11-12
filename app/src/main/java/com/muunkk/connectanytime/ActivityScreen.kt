@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Calendar
import java.util.Locale

/** One activity line, split into the "MM-dd HH:mm:ss" stamp ActivityLog writes and its text. */
data class LogLine(val day: String?, val time: String?, val text: String)

private val stamp = Regex("""^(\d\d-\d\d) (\d\d:\d\d:\d\d) (.*)$""")

fun parseLogLine(line: String): LogLine =
    stamp.find(line)?.destructured?.let { (day, time, text) -> LogLine(day, time, text) } ?: LogLine(null, null, line)

/** Newest first, grouped by day; lines without a stamp stay with the day above them. */
fun groupLogLines(lines: List<String>): List<Pair<String?, List<LogLine>>> {
    val groups = mutableListOf<Pair<String?, MutableList<LogLine>>>()
    for (raw in lines.asReversed()) {
        val line = parseLogLine(raw)
        val day = line.day ?: groups.lastOrNull()?.first
        if (groups.isEmpty() || groups.last().first != day) groups += day to mutableListOf()
        groups.last().second += line
    }
    return groups
}

@Composable
private fun dayLabel(day: String?): String {
    if (day == null) return ""
    val locale = LocalConfiguration.current.locales[0]
    val now = Calendar.getInstance()
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun Calendar.key() = String.format(Locale.US, "%02d-%02d", get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH))
    return when (day) {
        now.key() -> stringResource(R.string.today)
        yesterday.key() -> stringResource(R.string.yesterday)
        else -> runCatching {
            // The log stores no year; show month and day in the user's locale.
            val (month, date) = day.split("-").map(String::toInt)
            val c = Calendar.getInstance().apply { set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, date) }
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
            java.text.SimpleDateFormat(pattern, locale).format(c.time)
        }.getOrDefault(day)
    }
}

@Composable
fun ActivityScreen(log: ActivityLog, onBack: () -> Unit, onShare: (List<String>) -> Unit) {
    val lines by log.state.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val groups = remember(lines) { groupLogLines(lines) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = {
        MediumFlexibleTopAppBar(
            title = { Text(stringResource(R.string.activity)) },
            subtitle = if (lines.isEmpty()) null else { { Text(pluralStringResource(R.plurals.activity_count, lines.size, lines.size)) } },
            navigationIcon = {
                IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(enabled = lines.isNotEmpty(), onClick = { onShare(lines) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.share_activity))
                }
                IconButton(enabled = lines.isNotEmpty(), onClick = { confirmClear = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.clear_activity))
                }
            },
            scrollBehavior = scroll,
        )
    }) { padding ->
        if (lines.isEmpty()) {
            Empty(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        // At large font sizes the time sits above its line instead of in a column.
        val stacked = LocalDensity.current.fontScale >= 1.5f
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            groups.forEachIndexed { g, (day, entries) ->
                if (day != null) item(key = "day$g") {
                    Text(dayLabel(day), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (g == 0) 8.dp else 20.dp, bottom = 6.dp).semantics { heading() })
                }
                items(entries) { line -> Entry(line, stacked) }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.clear_title)) }, text = { Text(stringResource(R.string.clear_help)) },
        confirmButton = { TextButton(onClick = { log.clear(); confirmClear = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun Entry(line: LogLine, stacked: Boolean) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val time = @Composable {
        line.time?.let { Text(it, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    SelectionContainer {
        if (stacked) Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { time(); Text(line.text, style = mono) }
        else Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            time(); Text(line.text, style = mono, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Empty(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_list), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.no_activity), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.no_activity_help), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    } }
}
