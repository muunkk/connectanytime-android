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
    @Test fun tetherSharingDefaultsOffAndPersistsWithoutVpnRestart() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        app.store.prefs.edit().remove("shareWithTether").commit()
        assertFalse(app.store.shareWithTether)
        app.store.shareWithTether = true
        assertTrue(Store(app).shareWithTether)
        app.store.shareWithTether = false
        assertFalse(Store(app).shareWithTether)
    }

    @Test fun hotspotSharingIsSeparateAndStaysOffForExistingUsbSharers() {
        val app = ApplicationProvider.getApplicationContext<ConnectApp>()
        app.store.prefs.edit().clear().putBoolean("shareWithTether", true).commit() // as 0.1.1 left it
        assertFalse(Store(app).shareOverHotspot)
        assertTrue(Store(app).shares(TetherLink.Usb))
        assertFalse(Store(app).shares(TetherLink.Hotspot))
        app.store.shareOverHotspot = true; app.store.shareWithTether = false
        assertTrue(Store(app).sharing)
        assertTrue(Store(app).shares(TetherLink.Hotspot))
        app.store.shareOverHotspot = false
        assertFalse(Store(app).sharing)
    }

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

    @Test fun aSessionKeepsItsCandidateKeyAndOlderSessionsHaveNone() {
        val saved = SavedSession("vpn.example.edu", "ab123", "https://vpn.example.edu/", "openconnect_strapkey=A; webvpn=x",
            "vpn.example.edu", "192.0.2.1", "openconnect_strapkey=B; webvpn=x")
        val read = SavedSession.fromJson(saved.json())
        assertEquals(saved, read)
        assertArrayEquals(arrayOf(saved.url, saved.cookie, saved.dnsName, saved.ip), read.native())
        assertArrayEquals(arrayOf(saved.url, saved.candidate, saved.dnsName, saved.ip), read.candidateNative())
        // As 0.1.3 saved it: no candidate field.
        val legacy = org.json.JSONObject().put("server", saved.server).put("user", saved.user).put("url", saved.url)
            .put("cookie", saved.cookie).put("dnsName", saved.dnsName).put("ip", saved.ip).toString()
        assertEquals(saved.copy(candidate = ""), SavedSession.fromJson(legacy))
        assertFalse(saved.toString().contains("strapkey"))
    }

    @Test fun aTruncatedValueIsDiscardedNotThrown() {
        val store = ApplicationProvider.getApplicationContext<ConnectApp>().store
        store.prefs.edit().putString("session", android.util.Base64.encodeToString(ByteArray(5), android.util.Base64.NO_WRAP)).commit()
        assertNull(store.session())
        assertFalse(store.hasSession)
    }
}
