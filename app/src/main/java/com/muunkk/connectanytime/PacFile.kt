package com.muunkk.connectanytime

import java.net.InetAddress

internal data class Ipv4Range(val address: Int, val prefix: Int) {
    init { require(prefix in 0..32) }
    private val bits: Int get() = if (prefix == 0) 0 else -1 shl (32 - prefix)
    val mask: String get() = dotted(bits)
    fun contains(ip: Int) = (ip and bits) == (address and bits)
    fun overlaps(other: Ipv4Range) = contains(other.address) || other.contains(address)
    override fun toString() = "${dotted(address and bits)}/$prefix"
    companion object {
        private fun dotted(ip: Int) = (3 downTo 0).joinToString(".") { ((ip ushr (it * 8)) and 255).toString() }
        fun parse(text: String): Ipv4Range? {
            val parts = text.split('/')
            if (parts.size != 2 || !parts[1].matches(Regex("[0-9]{1,2}"))) return null
            val prefix = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            val octets = parts[0].split('.')
            if (octets.size != 4 || octets.any { !it.matches(Regex("(?:0|[1-9][0-9]{0,2})")) || it.toInt() > 255 }) return null
            val ip = octets.fold(0) { a, b -> (a shl 8) or b.toInt() }
            return Ipv4Range(ip and (if (prefix == 0) 0 else -1 shl (32 - prefix)), prefix)
        }
        fun from(address: InetAddress, prefix: Int): Ipv4Range? {
            if (address.address.size != 4 || prefix !in 0..32) return null
            return parse("${address.hostAddress}/$prefix")
        }
    }
}

internal fun parseDirectRanges(text: String): List<Ipv4Range>? {
    val entries = text.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
    if (entries.size > 16) return null
    return entries.map { Ipv4Range.parse(it)?.takeIf { range -> range.prefix >= 8 } ?: return null }
}

internal const val PAC_PATH = "/proxy.pac"
internal fun pacUrl(address: String, port: Int) = "http://$address:$port$PAC_PATH"

internal fun pacFile(proxyAddress: String, port: Int, link: Ipv4Range, home: List<Ipv4Range>): String {
    require(Ipv4Range.parse("$proxyAddress/32") != null && port in 1..65535)
    val ranges = listOf(Ipv4Range.parse("100.64.0.0/10")!!) + home + link
    // Addresses are compared here as numbers. Not isInNet: Apple's calls dnsResolve even for a
    // literal, and nothing in this file may look a name up.
    return """
        // ConnectAnytime: DIRECT for plain names, ts.net, .local, fd7a:115c:a1e0::/48,
        // and ${ranges.joinToString(", ")}. Names are matched as written, never looked up.
        function ipv4(text) {
            if (!/^[0-9]{1,3}(\.[0-9]{1,3}){3}§/.test(text)) return -1;
            var parts = text.split("."), value = 0;
            for (var i = 0; i < 4; i++) {
                var octet = parseInt(parts[i], 10);
                if (octet > 255) return -1;
                value = value * 256 + octet;
            }
            return value;
        }
        function inRange(ip, base, size) { return ip >= base && ip < base + size; }
        function tailnetV6(text) {
            var v6 = text.replace(/^\[/, "").replace(/\]§/, "").replace(/%.*§/, "");
            if (v6.indexOf(":") === -1 || !/^[0-9a-f:.]+§/.test(v6)) return false;
            // An embedded IPv4 tail stands for the last two groups.
            var dotted = v6.lastIndexOf(":");
            if (v6.indexOf(".") !== -1) {
                var tail = ipv4(v6.substring(dotted + 1));
                if (tail < 0) return false;
                v6 = v6.substring(0, dotted + 1) + Math.floor(tail / 65536).toString(16) + ":" + (tail % 65536).toString(16);
            }
            var halves = v6.split("::");
            if (halves.length > 2) return false;
            var head = halves[0] === "" ? [] : halves[0].split(":");
            var rest = halves.length === 2 ? (halves[1] === "" ? [] : halves[1].split(":")) : [];
            var groups = head.slice(0);
            if (halves.length === 2) { for (var g = head.length + rest.length; g < 8; g++) groups.push("0"); }
            groups = groups.concat(rest);
            if (groups.length !== 8) return false;
            for (var j = 0; j < 8; j++) if (!/^[0-9a-f]{1,4}§/.test(groups[j])) return false;
            return parseInt(groups[0], 16) === 0xfd7a && parseInt(groups[1], 16) === 0x115c && parseInt(groups[2], 16) === 0xa1e0;
        }
        function FindProxyForURL(url, host) {
            host = host.toLowerCase();
            // A name written with the DNS root's final dot is the same name.
            if (host.length > 1 && host.charAt(host.length - 1) === ".") host = host.substring(0, host.length - 1);
            if (isPlainHostName(host) && host.indexOf(":") === -1) return "DIRECT";
            if (host === "ts.net" || dnsDomainIs(host, ".ts.net") || dnsDomainIs(host, ".local")) return "DIRECT";
            if (tailnetV6(host)) return "DIRECT";
            var ip = ipv4(host);
            if (ip >= 0) {
                ${ranges.joinToString("\n                ") { "if (inRange(ip, ${it.address.toLong() and 0xFFFFFFFFL}, ${1L shl (32 - it.prefix)})) return \"DIRECT\"; // $it" }}
            }
            return "PROXY $proxyAddress:$port; DIRECT";
        }
    """.trimIndent().replace('§', '$') + "\n"
}
