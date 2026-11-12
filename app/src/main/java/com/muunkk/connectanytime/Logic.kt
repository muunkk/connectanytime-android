package com.muunkk.connectanytime

import java.net.InetAddress
import java.math.BigInteger
import java.util.Locale

/** No DNS lookups while parsing routes supplied by the server. */
data class Prefix(val address: BigInteger, val bits: Int, val width: Int) {
    init { require(width == 32 || width == 128); require(bits in 0..width); require(address.signum() >= 0 && address.bitLength() <= width) }
    private val mask get() = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE).shiftRight(width - bits).shiftLeft(width - bits)
    val network get() = address.and(mask)
    val text: String get() {
        val raw = network.toByteArray()
        val bytes = ByteArray(width / 8)
        val count = minOf(bytes.size, raw.size)
        raw.copyInto(bytes, bytes.size - count, raw.size - count)
        return InetAddress.getByAddress(bytes).hostAddress!! + "/$bits"
    }
    fun contains(other: Prefix) = width == other.width && bits <= other.bits && other.network.and(mask) == network
    fun subtract(exclude: Prefix): List<Prefix> {
        if (exclude.contains(this)) return emptyList()
        if (!contains(exclude)) return listOf(copy(address = network))
        val next = bits + 1
        return listOf(Prefix(network, next, width), Prefix(network.setBit(width - next), next, width))
            .flatMap { it.subtract(exclude) }
    }
    companion object {
        fun parse(text: String): Prefix {
            val parts = text.trim().split("/")
            require(parts.size == 2)
            val host = parts[0]
            val width = if (host.contains(":")) 128 else 32
            val bytes = if (width == 32) ipv4(host) else {
                require(host.matches(Regex("[0-9a-fA-F:]+")))
                InetAddress.getByName(host).address.also { require(it.size == 16) }
            }
            val bits = if (parts[1].contains(".")) {
                require(width == 32)
                val mask = BigInteger(1, ipv4(parts[1])).toLong()
                val inverted = mask xor 0xffffffffL
                require(inverted and (inverted + 1) == 0L) { "Non-contiguous netmask" }
                java.lang.Long.bitCount(mask)
            } else parts[1].toInt()
            return Prefix(BigInteger(1, bytes), bits, width).let { it.copy(address = it.network) }
        }
        private fun ipv4(host: String): ByteArray {
            val parts = host.split(".")
            require(parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 })
            return parts.map { it.toInt().toByte() }.toByteArray()
        }
        fun complement(includes: List<Prefix>, excludes: List<Prefix>) =
            excludes.fold(includes.distinct()) { routes, exclude -> routes.flatMap { it.subtract(exclude) } }
    }
}

object Hostnames {
    // RFC 6125 DNS SAN only. A wildcard consumes exactly one whole label.
    // Reject IP literals, partial wildcards, empty labels and non-ASCII input;
    // no CN fallback and no DNS queries during verification.
    private fun valid(name: String) = name.length <= 253 && name.split(".").all {
        it.length in 1..63 && it.first() != '-' && it.last() != '-' &&
            it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }
    } && !name.all { it.isDigit() || it == '.' }
    fun matches(hostname: String, san: String): Boolean {
        val host = hostname.lowercase(Locale.ROOT)
        val pattern = san.lowercase(Locale.ROOT)
        if (!valid(host)) return false
        if (!pattern.startsWith("*.")) return valid(pattern) && host == pattern
        val suffix = pattern.substring(2)
        return valid(suffix) && suffix.contains(".") &&
            host.substringAfter(".", "") == suffix && host.count { it == '.' } == suffix.count { it == '.' } + 1
    }
}
class RejoinBackoff {
    private var tries = 0
    private var last = Long.MIN_VALUE
    fun next(now: Long): Long {
        if (last == Long.MIN_VALUE || now - last > 120_000) tries = 0
        last = now
        return if (tries++ == 0) 0 else minOf(5_000L shl minOf(tries - 2, 4), 60_000)
    }
    fun reset() { tries = 0; last = Long.MIN_VALUE }
}
object StartRule {
    const val REFUSAL = "The VPN session has ended. Tap Connect in ConnectAnytime to log in again."
    fun mayLogin(manual: Boolean, disconnected: Boolean) = manual && !disconnected
    fun refusal(manual: Boolean, hasSession: Boolean, disconnected: Boolean): String? =
        if ((!manual && disconnected) || (!hasSession && !mayLogin(manual, disconnected))) REFUSAL else null
}
object LogLines {
    fun filter(line: String): String? = line.trimEnd().takeIf { it.isNotBlank() && !it.startsWith("sleep ") }
}

data class TunnelConfig(
    val address: String, val mask: String, val address6: String, val dns: List<String>,
    val domain: String, val gateway: String, val includes: List<String>, val excludes: List<String>,
    val splitDns: List<String>, val mtu: Int
) {
    // Structural equality includes every input to Builder. No delimiter collisions.
    fun changedFrom(previous: TunnelConfig?) = this != previous
    companion object {
        fun from(a: Array<String>) = TunnelConfig(a[0], a[1], a[2], a.slice(3..5).filter(String::isNotEmpty),
            a[6], a[7], lines(a[8]), lines(a[9]), lines(a[10]), a[11].toInt())
        private fun lines(s: String) = s.lineSequence().filter(String::isNotBlank).toList()
    }
}

/** Commands cross the main thread, worker queue and posted restart boundary. */
class CommandGeneration {
    private var generation = 0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun isCurrent(token: Long): Boolean = token == generation
}

/** The first callback is a baseline, including a callback-derived offline state. */
class NetworkBaseline {
    private var identity: String? = null
    fun changed(next: String): Boolean {
        val changed = identity != null && identity != next
        identity = next
        return changed
    }
}
