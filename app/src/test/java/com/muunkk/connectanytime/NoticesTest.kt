package com.muunkk.connectanytime

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticesTest {
    @Test fun ongoingNotificationOpensTheLauncherTask() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        val intent = shadowOf(Notices.ongoing(app, "Connected").contentIntent).savedIntent
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_LAUNCHER))
        assertEquals(ComponentName(app, MainActivity::class.java), intent.component)
    }
}
