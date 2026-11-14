package com.muunkk.connectanytime

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the copied Terminal commands for real, in bash and (if installed) zsh, against a
 * fake `networksetup` and `sudo` that log their calls. Covers the reviewers' cases: a
 * disabled service with the same router, two matches, a failed or cancelled sudo, dots in
 * the address, and turning the proxies off again.
 */
class MacCommandTest {
    private val ip = "192.168.42.129"

    /** services: name → router; socks: name → (enabled, port) of its SOCKS proxy. */
    private fun run(shell: String, command: String, services: Map<String, String>, socks: Map<String, Pair<Boolean, Int>> = emptyMap(),
                    sudoFails: Boolean = false, auto: Map<String, Pair<Boolean, String>> = emptyMap(),
                    socksServer: String = ip, sudoFailsOn: String? = null): Pair<String, List<String>> {
        val dir = Files.createTempDirectory("maccmd").toFile()
        val calls = File(dir, "calls")
        val list = services.keys.joinToString("\\n")
        val info = services.entries.joinToString(" ") { (n, r) -> "\"$n\") echo 'Router: $r';;" }
        val socksInfo = socks.entries.joinToString(" ") { (n, v) ->
            "\"$n\") printf 'Enabled: ${if (v.first) "Yes" else "No"}\\nServer: $socksServer\\nPort: ${v.second}\\nAuthenticated Proxy Enabled: 0\\n';;" }
        val autoInfo = auto.entries.joinToString(" ") { (n, v) ->
            "\"$n\") printf 'Enabled: ${if (v.first) "Yes" else "No"}\\nURL: ${v.second}\\n';;" }
        File(dir, "networksetup").writeText("""
            #!/bin/sh
            case "${'$'}1" in
              -listallnetworkservices) printf 'An asterisk (*) denotes that a network service is disabled.\n$list\n';;
              -getinfo) case "${'$'}2" in $info *) echo 'Router: none';; esac;;
              -getautoproxyurl) case "${'$'}2" in $autoInfo *) printf 'Enabled: No\nURL: \n';; esac;;
              -getsocksfirewallproxy) case "${'$'}2" in $socksInfo *) printf 'Enabled: No\nServer: \nPort: 0\n';; esac;;
              *) echo "${'$'}*" >> "${calls.path}";;
            esac
        """.trimIndent() + "\n")
        File(dir, "sudo").writeText(when {
            sudoFails -> "#!/bin/sh\nexit 1\n"
            sudoFailsOn != null -> "#!/bin/sh\ncase \"\$*\" in *\"$sudoFailsOn\"*) exit 1;; esac\nexec \"\$@\"\n"
            else -> "#!/bin/sh\nexec \"\$@\"\n"
        })
        dir.listFiles()!!.forEach { it.setExecutable(true) }
        val args = if (shell == "zsh") listOf("zsh", "-f", "-c", command) else listOf("bash", "--noprofile", "--norc", "-c", command)
        val p = ProcessBuilder(args).redirectErrorStream(true).also { it.environment()["PATH"] = "${dir.path}:/usr/bin:/bin" }.start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out to (if (calls.exists()) calls.readLines() else emptyList())
    }
    private fun shells() = listOf("bash", "zsh").filter { sh ->
        runCatching { ProcessBuilder(sh, "-c", "exit 0").start().waitFor() == 0 }.getOrDefault(false)
    }.also { assumeTrue(it.isNotEmpty()) }

    @Test fun setsAllThreeProxiesOnThePhonesService() = shells().forEach { sh ->
        val (out, calls) = run(sh, macCommand(ip, 1080), linkedMapOf("Wi-Fi" to "192.168.1.1", "Pixel 9" to ip))
        assertEquals(sh, "Proxies set on Pixel 9", out)
        assertEquals(sh, listOf("-setwebproxy Pixel 9 $ip 1080", "-setsecurewebproxy Pixel 9 $ip 1080",
            "-setsocksfirewallproxy Pixel 9 $ip 1080"), calls)
    }
    @Test fun skipsADisabledServiceWithTheSameRouter() = shells().forEach { sh ->
        val (out, calls) = run(sh, macCommand(ip, 1080), linkedMapOf("*Old Ethernet" to ip, "Pixel 9" to ip))
        assertEquals(sh, "Proxies set on Pixel 9", out)
        assertTrue(sh, calls.all { "Pixel 9" in it } && calls.size == 3)
    }
    @Test fun refusesToGuessBetweenTwoMatches() = shells().forEach { sh ->
        val (out, calls) = run(sh, macCommand(ip, 1080), linkedMapOf("USB A" to ip, "USB B" to ip))
        assertTrue(sh, out.startsWith("More than one network service matches."))
        assertEquals(sh, emptyList<String>(), calls)
    }
    @Test fun dotsInTheAddressAreLiteral() = shells().forEach { sh ->
        val (out, calls) = run(sh, macCommand(ip, 1080), linkedMapOf("Odd" to "192x168x42x129"))
        assertEquals(sh, "Couldn't find the phone's connection. Is USB tethering or the hotspot on?", out)
        assertEquals(sh, emptyList<String>(), calls)
    }
    @Test fun aFailedSudoIsReportedNotCalledSuccess() = shells().forEach { sh ->
        val (out, _) = run(sh, macCommand(ip, 1080), linkedMapOf("Pixel 9" to ip), sudoFails = true)
        assertEquals(sh, "Couldn't change the proxies on Pixel 9.", out)
    }
    @Test fun turnsTheProxiesOffByTheProxyNotTheRouter() = shells().forEach { sh ->
        // Unplugged: no router any more, but the service still has our proxy.
        val (out, calls) = run(sh, macOffCommand(1080), linkedMapOf("Wi-Fi" to "192.168.1.1", "Pixel 9" to "none"),
            socks = mapOf("Pixel 9" to (true to 1080)))
        assertEquals(sh, "Proxies turned off on Pixel 9", out)
        assertEquals(sh, listOf("-setwebproxystate Pixel 9 off", "-setsecurewebproxystate Pixel 9 off",
            "-setsocksfirewallproxystate Pixel 9 off"), calls)
    }
    @Test fun turnsTheProxiesOffOnEveryServiceThatHasThem() = shells().forEach { sh ->
        // A Mac that used both USB and the hotspot has our proxy on both services.
        val (out, calls) = run(sh, macOffCommand(1080), linkedMapOf("Wi-Fi" to "10.84.137.1", "Pixel 9" to "none"),
            socks = mapOf("Wi-Fi" to (true to 1080), "Pixel 9" to (true to 1080)))
        assertEquals(sh, "Proxies turned off on Wi-Fi, Pixel 9", out)
        assertEquals(sh, 6, calls.size)
        assertTrue(sh, calls.containsAll(listOf("-setsocksfirewallproxystate Wi-Fi off", "-setsocksfirewallproxystate Pixel 9 off")))
    }
    @Test fun aFailedSudoWhenTurningOffIsReported() = shells().forEach { sh ->
        val (out, _) = run(sh, macOffCommand(1080), linkedMapOf("Pixel 9" to "none"), socks = mapOf("Pixel 9" to (true to 1080)), sudoFails = true)
        assertEquals(sh, "Couldn't change the proxies on Pixel 9.", out)
    }
    @Test fun turnOffLeavesOtherProxiesAlone() = shells().forEach { sh ->
        // Another SOCKS proxy on a different port, and ours already switched off: neither is touched.
        val (out, calls) = run(sh, macOffCommand(1080), linkedMapOf("Wi-Fi" to "192.168.1.1", "Pixel 9" to "none"),
            socks = mapOf("Wi-Fi" to (true to 9050), "Pixel 9" to (false to 1080)))
        assertEquals(sh, "No network service uses this proxy. Nothing to turn off.", out)
        assertEquals(sh, emptyList<String>(), calls)
    }
    @Test fun setsAutomaticProxyAndChecksSudo() = shells().forEach { sh ->
        val services = linkedMapOf("*Disabled" to ip, "Wi-Fi" to ip)
        val (out, calls) = run(sh, macAutoCommand(ip, 1080), services)
        assertEquals("Automatic proxy set on Wi-Fi", out)
        assertEquals(listOf("-setautoproxyurl Wi-Fi http://$ip:1080/proxy.pac"), calls)
        assertTrue(run(sh, macAutoCommand(ip, 1080), services, sudoFails = true).first.startsWith("Couldn't change"))
        assertTrue(run(sh, macAutoCommand(ip, 1080), mapOf("A" to ip, "B" to ip)).second.isEmpty())
    }
    @Test fun switchingModesTurnsOffOnlyThisAppsOtherKind() = shells().forEach { sh ->
        val services = linkedMapOf("Wi-Fi" to ip)
        // Manual → Automatic: the PAC URL is set first, then our SOCKS on 1080 goes off.
        val (outAuto, toAuto) = run(sh, macAutoCommand(ip, 1080), services, socks = mapOf("Wi-Fi" to (true to 1080)))
        assertEquals(sh, "Automatic proxy set on Wi-Fi", outAuto)
        assertEquals(sh, listOf("-setautoproxyurl Wi-Fi http://$ip:1080/proxy.pac",
            "-setwebproxystate Wi-Fi off", "-setsecurewebproxystate Wi-Fi off", "-setsocksfirewallproxystate Wi-Fi off"), toAuto)
        // If the PAC URL can't be set, the working Manual proxies stay.
        assertFalse(sh, run(sh, macAutoCommand(ip, 1080), services, socks = mapOf("Wi-Fi" to (true to 1080)),
            sudoFailsOn = "-setautoproxyurl").second.any { it.endsWith("state Wi-Fi off") })
        // Someone else's SOCKS proxy is left alone.
        assertEquals(sh, listOf("-setautoproxyurl Wi-Fi http://$ip:1080/proxy.pac"),
            run(sh, macAutoCommand(ip, 1080), services, socks = mapOf("Wi-Fi" to (true to 9050))).second)
        // Automatic → Manual: our PAC URL goes off after the three proxies are set.
        val toManual = run(sh, macCommand(ip, 1080), services, auto = mapOf("Wi-Fi" to (true to "http://$ip:1080/proxy.pac"))).second
        assertEquals(sh, "-setautoproxystate Wi-Fi off", toManual.last())
        assertEquals(sh, 4, toManual.size)
        // Another PAC URL is left alone.
        assertEquals(sh, 3, run(sh, macCommand(ip, 1080), services, auto = mapOf("Wi-Fi" to (true to "http://corp/proxy.pac"))).second.size)
    }
    @Test fun switchingLeavesLookalikesLocalProxiesAndFailedSetupsAlone() = shells().forEach { sh ->
        val services = linkedMapOf("Wi-Fi" to ip)
        // A URL that only looks like ours ("proxyXpac") isn't ours.
        assertEquals(sh, 3, run(sh, macCommand(ip, 1080), services, auto = mapOf("Wi-Fi" to (true to "http://corp:1080/proxyXpac"))).second.size)
        // `ssh -D 1080` is a local SOCKS proxy on the same port, however it's written: left on.
        for (local in listOf("127.0.0.1", "127.0.0.2", "localhost", "::1", "0:0:0:0:0:0:0:1"))
            assertEquals("$sh $local", listOf("-setautoproxyurl Wi-Fi http://$ip:1080/proxy.pac"),
                run(sh, macAutoCommand(ip, 1080), services, socks = mapOf("Wi-Fi" to (true to 1080)), socksServer = local).second)
        // A disabled SOCKS setting on this port is left as it is.
        assertEquals(sh, listOf("-setautoproxyurl Wi-Fi http://$ip:1080/proxy.pac"),
            run(sh, macAutoCommand(ip, 1080), services, socks = mapOf("Wi-Fi" to (false to 1080))).second)
        // Manual setup that fails partway keeps the working Automatic.
        val (out, calls) = run(sh, macCommand(ip, 1080), services, auto = mapOf("Wi-Fi" to (true to "http://$ip:1080/proxy.pac")),
            sudoFailsOn = "-setsecurewebproxy")
        assertTrue(sh, out.startsWith("Couldn't change"))
        assertFalse(sh, calls.any { it.startsWith("-setautoproxystate") })
    }
    @Test fun turnsOffManualAndAutomaticButLeavesOtherUrls() = shells().forEach { sh ->
        val (out, calls) = run(sh, macOffCommand(1080), linkedMapOf("USB" to "none", "Wi-Fi" to "none", "Other" to "none", "Disabled PAC" to "none"),
            socks = mapOf("USB" to (true to 1080)), auto = mapOf("Wi-Fi" to (true to "http://old:1080/proxy.pac"),
                "Other" to (true to "http://other:1080/proxy.pac?x"), "Disabled PAC" to (false to "http://old:1080/proxy.pac")))
        assertEquals("Proxies turned off on USB, Wi-Fi", out)
        assertEquals(4, calls.size)
        assertTrue(calls.contains("-setautoproxystate Wi-Fi off"))
        assertFalse(calls.any { "Other" in it || "Disabled PAC" in it })
        assertTrue(run(sh, macOffCommand(1080), mapOf("Wi-Fi" to "none"), sudoFails = true,
            auto = mapOf("Wi-Fi" to (true to "http://old:1080/proxy.pac"))).first.startsWith("Couldn't change"))
    }
    @Test fun oneLineEach() {
        assertFalse(macCommand(ip, 1080).contains('\n'))
        assertFalse(macAutoCommand(ip, 1080).contains('\n'))
        assertFalse(macOffCommand(1080).contains('\n'))
    }
}
