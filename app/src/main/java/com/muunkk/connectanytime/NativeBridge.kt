package com.muunkk.connectanytime

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class NativeBridge {
    interface Callbacks {
        fun onLog(line: ByteArray)
        fun onEvent(event: Int, config: Array<String>?)
        fun verify(chain: Array<ByteArray>, hostname: String): Boolean
        fun protectSocket(fd: Int): Boolean
    }
    external fun setNetwork(handle: Long)
    external fun create(server: ByteArray, user: ByteArray, password: ByteArray, callbacks: Callbacks): Long
    external fun connect(handle: Long): Int
    external fun resume(handle: Long, saved: Array<String>): Int
    external fun error(handle: Long): String
    external fun saved(handle: Long): Array<String>
    external fun config(handle: Long): Array<String>?
    external fun run(handle: Long, fd: Int): Int
    external fun abortBeforePassword(handle: Long): Boolean
    external fun command(handle: Long, command: Int)
    external fun free(handle: Long)
    companion object {
        const val UNREACHABLE = -3
        const val SESSION_ENDED = -2
        const val RECONNECT = 0
        const val CANCEL = 1
        const val DETACH = 2
        init { System.loadLibrary("connectanytime") }
    }
}
object PlatformTrust {
    fun verify(chain: Array<ByteArray>, hostname: String): Boolean = runCatching {
        require(chain.isNotEmpty())
        val factory = CertificateFactory.getInstance("X.509")
        val certs = chain.map { factory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }.toTypedArray()
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val trust = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        trust.checkServerTrusted(certs, certs[0].publicKey.algorithm)
        certs[0].subjectAlternativeNames.orEmpty().any {
            it.size >= 2 && it[0] == 2 && (it[1] as? String)?.let { name -> Hostnames.matches(hostname, name) } == true
        }
    }.getOrDefault(false)
}
