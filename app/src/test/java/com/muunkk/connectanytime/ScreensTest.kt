package com.muunkk.connectanytime

import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: ConnectApp
    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.store.prefs.edit().clear().commit()
        app.log.clear()
    }
    @After fun restoreMotion() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    private fun home(state: VpnState = VpnState(), denied: Boolean = false) =
        compose.setContent { ConnectTheme { HomeScreen(app.store, state, denied, {}, {}, {}, {}) } }
    private fun status(prefix: String) =
        compose.onNodeWithTag("status").assert(hasContentDescription("$prefix. ", substring = true))

    @Test fun mainStates() {
        var state by mutableStateOf(VpnState())
        compose.setContent { ConnectTheme { HomeScreen(app.store, state, false, {}, {}, {}, {}) } }
        status("Not connected")
        compose.onNodeWithTag("primary").assert(hasText("Connect"))

        state = VpnState("Connecting… approve Duo on your phone", busy = true)
        status("Connecting…").assert(hasContentDescription("Approve Duo on your phone", substring = true))
        compose.onNodeWithTag("primary").assert(hasText("Disconnect"))
        compose.onNodeWithTag("loading").assertExists()

        state = VpnState("Connected", busy = true, connected = true)
        status("Connected").assert(hasContentDescription("Using vpn.shanghai.nyu.edu", substring = true))
        compose.onNodeWithTag("primary").assert(hasText("Disconnect"))
        compose.onNodeWithTag("loading").assertDoesNotExist()

        state = VpnState("Reconnecting…", busy = true)
        status("Reconnecting…")
        compose.onNodeWithTag("loading").assertExists()

        state = VpnState(error = "Couldn't reach the VPN server.")
        status("Couldn’t connect")
        compose.onNodeWithText("Couldn't reach the VPN server.").assertIsDisplayed()
        compose.onNodeWithTag("primary").assert(hasText("Connect"))

        state = VpnState(error = StartRule.REFUSAL)
        status("VPN session ended")
    }
    @Test fun reducedMotionUsesStaticBadge() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        home(VpnState("Connecting… approve Duo on your phone", busy = true))
        compose.onNodeWithTag("loading").assertDoesNotExist()
        compose.onNodeWithTag("badge").assertExists()
        status("Connecting…")
    }
    @Test fun emptyActivityAndClearConfirmation() {
        app.log.add("First event")
        app.log.add("Latest event")
        compose.setContent { ConnectTheme { ActivityScreen(app.log, {}, {}) } }
        compose.onNodeWithText("Latest event").assertExists()
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("2 lines").assertExists()
        compose.onNodeWithContentDescription("Clear activity").performClick()
        compose.onNodeWithText("Clear activity?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Latest event").assertExists()
        compose.onNodeWithContentDescription("Clear activity").performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("No activity yet").assertExists()
        compose.onNodeWithContentDescription("Clear activity").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share activity").assertIsNotEnabled()
    }
    @Test fun activityIsNewestFirst() {
        // A line without a stamp (older log files) stays under the newer day shown above it.
        val lines = listOf("11-11 23:59:00 Old", "no stamp", "11-12 08:00:00 New")
        val groups = groupLogLines(lines)
        assertEquals(listOf("11-12", "11-11"), groups.map { it.first })
        assertEquals(listOf("New", "no stamp"), groups[0].second.map { it.text })
        assertEquals("08:00:00", groups[0].second[0].time)
        assertEquals(null, groups[0].second[1].time)
    }
    @Test fun accountValidationAndPasswordVisibility() {
        home()
        compose.onNodeWithTag("primary").performClick()
        compose.onNodeWithText("Enter your NetID first.").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Password")).performScrollTo().performTextInput("temporary")
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
    }
    @Test fun removeSavedPasswordNeedsConfirmation() {
        // Only presence is read by this screen; do not ask Robolectric to emulate Keystore.
        app.store.prefs.edit().putString("password", "test-presence").commit()
        home(VpnState("Connecting", busy = true))
        compose.onNodeWithText("Password saved").assertExists()
        compose.onNodeWithText("Remove saved password").performScrollTo().performClick()
        compose.onNodeWithText("Remove saved password?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(app.store.hasPassword)
        compose.onNodeWithText("Remove saved password").performScrollTo().performClick()
        compose.onNodeWithText("Remove").performClick()
        assertFalse(app.store.hasPassword)
        compose.onNodeWithText("Remove saved password").assertDoesNotExist()
        compose.onNodeWithText("vpn.shanghai.nyu.edu").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Disconnect to change the server.").assertExists()
    }
    @Test fun stayConnectedIsASwitch() {
        home()
        val row = compose.onNode(hasText("Stay Connected") and isToggleable())
        row.performScrollTo().assertIsOn()
        row.performClick().assertIsOff()
        assertFalse(app.store.stayConnected)
    }
    @Test @Config(qualifiers = "land") fun largeTextCanReachAboutAndSettings() {
        compose.setContent { ConnectTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HomeScreen(app.store, VpnState(), true, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithText("Android VPN settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.BUILD_DESCRIPTION).performScrollTo().assertIsDisplayed()
    }
}
