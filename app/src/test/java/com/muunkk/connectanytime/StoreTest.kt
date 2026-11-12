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

    @Test fun aTransientKeystoreErrorKeepsBothSavedValues() {
        val store = ApplicationProvider.getApplicationContext<ConnectApp>().store
        store.prefs.edit().putString("session", "kept-session").putString("password", "kept-password").commit()
        for (e in listOf(java.security.UnrecoverableKeyException("System error"), java.security.InvalidKeyException("busy"),
                         javax.crypto.IllegalBlockSizeException("busy"), java.security.ProviderException("busy"))) {
            assertThrows(Exception::class.java) { store.recover("session", reader = { throw e }) { it } }
            assertThrows(Exception::class.java) { store.recover("password", reader = { throw e }) { it } }
        }
        assertTrue(store.hasSession)
        assertTrue(store.hasPassword)
    }

    @Test fun onlyUnreadableDataCountsAsPermanent() {
        assertTrue(CredentialFailure.isPermanent(javax.crypto.AEADBadTagException()))
        assertTrue(CredentialFailure.isPermanent(android.security.keystore.KeyPermanentlyInvalidatedException()))
        assertTrue(CredentialFailure.isPermanent(IllegalArgumentException()))
        assertTrue(CredentialFailure.isPermanent(org.json.JSONException("x")))
        assertFalse(CredentialFailure.isPermanent(java.security.UnrecoverableKeyException()))
        assertFalse(CredentialFailure.isPermanent(java.security.InvalidKeyException()))
        assertFalse(CredentialFailure.isPermanent(javax.crypto.IllegalBlockSizeException()))
        assertFalse(CredentialFailure.isPermanent(java.security.ProviderException()))
    }

    @Test fun aTruncatedValueIsDiscardedNotThrown() {
        val store = ApplicationProvider.getApplicationContext<ConnectApp>().store
        store.prefs.edit().putString("session", android.util.Base64.encodeToString(ByteArray(5), android.util.Base64.NO_WRAP)).commit()
        assertNull(store.session())
        assertFalse(store.hasSession)
    }
}
