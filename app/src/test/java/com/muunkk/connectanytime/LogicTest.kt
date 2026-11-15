package com.muunkk.connectanytime

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun disconnectCancelsQueuedConnect() {
        val commands = CommandGeneration()
        commands.next() // Disconnect
        val queued = commands.next() // Connect while stopping
        commands.next() // Disconnect before the queue drains
        assertFalse(commands.isCurrent(queued))
    }
    @Test fun disconnectCancelsAlreadyPostedRestart() {
        val commands = CommandGeneration()
        commands.next()
        val posted = commands.next()
        assertTrue(commands.isCurrent(posted)) // queue posts restart
        commands.next()
        assertFalse(commands.isCurrent(posted)) // main executes restart
        val newer = commands.next()
        assertTrue(commands.isCurrent(newer))
        assertFalse(commands.isCurrent(posted))
    }
    @Test fun firstNetworkCallbackIsBaseline() {
        val baseline = NetworkBaseline()
        assertFalse(baseline.changed("wifi,wlan0,gateway"))
        assertFalse(baseline.changed("wifi,wlan0,gateway"))
        assertTrue(baseline.changed("cell,rmnet0,gateway"))
        assertTrue(baseline.changed("offline"))
        assertFalse(baseline.changed("offline"))
    }
    @Test fun firstOfflineCallbackIsAlsoBaseline() {
        val baseline = NetworkBaseline()
        assertFalse(baseline.changed("offline"))
        assertTrue(baseline.changed("wifi"))
    }

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
    @Test fun everyBuilderInputNeedsANewInterface() {
        assertTrue(cfg.needsNewInterface(null))
        assertFalse(cfg.needsNewInterface(cfg.copy()))
        listOf(cfg.copy(address = "10.0.0.3"), cfg.copy(mask = "255.255.0.0"),
            cfg.copy(address6 = "fd00::1/128"), cfg.copy(dns = listOf("10.0.0.3")),
            cfg.copy(dns = listOf("10.0.0.2", "10.0.0.4")), cfg.copy(domain = "shanghai.nyu.edu"),
            cfg.copy(includes = listOf("10.0.0.0/8")), cfg.copy(excludes = listOf("192.168.0.0/16")),
            cfg.copy(mtu = 1280)).forEach {
            assertTrue(it.toString(), it.needsNewInterface(cfg))
        }
    }
    @Test fun aReconnectThatChangesNothingTheInterfaceUsesKeepsIt() {
        val routes = cfg.copy(includes = listOf("10.0.0.0/8", "172.16.0.0/12"), excludes = listOf("10.1.0.0/16", "10.2.0.0/16"))
        listOf(
            cfg.copy(mtu = 1406), // larger MTU still fits
            cfg.copy(gateway = "192.0.2.2"), // not a Builder input
            cfg.copy(splitDns = listOf("nyu.edu")), // not a Builder input
            cfg.copy(domain = "  nyu.edu "), // same search domains
        ).forEach { assertFalse(it.toString(), it.needsNewInterface(cfg)) }
        val reordered = routes.copy(includes = routes.includes.reversed(), excludes = routes.excludes.reversed())
        assertFalse(reordered.needsNewInterface(routes))
        // DNS order is priority.
        val two = cfg.copy(dns = listOf("10.0.0.2", "10.0.0.3"))
        assertTrue(two.copy(dns = two.dns.reversed()).needsNewInterface(two))
        // An interface that set no MTU got Android's 1500.
        assertFalse(cfg.copy(mtu = 1500).needsNewInterface(cfg.copy(mtu = 0)))
        assertTrue(cfg.copy(mtu = 1406).needsNewInterface(cfg.copy(mtu = 0)))
    }
    @Test fun changesNameFieldsButNotTheirValues() {
        assertEquals(emptyList<String>(), cfg.changes(cfg.copy()))
        val next = cfg.copy(address = "10.9.9.9", gateway = "192.0.2.77", mtu = 1406,
            dns = listOf("10.0.0.2", "10.0.0.8"), includes = listOf("10.0.0.0/8"))
        val changes = next.changes(cfg)
        assertEquals(listOf("address changed", "DNS servers changed (1 → 2)", "server address changed",
            "split-include routes changed (0 → 1)", "MTU 1400 → 1406"), changes)
        val text = changes.joinToString()
        listOf("10.9.9.9", "192.0.2.77", "10.0.0.8", "10.0.0.0").forEach { assertFalse(it, text.contains(it)) }
        val two = cfg.copy(dns = listOf("10.0.0.2", "10.0.0.3"))
        assertEquals(listOf("DNS servers reordered"), two.copy(dns = two.dns.reversed()).changes(two))
    }
    @Test fun aSettingsChangeSwitchesInterfacesWithoutEndingTheRun() {
        val closed = mutableListOf<String>()
        val tuns = TunHandoff<String> { closed += it }
        tuns.install("a") // first login
        assertEquals("a", tuns.current())
        assertNull(tuns.take()) // nothing offered: OpenConnect keeps its fd
        tuns.offer("b") // reconnect brought new settings: b is established, a still in use
        assertEquals(emptyList<String>(), closed)
        assertEquals("b", tuns.take()) // OpenConnect's thread switches
        assertEquals(emptyList<String>(), closed) // a stays open until the switch is reported
        tuns.switched()
        assertEquals(listOf("a"), closed)
        assertEquals("b", tuns.current())
        tuns.switched()
        assertEquals(listOf("a"), closed)
    }
    @Test fun aSecondChangeBeforeTheSwitchReplacesTheFirst() {
        val closed = mutableListOf<String>()
        val tuns = TunHandoff<String> { closed += it }
        tuns.install("a")
        tuns.offer("b")
        tuns.offer("c") // b was never handed to OpenConnect
        assertEquals(listOf("b"), closed)
        assertEquals("c", tuns.take())
        assertNull(tuns.take()) // the second SWITCH_TUN finds nothing
        tuns.switched()
        assertEquals(listOf("b", "a"), closed)
    }
    @Test fun aSwitchBringsTheVpnUpOnlyIfNoNewerReconnectStarted() {
        val tuns = TunHandoff<String> { }
        tuns.install("a")
        tuns.offer("b") // RECONNECTED with new settings
        assertTrue(tuns.awaitPending()) // so the service doesn't say Connected yet
        assertEquals("b", tuns.take())
        assertTrue(tuns.switched()) // SWITCHED: Connected
        tuns.offer("c")
        tuns.reconnecting() // the next reconnect started before OpenConnect read SWITCH_TUN
        assertEquals("c", tuns.take()) // read during that reconnect
        assertFalse(tuns.switched()) // still Reconnecting…; the watchdog keeps going
        assertFalse(tuns.awaitPending()) // its RECONNECTED, nothing left to take: Connected
    }
    @Test fun aReconnectThatOvertakesAnOfferWaitsForIt() {
        val tuns = TunHandoff<String> { }
        tuns.install("a")
        tuns.offer("b")
        tuns.reconnecting() // a newer reconnect before the switch
        assertTrue(tuns.awaitPending()) // its RECONNECTED needs no new interface, but b isn't taken yet
        tuns.take()
        assertTrue(tuns.switched())
    }
    @Test fun stoppingWhileASwitchIsOfferedClosesItAndALateSwitchChangesNothing() {
        val closed = mutableListOf<String>()
        val tuns = TunHandoff<String> { closed += it }
        tuns.install("a")
        tuns.offer("b")
        tuns.closeAll() // Disconnect before OpenConnect took b
        assertEquals(listOf("a", "b"), closed)
        assertNull(tuns.take())
        assertFalse(tuns.switched())
        // A SWITCHED from a run that already ended, after the rejoin installed c:
        tuns.install("c")
        tuns.offer("d"); tuns.take()
        tuns.install("e") // that run ended; the rejoin built e
        closed.clear()
        assertFalse(tuns.switched()) // the stale report neither says Connected...
        assertEquals(emptyList<String>(), closed) // ...nor closes anything in use
        assertEquals("e", tuns.current())
    }
    @Test fun onlyAnEndedSessionIsntSavedWhenARunEnds() {
        assertFalse(RunEnd.keepsSession(-1 * 1)) // EPERM: 401
        assertFalse(RunEnd.keepsSession(-4)) // EINTR: logged out
        assertFalse(RunEnd.keepsSession(-32)) // EPIPE: the server terminated it
        assertTrue(RunEnd.keepsSession(-103)) // ECONNABORTED: detached (OS stop, give-up)
        assertTrue(RunEnd.keepsSession(-110)) // ETIMEDOUT
        assertTrue(RunEnd.keepsSession(-1 * 5)) // EIO
        assertTrue(RunEnd.keepsSession(-22)) // EINVAL
    }
    @Test fun aRunThatEndsBeforeSwitchingLeavesTheNewestInterface() {
        val closed = mutableListOf<String>()
        val tuns = TunHandoff<String> { closed += it }
        tuns.install("a")
        tuns.offer("b") // Android already routes through b
        assertEquals("b", tuns.idle()) // the rejoin runs on b, not on a's dead interface
        assertEquals(listOf("a"), closed)
        tuns.install("c") // the rejoin needed yet another interface
        assertEquals(listOf("a", "b"), closed)
        tuns.offer("d"); tuns.take(); tuns.offer("e")
        tuns.closeAll() // stop: everything goes, including what was retired but not reported
        assertEquals(setOf("a", "b", "c", "d", "e"), closed.toSet())
        assertNull(tuns.current())
    }
    @Test fun logDropsCountdownsAndEmptyLines() {
        assertNull(LogLines.filter("sleep 5s"))
        assertNull(LogLines.filter(" \n"))
        assertEquals("Reconnecting", LogLines.filter("Reconnecting\r\n"))
        assertEquals("Server limit Idle-Timeout: 300", LogLines.filter("Server limit Idle-Timeout: 300"))
    }

    @Test fun runStampTellsARestartOrUpdateFromACrash() {
        val now = RunStamp.of(12, 1000L)
        assertEquals(RunStamp.Cause.Reboot, RunStamp.cause(RunStamp.of(11, 1000L), now))
        assertEquals(RunStamp.Cause.Update, RunStamp.cause(RunStamp.of(12, 999L), now))
        assertEquals(RunStamp.Cause.Unexpected, RunStamp.cause(now, now))
        // A marker from a build before stamps existed: can't tell, so no "quit unexpectedly".
        assertEquals(RunStamp.Cause.Reboot, RunStamp.cause("", now))
    }

}
