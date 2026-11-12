package com.muunkk.connectanytime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProxyProtocolTest {
    private fun input(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1))
    private fun socks(vararg bytes: Int): ProxyTarget = ProxyProtocol.socks(ByteArrayInputStream(bytes.map { it.toByte() }.toByteArray()), ByteArrayOutputStream())
    private fun http(s: String) = ProxyProtocol.http(input(s))
    @Test fun socksIpv4() { assertEquals(ProxyTarget("1.2.3.4", 443), socks(1, 0, 5, 1, 0, 1, 1, 2, 3, 4, 1, 187)) }
    @Test fun socksDomainIsUnresolved() {
        val bytes = byteArrayOf(1, 0, 5, 1, 0, 3, 11) + "example.org".toByteArray() + byteArrayOf(0, 80)
        assertEquals(ProxyTarget("example.org", 80), ProxyProtocol.socks(ByteArrayInputStream(bytes), ByteArrayOutputStream()))
    }
    @Test fun socksIpv6() {
        val address = ByteArray(16).also { it[0] = 0x20; it[1] = 1; it[15] = 1 }
        val target = ProxyProtocol.socks(ByteArrayInputStream(byteArrayOf(1, 0, 5, 1, 0, 4) + address + byteArrayOf(0, 80)), ByteArrayOutputStream())
        assertArrayEquals(address, InetAddress.getByName(target.host).address)
    }
    @Test fun socksRejectsBindAndUdp() {
        for (command in listOf(2, 3)) assertEquals(7, assertThrows(ProxyFailure::class.java) { socks(1, 0, 5, command, 0, 1) }.socksCode)
    }
    @Test fun socksRejectsAddressTypeAndReservedByte() {
        assertEquals(8, assertThrows(ProxyFailure::class.java) { socks(1, 0, 5, 1, 0, 9) }.socksCode)
        assertThrows(ProxyFailure::class.java) { socks(1, 0, 5, 1, 1, 1) }
    }
    @Test fun socksTruncatedAtEveryByte() {
        val valid = byteArrayOf(1, 0, 5, 1, 0, 1, 1, 2, 3, 4, 0, 80)
        for (n in valid.indices) assertThrows(EOFException::class.java) { ProxyProtocol.socks(ByteArrayInputStream(valid.copyOf(n)), ByteArrayOutputStream()) }
    }
    @Test fun socksNoAuthenticationOnly() {
        val out = ByteArrayOutputStream()
        assertThrows(EOFException::class.java) { ProxyProtocol.socks(ByteArrayInputStream(byteArrayOf(1, 2)), out) }
        assertArrayEquals(byteArrayOf(5, -1), out.toByteArray())
    }
    @Test fun socksSuccessReplyAndGreeting() {
        val out = ByteArrayOutputStream()
        ProxyProtocol.socks(ByteArrayInputStream(byteArrayOf(1, 0, 5, 1, 0, 1, 1, 2, 3, 4, 0, 80)), out)
        ProxyProtocol.socksReply(out, 0)
        assertArrayEquals(byteArrayOf(5, 0, 5, 0, 0, 1, 0, 0, 0, 0, 0, 0), out.toByteArray())
    }
    @Test fun httpConnectAndIpv6() {
        assertEquals(ProxyTarget("example.org", 443), http("CONNECT example.org:443 HTTP/1.1\r\n\r\n").target)
        assertEquals(ProxyTarget("2001:db8::1", 443), http("CONNECT [2001:db8::1]:443 HTTP/1.1\r\n\r\n").target)
    }
    @Test fun absoluteUriRewritesHostAndPath() {
        val request = http("GET http://example.org:8080/a?q=b HTTP/1.1\r\nHost: attacker\r\n\r\n")
        assertEquals("GET /a?q=b HTTP/1.1", request.head.first)
        assertEquals(listOf("example.org:8080"), request.head.values("Host"))
    }
    @Test fun nominatedHostIsRecreatedFromAbsoluteUri() {
        val h = http("GET http://example.org/ HTTP/1.1\r\nConnection: Host, X-Hop\r\nHost: attacker\r\nX-Hop: removed\r\n\r\n").head
        val out = ByteArrayOutputStream(); ProxyProtocol.writeHead(out, h)
        assertTrue(out.toString().contains("Host: example.org\r\n"))
        assertFalse(out.toString().contains("X-Hop:"))
    }
    @Test fun malformedChunkSizesAreRejected() {
        val h = ProxyProtocol.Head("POST / HTTP/1.1", listOf("Transfer-Encoding" to "chunked"))
        for (size in listOf("+1", "-0", "xyz", "fffffffffffffffff"))
            assertThrows(ProxyFailure::class.java) { ProxyProtocol.body(input("$size\r\na\r\n0\r\n\r\n"), ByteArrayOutputStream(), h) }
    }
    @Test fun malformedAndUnsupportedHttp() {
        for (target in listOf("/path", "https://example.org/", "http://u:p@example.org/", "http://example.org/#fragment"))
            assertThrows(ProxyFailure::class.java) { http("GET $target HTTP/1.1\r\n\r\n") }
        assertThrows(ProxyFailure::class.java) { http("CONNECT example.org HTTP/1.1\r\n\r\n") }
        assertThrows(ProxyFailure::class.java) { http("GET http://example.org/ HTTP/1.1\r\n folded: value\r\n\r\n") }
    }
    @Test fun httpTruncationDoesNotConsumeTunnelBytes() {
        val valid = "CONNECT example.org:443 HTTP/1.1\r\nHost: example.org\r\n\r\n"
        for (n in valid.indices) assertThrows(EOFException::class.java) { http(valid.take(n)) }
        val source = input(valid + "TLS")
        ProxyProtocol.http(source)
        assertEquals('T'.code, source.read())
    }
    @Test fun hopHeadersAndConnectionNominatedHeadersRemoved() {
        val h = ProxyProtocol.head(input("HTTP/1.1 200 OK\r\nConnection: X-Private, keep-alive\r\nX-Private: secret\r\nKeep-Alive: timeout=3\r\nProxy-Authorization: secret\r\nTE: trailers\r\nUpgrade: websocket\r\nEnd-To-End: kept\r\n\r\n"))
        assertEquals(listOf("End-To-End" to "kept"), h.cleaned())
        val out = ByteArrayOutputStream(); ProxyProtocol.writeHead(out, h)
        assertTrue(out.toString().contains("Connection: close\r\n"))
    }
    @Test fun ambiguousFramingRejected() {
        for (headers in listOf("Content-Length: 1\r\nContent-Length: 2", "Content-Length: -1", "Content-Length: 999999999999999999999999", "Transfer-Encoding: chunked\r\nContent-Length: 1", "Transfer-Encoding: gzip", "Connection: Content-Length\r\nContent-Length: 1"))
            assertThrows(ProxyFailure::class.java) { http("POST http://example.org/ HTTP/1.1\r\n$headers\r\n\r\n") }
    }
    @Test fun headerAndLineLimits() {
        assertThrows(ProxyFailure::class.java) { http("GET http://example.org/ HTTP/1.1\r\nX: ${"a".repeat(8193)}\r\n\r\n") }
        assertThrows(ProxyFailure::class.java) { http("GET http://example.org/ HTTP/1.1\r\n" + "X: a\r\n".repeat(102) + "\r\n") }
    }
    @Test fun chunkedBodyDropsTrailersAndLeavesNextRequest() {
        val h = http("POST http://example.org/ HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n").head
        val out = ByteArrayOutputStream(); ProxyProtocol.writeHead(out, h)
        assertTrue(out.toString().contains("Transfer-Encoding: chunked"))
        out.reset()
        val source = input("3;ext=a\r\nabc\r\n0\r\nX-Secret: gone\r\n\r\nNEXT")
        ProxyProtocol.body(source, out, h)
        assertEquals("3\r\nabc\r\n0\r\n\r\n", out.toString()); assertEquals('N'.code, source.read())
    }
    @Test fun fixedBodyLeavesNextRequestAndRejectsTruncation() {
        val h = ProxyProtocol.Head("POST / HTTP/1.1", listOf("Content-Length" to "3"))
        val source = input("abcNEXT"); val out = ByteArrayOutputStream()
        ProxyProtocol.body(source, out, h); assertEquals("abc", out.toString()); assertEquals('N'.code, source.read())
        assertThrows(EOFException::class.java) { ProxyProtocol.body(input("a"), out, h) }
    }
    @Test fun usbNamesAreAnchoredAndExcludeOtherNetworks() {
        listOf("usb0", "rndis0", "rndis12", "ncm0").forEach { assertTrue(it, usbTetherName(it)) }
        listOf("wlan0", "rmnet0", "tun0", "lo", "eth0", "bt-pan", "usb", "rndis0.1", "xusb0").forEach { assertFalse(it, usbTetherName(it)) }
    }
    @Test fun subnetFilterUsesActualPrefix() {
        fun ip(s: String) = InetAddress.getByName(s)
        assertTrue(sameSubnet(ip("192.168.42.129"), 25, ip("192.168.42.200")))
        assertFalse(sameSubnet(ip("192.168.42.129"), 25, ip("192.168.42.100")))
        assertFalse(sameSubnet(ip("192.168.42.1"), 24, ip("192.168.43.2")))
        assertFalse(sameSubnet(ip("192.168.42.1"), 0, ip("192.168.42.2")))
        assertFalse(sameSubnet(ip("192.168.42.1"), 24, ip("::1")))
        assertFalse(sameSubnet(ip("127.0.0.1"), 8, ip("127.0.0.2")))
    }
    @Test fun noVpnRefusesWithoutResolvingOrConnecting() {
        var registered = false
        assertThrows(ProxyFailure::class.java) { connectViaVpn(null, ProxyTarget("example.org", 80)) { registered = true } }
        assertFalse(registered)
    }
    @Test fun networkFailuresNeverFallBack() {
        var attempts = 0
        val vpn = object : ProxyNetwork {
            override fun resolve(host: String) = listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)))
            override fun connect(address: InetAddress, port: Int, register: (Socket) -> Unit): Socket { attempts++; throw ProxyFailure() }
        }
        assertThrows(ProxyFailure::class.java) { connectViaVpn(vpn, ProxyTarget("example.org", 80)) {} }; assertEquals(1, attempts)
        val noDns = object : ProxyNetwork {
            override fun resolve(host: String): List<InetAddress> = throw ProxyFailure()
            override fun connect(address: InetAddress, port: Int, register: (Socket) -> Unit): Socket = error("must not connect")
        }
        assertThrows(ProxyFailure::class.java) { connectViaVpn(noDns, ProxyTarget("example.org", 80)) {} }
    }
    @Test fun loopbackNetworkSeamPreservesHalfClose() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val response = executor.submit<String> { server.accept().use { peer ->
                    val body = peer.getInputStream().readBytes().toString(Charsets.UTF_8)
                    peer.getOutputStream().write("reply:$body".toByteArray()); peer.shutdownOutput(); body
                } }
                val vpn = object : ProxyNetwork {
                    override fun resolve(host: String): List<InetAddress> { assertEquals("phone-resolved.invalid", host); return listOf(server.inetAddress) }
                    override fun connect(address: InetAddress, port: Int, register: (Socket) -> Unit) = Socket().also { register(it); it.connect(java.net.InetSocketAddress(address, port), 1000) }
                }
                connectViaVpn(vpn, ProxyTarget("phone-resolved.invalid", server.localPort)) {}.use { client ->
                    client.soTimeout = 2000
                    client.getOutputStream().write("hello".toByteArray()); client.shutdownOutput()
                    assertEquals("reply:hello", client.getInputStream().readBytes().toString(Charsets.UTF_8))
                }
                assertEquals("hello", response.get(2, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
}
