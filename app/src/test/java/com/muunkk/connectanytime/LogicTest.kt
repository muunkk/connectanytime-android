package com.muunkk.connectanytime

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun routesAcceptMasksAndNormalize() {
        assertEquals("10.0.0.0/8", Prefix.parse("10.1.2.3/255.0.0.0").text)
        assertEquals("192.168.1.0/24", Prefix.parse("192.168.1.4/24").text)
        assertEquals(128, Prefix.parse("2001:db8::5/64").width)
        assertEquals(64, Prefix.parse("2001:db8::5/64").bits)
        assertEquals(0, Prefix.parse("0.0.0.0/0").bits)
    }
    @Test fun routesRejectMalformedAndNoncontiguousMasks() {
        listOf("host.test/8", "1.2.3.256/24", "1.2.3.4/33", "1.2.3.4/-1",
            "10.0.0.0/255.0.255.0", "::1/129", "::ffff:192.0.2.1/128", "1.2.3/24").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { Prefix.parse(it) }
        }
    }
    @Test fun complementCoversExactlyTheIncludedAddresses() {
        // Exhaustively check a /24: no gaps, overlaps or excluded destinations.
        val includes = listOf(Prefix.parse("10.0.0.0/24"))
        val excludes = listOf(Prefix.parse("10.0.0.64/26"), Prefix.parse("10.0.0.17/32"))
        val result = Prefix.complement(includes, excludes)
        for (i in 0..255) {
            val address = Prefix.parse("10.0.0.$i/32")
            val wanted = i !in 64..127 && i != 17
            assertEquals("address $i", if (wanted) 1 else 0, result.count { it.contains(address) })
        }
        assertTrue(Prefix.complement(includes, listOf(Prefix.parse("0.0.0.0/0"))).isEmpty())
        assertEquals(includes, Prefix.complement(includes, listOf(Prefix.parse("192.168.0.0/16"))))
    }
    @Test fun ipv6ComplementAlsoWorks() {
        val result = Prefix.complement(listOf(Prefix.parse("::/0")), listOf(Prefix.parse("2001:db8::/32")))
        assertEquals(32, result.size)
        assertTrue(result.none { it.contains(Prefix.parse("2001:db8::1/128")) })
        assertTrue(result.any { it.contains(Prefix.parse("fd00:ca::1/128")) })
    }
    @Test fun sanHostnameRules() {
        assertTrue(Hostnames.matches("VPN.Shanghai.NYU.EDU", "vpn.shanghai.nyu.edu"))
        assertTrue(Hostnames.matches("vpn.shanghai.nyu.edu", "*.shanghai.nyu.edu"))
        listOf(
            "shanghai.nyu.edu" to "*.shanghai.nyu.edu",
            "a.vpn.shanghai.nyu.edu" to "*.shanghai.nyu.edu",
            "vpn.shanghai.nyu.edu" to "v*.shanghai.nyu.edu",
            "vpn.nyu.edu.attacker.org" to "*.nyu.edu",
            "127.0.0.1" to "127.0.0.1",
            "::1" to "::1",
            "a..nyu.edu" to "*.nyu.edu",
            "nyu.edu" to "*.edu",
            "vpn.nyu.edu." to "vpn.nyu.edu",
            "vpn.nyu.edu" to "*.*.edu"
        ).forEach { (host, san) -> assertFalse("$host $san", Hostnames.matches(host, san)) }
    }
    @Test fun backoffCapsAndResetsOnlyAfterQuietTime() {
        val b = RejoinBackoff()
        val actual = (0..7).map { b.next(it * 10_000L) }
        assertEquals(listOf(0L, 5000L, 10000L, 20000L, 40000L, 60000L, 60000L, 60000L), actual)
        assertEquals(60000L, b.next(190_000))
        assertEquals(0L, b.next(310_001))
        b.reset()
        assertEquals(0L, b.next(310_002))
    }
    @Test fun automaticStartsNeverLoginAndDisconnectWins() {
        for (manual in listOf(false, true)) for (saved in listOf(false, true)) for (disconnected in listOf(false, true)) {
            val refusal = StartRule.refusal(manual, saved, disconnected)
            if (!manual && (!saved || disconnected)) assertNotNull(refusal)
            if (manual && !disconnected) assertNull(refusal)
            assertEquals(manual && !disconnected, StartRule.mayLogin(manual, disconnected))
        }
    }
    private val cfg = TunnelConfig("10.0.0.1", "255.255.255.0", "", listOf("10.0.0.2"),
        "nyu.edu", "192.0.2.1", emptyList(), emptyList(), emptyList(), 1400)
    @Test fun configChangesIncludeAllBuilderInputs() {
        assertFalse(cfg.changedFrom(cfg.copy()))
        assertTrue(cfg.changedFrom(null))
        listOf(cfg.copy(address = "10.0.0.3"), cfg.copy(mask = "255.255.0.0"),
            cfg.copy(address6 = "fd00::1/128"), cfg.copy(dns = listOf("10.0.0.3")),
            cfg.copy(domain = "shanghai.nyu.edu"), cfg.copy(gateway = "192.0.2.2"),
            cfg.copy(includes = listOf("10.0.0.0/8")), cfg.copy(excludes = listOf("192.168.0.0/16")),
            cfg.copy(splitDns = listOf("nyu.edu")), cfg.copy(mtu = 1280)).forEach {
            assertTrue(it.changedFrom(cfg))
        }
    }
    @Test fun logDropsCountdownsAndEmptyLines() {
        assertNull(LogLines.filter("sleep 5s"))
        assertNull(LogLines.filter(" \n"))
        assertEquals("Reconnecting", LogLines.filter("Reconnecting\r\n"))
        assertEquals("Server limit Idle-Timeout: 300", LogLines.filter("Server limit Idle-Timeout: 300"))
    }
}
