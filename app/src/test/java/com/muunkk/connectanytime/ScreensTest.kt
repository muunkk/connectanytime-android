package com.muunkk.connectanytime

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
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
    @Test fun emptyActivityAndClearConfirmation() {
        app.log.add("First event")
        app.log.add("Latest event")
        compose.setContent { ConnectTheme { ActivityScreen(app.log, {}, {}) } }
        compose.onNodeWithText("Latest event", substring = true).assertExists()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Clear activity?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Latest event", substring = true).assertExists()
        compose.onNodeWithText("Clear").performClick()
        compose.onAllNodesWithText("Clear").onLast().performClick()
        compose.onNodeWithText("No activity yet").assertExists()
    }
    @Test fun accountValidationAndPasswordVisibility() {
        compose.setContent { ConnectTheme { HomeScreen(app.store, VpnState(), false, {}, {}, {}, {}) } }
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Enter your NetID first.").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Password")).performScrollTo().performTextInput("temporary")
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
    }
    @Test fun removeSavedPasswordAndBusyServer() {
        // Only presence is read by this screen; do not ask Robolectric to emulate Keystore.
        app.store.prefs.edit().putString("password", "test-presence").commit()
        compose.setContent { ConnectTheme { HomeScreen(app.store, VpnState("Connecting", true), false, {}, {}, {}, {}) } }
        compose.onNodeWithText("Remove saved password").performScrollTo().performClick()
        assertFalse(app.store.hasPassword)
        compose.onNodeWithText("vpn.shanghai.nyu.edu").performScrollTo().assertIsNotEnabled()
    }
    @Test @Config(qualifiers = "land") fun largeTextCanReachAboutAndSettings() {
        compose.setContent { ConnectTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HomeScreen(app.store, VpnState(), true, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithText("Open Android VPN settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.BUILD_DESCRIPTION).performScrollTo().assertIsDisplayed()
    }
}
