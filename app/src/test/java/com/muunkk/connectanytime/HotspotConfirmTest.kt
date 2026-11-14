package com.muunkk.connectanytime

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widget's first hotspot tap asks in the app (from Opus's council round 4 harness). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HotspotConfirmTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: ConnectApp get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() { app.store.prefs.edit().clear().putBoolean("autoUpdate", false).commit() }
    private fun ask() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(Controls.EXTRA_CONFIRM_HOTSPOT, true)
    private fun shown(): Boolean { compose.waitForIdle(); return runCatching { compose.onNodeWithText("Share over hotspot?").assertExists() }.isSuccess }

    @Test fun asksOnceAndKeepsItsAnswerAcrossRecreation() {
        var activity = Robolectric.buildActivity(MainActivity::class.java, ask()).setup()
        assertEquals(true, shown())
        activity = activity.recreate()
        assertEquals("still asking after recreation", true, shown())
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(false, shown())
        assertEquals(false, app.store.shareOverHotspot); assertEquals(false, app.store.hotspotExplained)
        activity = activity.recreate()
        assertEquals("not again after Cancel and recreation", false, shown())
        activity.newIntent(ask())
        assertEquals("asks when already open", true, shown())
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(false, shown())
        assertEquals(true, app.store.shareOverHotspot); assertEquals(true, app.store.hotspotExplained)
        activity.pause().stop().destroy()
    }

    @Test fun notFromRecentsAndNotWhenAlreadyOn() {
        val recents = Robolectric.buildActivity(MainActivity::class.java, ask().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).setup()
        assertEquals("a Recents relaunch resends the first intent: ignored", false, shown())
        recents.pause().stop().destroy()
        app.store.shareOverHotspot = true
        val on = Robolectric.buildActivity(MainActivity::class.java, ask()).setup()
        assertEquals(false, shown())
        on.pause().stop().destroy()
    }
}
