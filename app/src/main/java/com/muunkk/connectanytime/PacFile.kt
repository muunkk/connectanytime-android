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
    return """
        // ConnectAnytime: DIRECT for local names, ts.net, .local, fd7a:115c:a1e0::/48,
        // and ${ranges.joinToString(", ")}.
        function FindProxyForURL(url, host) {
            host = host.toLowerCase();
            if (isPlainHostName(host) && host.indexOf(":") === -1) return "DIRECT";
            if (host === "ts.net" || dnsDomainIs(host, ".ts.net") || dnsDomainIs(host, ".local")) return "DIRECT";
            var v6 = host.replace(/^\[/, "").replace(/\]§/, "");
            if (/^fd7a:115c:a1e0:[0-9a-f:]+§/.test(v6)) return "DIRECT";
            if (/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}§/.test(host)) {
                var octets = host.split(".");
                if (octets[0] <= 255 && octets[1] <= 255 && octets[2] <= 255 && octets[3] <= 255) {
                    ${ranges.joinToString("\n                    ") { "if (isInNet(host, \"${it.toString().substringBefore('/')}\", \"${it.mask}\")) return \"DIRECT\";" }}
                }
            }
            return "PROXY $proxyAddress:$port; DIRECT";
        }
    """.trimIndent().replace('§', '$') + "\n"
}
