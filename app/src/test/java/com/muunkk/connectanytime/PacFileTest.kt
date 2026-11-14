package com.muunkk.connectanytime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context

class PacFileTest {
    private val link = Ipv4Range.parse("10.84.137.1/24")!!
    private val file = pacFile("10.84.137.1", 1080, link, parseDirectRanges("192.168.68.0/22")!!)

    @Test fun evaluatesWithoutAnyDnsResolution() {
        // A copy for checking in other engines (JavaScriptCore: see VERIFICATION.md).
        java.io.File(System.getProperty("user.dir"), "build/sample-proxy.pac").apply { parentFile.mkdirs() }.writeText(file)
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_1_5
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, """
                function isPlainHostName(h) { return h.indexOf('.') === -1; }
                function dnsDomainIs(h, d) { return h.slice(-d.length) === d; }
                function shExpMatch(h, p) { return h === p; }
                function dnsResolve(h) { throw 'DNS forbidden'; }
                function myIpAddress() { throw 'DNS forbidden'; }
                // Apple's isInNet resolves even literals: the file must never call it.
                function isInNet(h, net, mask) { throw 'isInNet called'; }
            """.trimIndent() + file, "pac", 1, null)
            val direct = listOf("truenas", "nas.tail1234.ts.net", "ts.net", "printer.local", "100.101.102.103",
                "100.64.0.0", "100.127.255.255", "192.168.68.78", "192.168.68.0", "192.168.71.255", "10.84.137.78",
                // The DNS root's final dot.
                "nas.tail1234.ts.net.", "printer.local.", "truenas.",
                // Every way of writing an address in fd7a:115c:a1e0::/48.
                "fd7a:115c:a1e0::1", "[fd7a:115c:a1e0::1]", "FD7A:115C:A1E0::1", "[FD7A:115C:A1E0::1]",
                "fd7a:115c:a1e0:0:0:0:192.168.68.1", "fd7a:115c:a1e0::192.168.68.1", "fd7a:115c:a1e0::1%en0",
                "[fd7a:115c:a1e0::1%25en0]", "fd7a:115c:a1e0:ab12:4843:cd96:6258:b240", "fd7a:115c:a1e0::", "local")
            val proxy = listOf("100.128.0.1", "100.63.255.255", "192.168.72.1", "192.168.67.255", "fd7a:115c:a1e1::1",
                "fd7a:115c::a1e0:1", "fd7a:115c:a1e0:1:2:3:4:5:6", "fd7a::115c::a1e0", "fd7a:115c:a1e0::1.2.3.999",
                "example.com", "example.com.", "10.0.0.1", "100.64.0.1.evil.com", "999.64.0.1", "1.2.3.4.5",
                "ts.net.evil.com", "evil-ts.net", ".")
            for (host in direct + proxy) {
                val result = cx.evaluateString(scope, "FindProxyForURL('http://unused/', '$host')", "test", 1, null)
                assertEquals(host, if (host in direct) "DIRECT" else "PROXY 10.84.137.1:1080; DIRECT", Context.toString(result))
            }
        } finally { Context.exit() }
    }
    @Test fun parsesAndNormalizesRanges() {
        val range = Ipv4Range.parse("192.168.69.78/22")!!
        assertEquals("192.168.68.0/22", range.toString())
        assertEquals("255.255.252.0", range.mask)
        assertEquals(range, Ipv4Range.from(InetAddress.getByName("192.168.69.78"), 22))
        assertTrue(range.contains(Ipv4Range.parse("192.168.71.255/32")!!.address))
        assertFalse(range.contains(Ipv4Range.parse("192.168.72.0/32")!!.address))
        assertTrue(range.overlaps(Ipv4Range.parse("192.168.0.0/16")!!))
        assertTrue(Ipv4Range.parse("192.168.0.0/16")!!.overlaps(range))
        assertFalse(range.overlaps(Ipv4Range.parse("192.168.72.0/22")!!))
        assertEquals("0.0.0.0", Ipv4Range.parse("0.0.0.0/0")!!.mask)
        assertEquals("255.255.255.255", Ipv4Range.parse("1.2.3.4/32")!!.mask)
        assertNull(Ipv4Range.from(InetAddress.getByName("::1"), 24))
    }
    @Test fun validatesDirectRangeInput() {
        assertEquals(2, parseDirectRanges("192.168.68.1/22,\n 10.1.2.3/8")!!.size)
        assertEquals(emptyList<Ipv4Range>(), parseDirectRanges(" \n"))
        for (invalid in listOf("0.0.0.0/0", "10.0.0.0/7", "1.2.3.4/33", "256.1.2.3/24", "1.2.3/24", "localhost/24", "01.2.3.4/24", "1.2.3.4", "1.2.3.4/-1"))
            assertNull(invalid, parseDirectRanges(invalid))
        assertEquals(16, parseDirectRanges(List(16) { "10.0.0.0/8" }.joinToString(","))!!.size)
        assertNull(parseDirectRanges(List(17) { "10.0.0.0/8" }.joinToString(",")))
    }
    @Test fun servesOnlyThePacOriginEndpointWithoutAVpn() {
        for (method in listOf("GET", "HEAD")) {
            val http = ProxyProtocol.http(ByteArrayInputStream("$method /proxy.pac?cache=1 HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray()))
            val out = ByteArrayOutputStream()
            ProxyProtocol.servePac(out, http, file)
            val response = out.toString("UTF-8")
            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(response.contains("Content-Type: application/x-ns-proxy-autoconfig\r\n"))
            assertTrue(response.contains("Content-Length: ${file.toByteArray().size}\r\n"))
            assertTrue(response.contains("Cache-Control: no-cache\r\nConnection: close\r\n"))
            assertEquals(if (method == "GET") file else "", response.substringAfter("\r\n\r\n"))
        }
        for (request in listOf("GET /other", "POST /proxy.pac", "GET //evil/proxy.pac", "GET /proxy.pac#fragment"))
            assertThrows(ProxyFailure::class.java) { ProxyProtocol.http(ByteArrayInputStream("$request HTTP/1.1\r\n\r\n".toByteArray())) }
        assertFalse(ProxyProtocol.http(ByteArrayInputStream("GET http://example.com/proxy.pac HTTP/1.1\r\n\r\n".toByteArray())).origin)
    }
}
