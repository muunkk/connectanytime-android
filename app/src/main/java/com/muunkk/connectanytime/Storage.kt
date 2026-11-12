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

data class SavedSession(val server: String, val user: String, val url: String, val cookie: String, val dnsName: String, val ip: String) {
    fun native() = arrayOf(url, cookie, dnsName, ip)
    // Deliberately no credential-bearing toString (including crash diagnostics).
    override fun toString() = "SavedSession([private])"
}
class Store(private val context: Context) {
    val prefs = context.getSharedPreferences("connectanytime", Context.MODE_PRIVATE)
    var user: String
        get() = prefs.getString("user", "")!!
        set(v) { prefs.edit().putString("user", v.trim()).apply() }
    var server: String
        get() = prefs.getString("server", "vpn.shanghai.nyu.edu")!!
        set(v) { prefs.edit().putString("server", v.trim()).apply() }
    var stayConnected: Boolean
        get() = prefs.getBoolean("stayConnected", true)
        set(v) { prefs.edit().putBoolean("stayConnected", v).apply() }
    var disconnected: Boolean
        get() = prefs.getBoolean("disconnected", false)
        set(v) { check(prefs.edit().putBoolean("disconnected", v).commit()) }
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(v) { check(prefs.edit().putBoolean("running", v).commit()) }
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
    @Synchronized private fun <T> recover(name: String, decode: (String) -> T): T? = try {
        read(name)?.let(decode)
    } catch (e: Exception) {
        // Only failures that mean this data can never be read: a lost or replaced key,
        // tampered or corrupt ciphertext, or unparsable contents. Anything else (say a
        // Keystore hiccup) is rethrown rather than costing the user their password.
        if (e !is java.security.GeneralSecurityException && e !is org.json.JSONException &&
            e !is IllegalArgumentException) throw e
        if (e is java.security.InvalidKeyException || e is java.security.UnrecoverableKeyException) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("connectanytime") }
        }
        check(prefs.edit().remove(name).commit())
        (context.applicationContext as? ConnectApp)?.log?.add(context.getString(if (name == "session") R.string.discarded_session else R.string.discarded_password))
        null
    }
    fun password(): String? = recover("password") { it }
    fun savePassword(value: String) = write("password", value)
    fun session(): SavedSession? = recover("session") {
        val j = JSONObject(it)
        SavedSession(j.getString("server"), j.getString("user"), j.getString("url"), j.getString("cookie"), j.getString("dnsName"), j.getString("ip"))
    }
    fun saveSession(s: SavedSession) {
        val j = JSONObject().put("server", s.server).put("user", s.user).put("url", s.url).put("cookie", s.cookie).put("dnsName", s.dnsName).put("ip", s.ip)
        write("session", j.toString())
    }
    fun clearSession() { check(prefs.edit().remove("session").commit()) }
}

/** Single process, shared by the activity and service. One file write per burst. */
class ActivityLog(context: Context) {
    private val file = File(context.filesDir, "activity.txt")
    private val writes = Executors.newSingleThreadScheduledExecutor()
    private val lines = ArrayDeque<String>(if (file.exists()) file.readLines().takeLast(1500) else emptyList())
    private val mutable = MutableStateFlow(lines.toList())
    val state = mutable.asStateFlow()
    private var pending = false
    @Synchronized fun add(message: String) {
        LogLines.filter(message)?.lineSequence()?.forEach {
            lines.addLast(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " " + it)
            while (lines.size > 1500) lines.removeFirst()
        } ?: return
        mutable.value = lines.toList()
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
