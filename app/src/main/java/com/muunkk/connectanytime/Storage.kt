package com.muunkk.connectanytime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [cookie] carries the STRAP key the server last accepted. [candidate] ("" if none)
 *  carries a newer key a reconnect presented without hearing back: try it only when
 *  [cookie] is refused. */
data class SavedSession(val server: String, val user: String, val url: String, val cookie: String, val dnsName: String, val ip: String,
                        val candidate: String = "") {
    fun native() = arrayOf(url, cookie, dnsName, ip)
    fun candidateNative() = arrayOf(url, candidate, dnsName, ip)
    fun json(): String = JSONObject().put("server", server).put("user", user).put("url", url).put("cookie", cookie)
        .put("dnsName", dnsName).put("ip", ip).put("candidate", candidate).toString()
    companion object {
        /** Sessions saved before 0.1.4 have no candidate. */
        fun fromJson(text: String) = JSONObject(text).let { j ->
            SavedSession(j.getString("server"), j.getString("user"), j.getString("url"), j.getString("cookie"),
                j.getString("dnsName"), j.getString("ip"), j.optString("candidate", ""))
        }
    }
    // Deliberately no credential-bearing toString (including crash diagnostics).
    override fun toString() = "SavedSession([private])"
}
enum class ProxySetup { Manual, Automatic }

class Store(private val context: Context) {
    val prefs = context.getSharedPreferences("connectanytime", Context.MODE_PRIVATE)
    var user: String
        get() = prefs.getString("user", "")!!
        set(v) { prefs.edit().putString("user", v.trim()).apply() }
    var server: String
        get() = prefs.getString("server", "vpn.shanghai.nyu.edu")!!
        set(v) { prefs.edit().putString("server", v.trim()).apply() }
    /** Whether sharing has ever been up, so the Mac may still have its proxies set. */
    var sharedBefore: Boolean
        get() = prefs.getBoolean("sharedBefore", false)
        set(v) { prefs.edit().putBoolean("sharedBefore", v).apply() }
    /** Hotspot sharing was turned on in the app, where its warning is shown: the widget may then flip it directly. */
    var hotspotExplained: Boolean
        get() = prefs.getBoolean("hotspotExplained", false)
        set(v) { prefs.edit().putBoolean("hotspotExplained", v).apply() }
    /** Whether the hotspot has shared, so an iPhone may still have the proxy set. */
    var sharedHotspotBefore: Boolean
        get() = prefs.getBoolean("sharedHotspotBefore", false)
        set(v) { prefs.edit().putBoolean("sharedHotspotBefore", v).apply() }
    /** Share over USB tethering. The key predates hotspot sharing, which has its own. */
    var shareWithTether: Boolean
        get() = prefs.getBoolean("shareWithTether", false)
        set(v) { prefs.edit().putBoolean("shareWithTether", v).apply() }
    /** Share over the Wi-Fi hotspot, where anyone who joins can use it: separate, and off by default. */
    var shareOverHotspot: Boolean
        get() = prefs.getBoolean("shareOverHotspot", false)
        set(v) { prefs.edit().putBoolean("shareOverHotspot", v).apply() }
    var measureBattery: Boolean
        get() = prefs.getBoolean("measureBattery", false)
        set(v) { prefs.edit().putBoolean("measureBattery", v).apply() }
    var batteryAutoStopped: Boolean
        get() = prefs.getBoolean("batteryAutoStopped", false)
        set(v) { prefs.edit().putBoolean("batteryAutoStopped", v).apply() }
    var batteryDeadline: Long
        get() = prefs.getLong("batteryDeadline", 0)
        set(v) { prefs.edit().putLong("batteryDeadline", v).apply() }
    var batteryRec: Long
        get() = prefs.getLong("batteryRec", 0)
        set(v) { check(prefs.edit().putLong("batteryRec", v).commit()) }
    internal fun nextBatteryRec(): Long = synchronized(prefs) { (batteryRec + 1).also { batteryRec = it } }
    var proxySetup: ProxySetup
        get() = runCatching { ProxySetup.valueOf(prefs.getString("proxySetup", null).orEmpty()) }.getOrDefault(ProxySetup.Manual)
        set(v) { prefs.edit().putString("proxySetup", v.name).apply() }
    internal var directRanges: List<Ipv4Range>
        get() = parseDirectRanges(prefs.getString("directRanges", null) ?: "192.168.68.0/22")
            ?: listOf(Ipv4Range.parse("192.168.68.0/22")!!)
        set(v) {
            val text = v.joinToString(",")
            require(parseDirectRanges(text) != null)
            prefs.edit().putString("directRanges", text).apply()
        }
    val sharing: Boolean get() = shareWithTether || shareOverHotspot
    internal fun shares(link: TetherLink) = if (link == TetherLink.Usb) shareWithTether else shareOverHotspot
    /** Check GitHub for a newer release when the app opens (Updater.checkIfDue). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) { prefs.edit().putBoolean("autoUpdate", v).apply() }
    /** The last check that worked (found nothing newer, or a verified download). */
    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) { prefs.edit().putLong("lastUpdateCheck", v).apply() }
    /** When a check last found nothing newer; 0 once one did. Only this says "up to date". */
    var upToDateAt: Long
        get() = prefs.getLong("upToDateAt", 0)
        set(v) { prefs.edit().putLong("upToDateAt", v).apply() }
    var feedEtag: String?
        get() = prefs.getString("feedEtag", null)
        set(v) { prefs.edit().putString("feedEtag", v).apply() }
    /** The digest of a release Android or the app refused, so it isn't downloaded again. */
    var rejectedUpdate: String?
        get() = prefs.getString("rejectedUpdate", null)
        set(v) { prefs.edit().putString("rejectedUpdate", v).apply() }
    /** Who rejected [rejectedUpdate]: Updater.REJECTED_BY_APP or REJECTED_BY_ANDROID. */
    var rejectedBy: String?
        get() = prefs.getString("rejectedBy", null)
        set(v) { prefs.edit().putString("rejectedBy", v).apply() }
    // The updater's markers are written with commit() but never throw: it shares the VPN's process.
    /** The verified download waiting to be installed, as Release.json. */
    var pendingUpdate: String?
        get() = prefs.getString("pendingUpdate", null)
        set(v) { prefs.edit().apply { if (v == null) remove("pendingUpdate") else putString("pendingUpdate", v) }.commit() }
    /** The installer session of the current attempt, or Updater.NO_SESSION. */
    var installSession: Int
        get() = prefs.getInt("installSession", Updater.NO_SESSION)
        set(v) { prefs.edit().putInt("installSession", v).commit() }
    /** The build that started an install, so the updated app can say it worked. */
    /** Clears [updatingFrom] without waiting for the disk: BootReceiver runs on the main thread. */
    fun clearUpdatingFrom() { prefs.edit().remove("updatingFrom").apply() }
    var updatingFrom: String?
        get() = prefs.getString("updatingFrom", null)
        set(v) { prefs.edit().apply { if (v == null) remove("updatingFrom") else putString("updatingFrom", v) }.commit() }
    var stayConnected: Boolean
        get() = prefs.getBoolean("stayConnected", true)
        set(v) { prefs.edit().putBoolean("stayConnected", v).apply() }
    var disconnected: Boolean
        get() = prefs.getBoolean("disconnected", false)
        set(v) { check(prefs.edit().putBoolean("disconnected", v).commit()) }
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(v) { check(prefs.edit().putBoolean("running", v).commit()) }
    /** The boot and install the run marker was set under (see RunStamp). */
    var runStamp: String
        get() = prefs.getString("runStamp", "")!!
        set(v) { check(prefs.edit().putString("runStamp", v).commit()) }
    val hasSession get() = prefs.contains("session")
    val hasPassword get() = prefs.contains("password")
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("connectanytime", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("connectanytime", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray()) }
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    private fun read(name: String): String? {
        val value = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        // A 12-byte nonce and a 16-byte tag at least; shorter is truncated, not a Keystore fault.
        require(bytes.size >= 28) { "Truncated ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(name.toByteArray())
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    fun clearPassword() { check(prefs.edit().remove("password").commit()) }
    // This key is not auth-bound; prefs are credential-encrypted and this app is
    // not direct-boot aware (API 29+). There is no locked-user/authentication
    // retry state here. Invalid keys/ciphertext are unusable saved credentials.
    @Synchronized internal fun <T> recover(name: String, reader: (String) -> String? = ::read, decode: (String) -> T): T? = try {
        reader(name)?.let(decode)
    } catch (e: Exception) {
        // Discard only what can never be read (see CredentialFailure). Anything else, such
        // as the Keystore being busy right after boot, is rethrown and the data kept, as
        // iOS leaves a Keychain item it couldn't read. Never delete the key here: a lost
        // key comes back from getKey as null, a new one is made, and the old data then
        // fails its tag check, which is handled. Deleting it on a hiccup would cost both
        // the session and the password.
        if (!CredentialFailure.isPermanent(e)) throw e
        check(prefs.edit().remove(name).commit())
        (context.applicationContext as? ConnectApp)?.log?.add(context.getString(if (name == "session") R.string.discarded_session else R.string.discarded_password))
        null
    }
    fun password(): String? = recover("password") { it }
    /** User-initiated, so a key that's present but permanently broken is replaced here
     *  (and only here): the session under the old key then fails its tag check and goes. */
    @Synchronized fun savePassword(value: String) = try {
        write("password", value)
    } catch (e: java.security.InvalidKeyException) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("connectanytime") }
        write("password", value)
    }
    fun session(): SavedSession? = recover("session") { SavedSession.fromJson(it) }
    fun saveSession(s: SavedSession) = write("session", s.json())
    fun clearSession() { check(prefs.edit().remove("session").commit()) }
}

/** Whether a failed read means the stored value can never be decrypted or parsed. */
object CredentialFailure {
    fun isPermanent(e: Throwable) = e is javax.crypto.AEADBadTagException ||
        e is android.security.keystore.KeyPermanentlyInvalidatedException ||
        e is IllegalArgumentException || e is org.json.JSONException
}

/** Single process, shared by the activity and service. One file write per burst. */
class ActivityLog(context: Context) {
    private val file = File(context.filesDir, "activity.txt")
    private val writes = Executors.newSingleThreadScheduledExecutor()
    private val lines = ArrayDeque<String>(if (file.exists()) file.readLines().takeLast(1500) else emptyList())
    private val mutable = MutableStateFlow(lines.toList())
    val state = mutable.asStateFlow()
    private var pending = false
    @Volatile var observer: ((String) -> Unit)? = null
    @Synchronized fun add(message: String) {
        LogLines.filter(message)?.lineSequence()?.forEach {
            lines.addLast(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " " + it)
            while (lines.size > 1500) lines.removeFirst()
        } ?: return
        mutable.value = lines.toList()
        try { observer?.invoke(message) } catch (_: Throwable) { }
        if (!pending) {
            pending = true
            writes.schedule({ flush() }, 1, TimeUnit.SECONDS)
        }
    }
    @Synchronized fun flush() {
        if (!pending) return
        // Replace atomically, so a killed process cannot leave a half-written log.
        val temp = File(file.parentFile, "activity.tmp")
        temp.writeText(lines.joinToString("\n"))
        check(temp.renameTo(file))
        pending = false
    }
    @Synchronized fun clear() { lines.clear(); mutable.value = emptyList(); pending = true; flush() }
}
