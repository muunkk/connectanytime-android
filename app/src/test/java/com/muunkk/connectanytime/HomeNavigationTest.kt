package com.muunkk.connectanytime

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HomeNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: ConnectApp get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() {
        app.store.prefs.edit().clear().putBoolean("autoUpdate", false)
            .putBoolean("shareWithTether", true).commit()
    }

    @Test fun homeNetworkDraftSurvivesActivityRoundTrip() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            compose.onNodeWithTag("setup-automatic").performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.setup_edit_home)).performScrollTo().performClick()
            val draft = "192.168.42.0/24"
            compose.onNodeWithTag("setup-home-field").performScrollTo().performTextReplacement(draft)
            compose.onNodeWithText(app.getString(R.string.activity)).performScrollTo().performClick()
            // Wait for AnimatedContent to dispose HomeScreen before returning.
            compose.waitForIdle()
            compose.onNodeWithTag("setup-home-field").assertDoesNotExist()
            compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            compose.onNodeWithTag("setup-home-field").assertExists().assertTextContains(draft)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
