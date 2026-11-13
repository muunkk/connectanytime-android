package com.muunkk.connectanytime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A release on GitHub that this app can update to. */
data class Release(val versionName: String, val versionCode: Int, val apkUrl: String, val size: Long, val sha256: String, val page: String) {
    val fileName get() = "ConnectAnytime-$versionName-$versionCode.apk"
    fun json(): String = JSONObject().put("versionName", versionName).put("versionCode", versionCode).put("apkUrl", apkUrl)
        .put("size", size).put("sha256", sha256).put("page", page).toString()
    companion object {
        fun parse(json: String): Release? = runCatching {
            val j = JSONObject(json)
            Release(j.getString("versionName"), j.getInt("versionCode"), j.getString("apkUrl"), j.getLong("size"),
                j.getString("sha256"), j.getString("page"))
        }.getOrNull()
    }
}

/** Reads GitHub's release list for this repo. Pure, so it's tested without a network. */
object ReleaseFeed {
    const val REPO = "muunkk/connectanytime-android"
    const val API = "https://api.github.com/repos/$REPO/releases?per_page=20"
    private const val PAGES = "https://github.com/$REPO/releases/"
    private const val DOWNLOADS = "https://github.com/$REPO/releases/download/"
    const val MAX_APK = 200L * 1024 * 1024
    // The name scripts/verify-release.py gives the APK: ConnectAnytime-<versionName>-<versionCode>.apk
    private val NAME = Regex("""ConnectAnytime-(\d+(?:\.\d+){1,3})-(\d{1,9})\.apk""")
    private val DIGEST = Regex("""sha256:([0-9a-f]{64})""")

    /**
     * The newest release above [current], or null. Prereleases count (every release so far is
     * one). Skips drafts, and any APK that has no SHA-256 digest from GitHub, isn't served from
     * this repo's release downloads, or has an implausible size: the download is checked
     * against that digest, so without one there'd be nothing to check it against.
     */
    fun newest(json: String, current: Int): Release? {
        val releases = JSONArray(json)
        var best: Release? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val page = release.optString("html_url").takeIf { it.startsWith(PAGES) } ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                val match = NAME.matchEntire(name) ?: continue
                val code = match.groupValues[2].toIntOrNull() ?: continue
                val digest = DIGEST.matchEntire(asset.optString("digest"))?.groupValues?.get(1) ?: continue
                val url = asset.optString("browser_download_url")
                if (!url.startsWith(DOWNLOADS) || url.substringAfterLast('/') != name || ".." in url) continue
                if (asset.optString("state", "uploaded") != "uploaded") continue
                val size = asset.optLong("size", -1)
                if (size !in 1..MAX_APK) continue
                if (code > current && (best == null || code > best.versionCode))
                    best = Release(match.groupValues[1], code, url, size, digest, page)
            }
        }
        return best
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Downloaded and verified. [note] says why an install attempt didn't go ahead. */
    data class Ready(val release: Release, val note: Int? = null) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val reason: Int) : UpdateState
}

/** Why a check or download stopped, as the string the app shows. */
internal class UpdateFailure(val reason: Int, message: String) : IOException(message)

/** What Android reads from an APK: enough to tell whether it's this app's next release.
 *  [signers] are SHA-256 digests of certificates; null where Android can't say. */
internal data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>?)

/** One HTTP GET. A seam, so tests drive the updater without a network. */
internal fun interface Http { fun get(url: String, accept: String, etag: String?): Response }
internal class Response(val code: Int, val etag: String?, val body: InputStream?, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() { runCatching { body?.close() }; onClose() }
}

/**
 * Checks this repo's GitHub releases, downloads a newer APK, and verifies it before
 * Android's installer sees it: GitHub's SHA-256 digest, then the package name, version code
 * and signing certificate. Android itself also refuses an update signed by anyone else.
 *
 * Everything runs in order on one thread, including the installer's answers, so no two
 * steps interleave. App traffic goes through the VPN when it's up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Updater internal constructor(
    private val app: ConnectApp,
    private val http: Http = Http(::platformGet),
    private val inspect: (File) -> ApkFacts? = { platformFacts(app, it) },
    private val installedSigners: () -> Set<String>? = { platformSigners(app) },
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    // For the retry window only: elapsed time, which a clock change can't move.
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    constructor(app: ConnectApp) : this(app, Http(::platformGet))

    private val store get() = app.store
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    // Nothing here may take the process down: it's the VPN's process too.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
        app.log.add(app.getString(R.string.update_error_log, e.javaClass.simpleName))
        // Only a check or download in flight is ended here; Ready and Installing stand.
        val s = mutable.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) mutable.value = UpdateState.Failed(R.string.update_error)
    })
    private val checking = AtomicBoolean(false)
    private val confirmations = MutableStateFlow<Intent?>(null)
    /** Android's install confirmation, waiting for MainActivity to show it (see [takeConfirmation]). */
    val confirmation = confirmations.asStateFlow()
    /** Started MainActivity instances; with none, a confirmation also gets a notification. */
    private val started = java.util.concurrent.atomic.AtomicInteger()
    val foreground get() = started.get() > 0
    fun activityStarted() { started.incrementAndGet() }
    fun activityStopped() { started.updateAndGet { maxOf(0, it - 1) } }
    // The current attempt's confirmation, kept so Try again can show it again rather than
    // start over, and when it was committed, so a double tap doesn't replace it.
    private var lastConfirm: Intent? = null
    private var committedAt = 0L
    private val dir get() = File(app.noBackupFilesDir, "updates")
    private val feed get() = File(app.noBackupFilesDir, "release-feed.json")
    private val current get() = BuildConfig.VERSION_CODE

    init { scope.launch { restore() } }

    /**
     * After a restart: a download kept from earlier is ready again if it still verifies, and
     * an install still in Android's hands shows as installing. Anything else is cleared.
     */
    private fun restore() {
        val pending = store.pendingUpdate?.let(Release::parse)
        if (pending != null && pending.versionCode <= current) {
            // Installed (BootReceiver reports it) or overtaken: the download has done its job.
            forget(); store.installSession = NO_SESSION
        } else if (pending != null && runCatching { verify(File(dir, pending.fileName), pending) }.isSuccess) {
            val session = store.installSession
            if (session != NO_SESSION && app.packageManager.packageInstaller.getSessionInfo(session) != null) {
                mutable.value = UpdateState.Installing(pending)
            } else {
                // Not installing. Keep the session's ID, though: this process may have been
                // started to receive its answer (a cancel or refusal), queued behind this.
                mutable.value = UpdateState.Ready(pending)
                store.updatingFrom = null
            }
            return
        } else {
            forget(); endSession()
        }
        store.upToDateAt.takeIf { it > 0 }?.let { mutable.value = UpdateState.UpToDate(it) }
    }

    /** On opening the app: with automatic checks on, at most every [INTERVAL] after the
     *  last check that worked. After one that failed, the next opening tries again. */
    fun checkIfDue() {
        if (store.autoUpdate && System.currentTimeMillis() - store.lastUpdateCheck >= INTERVAL) check()
    }

    fun check() {
        if (!checking.compareAndSet(false, true)) return
        scope.launch {
            try { runCheck() } finally { checking.set(false) }
        }
    }

    private fun runCheck() {
        val before = mutable.value
        if (before is UpdateState.Installing) return
        // A ready update stays on screen while it checks for a newer one.
        val ready = before as? UpdateState.Ready
        if (ready == null) mutable.value = UpdateState.Checking
        try {
            val release = fetch()
            val now = System.currentTimeMillis()
            when {
                release == null -> {
                    forget()
                    store.lastUpdateCheck = now; store.upToDateAt = now
                    mutable.value = UpdateState.UpToDate(now)
                }
                ready != null && ready.release == release -> { store.lastUpdateCheck = now; mutable.value = ready }
                // Rejected once, by this app or by Android: don't fetch 16 MB again.
                release.sha256 == store.rejectedUpdate -> {
                    store.lastUpdateCheck = now // a working check: no need to ask again at every opening
                    mutable.value = ready ?: UpdateState.Failed(
                        if (store.rejectedBy == REJECTED_BY_ANDROID) R.string.update_refused else R.string.update_wrong_app)
                }
                else -> {
                    store.upToDateAt = 0
                    download(release)
                    store.lastUpdateCheck = now
                }
            }
        } catch (e: Exception) {
            // A failed check or download doesn't throw away an update that's already verified,
            // and the next opening of the app tries again.
            store.lastUpdateCheck = 0
            mutable.value = ready ?: UpdateState.Failed(reasonFor(e))
        }
    }

    private fun reasonFor(e: Exception) = when (e) {
        is UpdateFailure -> e.reason
        is JSONException -> R.string.update_bad_feed
        is IOException -> R.string.update_offline
        else -> R.string.update_error
    }

    /** The release list, revalidated with its ETag, so an unchanged list isn't sent again. */
    private fun fetch(): Release? {
        val cached = feed.takeIf { it.isFile }
        val body = http.get(ReleaseFeed.API, "application/vnd.github+json", store.feedEtag.takeIf { cached != null }).use { r ->
            when (r.code) {
                200 -> {
                    val text = (r.body ?: throw IOException("no body")).readCapped(2 * 1024 * 1024).toString(Charsets.UTF_8)
                    ReleaseFeed.newest(text, current) // parse before caching, so a bad list isn't kept
                    feed.parentFile?.mkdirs(); feed.writeText(text)
                    store.feedEtag = r.etag
                    text
                }
                304 -> cached?.readText() ?: throw IOException("304 without a cached list")
                403, 429 -> throw UpdateFailure(R.string.update_busy, "HTTP ${r.code}")
                else -> throw IOException("HTTP ${r.code}")
            }
        }
        return ReleaseFeed.newest(body, current)
    }

    /**
     * Downloads to a staging file and only then replaces the kept APK, so a failure here
     * leaves an earlier verified download (even one with the same name) ready to install.
     */
    private fun download(release: Release) {
        mutable.value = UpdateState.Downloading(release, 0f)
        dir.mkdirs()
        val kept = store.pendingUpdate?.let(Release::parse)?.fileName
        dir.listFiles()?.forEach { if (it.name != kept) it.delete() }
        if (dir.usableSpace < release.size * 2) throw UpdateFailure(R.string.update_storage, "low space")
        val staging = File(dir, STAGING)
        try {
            http.get(release.apkUrl, "application/octet-stream", null).use { r ->
                when (r.code) {
                    200 -> Unit
                    403, 429 -> throw UpdateFailure(R.string.update_busy, "HTTP ${r.code}")
                    else -> throw IOException("HTTP ${r.code}")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var shown = -1
                (r.body ?: throw IOException("no body")).let { input ->
                    staging.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            if (done > release.size) throw UpdateFailure(R.string.update_corrupt, "longer than the release")
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            val percent = (done * 100 / release.size).toInt()
                            if (percent != shown) { shown = percent; mutable.value = UpdateState.Downloading(release, done.toFloat() / release.size) }
                        }
                    }
                }
                if (done != release.size || hex(digest.digest()) != release.sha256)
                    throw UpdateFailure(R.string.update_corrupt, "digest mismatch")
            }
            try { verify(staging, release) } catch (e: UpdateFailure) {
                if (e.reason == R.string.update_wrong_app) {
                    store.rejectedUpdate = release.sha256; store.rejectedBy = REJECTED_BY_APP
                    app.log.add(app.getString(R.string.update_rejected, release.versionName, e.message ?: ""))
                }
                throw e
            }
            val file = File(dir, release.fileName)
            if (!staging.renameTo(file)) throw IOException("rename")
            store.pendingUpdate = release.json()
            dir.listFiles()?.forEach { if (it.name != file.name) it.delete() }
            app.log.add(app.getString(R.string.update_downloaded, release.versionName, release.versionCode))
            mutable.value = UpdateState.Ready(release)
        } finally {
            staging.delete()
        }
    }

    /** Throws unless [file] is exactly [release], for this app, signed like this app. */
    private fun verify(file: File, release: Release) {
        if (!file.isFile || file.length() != release.size) throw UpdateFailure(R.string.update_missing, "missing or wrong size")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (hex(digest.digest()) != release.sha256) throw UpdateFailure(R.string.update_corrupt, "digest mismatch")
        val facts = inspect(file) ?: throw UpdateFailure(R.string.update_wrong_app, "not an APK")
        if (facts.packageName != app.packageName) throw UpdateFailure(R.string.update_wrong_app, "package ${facts.packageName}")
        if (facts.versionCode != release.versionCode.toLong()) throw UpdateFailure(R.string.update_wrong_app, "version code ${facts.versionCode}")
        if (!signedAlike(installedSigners(), facts.signers)) throw UpdateFailure(R.string.update_wrong_app, "different signing certificate")
    }

    /**
     * Hands the verified APK to Android. Android may install it straight away (Android 12+
     * allows an app to update itself without asking) or show its confirmation first, and the
     * first time perhaps ask to allow installs from this app. Tapping Install again replaces
     * an attempt that's still open, so a lost confirmation never strands the update.
     */
    fun install() {
        scope.launch {
            val installer = app.packageManager.packageInstaller
            val release = when (val s = mutable.value) {
                is UpdateState.Ready -> s.release
                is UpdateState.Installing -> {
                    // Try again (offered after RETRY_AFTER). Sooner, it's a double tap: leave the
                    // attempt alone, as Android may be installing already.
                    val since = clock() - committedAt
                    if (since < RETRY_AFTER) return@launch
                    val info = installer.getSessionInfo(store.installSession)
                    // A confirmation that's still open is shown again rather than replaced.
                    val confirm = lastConfirm
                    if (confirm != null && info != null) {
                        Notices.clearConfirmInstall(app)
                        confirmations.value = confirm; return@launch
                    }
                    // No confirmation, and Android still has the session this process committed:
                    // most likely a silent install (Android 12+) checking and optimising the APK.
                    // Replacing it would only start that over. (In a new process committedAt is
                    // 0, so a session left from before is replaced.)
                    if (confirm == null && info != null && since < SILENT_GRACE) return@launch
                    s.release
                }
                else -> return@launch
            }
            mutable.value = UpdateState.Installing(release)
            try {
                val file = File(dir, release.fileName)
                verify(file, release)
                // Only one attempt at a time; answers from an older one are ignored (onStatus).
                installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
                endSession()
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                try {
                    installer.openSession(id).use { session ->
                        session.openWrite("base.apk", 0, file.length()).use { output ->
                            file.inputStream().use { it.copyTo(output) }
                            session.fsync(output)
                        }
                        store.installSession = id
                        // Read back after the update to say it worked (BootReceiver).
                        store.updatingFrom = BuildConfig.BUILD_DESCRIPTION
                        val intent = Intent(app, InstallReceiver::class.java).setPackage(app.packageName)
                        // Mutable: the installer adds the status extras. The intent is explicit.
                        val sender = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
                        app.log.add(app.getString(R.string.update_installing_log, release.versionName, release.versionCode))
                        committedAt = clock()
                        session.commit(sender)
                    }
                } catch (e: Exception) {
                    runCatching { installer.abandonSession(id) }
                    throw e
                }
            } catch (e: Exception) {
                endSession()
                if (e is UpdateFailure) { forget(); mutable.value = UpdateState.Failed(e.reason) }
                else mutable.value = UpdateState.Ready(release, R.string.update_install_failed)
            }
        }
    }

    /** The installer's answer, from InstallReceiver, which waits for the returned job. Success
     *  needs nothing: Android stops this process to replace the app, and BootReceiver reports it. */
    internal fun onStatus(session: Int, status: Int, message: String?, confirm: Intent?) = scope.launch {
        if (session == NO_SESSION || session != store.installSession) return@launch
        val release = when (val s = mutable.value) {
            is UpdateState.Installing -> s.release
            is UpdateState.Ready -> s.release // a fresh process: restore() found no open session
            else -> return@launch
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) { stopped(release, PackageInstaller.STATUS_FAILURE, "no confirmation"); return@launch }
                if (mutable.value !is UpdateState.Installing) return@launch
                lastConfirm = confirm
                confirmations.value = confirm
                // The notification opens Android's confirmation itself, so it still works if
                // this process is gone by the time it's tapped.
                if (!foreground) Notices.confirmInstall(app, release.versionName, confirm, session)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> stopped(release, status, message)
        }
    }

    private fun stopped(release: Release, status: Int, message: String?) {
        endSession()
        mutable.value = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Ready(release, R.string.update_cancelled)
            // This APK can never go in, so don't offer it again. CONFLICT is how a different
            // signer comes back (INSTALL_FAILED_UPDATE_INCOMPATIBLE); INCOMPATIBLE covers a
            // downgrade or an unsupported SDK or ABI; INVALID a broken package.
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_INVALID -> {
                app.log.add(app.getString(R.string.update_refused_log, release.versionName, message ?: status.toString()))
                store.rejectedUpdate = release.sha256; store.rejectedBy = REJECTED_BY_ANDROID
                forget()
                UpdateState.Failed(R.string.update_refused)
            }
            else -> {
                app.log.add(app.getString(R.string.update_refused_log, release.versionName, message ?: status.toString()))
                UpdateState.Ready(release, R.string.update_refused)
            }
        }
    }

    /** For MainActivity: the confirmation to show now, at most once. If its notification was
     *  posted and is gone, it was tapped (which opened the confirmation) or dismissed, so the
     *  app doesn't open it a second time; Try again does. */
    fun takeConfirmation(): Intent? {
        val intent = confirmations.getAndUpdateCompat() ?: return null
        val notified = Notices.confirmInstallPosted
        if (notified && !Notices.confirmInstallShowing(app)) { Notices.confirmInstallPosted = false; return null }
        Notices.clearConfirmInstall(app)
        return intent
    }

    /** Tests: a confirmation as if the installer had just sent one. */
    internal fun offerConfirmation(intent: Intent) { confirmations.value = intent }

    private fun MutableStateFlow<Intent?>.getAndUpdateCompat(): Intent? {
        while (true) { val v = value ?: return null; if (compareAndSet(v, null)) return v }
    }

    private fun endSession() {
        store.installSession = NO_SESSION
        store.updatingFrom = null
        confirmations.value = null
        lastConfirm = null
        Notices.clearConfirmInstall(app)
    }

    private fun forget() {
        store.pendingUpdate = null
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val INTERVAL = 6 * 60 * 60 * 1000L
        const val NO_SESSION = -1
        const val REJECTED_BY_APP = "app"
        const val REJECTED_BY_ANDROID = "android"
        /** When Try again appears (UpdateSection) and starts to count. */
        const val RETRY_AFTER = 30_000L
        private const val SILENT_GRACE = 120_000L
        private const val STAGING = "download.apk"
        internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** The installed certificates must all be among the update's signers or its history
         *  (which allows key rotation). Where Android can't read them, its installer's own
         *  check still applies. */
        internal fun signedAlike(installed: Set<String>?, update: Set<String>?) =
            installed == null || update == null || (installed.isNotEmpty() && update.containsAll(installed))
    }
}

private fun platformGet(url: String, accept: String, etag: String?): Response {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000; c.readTimeout = 30_000
    // A generic name: GitHub asks API clients for one, and it says nothing about who's asking.
    c.setRequestProperty("User-Agent", "ConnectAnytime-Android")
    c.setRequestProperty("Accept", accept)
    if (accept.contains("github")) c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    if (etag != null) c.setRequestProperty("If-None-Match", etag)
    return try {
        val code = c.responseCode
        Response(code, c.getHeaderField("ETag"), if (code == 200) c.inputStream else null) { c.disconnect() }
    } catch (e: Exception) { c.disconnect(); throw e }
}

private fun digests(signatures: Array<android.content.pm.Signature>?) =
    signatures?.map { Updater.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet()

private fun platformFacts(app: Context, file: File): ApkFacts? {
    val pm = app.packageManager
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val info: PackageInfo = (if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, flags)) ?: return null
    val signing = info.signingInfo
    val signers = signing?.let { digests(it.apkContentsSigners).orEmpty() + digests(it.signingCertificateHistory).orEmpty() }
    return ApkFacts(info.packageName, info.longVersionCode, signers?.takeIf { it.isNotEmpty() })
}

/** This app's current signing certificates (not past ones). */
private fun platformSigners(app: Context): Set<String>? {
    val pm = app.packageManager
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") pm.getPackageInfo(app.packageName, flags)
    val signing = info.signingInfo ?: return null
    return (if (signing.hasMultipleSigners()) digests(signing.apkContentsSigners)
        else digests(signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()))?.takeIf { it.isNotEmpty() }
}

/** Reads at most [limit] bytes; a longer body is refused rather than cut short. */
private fun InputStream.readCapped(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > limit) throw UpdateFailure(R.string.update_bad_feed, "release list too large")
    }
    return out.toByteArray()
}

/** The installer's answer for a session committed by Updater.install. Not exported: only
 *  the PendingIntent the app gave the installer reaches it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Never let a bad answer crash the process: it's the VPN's process too.
        runCatching {
            val app = context.applicationContext as ConnectApp
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            // The untyped call: the typed one is unreliable on Android 13.
            @Suppress("DEPRECATION") val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION)
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) else null
            // Keep the process alive until the answer is handled: with the VPN off and the app
            // in the background, Android could otherwise end it as soon as this returns.
            val pending: PendingResult? = goAsync() // null only when called outside a real broadcast
            val job = runCatching {
                app.updater.onStatus(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, Updater.NO_SESSION), status,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
            }.getOrNull()
            if (job == null) pending?.finish() else job.invokeOnCompletion { runCatching { pending?.finish() } }
        }
    }
}
