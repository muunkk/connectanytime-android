package com.muunkk.connectanytime

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class NotificationDeniedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: ConnectApp get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() {
        app.store.prefs.edit().clear().putBoolean("autoUpdate", false)
            .putBoolean("askedNotifications", true).putString("user", "test-user")
            .putString("password", "test-presence").commit()
    }

    @Test fun notificationDeniedNoteSurvivesRecreation() {
        ShadowVpnService.setPrepareResult(null)
        var activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertEquals(PackageManager.PERMISSION_DENIED,
                activity.get().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            compose.onNodeWithTag("primary").performClick()
            val note = app.getString(R.string.notification_denied)
            compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
            // Robolectric records the request without creating or running the VPN service.
            assertEquals(ConnectVpnService.CONNECT, shadowOf(app).nextStartedService.action)
            assertFalse(ConnectApp.state.value.busy)
            activity = activity.recreate()
            compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
