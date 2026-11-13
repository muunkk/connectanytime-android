package com.muunkk.connectanytime

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: ConnectApp
    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.store.prefs.edit().clear().commit()
        app.log.clear()
    }

    private val digest = "a".repeat(64)
    private fun asset(name: String, digest: String? = "sha256:${this.digest}", size: Long = 16_000_000,
        url: String = "https://github.com/${ReleaseFeed.REPO}/releases/download/v1/$name", state: String = "uploaded") =
        JSONObject().put("name", name).put("size", size).put("state", state).put("browser_download_url", url)
            .apply { if (digest != null) put("digest", digest) }
    private fun release(tag: String, vararg assets: JSONObject, draft: Boolean = false, prerelease: Boolean = true) =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("html_url", "https://github.com/${ReleaseFeed.REPO}/releases/tag/$tag").put("assets", JSONArray(assets.toList()))
    private fun feed(vararg releases: JSONObject) = JSONArray(releases.toList()).toString()

    @Test fun picksTheNewestBuildAboveThisOne() {
        val json = feed(release("v0.1.2", asset("ConnectAnytime-0.1.2-3.apk")), release("v0.1.3", asset("ConnectAnytime-0.1.3-4.apk")),
            release("v0.1.1", asset("ConnectAnytime-0.1.1-2.apk")))
        val newest = ReleaseFeed.newest(json, 2)!!
        assertEquals("0.1.3", newest.versionName)
        assertEquals(4, newest.versionCode)
        assertEquals(digest, newest.sha256)
        assertEquals("https://github.com/${ReleaseFeed.REPO}/releases/tag/v0.1.3", newest.page)
        assertNull(ReleaseFeed.newest(json, 4))
    }

    /** GitHub's real answer on 2026-11-12, trimmed to the fields that matter. */
    @Test fun readsTheLiveFeed() {
        val json = javaClass.classLoader!!.getResource("github-releases.json")!!.readText()
        val newest = ReleaseFeed.newest(json, 1)!!
        assertEquals(Release("0.1.1", 2,
            "https://github.com/${ReleaseFeed.REPO}/releases/download/v0.1.1/ConnectAnytime-0.1.1-2.apk", 16362772,
            "d29b1be56e52df96a6af417fbd2cbb92968d5f3cb442d119a62196b02cfeb90e",
            "https://github.com/${ReleaseFeed.REPO}/releases/tag/v0.1.1"), newest)
        assertNull(ReleaseFeed.newest(json, 2))
    }

    @Test fun ignoresAnythingItCannotCheck() {
        fun only(vararg assets: JSONObject, draft: Boolean = false) = ReleaseFeed.newest(feed(release("v9", *assets, draft = draft)), 1)
        val name = "ConnectAnytime-9.0-9.apk"
        assertNull(only(asset(name), draft = true))
        assertNull(only(asset(name, digest = null)))
        assertNull(only(asset(name, digest = "sha1:" + "a".repeat(40))))
        assertNull(only(asset(name, digest = "sha256:" + "A".repeat(64))))
        assertNull(only(asset(name, url = "https://example.com/$name")))
        assertNull(only(asset(name, url = "https://github.com/someone/else/releases/download/v9/$name")))
        assertNull(only(asset(name, url = "https://github.com/${ReleaseFeed.REPO}/releases/download/v9/Other.apk")))
        assertNull(only(asset(name, url = "https://github.com/${ReleaseFeed.REPO}/releases/download/../../x/$name")))
        assertNull(only(asset(name, size = 0)))
        assertNull(only(asset(name, size = ReleaseFeed.MAX_APK + 1)))
        assertNull(only(asset(name, state = "starter")))
        assertNull(only(asset("ConnectAnytime-9.0-9.apk.sha256")))
        assertNull(only(asset("connectanytime-9.0-9.apk")))
        assertNull(only(asset("ConnectAnytime-9.0-x.apk")))
        // And the release page must be this repo's.
        val elsewhere = release("v9", asset(name)).put("html_url", "https://example.com/v9")
        assertNull(ReleaseFeed.newest(feed(elsewhere), 1))
        assertEquals(9, only(asset(name))!!.versionCode)
    }

    @Test fun malformedFeedThrows() {
        assertTrue(runCatching { ReleaseFeed.newest("{\"message\":\"Not Found\"}", 1) }.isFailure)
        assertNull(ReleaseFeed.newest("[]", 1))
        assertNull(ReleaseFeed.newest("[null, 3, {\"assets\": 4}]", 1))
    }

    @Test fun releaseRoundTrips() {
        val r = Release("0.1.2", 3, "https://github.com/x", 10, digest, "https://github.com/p")
        assertEquals(r, Release.parse(r.json()))
        assertNull(Release.parse("{}"))
        assertNull(Release.parse("not json"))
        assertEquals("ConnectAnytime-0.1.2-3.apk", r.fileName)
    }

    @Test fun updatedAppSaysSoOnce() {
        app.store.updatingFrom = "ConnectAnytime 0.1.1 (build 2)"
        val replaced = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        BootReceiver().onReceive(app, replaced)
        assertNull(app.store.updatingFrom)
        assertTrue(app.log.state.value.last().endsWith("Updated from ConnectAnytime 0.1.1 (build 2) to ${BuildConfig.BUILD_DESCRIPTION}"))
        val lines = app.log.state.value.size
        BootReceiver().onReceive(app, replaced)
        assertEquals(lines, app.log.state.value.size)
        // A boot is not an update.
        app.store.updatingFrom = "x"
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("x", app.store.updatingFrom)
    }

    private val ready = Release("0.1.2", 3, "https://github.com/x", 16_000_000, digest, "https://github.com/${ReleaseFeed.REPO}/releases/tag/v0.1.2")
    private fun home(update: UpdateState, vpn: VpnState = VpnState(), onCheck: () -> Unit = {}, onInstall: () -> Unit = {}) =
        compose.setContent { ConnectTheme { HomeScreen(app.store, vpn, false, {}, {}, {}, {}, update, onCheck, onInstall) } }

    private fun shows(tag: String, text: String) =
        compose.onNode(hasTestTag(tag) and hasAnyDescendant(hasText(text, substring = true))).assertExists()

    @Test fun checkRowShowsWhereItStands() {
        var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        var checks = 0
        compose.setContent { ConnectTheme { HomeScreen(app.store, VpnState(), false, {}, {}, {}, {}, update, { checks++ }) } }
        compose.onNodeWithTag("update-check").performScrollTo().assert(hasText("GitHub releases", substring = true)).performClick()
        assertEquals(1, checks)
        update = UpdateState.Checking
        compose.onNodeWithTag("update-check").assert(hasText("Checking GitHub", substring = true)).assertIsNotEnabled()
        update = UpdateState.UpToDate(System.currentTimeMillis())
        compose.onNodeWithTag("update-check").assert(hasText("latest version. Checked just now.", substring = true)).assertIsEnabled()
        update = UpdateState.Failed(R.string.update_busy)
        compose.onNodeWithTag("update-check").assert(hasText("limiting update checks", substring = true)).performClick()
        assertEquals(2, checks)
        update = UpdateState.Downloading(ready, 0.42f)
        shows("update-downloading", "42%")
        compose.onNodeWithTag("update-banner").assertDoesNotExist()
    }

    @Test fun readyUpdateOffersInstallInBothPlaces() {
        var installs = 0
        app.store.prefs.edit().putString("session", "presence-only").commit()
        home(UpdateState.Ready(ready), VpnState("Connected", busy = true, connected = true), onInstall = { installs++ })
        shows("update-banner", "Update ready: version 0.1.2")
        shows("update-banner", "rejoins your saved session by itself, without Duo")
        compose.onNode(hasText("Install") and hasAnyAncestor(hasTestTag("update-banner"))).performClick()
        compose.onNodeWithTag("update-install").performScrollTo().performClick()
        assertEquals(2, installs)
        compose.onNodeWithText("What’s new", substring = true).assertExists()
    }

    @Test fun installNoteFollowsTheVpn() {
        app.store.stayConnected = false
        home(UpdateState.Ready(ready, R.string.update_cancelled), VpnState("Connected", busy = true, connected = true))
        shows("update-banner", "turns off for the update")
        compose.onNodeWithTag("update-ready").performScrollTo()
        shows("update-ready", "install was cancelled")
    }

    @Test fun installNoteSaysTheVpnStartsWhenStayConnectedWill() {
        app.store.prefs.edit().putString("session", "presence-only").commit()
        home(UpdateState.Ready(ready))
        shows("update-banner", "Stay Connected rejoins your saved session")
    }

    @Test fun installNoteAfterDisconnect() {
        // Disconnect pauses automatic starts, so an update doesn't start the VPN.
        app.store.prefs.edit().putString("session", "presence-only").commit()
        app.store.disconnected = true
        home(UpdateState.Ready(ready))
        shows("update-banner", "Open it again afterwards")
    }

    @Test fun installingOffersTryAgain() {
        var installs = 0
        home(UpdateState.Installing(ready), onInstall = { installs++ })
        shows("update-banner", "Installing version 0.1.2")
        // Not at first: a silent install takes a while, and a double tap mustn't land on it.
        compose.onNode(hasText("Try again") and hasAnyAncestor(hasTestTag("update-banner"))).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(Updater.RETRY_AFTER + 1_000)
        shows("update-banner", "Nothing showing? Tap Try again")
        compose.onNode(hasText("Install") and hasAnyAncestor(hasTestTag("update-banner"))).assertDoesNotExist()
        compose.onNode(hasText("Try again") and hasAnyAncestor(hasTestTag("update-banner"))).performClick()
        compose.onNode(hasText("Try again") and hasAnyAncestor(hasTestTag("update-installing"))).performScrollTo().performClick()
        assertEquals(2, installs)
    }

    @Test fun automaticChecksCanBeTurnedOff() {
        home(UpdateState.Idle)
        assertTrue(app.store.autoUpdate)
        compose.onNodeWithTag("update-auto").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(app.store.autoUpdate)
    }
}
