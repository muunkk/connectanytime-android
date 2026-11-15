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
/** Why the last run ended without stopping, from the boot and install it ran under. A
 *  restart or update kills the process without onDestroy, which is expected; only a
 *  run that ended on the same boot and install really quit unexpectedly. */
object RunStamp {
    enum class Cause { Reboot, Update, Unexpected }
    fun of(bootCount: Int, lastUpdateTime: Long) = "$bootCount/$lastUpdateTime"
    fun cause(saved: String, now: String): Cause {
        val (savedBoot, savedUpdate) = saved.split("/").let { it.getOrNull(0) to it.getOrNull(1) }
        val (boot, update) = now.split("/").let { it[0] to it[1] }
        return when {
            savedBoot != boot -> Cause.Reboot
            savedUpdate != update -> Cause.Update
            else -> Cause.Unexpected
        }
    }
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
    private val domains get() = domain.split(Regex("\\s+")).filter(String::isNotBlank)
    private val interfaceMtu get() = if (mtu > 0) mtu else DEFAULT_MTU

    /** Whether an interface built from [installed] can't carry these settings. Builder
     *  can't change an established interface, but most of what a reconnect changes
     *  never reached it: the server's address and split DNS aren't used, routes are a
     *  set, and a larger MTU still fits. A smaller one doesn't: OpenConnect reads each
     *  packet into a buffer of the new MTU. DNS servers and search domains stay
     *  ordered, because the order is their priority. */
    fun needsNewInterface(installed: TunnelConfig?) = installed == null ||
        address != installed.address || mask != installed.mask || address6 != installed.address6 ||
        dns != installed.dns || domains != installed.domains ||
        includes.toSet() != installed.includes.toSet() || excludes.toSet() != installed.excludes.toSet() ||
        interfaceMtu < installed.interfaceMtu

    /** What changed since [previous], for the activity log: field names only, never the
     *  addresses, servers or routes themselves; the MTU is shown since it says nothing
     *  about the user. */
    fun changes(previous: TunnelConfig): List<String> = buildList {
        fun list(name: String, now: List<String>, was: List<String>) {
            if (now == was) return
            add(when {
                now.toSet() == was.toSet() -> "$name reordered"
                now.size != was.size -> "$name changed (${was.size} → ${now.size})"
                else -> "$name changed"
            })
        }
        if (address != previous.address) add("address changed")
        if (mask != previous.mask) add("netmask changed")
        if (address6 != previous.address6) add("IPv6 address changed")
        list("DNS servers", dns, previous.dns)
        list("search domains", domains, previous.domains)
        if (gateway != previous.gateway) add("server address changed")
        list("split-include routes", includes, previous.includes)
        list("split-exclude routes", excludes, previous.excludes)
        list("split DNS", splitDns, previous.splitDns)
        if (mtu != previous.mtu) add("MTU ${previous.mtu} → $mtu")
    }
    companion object {
        /** What Android gives an interface whose Builder set no MTU. */
        const val DEFAULT_MTU = 1500
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

/** Which tun interface OpenConnect reads and writes, and which can be closed. A live
 *  session switches to a new interface without reconnecting: the service establishes it
 *  (so traffic never bypasses the VPN) and [offer]s it, OpenConnect [take]s it on its own
 *  thread, and once it reports the switch, [switched] closes the old one. Never close an
 *  interface OpenConnect may still use: the fd number could be reused under it.
 *  Also tracks whether a reported switch brings the VPN back up: not if a newer
 *  reconnect started after the offer ([reconnecting]).
 *  Thread-safe. `close` runs under the lock: closing a tun fd can take the kernel's
 *  network configuration lock for a few milliseconds, and [take] on OpenConnect's
 *  thread waits that long at most. */
class TunHandoff<T : Any>(private val close: (T) -> Unit) {
    private var current: T? = null
    private var next: T? = null
    private val retired = ArrayList<T>()
    private var awaiting = false

    /** The interface a new run uses. */
    @Synchronized fun current(): T? = current

    /** No run is using any interface: make the newest established one current, and close
     *  the rest (one offered to a run that ended before taking it, for instance). */
    @Synchronized fun idle(): T? {
        next?.let { n -> current?.let(retired::add); current = n; next = null }
        awaiting = false
        closeRetired()
        return current
    }

    /** No run is using any interface: [tun] replaces them all. */
    @Synchronized fun install(tun: T) {
        listOfNotNull(current, next).forEach(retired::add)
        current = tun; next = null; awaiting = false
        closeRetired()
    }

    /** A run is going: switch to [tun] when OpenConnect asks. Replaces (and closes) one
     *  offered earlier that it hasn't taken yet. The VPN is up once it has switched. */
    @Synchronized fun offer(tun: T) {
        next?.let(close)
        next = tun
        awaiting = true
    }

    /** A reconnect came back: if an offered interface still waits to be taken, the VPN
     *  is up only once OpenConnect switches to it. Returns whether one does. */
    @Synchronized fun awaitPending(): Boolean {
        if (next != null) awaiting = true
        return next != null
    }

    /** A (newer) reconnect is under way. */
    @Synchronized fun reconnecting() { awaiting = false }

    /** On OpenConnect's thread: the interface to switch to, or null. */
    @Synchronized fun take(): T? {
        val n = next ?: return null
        current?.let(retired::add)
        current = n; next = null
        return n
    }

    /** OpenConnect has switched to what it took: close what it used before. Returns
     *  whether that brings the VPN up, i.e. no newer reconnect started since the offer. */
    @Synchronized fun switched(): Boolean {
        closeRetired()
        return awaiting.also { awaiting = false }
    }

    /** Stopped, and no run is using any interface. */
    @Synchronized fun closeAll() {
        listOfNotNull(current, next).forEach(retired::add)
        current = null; next = null; awaiting = false
        closeRetired()
    }

    private fun closeRetired() { retired.forEach(close); retired.clear() }
}

/** How an OpenConnect run ended (its -errno), as the service handles it. */
object RunEnd {
    // Linux errno values; OpenConnect's mainloop returns them negated.
    private const val EPERM = 1 // 401: the server ended the session
    private const val EINTR = 4 // OC_CMD_CANCEL: logged out
    private const val EPIPE = 32 // the server terminated the session
    /** Whether the session may still be rejoined, so what the run last held is worth saving. */
    fun keepsSession(result: Int) = result != -EPERM && result != -EINTR && result != -EPIPE
}
