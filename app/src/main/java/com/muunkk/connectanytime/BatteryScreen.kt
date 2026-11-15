@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// ---- Formatting ---------------------------------------------------------------

/** Every number on the Battery screen and in its share text. Rates arrive as percentage
 *  points (2.0 = 2 %), shares as 0–1, sizes as bytes. */
internal class BatteryFormat(private val context: Context, val locale: Locale = context.resources.configuration.locales[0]) {
    /** §3.7: one decimal under 10, none at 10 or more. */
    private fun digits(value: Double) = if (abs(value) < 10) 1 else 0
    private fun exact(value: Double) = BigDecimal.valueOf(value)
    // Built once per instance and reused (main thread only), keyed by fraction digits.
    private val percents = HashMap<Int, NumberFormat>()
    private val numbers = HashMap<Int, NumberFormat>()
    private fun NumberFormat.fixed(d: Int) = apply { roundingMode = RoundingMode.HALF_UP; minimumFractionDigits = d; maximumFractionDigits = d }
    private fun percentFormat(d: Int) = percents.getOrPut(d) { NumberFormat.getPercentInstance(locale).fixed(d) }
    private fun numberFormat(d: Int) = numbers.getOrPut(d) { NumberFormat.getNumberInstance(locale).fixed(d) }
    private val shortMeasure by lazy { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT) }
    private val wideMeasure by lazy { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE) }
    /** A percentage (2.0 → "2.0%"); also CPU, which may exceed 100. Above zero but under what
     *  rounds to 0.1 it's "less than 0.1%", never a "0.0%" that reads as none; exact zero is "0%".
     *  [spoken] says the unit as a word where the shown form needs it ("less than 0.1 percent"). */
    fun percent(value: Double, spoken: Boolean = false): String = when {
        value > 0 && value < 0.05 -> if (spoken) context.getString(R.string.battery_less_than_percent_spoken, numberFormat(1).format(0.1))
            else context.getString(R.string.battery_less_than_percent, percentFormat(1).format(0.001))
        value == 0.0 -> percentFormat(0).format(0)
        else -> percentFormat(digits(value)).format(exact(value).movePointLeft(2))
    }
    fun share(fraction: Double, spoken: Boolean = false) = percent(fraction * 100, spoken)
    /** The comparison difference: a plain decimal with no percent sign. */
    fun plain(value: Double): String = numberFormat(digits(value)).format(exact(value))
    fun plainIsZero(value: Double) = exact(value).setScale(digits(value), RoundingMode.HALF_UP).signum() == 0
    fun watts(value: Double): String = numberFormat(1).format(exact(value))
    fun size(bytes: Double): String = Formatter.formatShortFileSize(context, bytes.roundToLong().coerceAtLeast(0))
    /** MeasureFormat SHORT for display, WIDE when spoken. Zero shows as zero minutes. */
    fun duration(ms: Long, spoken: Boolean = false, hoursOnly: Boolean = false): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        val format = if (spoken) wideMeasure else shortMeasure
        if (hoursOnly) return format.formatMeasures(Measure((ms + 1_800_000) / 3_600_000, MeasureUnit.HOUR))
        val measures = buildList {
            if (minutes >= 60) add(Measure(minutes / 60, MeasureUnit.HOUR))
            if (minutes % 60 != 0L || minutes == 0L) add(Measure(minutes % 60, MeasureUnit.MINUTE))
        }
        return format.formatMeasures(*measures.toTypedArray())
    }
    fun hours(value: Double, spoken: Boolean = false) = duration((value * 3_600_000).roundToLong(), spoken)
    /** Full to empty: hours only above 3 h. */
    fun life(hours: Double, spoken: Boolean = false) = (hours * 3_600_000).roundToLong().let { duration(it, spoken, hoursOnly = hours > 3) }
    fun dates(first: Long, last: Long, spoken: Boolean = false): String = DateUtils.formatDateRange(context, first * 60_000, last * 60_000,
        DateUtils.FORMAT_SHOW_DATE or (if (spoken) 0 else DateUtils.FORMAT_ABBREV_MONTH))
}

internal fun uplinkTitle(uplink: Uplink?) = uplink?.let(::uplinkShortName) ?: R.string.battery_uplink_short_none

/** CouldNotSave covers two problems; without a reason from the core it reads as a save failure. */
private val BatteryProblem?.startFailed get() = this == BatteryProblem.StartFailed

internal fun batteryStatusText(status: BatteryStatus, problem: BatteryProblem? = null) = when (status) {
    BatteryStatus.Off -> R.string.battery_home_help
    BatteryStatus.Measuring -> R.string.battery_status_measuring
    BatteryStatus.PausedVpnOff -> R.string.battery_status_paused_vpn
    BatteryStatus.PausedCharging -> R.string.battery_status_paused_charging
    BatteryStatus.CouldNotSave -> if (problem.startFailed) R.string.battery_status_start_problem else R.string.battery_status_problem
    BatteryStatus.AutoStopped -> R.string.battery_status_auto
}
private fun stateDescriptionOf(status: BatteryStatus, problem: BatteryProblem?) = when (status) {
    BatteryStatus.Off -> R.string.battery_state_off
    BatteryStatus.Measuring -> R.string.battery_state_measuring
    BatteryStatus.PausedVpnOff -> R.string.battery_state_paused_vpn
    BatteryStatus.PausedCharging -> R.string.battery_state_paused_charging
    BatteryStatus.CouldNotSave -> if (problem.startFailed) R.string.battery_state_start_failed else R.string.battery_state_save_failed
    BatteryStatus.AutoStopped -> R.string.battery_state_auto
}
/** Home's whole spoken row, one string per status. Off reads the row's help, as it shows. */
internal fun batteryHomeSpoken(status: BatteryStatus, problem: BatteryProblem? = null) = when (status) {
    BatteryStatus.Off -> R.string.battery_home_spoken_off
    BatteryStatus.Measuring -> R.string.battery_home_spoken_measuring
    BatteryStatus.PausedVpnOff -> R.string.battery_home_spoken_paused_vpn
    BatteryStatus.PausedCharging -> R.string.battery_home_spoken_paused_charging
    BatteryStatus.CouldNotSave -> if (problem.startFailed) R.string.battery_home_spoken_start_failed else R.string.battery_home_spoken_save_failed
    BatteryStatus.AutoStopped -> R.string.battery_home_spoken_auto
}
/** Home's shown status when there is a problem. */
internal fun batteryHomeProblem(problem: BatteryProblem?) =
    if (problem.startFailed) R.string.battery_status_start_failed_home else R.string.battery_status_save_failed_home
private fun statusGlyph(status: BatteryStatus) = when (status) {
    BatteryStatus.PausedCharging -> R.drawable.ic_charging
    BatteryStatus.PausedVpnOff -> R.drawable.ic_vpn
    BatteryStatus.CouldNotSave -> R.drawable.ic_error
    BatteryStatus.AutoStopped -> R.drawable.ic_info
    else -> R.drawable.ic_battery
}
private val BatteryStatus.on get() = this != BatteryStatus.Off && this != BatteryStatus.AutoStopped

/** Title-bar subtitle: display and spoken forms, or null before there is anything measured. */
internal fun batterySubtitle(context: Context, format: BatteryFormat, status: BatteryStatus, report: BatteryReport?): Pair<String, String>? {
    val first = report?.firstWallMinute ?: return null
    val last = report.lastWallMinute ?: first
    if (report.rowCount == 0) return null
    fun text(spoken: Boolean): String {
        val dates = format.dates(first, last, spoken)
        // Under ten counted minutes the rounded duration would read "0 min": dates only.
        if (!report.subtitleReady) return when (status) {
            BatteryStatus.Off -> context.getString(if (spoken) R.string.battery_subtitle_stopped_dates_spoken else R.string.battery_subtitle_stopped_dates, dates)
            BatteryStatus.AutoStopped -> context.getString(if (spoken) R.string.battery_subtitle_auto_dates_spoken else R.string.battery_subtitle_auto_dates, dates)
            else -> dates
        }
        val id = when (status) {
            BatteryStatus.Off -> if (spoken) R.string.battery_subtitle_stopped_spoken else R.string.battery_subtitle_stopped
            BatteryStatus.AutoStopped -> if (spoken) R.string.battery_subtitle_auto_spoken else R.string.battery_subtitle_auto
            else -> if (spoken) R.string.battery_subtitle_on_spoken else R.string.battery_subtitle_on
        }
        return context.getString(id, format.duration(report.subtitleCountedMs, spoken), dates)
    }
    return text(false) to text(true)
}

/** EXTRA_TEXT for a share: the summary lines only. */
internal fun batterySummary(context: Context, status: BatteryStatus, report: BatteryReport): String {
    val f = BatteryFormat(context)
    val lines = mutableListOf(BuildConfig.BUILD_DESCRIPTION, context.getString(R.string.battery_title))
    batterySubtitle(context, f, status, report)?.let { lines += it.first }
    report.hero?.takeIf { it.ready }?.let { hero ->
        lines += context.getString(R.string.battery_hero_label, context.getString(uplinkTitle(report.heroUplink))) + ": " +
            context.getString(R.string.battery_rate_short, f.percent(hero.percentPerHour!!))
        hero.rangePerHour?.let { lines += context.getString(R.string.battery_hero_range, f.percent(it)) }
    }
    // The difference never travels without its caveat.
    report.comparison?.let { c -> comparisonText(context, f, c)?.let { lines += it; lines += context.getString(R.string.battery_compare_caveat) } }
    report.all.percentPerHour?.let { lines += context.getString(R.string.battery_d_all, f.percent(it), f.hours(report.all.hours)) }
    report.cpu.totalPercent?.let { lines += context.getString(R.string.battery_cpu_title) + ": " + context.getString(R.string.battery_cpu_total, f.percent(it)) }
    return lines.joinToString("\n")
}

/** The difference sentence. [named] leaves the network out, for the screen, where "Both sides
 *  on …" right above it already says it; the shared summary names it in the sentence. */
private fun comparisonText(context: Context, f: BatteryFormat, c: BatteryComparison, named: Boolean = false): String? {
    val points = c.pointsPerHour ?: return null
    val d = c.difference ?: return null
    if (d == BatteryDifference.None || f.plainIsZero(points)) return context.getString(R.string.battery_compare_none)
    val more = d == BatteryDifference.More
    return if (named) context.getString(if (more) R.string.battery_compare_more_here else R.string.battery_compare_less_here, f.plain(points))
    else context.getString(if (more) R.string.battery_compare_more else R.string.battery_compare_less, f.plain(points), context.getString(uplinkTitle(c.uplink)))
}

// ---- Screen -------------------------------------------------------------------

/** The Battery screen, wired to the app's [BatteryControl]. */
@Composable
fun BatteryScreen(app: ConnectApp, onBack: () -> Unit, onShare: (File, String) -> Unit) {
    val battery = app.battery
    val status by battery.status.collectAsStateWithLifecycle()
    val report by battery.report.collectAsStateWithLifecycle()
    val autoStopped by battery.autoStopped.collectAsStateWithLifecycle()
    val clearing by battery.clearing.collectAsStateWithLifecycle()
    val problem by battery.problem.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The auto-stop copy stays for the visit, even after the pref is cleared on leaving.
    var latched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStopped) { if (autoStopped) latched = true }
    LaunchedEffect(Unit) { battery.refresh() }
    DisposableEffect(Unit) { onDispose { if (app.store.batteryAutoStopped) app.store.batteryAutoStopped = false } }
    val shown = if (latched && !status.on) BatteryStatus.AutoStopped else status
    BatteryContent(app.store, shown, report, clearing, onBack, problem = problem,
        onToggle = { on -> if (on) latched = false; battery.setEnabled(on) },
        onClear = { latched = false; battery.clear() },
        onRetry = battery::retrySave,
        onShare = {
            val snapshot = report ?: return@BatteryContent
            battery.export { file ->
                if (file != null) onShare(file, batterySummary(context, shown, snapshot))
                else Toast.makeText(context, R.string.battery_share_failed, Toast.LENGTH_SHORT).show()
            }
        })
}

/** Stands in for the display cutout in tests and screenshots; null on a phone, which uses the real one. */
internal val LocalCutoutInsets = staticCompositionLocalOf<WindowInsets?> { null }

/** The Scaffold's [padding], then whatever of the side [cutout] it hasn't already covered, or that
 *  an ancestor hasn't already consumed: the cutout is applied once. */
internal fun Modifier.readablePadding(padding: PaddingValues, cutout: WindowInsets): Modifier =
    padding(padding).consumeWindowInsets(padding).windowInsetsPadding(cutout.only(WindowInsetsSides.Horizontal))

/** Side padding that keeps lines readable on wide screens, plus any cutout not already applied. */
@Composable
internal fun ReadableLazyColumn(padding: PaddingValues, modifier: Modifier = Modifier, bottom: Dp = 24.dp, content: LazyListScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().readablePadding(padding, LocalCutoutInsets.current ?: WindowInsets.displayCutout)) {
        val side = readableSide(maxWidth)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, bottom = bottom), content = content)
    }
}

/** Readable content column width on wide screens: at most 640 dp, at least 16 dp each side. */
private fun readableSide(maxWidth: Dp) = max(16.dp, (maxWidth - 640.dp) / 2)

/** The top app bar's slot, with insets that line its edges (and so its title) up with
 *  [ReadableLazyColumn]'s capped column. On a phone the extra is zero. */
@Composable
internal fun ReadableTopBar(bar: @Composable (WindowInsets) -> Unit) {
    val system = TopAppBarDefaults.windowInsets
    BoxWithConstraints {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val left = with(density) { system.getLeft(density, direction).toDp() }
        val right = with(density) { system.getRight(density, direction).toDp() }
        val extra = readableSide(maxWidth - left - right) - 16.dp
        bar(system.add(WindowInsets(left = extra, right = extra)))
    }
}

/** Stateless: everything comes in as values, so tests can drive any state. */
@Composable
internal fun BatteryContent(store: Store, status: BatteryStatus, report: BatteryReport?, clearing: Boolean, onBack: () -> Unit,
    onToggle: (Boolean) -> Unit, onClear: () -> Unit, onRetry: () -> Unit, onShare: () -> Unit, problem: BatteryProblem? = null) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val f = remember(context, configuration) { BatteryFormat(context) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // A status change is announced only when the system caused it; the user's own toggle is
    // already announced by the switch, which sets [expectOn]. The classification is derived from
    // the last committed snapshot, and only committed after the composition is applied, so an
    // abandoned composition can't move it on.
    var committed by remember { mutableStateOf(StatusAnnouncement(status)) }
    var expectOn by remember { mutableStateOf<Boolean?>(null) }
    val current = committed.next(status, expectOn)
    SideEffect { if (current != committed) { committed = current; expectOn = null } }
    var toggles by remember { mutableIntStateOf(0) }
    // A toggle whose status never arrives (or arrives unchanged) stops counting after a while.
    LaunchedEffect(toggles) { delay(5_000); expectOn = null }
    // "Measuring again" is left in the tree long enough to be announced, then the line goes back
    // to the hidden Measuring it is otherwise.
    var settled by remember { mutableIntStateOf(StatusAnnouncement.NEVER) }
    val resumedAt = current.resumedAt
    LaunchedEffect(resumedAt) { if (resumedAt != StatusAnnouncement.NEVER) { delay(4_000); settled = resumedAt } }
    val hasRows = report != null && report.rowCount > 0
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = { ReadableTopBar { insets ->
        val subtitle = batterySubtitle(context, f, status, report)
        MediumFlexibleTopAppBar(
            title = { Text(stringResource(R.string.battery_title)) },
            subtitle = subtitle?.let { (shown, spoken) -> { Text(shown, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = spoken }) } },
            navigationIcon = {
                IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(enabled = hasRows, onClick = onShare, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.battery_share))
                }
                IconButton(enabled = hasRows, onClick = { confirmClear = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.battery_clear))
                }
            },
            windowInsets = insets,
            scrollBehavior = scroll,
        )
    } }) { padding ->
        ReadableLazyColumn(padding) {
            item(key = "switch") {
                Column(Modifier.padding(top = 8.dp)) {
                    Group(1) { SwitchRow(status, problem) { expectOn = !status.on; toggles++; onToggle(!status.on) } }
                }
            }
            // Always there, even when Off (empty then), so the live region is never a node that
            // appears already live.
            item(key = "status") { StatusLine(status, problem, current, settled) }
            if (status == BatteryStatus.CouldNotSave) item(key = "retry") { ProblemRow(problem, onRetry) }
            if (report == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        val label = stringResource(R.string.battery_loading)
                        val modifier = Modifier.semantics { contentDescription = label }
                        // Reduced motion: a still shape instead of the morphing one.
                        if (LocalReducedMotion.current) LoadingIndicator(progress = { 0f }, modifier = modifier)
                        else LoadingIndicator(modifier)
                    }
                }
                return@ReadableLazyColumn
            }
            val heroReady = report.hero?.ready == true
            when {
                status == BatteryStatus.Off && !hasRows -> item(key = "panel") {
                    Panel(stringResource(R.string.battery_off_title), stringResource(R.string.battery_off_help))
                }
                // The status line already says it stopped; "not measuring yet" would be wrong.
                status == BatteryStatus.AutoStopped && !hasRows -> item(key = "panel") { Panel(null, stringResource(R.string.battery_off_help)) }
                status == BatteryStatus.Measuring && !heroReady -> item(key = "panel") {
                    val body = stringResource(when {
                        !store.shareOverHotspot -> R.string.battery_keep_body_noshare
                        report.path == BatteryPath.Level -> R.string.battery_keep_body_level
                        else -> R.string.battery_keep_body
                    })
                    Panel(stringResource(R.string.battery_keep_title), body,
                        if (report.anySituationReady) stringResource(R.string.battery_keep_other_ready) else null)
                }
                status == BatteryStatus.PausedVpnOff -> item(key = "panel") {
                    Panel(null, stringResource(if (hasRows) R.string.battery_paused_vpn_body else R.string.battery_paused_vpn_body_empty))
                }
                status == BatteryStatus.PausedCharging -> item(key = "panel") { Panel(null, stringResource(R.string.battery_paused_charging_body)) }
            }
            if (hasRows) {
                if (heroReady) item(key = "hero") { Hero(f, report) }
                item(key = "compared") { Compared(f, store, report) }
                item(key = "app") { ThisApp(f, report) }
                item(key = "situations") { Situations(f, report) }
                item(key = "more") { MoreDetail(f, report) }
            }
            item(key = "foot") { Foot(f) }
        }
    }
    if (confirmClear) {
        // The same dates as the subtitle: what's kept is the rows across that range, which can be
        // far longer than the counted time.
        val first = report?.firstWallMinute
        val last = report?.lastWallMinute ?: first
        val body = if (status.on) R.string.battery_clear_body_on else R.string.battery_clear_body
        AlertDialog(onDismissRequest = { confirmClear = false },
            icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
            title = { Text(stringResource(R.string.battery_clear_title)) },
            text = if (first == null || last == null) null else { {
                val spoken = stringResource(body, f.dates(first, last, spoken = true))
                Text(stringResource(body, f.dates(first, last)), modifier = Modifier.semantics { contentDescription = spoken })
            } },
            confirmButton = {
                TextButton(enabled = !clearing, onClick = { confirmClear = false; onClear() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
    }
}

/** What the status line says to TalkBack, and who caused the latest status change. Immutable:
 *  [next] works out the following state. `expectOn` is the on/off state the user just toggled
 *  to; a change that lands on it is theirs, anything else is the phone's.
 *
 *  The line is a polite live region at all times, so an announcement is its spoken text
 *  changing. [said] is the status that text is for: a change the phone makes moves it on (and
 *  is announced); the user's own change leaves it where it was, since the switch already says
 *  it, and the line is hidden from TalkBack until the phone next changes something. */
internal data class StatusAnnouncement(val last: BatteryStatus, val said: BatteryStatus = last, val changes: Int = 0,
    val resumedAt: Int = NEVER) {
    fun next(status: BatteryStatus, expectOn: Boolean?): StatusAnnouncement {
        if (status == last) return this
        if (expectOn == status.on) return copy(last = status, changes = changes + 1)
        val paused = last == BatteryStatus.PausedCharging || last == BatteryStatus.PausedVpnOff
        val resumed = status == BatteryStatus.Measuring && paused
        return StatusAnnouncement(status, status, changes + 1, if (resumed) changes + 1 else NEVER)
    }
    /** The spoken text for [said], or null for none (Off). Measuring after a pause the phone
     *  ended is "Measuring again". */
    fun spoken(problem: BatteryProblem?): Int? = when (said) {
        BatteryStatus.Off -> null
        BatteryStatus.Measuring -> if (resumedAt != NEVER) R.string.battery_status_resumed else R.string.battery_status_measuring
        BatteryStatus.CouldNotSave ->
            if (problem.startFailed) R.string.battery_status_start_problem_spoken else R.string.battery_status_problem_spoken
        else -> batteryStatusText(said)
    }
    /** Hidden from TalkBack, keeping the node: Off; plain Measuring (the switch says it), and
     *  "Measuring again" once it has been announced ([settled] is the change it was announced
     *  at); and after the user's own change, whose text the line didn't take. */
    fun hidden(settled: Int): Boolean = said != last || last == BatteryStatus.Off ||
        (last == BatteryStatus.Measuring && (resumedAt == NEVER || resumedAt == settled))
    companion object { const val NEVER = -1 }
}

@Composable
private fun SwitchRow(status: BatteryStatus, problem: BatteryProblem?, onClick: () -> Unit) {
    val state = stringResource(stateDescriptionOf(status, problem))
    SegmentedListItem(colors = segmentColors(), onClick = onClick, shapes = standaloneShapes(),
        // A switch to TalkBack, with the whole row as its touch target; the state says why it's paused.
        modifier = Modifier.testTag("battery-switch").semantics {
            role = Role.Switch; toggleableState = ToggleableState(status.on); stateDescription = state
        },
        trailingContent = { Switch(checked = status.on, onCheckedChange = null) },
        supportingContent = { Text(stringResource(R.string.battery_measure_help)) }) { Text(stringResource(R.string.battery_measure)) }
}

/** The only live region on the screen, and one stable node in every state, so TalkBack sees its
 *  content change rather than a node that appears already live. It never ticks. */
@Composable
private fun StatusLine(status: BatteryStatus, problem: BatteryProblem?, announcement: StatusAnnouncement, settled: Int) {
    val spoken = announcement.spoken(problem)?.let { stringResource(it) }
    val hidden = announcement.hidden(settled)
    val error = status == BatteryStatus.CouldNotSave
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val shown = status != BatteryStatus.Off
    // One rhythm in every shown state (a full touch-exploration target), so the list doesn't jump
    // between Measuring and Paused; Off takes no space.
    val layout = if (shown) Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).heightIn(min = 48.dp) else Modifier
    Row(Modifier.fillMaxWidth().then(layout).testTag("battery-status")
        .clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            spoken?.let { contentDescription = it }
            if (hidden) hideFromAccessibility()
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (shown) {
            // Sized with the text so it scales with the font size.
            Icon(painterResource(statusGlyph(status)), contentDescription = null, tint = color,
                modifier = Modifier.size(with(LocalDensity.current) { 18.sp.toDp() }))
            Text(stringResource(batteryStatusText(status, problem)), style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

/** A row that stands alone as a message (the switch, the problem row): large corners all round,
 *  where a list group's rows take segmentedShapes like Home's. */
@Composable
private fun standaloneShapes(): ListItemShapes = ListItemDefaults.segmentedShapes(0, 1).copy(shape = MaterialTheme.shapes.large)

/** The CouldNotSave row: why, and the retry button under the text (the spoken status says "below"). */
@Composable
private fun ProblemRow(problem: BatteryProblem?, onRetry: () -> Unit) {
    val start = problem.startFailed
    Column(Modifier.padding(top = 8.dp)) {
        Group(1) {
            FieldSegment(0, 1, padded = false, shape = standaloneShapes().shape) {
                ListItem(modifier = Modifier.testTag("battery-save-failed"),
                    headlineContent = { Text(stringResource(if (start) R.string.battery_start_failed else R.string.battery_save_failed),
                        color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    supportingContent = {
                        // Always under the text, starting where it starts (the button's own
                        // padding is pulled back so its label lines up).
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(stringResource(if (start) R.string.battery_start_failed_help else R.string.battery_save_failed_help))
                            val inset = ButtonDefaults.TextButtonContentPadding
                            TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes(), contentPadding = inset,
                                modifier = Modifier.offset(x = -inset.calculateLeftPadding(LayoutDirection.Ltr))
                                    .heightIn(min = 48.dp).testTag("battery-retry")) {
                                Text(stringResource(if (start) R.string.battery_start_retry else R.string.battery_retry))
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
}

@Composable
private fun Panel(title: String?, body: String, more: String? = null) {
    Surface(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("battery-panel"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // The paused panel is its body only: the status line already says what happened.
            if (title != null) Icon(painterResource(R.drawable.ic_battery), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                // A second paragraph is its own Text, a fixed gap under the first.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                    more?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun Hero(f: BatteryFormat, report: BatteryReport) {
    val hero = report.hero ?: return
    val value = f.percent(hero.percentPerHour ?: return)
    val uplink = stringResource(uplinkTitle(report.heroUplink))
    val range = hero.rangePerHour?.let { f.percent(it) }
    val valueSpoken = f.percent(hero.percentPerHour, spoken = true)
    val rangeSpoken = hero.rangePerHour?.let { f.percent(it, spoken = true) }
    val watts = hero.watts?.let { f.watts(it) }
    val measured = hero.wattsLabel == BatteryWattsLabel.Measured
    val life = hero.fullToEmptyHours
    val spoken = listOfNotNull(
        stringResource(R.string.battery_hero_value_spoken, valueSpoken),
        rangeSpoken?.let { stringResource(R.string.battery_hero_range_spoken, it) },
        watts?.let { stringResource(if (measured) R.string.battery_hero_watts_measured_spoken else R.string.battery_hero_watts_about_spoken, it) },
        life?.let { stringResource(R.string.battery_life, f.life(it, spoken = true)).removeSuffix(".") },
    ).joinToString(". ") + "."
    Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val labelSpoken = stringResource(R.string.battery_hero_label_spoken, uplink)
            Text(stringResource(R.string.battery_hero_label, uplink), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("battery-hero-label").clearAndSetSemantics { heading(); contentDescription = labelSpoken })
            Column(Modifier.testTag("battery-hero").clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, style = MaterialTheme.typography.displayMediumEmphasized.copy(fontFeatureSettings = "tnum"), modifier = Modifier.alignByBaseline())
                    Text(stringResource(R.string.battery_per_hour), style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                }
                range?.let { Text(stringResource(R.string.battery_hero_range, it), style = MaterialTheme.typography.bodyLarge) }
                watts?.let {
                    Text(stringResource(if (measured) R.string.battery_hero_watts_measured else R.string.battery_hero_watts_about, it),
                        style = MaterialTheme.typography.bodyLarge)
                }
                life?.let { Text(stringResource(R.string.battery_life, f.life(it)), style = MaterialTheme.typography.bodyLarge) }
            }
            Text(stringResource(R.string.battery_hero_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A list row that only shows values. With a spoken form it's read as one node. At large text
 *  (or with [stack]) the value moves under the label. */
@Composable
private fun InfoRow(index: Int, count: Int, headline: String, supporting: List<String> = emptyList(), value: String? = null,
    spoken: String? = null, leading: Int? = null, tag: String? = null, stack: Boolean = false) {
    val stacked = stack || LocalDensity.current.fontScale >= 1.5f
    FieldSegment(index, count, padded = false) {
        var modifier: Modifier = Modifier
        if (tag != null) modifier = modifier.testTag(tag)
        if (spoken != null) modifier = modifier.clearAndSetSemantics { contentDescription = spoken }
        ListItem(modifier = modifier, headlineContent = { Text(headline) },
            leadingContent = leading?.let { { Icon(painterResource(it), contentDescription = null) } },
            supportingContent = if (supporting.isEmpty() && (value == null || !stacked)) null else { {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    supporting.forEach { Text(it) }
                    if (stacked && value != null) Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            } },
            trailingContent = if (stacked || value == null) null else { { Text(value, style = MaterialTheme.typography.titleMedium) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

@Composable
private fun rateValue(f: BatteryFormat, rate: BatteryRate): Pair<String, String> =
    if (rate.ready && rate.percentPerHour != null) {
        stringResource(R.string.battery_rate_short, f.percent(rate.percentPerHour)) to
            stringResource(R.string.battery_rate_short_spoken, f.percent(rate.percentPerHour, spoken = true))
    } else stringResource(R.string.battery_not_yet) to stringResource(R.string.battery_not_yet_spoken)

@Composable
private fun Compared(f: BatteryFormat, store: Store, report: BatteryReport) {
    val context = LocalContext.current
    val setup = store.proxySetup
    val c = report.comparison
    // The rows shown are the comparison's own sides when there is one, so readiness comes from them.
    val (inUse, baseline) = comparisonSides(report)
    val bothReady = inUse.ready && baseline.ready
    Column {
        SectionTitle(if (setup == ProxySetup.Automatic) R.string.battery_compared_auto else R.string.battery_compared_manual)
        // Both sides come from one network; say which, once there's anything recorded.
        if (c != null || report.compareInUseProgressMs > 0 || report.compareBaselineProgressMs > 0) {
            Text(stringResource(R.string.battery_compared_uplink, stringResource(uplinkTitle(report.compareUplink))),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("battery-compare-uplink"))
        }
        if (c != null && bothReady) {
            comparisonText(context, f, c, named = true)?.let {
                Text(it, style = MaterialTheme.typography.bodyLargeEmphasized, modifier = Modifier.padding(horizontal = 16.dp).testTag("battery-compare"))
            }
            // "Points" only needs explaining when a number of them is shown; the caveat always stays.
            val points = c.pointsPerHour
            val numeric = points != null && c.difference != null && c.difference != BatteryDifference.None && !f.plainIsZero(points)
            val caveat = stringResource(R.string.battery_compare_caveat)
            Text(if (numeric) stringResource(R.string.battery_points_help) + " " + caveat else caveat,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
        }
        // Both sides always show; a side with nothing recorded yet is a not-ready placeholder.
        val count = if (bothReady) 2 else 3
        // Keyed by role: the in-use and baseline rows keep their nodes when the help row comes or goes.
        KeyedGroup(listOf("in-use", "baseline", "how").take(count)) { index ->
            when (index) {
                0 -> Side(f, index, count, stringResource(R.string.battery_side_in_use), inUse, report.compareInUseProgressMs, "battery-side-in-use")
                1 -> Side(f, index, count,
                    stringResource(if (setup == ProxySetup.Automatic) R.string.battery_side_off else R.string.battery_side_idle), baseline,
                    report.compareBaselineProgressMs, "battery-side-baseline")
                else -> InfoRow(index, count, stringResource(R.string.battery_how_title),
                    listOf(stringResource(if (setup == ProxySetup.Automatic) R.string.battery_how_auto else R.string.battery_how_manual)),
                    leading = R.drawable.ic_info)
            }
        }
    }
}

/** The two sides as shown: the comparison's own sides when there is one, otherwise the report's
 *  progress estimates; a side with nothing recorded yet is an empty not-ready rate. */
internal fun comparisonSides(report: BatteryReport): Pair<BatteryRate, BatteryRate> =
    (report.compareInUse ?: BatteryRate()) to (report.compareBaseline ?: BatteryRate())

/** [progressMs] is all the time recorded on this side, short windows included, for "N min so far". */
@Composable
private fun Side(f: BatteryFormat, index: Int, count: Int, name: String, rate: BatteryRate, progressMs: Long, tag: String) {
    val (line1, line1Spoken) = when {
        rate.ready -> f.hours(rate.hours) to f.hours(rate.hours, spoken = true)
        rate.reason == BatteryReason.ReadingsTooUneven -> stringResource(R.string.battery_not_ready_coarse).let { it to it }
        // Under a minute would read "0 min so far".
        progressMs < 60_000 -> stringResource(R.string.battery_not_yet) to stringResource(R.string.battery_not_yet_spoken)
        else -> stringResource(R.string.battery_not_ready_short, f.duration(progressMs), f.duration(1_800_000)) to
            stringResource(R.string.battery_not_ready_short, f.duration(progressMs, true), f.duration(1_800_000, true))
    }
    val unknown = stringResource(R.string.battery_unknown)
    val proxy = rate.proxyBytesPerHour?.let(f::size) ?: unknown
    val cell = rate.mobileBytesPerHour?.let(f::size) ?: unknown
    val awake = rate.awakeShare?.let { f.share(it) } ?: unknown
    val awakeSpoken = rate.awakeShare?.let { f.share(it, spoken = true) } ?: unknown
    // A side with nothing recorded yet has no detail line: it would be all "unknown".
    val empty = rate.proxyBytesPerHour == null && rate.mobileBytesPerHour == null && rate.awakeShare == null
    val detail = if (empty) null else stringResource(R.string.battery_side_detail, proxy, cell, awake)
    val detailSpoken = if (empty) null else stringResource(R.string.battery_side_detail_spoken, proxy, cell, awakeSpoken)
    val (value, valueSpoken) = rateValue(f, rate)
    InfoRow(index, count, name, listOfNotNull(line1, detail), value,
        spoken = listOfNotNull(name, line1Spoken, detailSpoken, valueSpoken).joinToString(". "), tag = tag)
}

@Composable
private fun ThisApp(f: BatteryFormat, report: BatteryReport) {
    Column {
        SectionTitle(R.string.battery_this_app)
        Text(stringResource(R.string.battery_this_app_note), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        val title = stringResource(R.string.battery_cpu_title)
        val help = stringResource(R.string.battery_cpu_help)
        val total = report.cpu.totalPercent
        val value = total?.let { stringResource(R.string.battery_cpu_total, f.percent(it)) } ?: stringResource(R.string.battery_not_yet)
        val spoken = total?.let { stringResource(R.string.battery_cpu_total_spoken, f.percent(it, spoken = true)) } ?: stringResource(R.string.battery_not_yet_spoken)
        // The value goes under the help, so a long "% of one core" never squeezes the text at 360 dp.
        Group(1) { InfoRow(0, 1, title, listOf(help), value, spoken = "$title. $help. $spoken", tag = "battery-cpu", stack = true) }
    }
}

@Composable
private fun situationName(s: BatterySituation, spoken: Boolean): String {
    val screen = stringResource(if (s.screenOn) R.string.battery_screen_on else R.string.battery_screen_off)
    val sharing = stringResource(when (s.sharing) {
        BatterySharing.InUse -> R.string.battery_sharing_in_use
        BatterySharing.Idle -> R.string.battery_sharing_idle
        BatterySharing.Off -> R.string.battery_sharing_off
    })
    val uplink = stringResource(uplinkTitle(s.uplink))
    return if (s.hotspot == BatteryHotspot.on) stringResource(if (spoken) R.string.battery_situation_spoken else R.string.battery_situation, screen, sharing, uplink)
    else stringResource(if (spoken) R.string.battery_situation_hotspot_spoken else R.string.battery_situation_hotspot, screen, sharing, uplink,
        stringResource(if (s.hotspot == BatteryHotspot.off) R.string.battery_hotspot_off else R.string.battery_hotspot_unknown))
}

@Composable
private fun situationDetail(f: BatteryFormat, rate: BatteryRate, highest: Boolean, spoken: Boolean): String {
    val time = f.hours(rate.hours, spoken)
    val measured = rate.wattsLabel == BatteryWattsLabel.Measured
    val base = rate.watts?.let {
        stringResource(when {
            measured -> if (spoken) R.string.battery_situation_detail_measured_spoken else R.string.battery_situation_detail_measured
            else -> if (spoken) R.string.battery_situation_detail_spoken else R.string.battery_situation_detail
        }, time, f.watts(it))
    } ?: stringResource(R.string.battery_situation_detail_no_w, time)
    return if (highest) stringResource(if (spoken) R.string.battery_detail_highest_spoken else R.string.battery_detail_highest, base,
        stringResource(R.string.battery_highest)) else base
}

/** A list group whose rows are keyed, so a row's node (and TalkBack focus) follows its key
 *  when the order changes. */
@Composable
private fun KeyedGroup(keys: List<Any>, item: @Composable (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        keys.forEachIndexed { index, k -> key(k) { item(index) } }
    }
}

private object OtherOrShortKey
private object NoSituationsKey

@Composable
private fun Situations(f: BatteryFormat, report: BatteryReport) {
    Column(Modifier.testTag("battery-situations")) {
        SectionTitle(R.string.battery_situations)
        // "Other or short" only when it has time to show.
        val other = (report.otherOrShort.hours * 3_600_000).roundToLong() >= 60_000
        val keys: List<Any> = report.situations.map { it.situation } + listOfNotNull(OtherOrShortKey.takeIf { other })
        if (keys.isEmpty()) {
            // The first minutes: nothing to break down yet, but the heading isn't left bare.
            KeyedGroup(listOf(NoSituationsKey)) {
                InfoRow(0, 1, stringResource(R.string.battery_not_yet), spoken = stringResource(R.string.battery_not_yet_spoken),
                    tag = "battery-situations-empty")
            }
            return@Column
        }
        val count = keys.size
        KeyedGroup(keys) { index ->
            val item = report.situations.getOrNull(index)
            val rate = item?.rate ?: report.otherOrShort
            val name = item?.let { situationName(it.situation, false) } ?: stringResource(R.string.battery_other_short)
            val nameSpoken = item?.let { situationName(it.situation, true) } ?: name
            val detail = situationDetail(f, rate, item?.highest == true, false)
            val detailSpoken = situationDetail(f, rate, item?.highest == true, true)
            val (value, valueSpoken) = rateValue(f, rate)
            InfoRow(index, count, name, listOf(detail), value, spoken = "$nameSpoken. $detailSpoken. $valueSpoken", tag = "battery-situation-$index")
        }
    }
}

@Composable
private fun MoreDetail(f: BatteryFormat, report: BatteryReport) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val target = if (expanded) (if (rtl) -90f else 90f) else 0f
    val angle by animateFloatAsState(target, if (reduced) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chevron")
    val state = stringResource(if (expanded) R.string.battery_expanded else R.string.battery_collapsed)
    val rows = detailRows(f, report)
    // While the rows show at all (expanding, open or collapsing) the header is segment 0 of
    // rows + 1 in one group with them; once they're gone it's a group of one.
    val visibility = remember { MutableTransitionState(expanded) }
    visibility.targetState = expanded
    val count = if (visibility.currentState || visibility.targetState) rows.size + 1 else 1
    Column(Modifier.padding(top = 24.dp).testTag("battery-more-group")) {
        SegmentedListItem(colors = segmentColors(), onClick = { expanded = !expanded }, shapes = ListItemDefaults.segmentedShapes(0, count),
            modifier = Modifier.heightIn(min = 56.dp).testTag("battery-more").semantics {
                role = Role.Button; stateDescription = state
                if (expanded) collapse { expanded = false; true } else expand { expanded = true; true }
            },
            trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.rotate(angle)) }) {
            Text(stringResource(R.string.battery_more))
        }
        val motion = MaterialTheme.motionScheme
        AnimatedVisibility(visibility, enter = if (reduced) EnterTransition.None else expandVertically(motion.defaultSpatialSpec()),
            exit = if (reduced) ExitTransition.None else shrinkVertically(motion.fastSpatialSpec())) {
            Column(Modifier.padding(top = ListItemDefaults.SegmentedGap), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                rows.forEachIndexed { i, row -> InfoRow(i + 1, rows.size + 1, row.headline, row.supporting, spoken = row.spoken, tag = "battery-detail-$i") }
            }
        }
    }
}

/** A More detail row; [spoken] when the shown text uses "·" or symbols that read badly. */
private data class DetailRow(val headline: String, val supporting: List<String> = emptyList(), val spoken: String? = null)

@Composable
private fun detailRows(f: BatteryFormat, report: BatteryReport): List<DetailRow> = buildList {
    val all = report.all
    all.percentPerHour?.let { add(DetailRow(stringResource(R.string.battery_d_all, f.percent(it), f.hours(all.hours)),
        spoken = stringResource(R.string.battery_d_all, f.percent(it, spoken = true), f.hours(all.hours, spoken = true)))) }
    report.cpu.vpnPercent?.let { add(DetailRow(stringResource(R.string.battery_d_vpn, f.percent(it)))) }
    report.cpu.forwardingPercent?.let { add(DetailRow(stringResource(R.string.battery_d_fwd, f.percent(it)))) }
    report.cpu.otherPercent?.let { add(DetailRow(stringResource(R.string.battery_d_other, f.percent(it)))) }
    report.reconnectsPerHour?.let {
        val n = report.reconnects.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        add(DetailRow(pluralStringResource(R.plurals.battery_d_reconnects, n, n, f.plain(it))))
    }
    // No connected time, no connection type: the shares would all be zero.
    if (report.connectedMs > 0) {
        val t = report.transport
        val title = stringResource(R.string.battery_transport_title)
        val note = stringResource(R.string.battery_transport_note)
        add(DetailRow(title, listOf(stringResource(R.string.battery_transport, f.share(t.dtls), f.share(t.tls), f.share(t.unknown)), note),
            spoken = "$title. " + stringResource(R.string.battery_transport_spoken, f.share(t.dtls, true), f.share(t.tls, true), f.share(t.unknown, true)) + ". $note"))
    }
    all.awakeShare?.let { add(DetailRow(stringResource(R.string.battery_d_awake, f.share(it)))) }
    all.proxyBytesPerHour?.let { add(DetailRow(stringResource(R.string.battery_d_proxy_mb, f.size(it)))) }
    all.mobileBytesPerHour?.let { add(DetailRow(stringResource(R.string.battery_d_cell_mb, f.size(it)))) }
    report.warmestThermal?.let {
        add(DetailRow(stringResource(R.string.battery_d_thermal, stringResource(when (it) {
            BatteryThermal.Normal -> R.string.battery_thermal_normal
            BatteryThermal.Warm -> R.string.battery_thermal_warm
            BatteryThermal.Hot -> R.string.battery_thermal_hot
            BatteryThermal.VeryHot -> R.string.battery_thermal_very_hot
        }))))
    }
}

@Composable
private fun Foot(f: BatteryFormat) {
    Column(Modifier.padding(top = 16.dp)) {
        Footnote(stringResource(R.string.battery_foot_measured))
        Footnote(stringResource(R.string.battery_foot_estimated))
        Footnote(stringResource(R.string.battery_foot_excluded, f.size(50_000.0), f.size(10_000.0)))
        Text(stringResource(R.string.battery_share_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
    }
}
