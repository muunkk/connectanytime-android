package com.muunkk.connectanytime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
private class UpdateFailure(val reason: Int, message: String) : IOException(message)

/**
 * Checks this repo's GitHub releases, downloads a newer APK, and verifies it before
 * Android's installer sees it: GitHub's SHA-256 digest, then the package name, version code
 * and signing certificate. Android itself also refuses an update signed by anyone else.
 *
 * One operation at a time, on one thread. App traffic goes through the VPN when it's up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Updater(private val app: ConnectApp) {
    private val store get() = app.store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var watchdog: Job? = null
    private val dir get() = File(app.cacheDir, "updates")
    private val current get() = BuildConfig.VERSION_CODE

    init { scope.launch { restore() } }

    /** A download kept from earlier is ready again if it still verifies; anything else goes. */
    private fun restore() {
        val pending = store.pendingUpdate?.let(Release::parse)
        if (pending != null && pending.versionCode > current && runCatching { verify(File(dir, pending.fileName), pending) }.isSuccess) {
            mutable.value = UpdateState.Ready(pending)
            return
        }
        store.pendingUpdate = null
        dir.listFiles()?.forEach { it.delete() }
        store.lastUpdateCheck.takeIf { it > 0 }?.let { mutable.value = UpdateState.UpToDate(it) }
    }

    /** On opening the app: with automatic checks on, at most every [INTERVAL]. */
    fun checkIfDue() {
        if (store.autoUpdate && System.currentTimeMillis() - store.lastUpdateCheck >= INTERVAL) check()
    }

    @Synchronized fun check() {
        if (job?.isActive == true || mutable.value is UpdateState.Installing) return
        job = scope.launch {
            val ready = mutable.value as? UpdateState.Ready
            mutable.value = UpdateState.Checking
            try {
                val release = fetch()
                store.lastUpdateCheck = System.currentTimeMillis()
                when {
                    release == null -> { forget(); mutable.value = UpdateState.UpToDate(store.lastUpdateCheck) }
                    ready?.release == release -> mutable.value = ready.copy(note = null)
                    else -> download(release)
                }
            } catch (e: Exception) {
                // A failed check doesn't throw away an update that's already downloaded.
                mutable.value = ready ?: UpdateState.Failed(reasonFor(e))
            }
        }
    }

    private fun reasonFor(e: Exception) = when (e) {
        is UpdateFailure -> e.reason
        is JSONException -> R.string.update_bad_feed
        else -> R.string.update_offline
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        // A generic name: GitHub asks API clients for one, and it says nothing about who's asking.
        c.setRequestProperty("User-Agent", "ConnectAnytime-Android")
        c.setRequestProperty("Accept", accept)
        if (accept.contains("github")) c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        when (val code = c.responseCode) {
            200 -> return c
            403, 429 -> { c.disconnect(); throw UpdateFailure(R.string.update_busy, "HTTP $code") }
            else -> { c.disconnect(); throw IOException("HTTP $code") }
        }
    }

    private fun fetch(): Release? {
        val c = open(ReleaseFeed.API, "application/vnd.github+json")
        try {
            val body = c.inputStream.use { input ->
                val bytes = input.readNBytesCompat(2 * 1024 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
            return ReleaseFeed.newest(body, current)
        } finally { c.disconnect() }
    }

    /** Keeps an earlier verified download until this one has verified, so a failure here
     *  leaves it ready to install. */
    private fun download(release: Release) {
        mutable.value = UpdateState.Downloading(release, 0f)
        dir.mkdirs()
        val kept = store.pendingUpdate?.let(Release::parse)?.fileName
        dir.listFiles()?.forEach { if (it.name != kept) it.delete() }
        if (dir.usableSpace < release.size * 2) throw UpdateFailure(R.string.update_storage, "low space")
        val part = File(dir, release.fileName + ".part")
        val file = File(dir, release.fileName)
        try {
            val c = open(release.apkUrl, "application/octet-stream")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var shown = -1
                c.inputStream.use { input ->
                    part.outputStream().use { output ->
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
            } finally { c.disconnect() }
            if (!part.renameTo(file)) throw IOException("rename")
            verify(file, release)
            store.pendingUpdate = release.json()
            dir.listFiles()?.forEach { if (it.name != file.name) it.delete() }
            app.log.add(app.getString(R.string.update_downloaded, release.versionName, release.versionCode))
            mutable.value = UpdateState.Ready(release)
        } catch (e: Exception) {
            part.delete(); file.delete()
            if (e is UpdateFailure && e.reason == R.string.update_wrong_app)
                app.log.add(app.getString(R.string.update_rejected, release.versionName, e.message ?: ""))
            throw e
        }
    }

    /** Throws unless [file] is exactly [release], for this app, signed like this app. */
    private fun verify(file: File, release: Release) {
        if (!file.isFile || file.length() != release.size) throw UpdateFailure(R.string.update_corrupt, "missing or wrong size")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (hex(digest.digest()) != release.sha256) throw UpdateFailure(R.string.update_corrupt, "digest mismatch")
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, flags)
        archive ?: throw UpdateFailure(R.string.update_wrong_app, "not an APK")
        if (archive.packageName != app.packageName) throw UpdateFailure(R.string.update_wrong_app, "package ${archive.packageName}")
        if (archive.longVersionCode != release.versionCode.toLong()) throw UpdateFailure(R.string.update_wrong_app, "version code ${archive.longVersionCode}")
        val installed = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageInfo(app.packageName, flags)
        if (!sameSigner(installed, archive)) throw UpdateFailure(R.string.update_wrong_app, "different signing certificate")
    }

    /** The installed certificates must appear in the update's (its history allows key rotation).
     *  Where Android can't read an archive's certificates, its installer still enforces this. */
    private fun sameSigner(installed: PackageInfo, update: PackageInfo): Boolean {
        val mine = installed.signingInfo ?: return true
        val theirs = update.signingInfo ?: return true
        val ours = (if (mine.hasMultipleSigners()) mine.apkContentsSigners else mine.signingCertificateHistory)?.toSet().orEmpty()
        val accepted = theirs.apkContentsSigners.orEmpty().toSet() + theirs.signingCertificateHistory.orEmpty().toSet()
        return ours.isNotEmpty() && accepted.containsAll(ours)
    }

    /** Hands the verified APK to Android. After the first update made from here, Android 12+
     *  installs later ones without asking; the first one asks, as it came from elsewhere. */
    @Synchronized fun install() {
        val ready = mutable.value as? UpdateState.Ready ?: return
        if (job?.isActive == true) return
        val release = ready.release
        mutable.value = UpdateState.Installing(release)
        job = scope.launch {
            try {
                val file = File(dir, release.fileName)
                verify(file, release)
                val installer = app.packageManager.packageInstaller
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
                        // Read back after the update to say it worked (BootReceiver).
                        store.updatingFrom = BuildConfig.BUILD_DESCRIPTION
                        val intent = Intent(app, InstallReceiver::class.java).setPackage(app.packageName)
                        // Mutable: the installer adds the status extras. The intent is explicit.
                        val sender = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
                        session.commit(sender)
                    }
                } catch (e: Exception) {
                    runCatching { installer.abandonSession(id) }
                    throw e
                }
                app.log.add(app.getString(R.string.update_installing_log, release.versionName, release.versionCode))
                // If no answer comes (a confirmation left open, then lost), offer Install again.
                watchdog?.cancel()
                watchdog = scope.launch {
                    delay(90_000)
                    if (mutable.value == UpdateState.Installing(release)) mutable.value = UpdateState.Ready(release)
                }
            } catch (e: Exception) {
                store.updatingFrom = null
                if (e is UpdateFailure) { forget(); mutable.value = UpdateState.Failed(e.reason) }
                else mutable.value = UpdateState.Ready(release, R.string.update_install_failed)
            }
        }
    }

    /** From InstallReceiver: the install didn't go ahead. Success needs no call, as Android
     *  stops this process to replace the app. */
    internal fun installStopped(status: Int, message: String?) {
        watchdog?.cancel()
        store.updatingFrom = null
        val release = (mutable.value as? UpdateState.Installing)?.release ?: store.pendingUpdate?.let(Release::parse) ?: return
        val note = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) R.string.update_cancelled else R.string.update_refused
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED)
            app.log.add(app.getString(R.string.update_refused_log, release.versionName, message ?: status.toString()))
        mutable.value = UpdateState.Ready(release, note)
    }

    private fun forget() {
        store.pendingUpdate = null
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val INTERVAL = 6 * 60 * 60 * 1000L
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Reads at most [limit] bytes; a longer body is refused rather than cut short. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > limit) throw IOException("release list too large")
    }
    return out.toByteArray()
}

/** The installer's answer for a session committed by Updater.install. Not exported: only
 *  the PendingIntent the app gave the installer reaches it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ConnectApp
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { app.updater.installStopped(PackageInstaller.STATUS_FAILURE, "no confirmation"); return }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { app.updater.installStopped(PackageInstaller.STATUS_FAILURE, it.message) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> app.updater.installStopped(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
