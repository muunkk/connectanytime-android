package com.muunkk.connectanytime

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Fake reports built through the real report math. */
internal object BatteryFakes {
    /** Nov 8 to Nov 14, 2026 (UTC), so goldens show a real date range. */
    private fun BatteryReport.dated() = copy(firstWallMinute = 29_902_140, lastWallMinute = 29_911_320)
    /** In use drains 8 %/h, sharing off 2 %/h: both ready, a clear difference. */
    fun compared(): BatteryReport = BatteryMath.report((batteryTrace(loss = 400_000) +
        batteryTrace(loss = 100_000, rec = 2, sharing = false, inUse = false)).asSequence(), ProxySetup.Automatic).dated()
    /** An hour in use and no baseline yet: only one side of the comparison recorded. */
    fun oneSide(): BatteryReport = BatteryMath.report(batteryTrace(loss = 400_000).asSequence(), ProxySetup.Automatic).dated()
    /** 20 minutes in use: rows, but nothing ready yet. */
    fun notReady(): BatteryReport = BatteryMath.report(batteryTrace(minutes = 20, loss = 30_000).asSequence(), ProxySetup.Automatic).dated()
    /** Just turned on: one reading, nothing counted, nothing to break down by situation. */
    fun firstMinutes(): BatteryReport = BatteryMath.report(batteryTrace(minutes = 5, loss = 10_000).take(1).asSequence(), ProxySetup.Automatic).dated()
    /** Five minutes in: under the ten minutes the subtitle's duration needs. */
    fun fiveMinutes(): BatteryReport = BatteryMath.report(batteryTrace(minutes = 5, loss = 10_000).asSequence(), ProxySetup.Automatic).dated()
    /** A ready hero on mobile data whose watts come partly from charge × voltage. */
    fun aboutWatts(): BatteryReport = compared().let { r -> r.copy(hero = r.hero!!.copy(wattsLabel = BatteryWattsLabel.About)) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class BatteryScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: ConnectApp
    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.store.prefs.edit().clear().commit()
    }
    private var status by mutableStateOf(BatteryStatus.Off)
    private var report by mutableStateOf<BatteryReport?>(BatteryReport())
    private var problem by mutableStateOf<BatteryProblem?>(null)
    private var retried = 0
    private var loadFailed by mutableStateOf(false)
    private var reloaded = 0
    private var reducedMotion = false
    /** False: the toggle goes nowhere (the status doesn't change). */
    private var toggleTakes = true
    private fun show() {
        compose.setContent { ConnectTheme { CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
            BatteryContent(app.store, status, report, false, {}, onToggle = { on ->
                if (toggleTakes) status = if (on) BatteryStatus.PausedVpnOff else BatteryStatus.Off
            }, onClear = {}, onRetry = { retried++ }, onShare = {}, problem = problem, loadFailed = loadFailed, onReload = { reloaded++ })
        } } }
        compose.waitForIdle()
    }
    private val switch get() = compose.onNodeWithTag("battery-switch")
    private val line get() = compose.onNodeWithTag("battery-status")
    private fun stateDescription(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private fun scrollTo(matcher: SemanticsMatcher) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    private val noLiveRegion = SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)
    /** The status line's spoken content, exactly. */
    private fun says(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))
    private val saysNothing = SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)
    /** Kept as a node, but out of TalkBack's reach. */
    private val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
    private val readable = SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility)

    @Test fun switchRowSaysWhyItIsPaused() {
        status = BatteryStatus.Measuring; show()
        switch.assert(isToggleable()).assertIsOn().assert(stateDescription("On, measuring"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assert(noLiveRegion)
        status = BatteryStatus.PausedCharging; compose.waitForIdle()
        switch.assert(stateDescription("On, paused while charging"))
        status = BatteryStatus.AutoStopped; compose.waitForIdle()
        switch.assertIsOff().assert(stateDescription("Off, stopped after 7 days"))
    }
    @Test fun onlySystemCausedChangesAreAnnounced() {
        status = BatteryStatus.Measuring; show()
        // One live node all along; plain Measuring is said by the switch, so it's hidden.
        line.assertExists().assert(polite).assert(hidden).assert(says("Measuring"))
        val id = line.fetchSemanticsNode().id
        // A change the phone made: the text changes, and the line is readable.
        status = BatteryStatus.PausedCharging; compose.waitForIdle()
        line.assert(polite).assert(readable).assert(says("Paused while charging"))
        line.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        // The user's own toggle is announced by the switch: the line stops being live, so its new
        // text isn't announced, but it's still there to read (Off has nothing to read).
        switch.performClick(); compose.waitForIdle()
        assertEquals(BatteryStatus.Off, status)
        line.assert(noLiveRegion).assert(hidden).assert(saysNothing)
        switch.performClick(); compose.waitForIdle()
        assertEquals(BatteryStatus.PausedVpnOff, status)
        line.assert(noLiveRegion).assert(readable).assert(says("Paused: the VPN is off"))
        compose.onNodeWithText("Paused: the VPN is off", useUnmergedTree = true).assertIsDisplayed()
        // The next change the phone makes is live, and announced, again.
        status = BatteryStatus.CouldNotSave; compose.waitForIdle()
        line.assert(polite).assert(readable).assert(says("Couldn’t save measurements. The Try saving again button is below."))
        // The same node throughout: its content changed, it never reappeared.
        assertEquals(id, line.fetchSemanticsNode().id)
    }
    @Test fun statusLineIsOneLiveNodeInEveryState() {
        status = BatteryStatus.Off; report = BatteryFakes.notReady(); show()
        val id = line.assert(polite).assert(hidden).assert(saysNothing).fetchSemanticsNode().id
        // Each a change the phone made (or the auto-stop), in turn: text, and whether it's hidden.
        val expected = listOf(
            Triple(BatteryStatus.PausedVpnOff, "Paused: the VPN is off", false),
            // Readable while it's announced (hidden a few seconds later: measuringAgainIsAnnouncedThenHidden).
            Triple(BatteryStatus.Measuring, "Measuring again", false),
            Triple(BatteryStatus.PausedCharging, "Paused while charging", false),
            Triple(BatteryStatus.CouldNotSave, "Couldn’t save measurements. The Try saving again button is below.", false),
            Triple(BatteryStatus.Measuring, "Measuring", true),
            Triple(BatteryStatus.AutoStopped, "Stopped after 7 days", false),
            Triple(BatteryStatus.Off, null, true))
        for ((next, text, isHidden) in expected) {
            status = next; compose.waitForIdle()
            line.assert(polite).assert(if (text == null) saysNothing else says(text)).assert(if (isHidden) hidden else readable)
            assertEquals("$next keeps the node", id, line.fetchSemanticsNode().id)
        }
    }
    @Test fun theUsersOwnToggleIntoAStoppedOrProblemStateStaysReadable() {
        status = BatteryStatus.CouldNotSave; problem = BatteryProblem.StartFailed; report = BatteryFakes.notReady(); show()
        toggleTakes = false
        // The user turns it off and it lands on the auto-stop copy; then back on into a problem.
        switch.performClick(); status = BatteryStatus.AutoStopped; compose.waitForIdle()
        line.assert(noLiveRegion).assert(readable).assert(says("Stopped after 7 days"))
        switch.performClick(); status = BatteryStatus.CouldNotSave; compose.waitForIdle()
        line.assert(noLiveRegion).assert(readable).assert(says("Couldn’t start measuring. The Try again button is below."))
        // The user's own Measuring stays hidden, as the switch says it.
        switch.performClick(); status = BatteryStatus.Off; compose.waitForIdle()
        switch.performClick(); status = BatteryStatus.Measuring; compose.waitForIdle()
        line.assert(noLiveRegion).assert(hidden)
    }
    @Test fun aToggleThatChangesNothingDoesNotSilenceTheNextSystemChange() {
        status = BatteryStatus.Measuring; show()
        toggleTakes = false
        switch.performClick(); compose.waitForIdle()
        assertEquals(BatteryStatus.Measuring, status)
        status = BatteryStatus.PausedCharging; compose.waitForIdle()
        line.assert(polite).assert(readable).assert(says("Paused while charging"))
    }
    @Test fun measuringAgainIsAnnouncedThenHidden() {
        status = BatteryStatus.Measuring; show()
        status = BatteryStatus.PausedVpnOff; compose.waitForIdle()
        status = BatteryStatus.Measuring; compose.waitForIdle()
        line.assert(polite).assert(readable).assert(says("Measuring again"))
        compose.onNodeWithText("Measuring", useUnmergedTree = true).assertIsDisplayed()
        // A few seconds later it has been said: hidden, the node and its text kept.
        compose.mainClock.advanceTimeBy(5_000); compose.waitForIdle()
        line.assert(polite).assert(hidden).assert(says("Measuring again"))
        compose.onNodeWithText("Measuring", useUnmergedTree = true).assertIsDisplayed()
        // Back from anything but a pause (here a save problem), Measuring is hidden at once.
        status = BatteryStatus.CouldNotSave; compose.waitForIdle()
        status = BatteryStatus.Measuring; compose.waitForIdle()
        line.assert(polite).assert(hidden).assert(says("Measuring"))
    }
    @Test fun statusLineKeepsOneHeightInEveryShownState() {
        status = BatteryStatus.Measuring; show()
        val measuring = line.getBoundsInRoot()
        for (next in listOf(BatteryStatus.PausedCharging, BatteryStatus.PausedVpnOff, BatteryStatus.AutoStopped)) {
            status = next; compose.waitForIdle()
            assertEquals("$next", measuring, line.getBoundsInRoot())
        }
    }
    @Test fun withOneSideRecordedBothSidesAndHowToCompareShow() {
        app.store.proxySetup = ProxySetup.Automatic
        status = BatteryStatus.Measuring; report = BatteryFakes.oneSide(); show()
        assertEquals(null, report!!.comparison)
        scrollTo(hasTestTag("battery-side-in-use"))
        compose.onNodeWithTag("battery-side-in-use").assert(hasContentDescription("8.0% an hour", substring = true))
        scrollTo(hasTestTag("battery-side-baseline"))
        compose.onNodeWithTag("battery-side-baseline").assert(hasContentDescription("Sharing off", substring = true))
            .assert(hasContentDescription("Not enough data yet", substring = true))
        scrollTo(hasText("How to compare"))
        compose.onNodeWithTag("battery-compare").assertDoesNotExist()
    }
    @Test fun otherOrShortIsLeftOutWithNoTime() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared().copy(otherOrShort = BatteryRate()); show()
        scrollTo(hasTestTag("battery-situations"))
        compose.onNodeWithText("Other or short", substring = true).assertDoesNotExist()
    }
    @Test fun statusLineNeverTicks() {
        status = BatteryStatus.Measuring; report = BatteryFakes.notReady(); show()
        val before = compose.onNodeWithText("Measuring", useUnmergedTree = true).fetchSemanticsNode().config
        report = report!!.copy(countedMs = report!!.countedMs + 60_000); compose.waitForIdle()
        assertEquals(before, compose.onNodeWithText("Measuring", useUnmergedTree = true).fetchSemanticsNode().config)
    }
    @Test fun subtitleDoesNotChangeBetweenFlushes() {
        status = BatteryStatus.Measuring; report = BatteryFakes.notReady(); show()
        val f = BatteryFormat(app)
        val first = batterySubtitle(app, f, status, report)!!
        val next = batterySubtitle(app, f, status, report!!.copy(countedMs = report!!.countedMs + 60_000, rowCount = report!!.rowCount + 1))!!
        assertEquals(first, next)
        compose.onNodeWithText(first.first).assertExists()
        assertTrue(first.second, "," in first.second && "·" !in first.second)
    }
    @Test fun subtitleUnderTenMinutesShowsOnlyTheDates() {
        val f = BatteryFormat(app)
        val report = BatteryFakes.fiveMinutes()
        assertFalse(report.subtitleReady)
        val dates = f.dates(report.firstWallMinute!!, report.lastWallMinute!!)
        val (shown, spoken) = batterySubtitle(app, f, BatteryStatus.Measuring, report)!!
        assertEquals(dates, shown)
        assertFalse(spoken, "measured" in spoken || "0 min" in spoken)
        val stopped = batterySubtitle(app, f, BatteryStatus.Off, report)!!
        assertEquals("Stopped · $dates", stopped.first)
        assertTrue(stopped.second, stopped.second.startsWith("Stopped, ") && "·" !in stopped.second)
        assertEquals("Stopped after 7 days · $dates", batterySubtitle(app, f, BatteryStatus.AutoStopped, report)!!.first)
        // From ten counted minutes the duration is back.
        assertTrue(batterySubtitle(app, f, BatteryStatus.Measuring, BatteryFakes.notReady())!!.first.contains("measured"))
    }
    @Test fun bySituationIsNeverABareHeading() {
        status = BatteryStatus.Measuring; report = BatteryFakes.firstMinutes(); show()
        assertTrue(report!!.situations.isEmpty())
        scrollTo(hasTestTag("battery-situations-empty"))
        compose.onNodeWithTag("battery-situations-empty").assert(hasContentDescription("Not enough data yet"))
        compose.onNodeWithText("Other or short", substring = true).assertDoesNotExist()
    }
    @Test fun noConnectedTimeLeavesOutConnectionType() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared().copy(connectedMs = 0); show()
        scrollTo(hasTestTag("battery-more"))
        compose.onNodeWithTag("battery-more").performSemanticsAction(SemanticsActions.Expand); compose.waitForIdle()
        scrollTo(hasText("Cellular data", substring = true))
        compose.onNode(hasContentDescription("Connection type", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Connection type").assertDoesNotExist()
    }
    @Test fun connectionTypeIsSpokenWithCommas() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        assertTrue(report!!.connectedMs > 0)
        scrollTo(hasTestTag("battery-more"))
        compose.onNodeWithTag("battery-more").performSemanticsAction(SemanticsActions.Expand); compose.waitForIdle()
        val spoken = hasContentDescription("Connection type. Fast (DTLS)", substring = true)
        scrollTo(spoken)
        val description = compose.onNode(spoken).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description, "·" !in description && "backup (TLS)" in description && description.endsWith("drops packets."))
    }
    @Test fun aScrolledBackStatusLineHoldsTheSameContent() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        status = BatteryStatus.PausedCharging; compose.waitForIdle()
        line.assert(polite).assert(says("Paused while charging"))
        // Scroll the status item out (disposed) and back (recreated): the content hasn't changed,
        // so there's nothing new to announce.
        scrollTo(hasText("Measured: battery charge", substring = true))
        line.assertDoesNotExist()
        scrollTo(hasTestTag("battery-status"))
        line.assert(polite).assert(says("Paused while charging"))
        // A later system change is the content changing again.
        status = BatteryStatus.CouldNotSave; compose.waitForIdle()
        line.assert(polite).assert(says("Couldn’t save measurements. The Try saving again button is below."))
    }
    @Test fun couldNotSaveRowIsNotClickableAndRetryIsItsOwnTarget() {
        status = BatteryStatus.CouldNotSave; report = BatteryFakes.notReady(); show()
        line.assert(says("Couldn’t save measurements. The Try saving again button is below."))
        line.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Couldn’t save", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("battery-save-failed").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Couldn’t save measurements").assertExists()
        val retry = compose.onNodeWithTag("battery-retry").assertHasClickAction()
        retry.assertHeightIsAtLeast(48.dp)
        retry.assertTextEquals("Try saving again")
        // The spoken status says the button "is below": it sits under the text.
        val help = compose.onNodeWithText(app.getString(R.string.battery_save_failed_help), useUnmergedTree = true).getBoundsInRoot()
        assertTrue("${retry.getBoundsInRoot()} under $help", retry.getBoundsInRoot().top >= help.bottom)
        retry.performClick()
        assertEquals(1, retried)
        switch.assertIsOn()
        assertTrue(compose.onAllNodesWithText("Problem", substring = true).fetchSemanticsNodes().isEmpty())
    }
    @Test fun startFailureSaysItCouldNotStartWithTryAgainBelow() {
        status = BatteryStatus.CouldNotSave; problem = BatteryProblem.StartFailed; report = BatteryFakes.notReady(); show()
        line.assert(polite).assert(says("Couldn’t start measuring. The Try again button is below."))
        switch.assert(stateDescription("On, couldn’t start"))
        compose.onNodeWithText("Couldn’t start measuring").assertExists()
        compose.onNodeWithText("Couldn’t save measurements").assertDoesNotExist()
        val help = compose.onNodeWithText("Something on this phone stopped the measurement from starting.", useUnmergedTree = true)
            .assertExists().getBoundsInRoot()
        val retry = compose.onNodeWithTag("battery-retry").assertTextEquals("Try again").assertHeightIsAtLeast(48.dp)
        assertTrue("${retry.getBoundsInRoot()} under $help", retry.getBoundsInRoot().top >= help.bottom)
        retry.performClick()
        assertEquals(1, retried)
        // The core's reason moves on to a save problem: the copy follows.
        problem = BatteryProblem.SaveFailed; compose.waitForIdle()
        line.assert(says("Couldn’t save measurements. The Try saving again button is below."))
        compose.onNodeWithTag("battery-retry").assertTextEquals("Try saving again")
    }
    @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
    @Test fun moreDetailHeaderAndRowsAreOneGroup() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        scrollTo(hasTestTag("battery-more"))
        val inGroup = hasAnyAncestor(hasTestTag("battery-more-group"))
        compose.onNodeWithTag("battery-more").assert(inGroup)
        compose.onNodeWithTag("battery-detail-0").assertDoesNotExist()
        compose.onNodeWithTag("battery-more").performSemanticsAction(SemanticsActions.Expand); compose.waitForIdle()
        scrollTo(hasTestTag("battery-detail-0"))
        val header = compose.onNodeWithTag("battery-more").getBoundsInRoot()
        val first = compose.onNodeWithTag("battery-detail-0").assert(inGroup).getBoundsInRoot()
        // Right under the header, a segment gap apart, as the next segment of the same group.
        assertEquals((header.bottom + ListItemDefaults.SegmentedGap).value, first.top.value, 1f)
        compose.onNodeWithTag("battery-more").performSemanticsAction(SemanticsActions.Collapse); compose.waitForIdle()
        compose.onNodeWithTag("battery-detail-0").assertDoesNotExist()
    }
    @Test fun heroLabelIsTheHeadingAndTheValueIsReadAsOne() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        compose.onNode(isHeading() and hasContentDescription("Whole phone, with the screen off and sharing in use, on Mobile data")).assertExists()
        compose.onNodeWithTag("battery-hero").assert(hasContentDescription(
            "8.0% an hour. Give or take 0.3% an hour, a sampling estimate. 1.6 watts from the battery, from the energy counter. " +
                "At this rate, about 13 hours from full to empty."))
        compose.onNodeWithText("Keep measuring").assertDoesNotExist()
    }
    @Test fun heroAboutWatts() {
        status = BatteryStatus.Measuring; report = BatteryFakes.aboutWatts(); show()
        compose.onNodeWithTag("battery-hero").assert(hasContentDescription("About 1.6 watts from the battery", substring = true))
        compose.onNodeWithText("About 1.6 W from the battery", useUnmergedTree = true).assertExists()
    }
    @Test fun comparisonIsAPlainDecimal() {
        app.store.proxySetup = ProxySetup.Automatic
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        scrollTo(hasTestTag("battery-compare"))
        compose.onNodeWithTag("battery-compare")
            .assertTextEquals("6.0 percentage points an hour more with sharing in use")
        // The network is named once, in the line above.
        compose.onNodeWithTag("battery-compare-uplink").assertTextEquals("Both sides on Mobile data")
        compose.onNodeWithText("Sharing in use vs off").assertExists()
        compose.onNodeWithText("How to compare").assertDoesNotExist()
    }
    @Test fun notReadyShowsTheKeepPanelAndHowToCompare() {
        app.store.shareOverHotspot = true
        status = BatteryStatus.Measuring; report = BatteryFakes.notReady(); show()
        compose.onNodeWithText("Keep measuring").assertExists()
        compose.onNodeWithText("The number appears after at least 30 minutes", substring = true).assertExists()
        compose.onNodeWithTag("battery-hero").assertDoesNotExist()
        scrollTo(hasText("How to compare"))
    }
    @Test fun situationRowKeepsItsNodeAcrossARebuild() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        scrollTo(hasTestTag("battery-situation-0"))
        val row = compose.onNodeWithTag("battery-situation-0")
        val id = row.fetchSemanticsNode().id
        report = BatteryFakes.compared().copy(rowCount = report!!.rowCount + 5); compose.waitForIdle()
        assertEquals(id, compose.onNodeWithTag("battery-situation-0").fetchSemanticsNode().id)
    }
    @Test fun situationRowFollowsItsSituationWhenRankingsSwap() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        val situations = report!!.situations
        assertTrue(situations.toString(), situations.size >= 2)
        val last = situations.size - 1
        scrollTo(hasTestTag("battery-situation-0"))
        val id = compose.onNodeWithTag("battery-situation-0").fetchSemanticsNode().id
        report = report!!.copy(situations = situations.reversed(), rowCount = report!!.rowCount + 5); compose.waitForIdle()
        scrollTo(hasTestTag("battery-situation-$last"))
        assertEquals(id, compose.onNodeWithTag("battery-situation-$last").fetchSemanticsNode().id)
    }
    @Test fun comparisonSidesComeFromTheReportsSides() {
        // The top-3 situations no longer matter: the sides are the core's, from every situation.
        val inUse = BatteryRate(hours = 0.4); val baseline = BatteryRate(hours = 0.3)
        val report = BatteryReport(compareInUse = inUse, compareBaseline = baseline, situations = listOf(BatterySituationRate(
            BatterySituation(false, BatteryHotspot.on, BatterySharing.InUse, null), BatteryRate(hours = 9.0))))
        assertEquals(inUse to baseline, comparisonSides(report))
        // A side with nothing recorded is an empty not-ready rate.
        val (a, b) = comparisonSides(BatteryReport(compareBaseline = baseline))
        assertEquals(0.0, a.hours, 0.0); assertFalse(a.ready); assertEquals(baseline, b)
    }
    @Test fun aSideUnderAMinuteSaysNotYet() {
        app.store.proxySetup = ProxySetup.Automatic
        status = BatteryStatus.Measuring; report = BatteryFakes.oneSide(); show()
        scrollTo(hasTestTag("battery-side-baseline"))
        compose.onNodeWithTag("battery-side-baseline").assert(hasContentDescription("Sharing off. Not enough data yet", substring = true))
        assertTrue(compose.onAllNodesWithText("0 min so far, needs 30 min", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
    @Test fun situationRowsSayMeasuredWattsWithoutAbout() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        assertEquals(BatteryWattsLabel.Measured, report!!.situations[0].rate.wattsLabel)
        scrollTo(hasTestTag("battery-situation-0"))
        val row = compose.onNodeWithTag("battery-situation-0")
        row.assert(hasContentDescription("watts, from the energy counter", substring = true))
        row.assert(!hasContentDescription("about", substring = true, ignoreCase = true))
        val about = report!!.situations[0].let { it.copy(rate = it.rate.copy(wattsLabel = BatteryWattsLabel.About)) }
        report = report!!.copy(situations = listOf(about) + report!!.situations.drop(1)); compose.waitForIdle()
        row.assert(hasContentDescription("about", substring = true))
    }
    @Test fun noDifferenceKeepsTheCaveatButNotThePointsHelp() {
        app.store.proxySetup = ProxySetup.Automatic
        val r = BatteryFakes.compared()
        status = BatteryStatus.Measuring; report = r.copy(comparison = r.comparison!!.copy(difference = BatteryDifference.None)); show()
        scrollTo(hasTestTag("battery-compare"))
        compose.onNodeWithText(app.getString(R.string.battery_compare_caveat)).assertExists()
        compose.onNodeWithText(app.getString(R.string.battery_points_help), substring = true).assertDoesNotExist()
        report = r; compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.battery_points_help), substring = true).assertExists()
    }
    @Test fun moreDetailIsAnExpandableButton() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        scrollTo(hasTestTag("battery-more"))
        val more = compose.onNodeWithTag("battery-more")
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assert(stateDescription("Collapsed"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).assertHeightIsAtLeast(56.dp)
        more.performSemanticsAction(SemanticsActions.Expand); compose.waitForIdle()
        more.assert(stateDescription("Expanded")).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        scrollTo(hasContentDescription("Connection type", substring = true))
        compose.onNode(hasContentDescription("Connection type", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("VPN: at least 3.3% of one core").assertExists()
    }
    @Test fun tinyProcessorSharesAndReconnectRatesSayUnderOneTenth() {
        val r = BatteryFakes.compared()
        status = BatteryStatus.Measuring
        report = r.copy(cpu = r.cpu.copy(vpnPercent = 0.04, forwardingPercent = 0.01, otherPercent = 0.0),
            reconnects = 1, reconnectsPerHour = 0.03); show()
        scrollTo(hasTestTag("battery-more"))
        compose.onNodeWithTag("battery-more").performSemanticsAction(SemanticsActions.Expand); compose.waitForIdle()
        for (text in listOf("VPN: under 0.1% of one core", "Forwarding: under 0.1% of one core", "Other: about 0% of one core",
            "VPN reconnects: 1 (less than 0.1 an hour)")) {
            scrollTo(hasText(text)); compose.onNodeWithText(text).assertExists()
        }
        assertTrue(compose.onAllNodesWithText("less than 0.1%", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("0.0 an hour", substring = true).fetchSemanticsNodes().isEmpty())
        // At 0.05 the ordinary forms come back.
        report = r.copy(cpu = r.cpu.copy(vpnPercent = 0.05), reconnects = 2, reconnectsPerHour = 0.05); compose.waitForIdle()
        for (text in listOf("VPN: at least 0.1% of one core", "VPN reconnects: 2 (0.1 an hour)")) {
            scrollTo(hasText(text)); compose.onNodeWithText(text).assertExists()
        }
        // None at all is "0 an hour", not "0.0".
        report = r.copy(reconnects = 0, reconnectsPerHour = 0.0); compose.waitForIdle()
        scrollTo(hasText("VPN reconnects: 0 (0 an hour)")); compose.onNodeWithText("VPN reconnects: 0 (0 an hour)").assertExists()
    }
    @Test fun offWithoutRowsShowsOnlyTheSwitchPanelAndFootnotes() {
        status = BatteryStatus.Off; report = BatteryReport(); show()
        compose.onNodeWithText("Not measuring yet").assertExists()
        line.assert(polite).assert(hidden).assert(saysNothing)
        compose.onNodeWithText("This app").assertDoesNotExist()
        compose.onNodeWithContentDescription("Share battery data").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear battery data").assertIsNotEnabled()
        scrollTo(hasText("Measured: battery charge", substring = true))
    }
    @Test fun firstLoadShowsTheIndicatorUnderTheSwitch() {
        status = BatteryStatus.Measuring; report = null; show()
        compose.onNodeWithContentDescription("Loading battery data").assertExists()
        switch.assertExists(); line.assertExists()
        compose.onNodeWithContentDescription("Share battery data").assertIsNotEnabled()
    }
    @Test fun reducedMotionLoaderReadsOnlyItsLabel() {
        reducedMotion = true; status = BatteryStatus.Measuring; report = null; show()
        compose.onNodeWithContentDescription("Loading battery data")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }
    @Test fun aFailedFirstLoadSaysSoWithTryAgainAndClearStaysEnabled() {
        status = BatteryStatus.Measuring; report = null; loadFailed = true; show()
        compose.onNodeWithContentDescription("Loading battery data").assertDoesNotExist()
        compose.onNodeWithText("Couldn’t read saved measurements.").assertIsDisplayed()
            .assert(hasContentDescription("Couldn’t read saved measurements. The Try again button is below."))
        val retry = compose.onNodeWithTag("battery-reload").assertTextEquals("Try again").assertHeightIsAtLeast(48.dp)
        retry.performClick()
        assertEquals(1, reloaded)
        compose.onNodeWithContentDescription("Share battery data").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear battery data").assertIsEnabled().performClick()
        compose.onNodeWithText("This removes the battery measurements saved on this device. Measuring continues.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        // A refresh that reads: the report replaces the message.
        report = BatteryFakes.notReady(); loadFailed = false; compose.waitForIdle()
        compose.onNodeWithTag("battery-load-failed").assertDoesNotExist()
        compose.onNodeWithText("Keep measuring").assertExists()
    }
    @Test fun clearDialogSaysHowMuchAndThatMeasuringContinues() {
        // 20 minutes of rows, 20 wall-clock minutes apart.
        status = BatteryStatus.Measuring
        report = BatteryMath.report(batteryTrace(minutes = 20, loss = 30_000).asSequence(), ProxySetup.Automatic); show()
        compose.onNodeWithContentDescription("Clear battery data").performClick()
        compose.onNodeWithText("Clear battery data?").assertExists()
        val f = BatteryFormat(app)
        fun dates(r: BatteryReport, spoken: Boolean = false) = f.dates(r.firstWallMinute!!, r.lastWallMinute!!, spoken)
        compose.onNodeWithText("This removes battery measurements recorded ${dates(report!!)} from this device. Measuring continues.")
            .assertExists()
            .assert(hasContentDescription("This removes battery measurements recorded ${dates(report!!, spoken = true)} from this device. Measuring continues."))
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear battery data?").assertDoesNotExist()
        // Counted time spread over six days: the dialog names the same dates as the subtitle.
        report = BatteryFakes.notReady(); compose.waitForIdle()
        val subtitle = batterySubtitle(app, f, status, report)!!.first
        assertTrue(subtitle, subtitle.endsWith(dates(report!!)))
        compose.onNodeWithContentDescription("Clear battery data").performClick()
        compose.onNodeWithText("This removes battery measurements recorded ${dates(report!!)} from this device. Measuring continues.").assertExists()
    }
    @Test fun comparedRowsAreTheComparisonsSides() {
        app.store.proxySetup = ProxySetup.Automatic
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        val r = report!!; val c = r.comparison!!
        assertEquals(c.inUse to c.baseline, comparisonSides(r))
        val f = BatteryFormat(app)
        compose.onNodeWithTag("battery-compare-uplink").assertTextEquals("Both sides on Mobile data")
        scrollTo(hasTestTag("battery-side-in-use"))
        compose.onNodeWithTag("battery-side-in-use").assert(hasContentDescription(
            "Sharing in use. ${f.hours(c.inUse.hours, spoken = true)} measured.", substring = true))
            .assert(hasContentDescription("${f.percent(c.inUse.percentPerHour!!)} an hour", substring = true))
        scrollTo(hasTestTag("battery-side-baseline"))
        compose.onNodeWithTag("battery-side-baseline").assert(hasContentDescription(
            "Sharing off. ${f.hours(c.baseline.hours, spoken = true)} measured.", substring = true))
            .assert(hasContentDescription("${f.percent(c.baseline.percentPerHour!!)} an hour", substring = true))
        // The sentence goes with the rows: a side that isn't ready drops it, whatever the comparison says.
        report = r.copy(compareBaseline = c.baseline.copy(ready = false), compareBaselineProgressMs = 25 * 60_000L); compose.waitForIdle()
        compose.onNodeWithTag("battery-compare").assertDoesNotExist()
        scrollTo(hasTestTag("battery-side-baseline"))
        compose.onNodeWithTag("battery-side-baseline").assert(hasContentDescription("25 minutes so far, needs 30 minutes", substring = true))
    }
    @Test fun aSideSoFarIsTheRecordedTimeIncludingShortWindows() {
        app.store.proxySetup = ProxySetup.Automatic
        val r = BatteryFakes.notReady()
        status = BatteryStatus.Measuring; report = r.copy(compareInUseProgressMs = 27 * 60_000L); show()
        scrollTo(hasTestTag("battery-side-in-use"))
        compose.onNodeWithText("27 min so far, needs 30 min", useUnmergedTree = true).assertExists()
    }
    @Test fun stoppedOrPausedWithNothingRecordedSaysNeitherNotYetNorKeep() {
        status = BatteryStatus.AutoStopped; report = BatteryReport(); show()
        compose.onNodeWithText("Not measuring yet").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.battery_off_help)).assertExists()
        status = BatteryStatus.PausedVpnOff; compose.waitForIdle()
        compose.onNodeWithText("Measuring starts when the VPN connects.").assertExists()
        compose.onNodeWithText("Connect the VPN to keep measuring.").assertDoesNotExist()
        report = BatteryFakes.notReady(); compose.waitForIdle()
        compose.onNodeWithText("Connect the VPN to keep measuring.").assertExists()
    }
    @Test fun processorUseValueSitsUnderItsHelp() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        scrollTo(hasTestTag("battery-cpu"))
        val help = compose.onNodeWithText(app.getString(R.string.battery_cpu_help), useUnmergedTree = true).getBoundsInRoot()
        val value = compose.onNodeWithText("of one core", substring = true, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("$value under $help", value.top >= help.bottom)
    }
    @Test fun sideCutoutPaddingIsAppliedOnce() {
        val side = WindowInsets(left = 24.dp)
        var case by mutableStateOf(0)
        compose.setContent {
            // 0: nothing applied yet. 1: an ancestor applied and consumed it. 2: the Scaffold padding
            // covers it. 3: a cutout at the top too, which this padding leaves alone.
            val outer = if (case == 1) Modifier.padding(start = 24.dp).consumeWindowInsets(side) else Modifier
            val padding = if (case == 2) PaddingValues(start = 24.dp) else PaddingValues()
            val cutout = if (case == 3) WindowInsets(left = 24.dp, top = 40.dp) else side
            Box(Modifier.fillMaxSize().then(outer)) {
                Box(Modifier.fillMaxSize().readablePadding(padding, cutout)) { Box(Modifier.size(10.dp).testTag("inside")) }
            }
        }
        for (c in 0..3) {
            case = c; compose.waitForIdle()
            compose.onNodeWithTag("inside").assertLeftPositionInRootIsEqualTo(24.dp).assertTopPositionInRootIsEqualTo(0.dp)
        }
    }
    @Test fun homeRowSpeaksItsStatusAndOpensDetails() {
        var opened = 0
        compose.setContent { ConnectTheme { HomeScreen(app.store, VpnState(), false, {}, {}, {}, {}, battery = BatteryStatus.CouldNotSave, onBattery = { opened++ }) } }
        val row = compose.onNodeWithTag("battery-row").performScrollTo()
        row.assert(hasContentDescription("Battery drain, on, couldn’t save"))
            .assert(SemanticsMatcher("open details") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "open details" })
        compose.onNodeWithText("Couldn’t save measurements. Open to retry", useUnmergedTree = true).assertExists()
        row.performClick()
        assertEquals(1, opened)
    }
    @Test fun homeRowWhenOffSpeaksTitleAndHelp() {
        compose.setContent { ConnectTheme { HomeScreen(app.store, VpnState(), false, {}, {}, {}, {}) } }
        compose.onNodeWithTag("battery-row").performScrollTo()
            .assert(hasContentDescription("Battery drain. How fast the phone’s battery drains while the VPN is on."))
    }
    @Test fun homeRowSpeaksOneWholeStringPerStatus() {
        val spoken = BatteryStatus.entries.map { app.getString(batteryHomeSpoken(it)) } + app.getString(batteryHomeSpoken(BatteryStatus.CouldNotSave, BatteryProblem.StartFailed))
        assertEquals(listOf("Battery drain. How fast the phone’s battery drains while the VPN is on.", "Battery drain, measuring",
            "Battery drain, paused: the VPN is off", "Battery drain, paused while charging", "Battery drain, on, couldn’t save",
            "Battery drain, stopped after 7 days", "Battery drain, on, couldn’t start").sorted(), spoken.sorted())
    }
    @Test fun homeRowSaysAStartFailure() {
        compose.setContent { ConnectTheme { HomeScreen(app.store, VpnState(), false, {}, {}, {}, {},
            battery = BatteryStatus.CouldNotSave, batteryProblem = BatteryProblem.StartFailed) } }
        compose.onNodeWithTag("battery-row").performScrollTo().assert(hasContentDescription("Battery drain, on, couldn’t start"))
        compose.onNodeWithText("Couldn’t start measuring. Open to retry", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Couldn’t save measurements. Open to retry", useUnmergedTree = true).assertDoesNotExist()
    }
}

/** Number formatting rules from §3.7 and §3.8. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatteryFormatTest {
    private val app: ConnectApp get() = ApplicationProvider.getApplicationContext()
    private fun f(locale: Locale = Locale.US) = BatteryFormat(app, locale)
    @Test fun percentRoundsHalfUpWithThePrecisionRule() {
        assertEquals("4.3%", f().percent(4.25))
        assertEquals("2.0%", f().percent(2.0))
        assertEquals("12%", f().percent(12.4))
        assertEquals("10%", f().percent(10.0))
        assertEquals("150%", f().percent(150.0))
        assertEquals("200%", f().percent(200.0))
    }
    @Test fun aTinyPercentIsLessThanOneTenthAndZeroIsZero() {
        assertEquals("less than 0.1%", f().percent(0.04))
        assertEquals("less than 0.1%", f().percent(0.0001))
        assertEquals("less than 0.1 percent", f().percent(0.04, spoken = true))
        assertEquals("0.1%", f().percent(0.05))
        assertEquals("0%", f().percent(0.0))
        assertEquals("less than 0.1%", f().share(0.0003))
    }
    @Test fun twoPercentFromTheChargeCounter() {
        val r = BatteryMath.report(batteryTrace().asSequence(), ProxySetup.Automatic)
        assertEquals("2.0%", f().percent(r.all.percentPerHour!!))
        assertEquals("10%", f().percent(r.cpu.totalPercent!!))
    }
    @Test fun comparisonIsAPlainLocalisedDecimal() {
        assertEquals("1.3", f().plain(1.25))
        assertEquals("1,3", f(Locale.GERMANY).plain(1.25))
        assertFalse("%" in f().plain(6.0))
        assertTrue(f().plainIsZero(.04))
        assertFalse(f().plainIsZero(.05))
        val none = BatteryComparison(Uplink.WiFi, ProxySetup.Automatic, BatteryRate(ready = true), BatteryRate(ready = true), BatteryDifference.More, .04)
        val caveat = app.getString(R.string.battery_compare_caveat)
        assertEquals(listOf("No clear difference yet. The two ranges overlap; keep measuring both.", caveat),
            batterySummary(app, BatteryStatus.Measuring, BatteryReport(comparison = none)).lines().takeLast(2))
        val less = none.copy(difference = BatteryDifference.Less, pointsPerHour = 1.25)
        assertTrue(batterySummary(app, BatteryStatus.Measuring, BatteryReport(comparison = less)).endsWith(
            "Wi‑Fi: 1.3 percentage points an hour less with sharing in use\n$caveat"))
    }
    @Test fun sharedSummaryKeepsTheCaveatWithTheDifference() {
        val caveat = app.getString(R.string.battery_compare_caveat)
        val lines = batterySummary(app, BatteryStatus.Measuring, BatteryFakes.compared()).lines()
        val at = lines.indexOfFirst { "percentage points an hour" in it }
        assertTrue(lines.toString(), at >= 0)
        assertEquals(caveat, lines[at + 1])
        // No comparison, no caveat.
        assertFalse(caveat in batterySummary(app, BatteryStatus.Measuring, BatteryFakes.notReady()))
    }
    @Test fun durations() {
        assertTrue(f().duration(200 * 60_000L), f().duration(200 * 60_000L).startsWith("3"))
        assertTrue(f().duration(200 * 60_000L, spoken = true).contains("hours"))
        assertTrue(f().life(50.0, spoken = true), f().life(50.0, spoken = true) == "50 hours")
    }
}

/** Navigation through the real activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class BatteryNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: ConnectApp get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() { app.store.prefs.edit().clear().putBoolean("autoUpdate", false).commit() }
    @Test fun backFromBatteryReturnsHomeAndHotspotConfirmShowsOverIt() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.onNodeWithTag("battery-row").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Measure battery drain").assertExists()
        activity.newIntent(Intent(app, MainActivity::class.java).putExtra(Controls.EXTRA_CONFIRM_HOTSPOT, true))
        compose.waitForIdle()
        compose.onNodeWithText("Share over hotspot?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Measure battery drain").assertExists()
        activity.get().onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithText("Measure battery drain").assertDoesNotExist()
        compose.onNodeWithTag("battery-row").assertExists()
        activity.pause().stop().destroy()
    }
    @Test fun autoStopCopyStaysForTheVisitAndIsClearedOnLeaving() {
        app.store.prefs.edit().putBoolean("batteryAutoStopped", true).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.onNodeWithTag("battery-row").performScrollTo().assert(hasContentDescription("Battery drain, stopped after 7 days")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("battery-status").assert(hasContentDescription("Stopped after 7 days"))
        activity.get().onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertFalse(app.store.batteryAutoStopped)
        activity.pause().stop().destroy()
    }
}
