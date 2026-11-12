package com.muunkk.connectanytime

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35])
class StoreTest {
    @Test fun corruptCredentialsAreRemovedAndDoNotBlockConnect() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        val store = app.store
        store.prefs.edit().putString("session", "!invalid-base64!")
            .putString("password", "!invalid-base64!").commit()
        assertNull(store.session())
        assertFalse(store.hasSession)
        assertNull(store.password())
        assertFalse(store.hasPassword)
        val lines = app.log.state.value.size
        assertNull(store.session())
        assertNull(store.password())
        assertEquals(lines, app.log.state.value.size) // one diagnostic per discarded value
        assertNull(StartRule.refusal(true, store.hasSession, false))
    }
}
