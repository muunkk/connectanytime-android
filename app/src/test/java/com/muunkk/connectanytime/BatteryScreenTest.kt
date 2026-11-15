package com.muunkk.connectanytime

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private var retried = 0
    /** False: the toggle goes nowhere (the status doesn't change). */
    private var toggleTakes = true
    private fun show() {
        compose.setContent { ConnectTheme { BatteryContent(app.store, status, report, false, {}, onToggle = { on ->
            if (toggleTakes) status = if (on) BatteryStatus.PausedVpnOff else BatteryStatus.Off
        }, onClear = {}, onRetry = { retried++ }, onShare = {}) } }
        compose.waitForIdle()
    }
    private val switch get() = compose.onNodeWithTag("battery-switch")
    private val line get() = compose.onNodeWithTag("battery-status")
    private fun stateDescription(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private fun scrollTo(matcher: SemanticsMatcher) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    private val noLiveRegion = SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)

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
        // Measuring is hidden from TalkBack, but the line is in the tree.
        line.assertExists().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)).assert(noLiveRegion)
        status = BatteryStatus.PausedCharging; compose.waitForIdle()
        line.assert(polite).assert(hasContentDescription("Paused while charging"))
        line.assertIsDisplayed()
        // The user's own toggle is announced by the switch, so the line drops its live region.
        switch.performClick(); compose.waitForIdle()
        assertEquals(BatteryStatus.Off, status)
        line.assertDoesNotExist()
        switch.performClick(); compose.waitForIdle()
        line.assert(hasContentDescription("Paused: the VPN is off")).assert(noLiveRegion)
        status = BatteryStatus.CouldNotSave; compose.waitForIdle()
        line.assert(polite)
    }
    @Test fun aToggleThatChangesNothingDoesNotSilenceTheNextSystemChange() {
        status = BatteryStatus.Measuring; show()
        toggleTakes = false
        switch.performClick(); compose.waitForIdle()
        assertEquals(BatteryStatus.Measuring, status)
        status = BatteryStatus.PausedCharging; compose.waitForIdle()
        line.assert(polite).assert(hasContentDescription("Paused while charging"))
    }
    @Test fun measuringAgainAfterASystemPauseIsAnnounced() {
        status = BatteryStatus.Measuring; show()
        status = BatteryStatus.PausedVpnOff; compose.waitForIdle()
        status = BatteryStatus.Measuring; compose.waitForIdle()
        line.assert(polite).assert(hasContentDescription("Measuring again"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        compose.onNodeWithText("Measuring", useUnmergedTree = true).assertIsDisplayed()
        // Back from anything but a pause (here a save problem), Measuring stays hidden and quiet.
        status = BatteryStatus.CouldNotSave; compose.waitForIdle()
        status = BatteryStatus.Measuring; compose.waitForIdle()
        line.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)).assert(noLiveRegion)
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
    @Test fun couldNotSaveRowIsNotClickableAndRetryIsItsOwnTarget() {
        status = BatteryStatus.CouldNotSave; report = BatteryFakes.notReady(); show()
        line.assert(hasContentDescription("Couldn’t save measurements. Try saving again is below."))
        line.assertIsDisplayed()
        compose.onNodeWithText("Couldn’t save", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("battery-save-failed").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Couldn’t save measurements").assertExists()
        val retry = compose.onNodeWithTag("battery-retry").assertHasClickAction()
        retry.assertHeightIsAtLeast(48.dp)
        retry.performClick()
        assertEquals(1, retried)
        switch.assertIsOn()
        assertTrue(compose.onAllNodesWithText("Problem", substring = true).fetchSemanticsNodes().isEmpty())
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
            .assertTextEquals("Mobile data: 6.0 percentage points an hour more with sharing in use")
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
    @Test fun moreDetailIsAnExpandableButton() {
        status = BatteryStatus.Measuring; report = BatteryFakes.compared(); show()
        scrollTo(hasTestTag("battery-more"))
        val more = compose.onNodeWithTag("battery-more")
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assert(stateDescription("Collapsed"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).assertHeightIsAtLeast(56.dp)
        more.performSemanticsAction(SemanticsActions.Expand); compose.waitForIdle()
        more.assert(stateDescription("Expanded")).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        scrollTo(hasText("Connection type"))
        compose.onNodeWithText("Connection type").assertIsDisplayed()
        compose.onNodeWithText("VPN: at least 3.3% of one core").assertExists()
    }
    @Test fun offWithoutRowsShowsOnlyTheSwitchPanelAndFootnotes() {
        status = BatteryStatus.Off; report = BatteryReport(); show()
        compose.onNodeWithText("Not measuring yet").assertExists()
        line.assertDoesNotExist()
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
    @Test fun clearDialogSaysHowMuchAndThatMeasuringContinues() {
        status = BatteryStatus.Measuring; report = BatteryFakes.notReady(); show()
        compose.onNodeWithContentDescription("Clear battery data").performClick()
        compose.onNodeWithText("Clear battery data?").assertExists()
        compose.onNodeWithText("This removes 20 min of battery measurements saved on this device. Measuring continues.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear battery data?").assertDoesNotExist()
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
        assertEquals("less than a minute", f().stored(59_000))
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
