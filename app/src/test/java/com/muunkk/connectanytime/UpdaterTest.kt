package com.muunkk.connectanytime

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** The updater's steps, with a fake GitHub and fake APK facts; runs each step to completion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdaterTest {
    private lateinit var app: ConnectApp
    private val dir get() = File(app.noBackupFilesDir, "updates")
    private val current = BuildConfig.VERSION_CODE

    /** What the fake GitHub serves. */
    private var apks = mutableMapOf<String, ByteArray>()
    private var feedJson = "[]"
    private var feedCode = 200
    private var downloadCode = 200
    private var served = mutableMapOf<String, ByteArray>() // URL -> bytes actually sent (may differ from apks)
    private val requests = mutableListOf<Pair<String, String?>>()
    /** What "Android" reads from an APK, by its bytes. */
    private var facts: (ByteArray) -> ApkFacts? = { ApkFacts(app.packageName, versionOf(it), setOf("me")) }
    private var installed: Set<String>? = setOf("me")

    private fun versionOf(bytes: ByteArray) = String(bytes).substringAfter("code=").substringBefore(';').toLong()
    private fun apk(code: Int, extra: String = "") = "apk;code=$code;$extra".toByteArray()
    private fun sha(b: ByteArray) = Updater.hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun url(name: String) = "https://github.com/${ReleaseFeed.REPO}/releases/download/v/$name"

    private fun publish(vararg releases: Pair<Int, ByteArray>, name: (Int) -> String = { "0.9.$it" }) {
        val list = JSONArray()
        for ((code, bytes) in releases) {
            val file = "ConnectAnytime-${name(code)}-$code.apk"
            served[url(file)] = bytes; apks[url(file)] = bytes
            list.put(JSONObject().put("draft", false).put("html_url", "https://github.com/${ReleaseFeed.REPO}/releases/tag/v$code")
                .put("assets", JSONArray().put(JSONObject().put("name", file).put("size", bytes.size).put("state", "uploaded")
                    .put("digest", "sha256:" + sha(bytes)).put("browser_download_url", url(file)))))
        }
        feedJson = list.toString()
    }

    private val http = Http { url, _, etag ->
        requests += url to etag
        if (url == ReleaseFeed.API) {
            if (etag != null && etag == "\"e-${feedJson.hashCode()}\"") Response(304, etag, null)
            else Response(feedCode, "\"e-${feedJson.hashCode()}\"", feedJson.byteInputStream())
        } else Response(downloadCode, null, served[url]?.inputStream())
    }

    private fun updater() = Updater(app, http, { f -> facts(f.readBytes()) }, { installed }, Dispatchers.Unconfined)

    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.store.prefs.edit().clear().commit()
        app.log.clear()
        dir.deleteRecursively()
        File(app.noBackupFilesDir, "release-feed.json").delete()
    }

    @Test fun nothingNewerIsUpToDate() {
        publish(current to apk(current))
        val u = updater()
        u.check()
        assertTrue(u.state.value is UpdateState.UpToDate)
        assertTrue(app.store.upToDateAt > 0 && app.store.lastUpdateCheck > 0)
        // And after a restart it still says so.
        assertTrue(updater().state.value is UpdateState.UpToDate)
    }

    @Test fun newerReleaseDownloadsVerifiesAndIsReady() {
        publish(current + 1 to apk(current + 1))
        val u = updater()
        u.check()
        val ready = u.state.value as UpdateState.Ready
        assertEquals(current + 1, ready.release.versionCode)
        assertTrue(File(dir, ready.release.fileName).isFile)
        assertEquals(listOf(ready.release.fileName), dir.list()!!.toList())
        assertEquals(ready.release, Release.parse(app.store.pendingUpdate!!))
        assertTrue(app.log.state.value.last().contains("Downloaded update"))
        // Survives a restart.
        assertEquals(ready, updater().state.value)
    }

    @Test fun corruptOrOversizedDownloadsAreDeletedAndRetried() {
        publish(current + 1 to apk(current + 1))
        val link = served.keys.single()
        served[link] = apk(current + 1, "tampered")
        val u = updater()
        u.check()
        assertEquals(UpdateState.Failed(R.string.update_corrupt), u.state.value)
        assertTrue(dir.list().isNullOrEmpty())
        // Not "up to date" afterwards, and the next opening tries again.
        assertEquals(0L, app.store.lastUpdateCheck)
        assertFalse(updater().state.value is UpdateState.UpToDate)
        served[link] = apks[link]!! + "more".toByteArray()
        u.check()
        assertEquals(UpdateState.Failed(R.string.update_corrupt), u.state.value)
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test fun wrongPackageVersionOrSignerIsRejectedOnce() {
        publish(current + 1 to apk(current + 1))
        installed = setOf("someone else")
        val u = updater()
        u.check()
        assertEquals(UpdateState.Failed(R.string.update_wrong_app), u.state.value)
        assertTrue(dir.list().isNullOrEmpty())
        assertTrue(app.log.state.value.last().contains("different signing certificate"))
        val downloads = requests.count { it.first != ReleaseFeed.API }
        u.check()
        assertEquals(downloads, requests.count { it.first != ReleaseFeed.API })
        assertEquals(UpdateState.Failed(R.string.update_wrong_app), u.state.value)

        reset(); installed = setOf("me")
        publish(current + 1 to apk(current + 1))
        facts = { ApkFacts("com.example.other", versionOf(it), setOf("me")) }
        updater().apply { check(); assertEquals(UpdateState.Failed(R.string.update_wrong_app), state.value) }

        reset()
        publish(current + 1 to apk(current + 5))
        facts = { ApkFacts(app.packageName, versionOf(it), setOf("me")) }
        updater().apply { check(); assertEquals(UpdateState.Failed(R.string.update_wrong_app), state.value) }
    }

    @Test fun signerRules() {
        assertTrue(Updater.signedAlike(setOf("a"), setOf("a")))
        assertTrue(Updater.signedAlike(setOf("a"), setOf("old", "a"))) // rotation history
        assertFalse(Updater.signedAlike(setOf("a"), setOf("b")))
        assertFalse(Updater.signedAlike(setOf("a", "b"), setOf("a")))
        assertFalse(Updater.signedAlike(emptySet(), setOf("a")))
        // Unreadable: Android's installer still enforces the same signer.
        assertTrue(Updater.signedAlike(null, setOf("a")))
        assertTrue(Updater.signedAlike(setOf("a"), null))
    }

    @Test fun aFailedNewerDownloadKeepsTheReadyOne() {
        publish(current + 1 to apk(current + 1))
        val u = updater()
        u.check()
        val ready = u.state.value as UpdateState.Ready
        publish(current + 1 to apk(current + 1), current + 2 to apk(current + 2))
        downloadCode = 500
        u.check()
        assertEquals(ready, u.state.value)
        assertTrue(File(dir, ready.release.fileName).isFile)
        downloadCode = 200
        u.check()
        assertEquals(current + 2, (u.state.value as UpdateState.Ready).release.versionCode)
        assertEquals(1, dir.list()!!.size)
    }

    @Test fun aRepublishedReleaseThatFailsKeepsTheVerifiedFile() {
        publish(current + 1 to apk(current + 1))
        val u = updater()
        u.check()
        val ready = u.state.value as UpdateState.Ready
        // Same name and build, new bytes; its download breaks.
        publish(current + 1 to apk(current + 1, "rebuilt"))
        served[served.keys.single()] = "broken".toByteArray()
        u.check()
        assertEquals(ready, u.state.value)
        val kept = File(dir, ready.release.fileName)
        assertEquals(ready.release.sha256, sha(kept.readBytes()))
    }

    @Test fun restoreDropsStaleOrMissingDownloads() {
        val old = Release("0.0.1", current, url("x"), 3, sha("abc".toByteArray()), "p")
        dir.mkdirs(); File(dir, old.fileName).writeBytes("abc".toByteArray())
        app.store.pendingUpdate = old.json()
        assertEquals(UpdateState.Idle, updater().state.value)
        assertNull(app.store.pendingUpdate)
        assertTrue(dir.list().isNullOrEmpty())

        val newer = Release("9.0", current + 1, url("y"), 3, sha("abc".toByteArray()), "p")
        app.store.pendingUpdate = newer.json()
        assertEquals(UpdateState.Idle, updater().state.value) // file missing
        assertNull(app.store.pendingUpdate)
    }

    @Test fun releaseListIsRevalidatedWithItsEtag() {
        publish(current to apk(current))
        val u = updater()
        u.check(); u.check()
        assertNull(requests[0].second)
        assertEquals("\"e-${feedJson.hashCode()}\"", requests[1].second)
        assertTrue(u.state.value is UpdateState.UpToDate)
    }

    @Test fun rateLimitsAndBadListsSayWhy() {
        feedCode = 403
        updater().apply { check(); assertEquals(UpdateState.Failed(R.string.update_busy), state.value) }
        feedCode = 200; feedJson = "{\"message\":\"x\"}"
        updater().apply { check(); assertEquals(UpdateState.Failed(R.string.update_bad_feed), state.value) }
        // A bad list isn't cached for the next 304.
        assertFalse(File(app.noBackupFilesDir, "release-feed.json").exists())
    }

    private fun readyUpdater(): Pair<Updater, Release> {
        publish(current + 1 to apk(current + 1))
        val u = updater()
        u.check()
        return u to (u.state.value as UpdateState.Ready).release
    }

    @Test fun installCommitsOneSessionAndRemembersIt() {
        val (u, release) = readyUpdater()
        u.install()
        assertEquals(UpdateState.Installing(release), u.state.value)
        val first = app.store.installSession
        assertNotEquals(Updater.NO_SESSION, first)
        assertEquals(BuildConfig.BUILD_DESCRIPTION, app.store.updatingFrom)
        // Try again replaces the attempt; the old one's answers no longer count.
        u.install()
        val second = app.store.installSession
        assertNotEquals(first, second)
        u.onStatus(first, PackageInstaller.STATUS_FAILURE_ABORTED, null, null)
        assertEquals(UpdateState.Installing(release), u.state.value)
        assertEquals(second, app.store.installSession)
    }

    @Test fun installerAnswers() {
        val (u, release) = readyUpdater()
        u.install()
        var session = app.store.installSession
        // Confirmation: held for the activity; with the app in the background, a notification too.
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        u.foreground = false
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        u.onStatus(session, PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirm)
        assertEquals(confirm, u.confirmation.value)
        val notices = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertTrue(notices.allNotifications.any { it.extras.getString("android.title")!!.startsWith("Finish installing") })
        assertEquals(confirm, u.takeConfirmation())
        assertNull(u.takeConfirmation())
        assertFalse(notices.allNotifications.any { it.extras.getString("android.title")!!.startsWith("Finish installing") })

        // Cancelled: ready again, markers cleared.
        u.onStatus(session, PackageInstaller.STATUS_FAILURE_ABORTED, null, null)
        assertEquals(UpdateState.Ready(release, R.string.update_cancelled), u.state.value)
        assertEquals(Updater.NO_SESSION, app.store.installSession)
        assertNull(app.store.updatingFrom)

        // A transient failure keeps the file; an incompatible APK is dropped for good.
        u.install(); session = app.store.installSession
        u.onStatus(session, PackageInstaller.STATUS_FAILURE_STORAGE, "full", null)
        assertEquals(UpdateState.Ready(release, R.string.update_refused), u.state.value)
        u.install(); session = app.store.installSession
        u.onStatus(session, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, "signatures", null)
        assertEquals(UpdateState.Failed(R.string.update_refused), u.state.value)
        assertTrue(dir.list().isNullOrEmpty())
        assertEquals(release.sha256, app.store.rejectedUpdate)
    }

    @Test fun aRestartDuringAnInstallFindsIt() {
        val (u, release) = readyUpdater()
        u.install()
        // The session is still open in Android, so a new process shows it as installing.
        assertEquals(UpdateState.Installing(release), updater().state.value)
        // Once Android has dropped it, it's ready again.
        app.packageManager.packageInstaller.abandonSession(app.store.installSession)
        assertEquals(UpdateState.Ready(release), updater().state.value)
        assertNull(app.store.updatingFrom)
    }

    @Test fun anUnexpectedErrorDoesNotCrash() {
        publish(current + 1 to apk(current + 1))
        facts = { throw IllegalStateException("boom") }
        val u = updater()
        u.check()
        assertTrue(u.state.value is UpdateState.Failed)
    }

    @Test fun anUpdateDoesNotChangeWhetherTheVpnRestarts() {
        app.store.updatingFrom = "x"
        app.store.disconnected = true
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(Controls.restartsAutomatically(app.store))
    }
}
