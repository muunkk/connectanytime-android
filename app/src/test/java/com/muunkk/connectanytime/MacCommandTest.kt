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

    /** services: name → router; proxied: services whose SOCKS proxy is `ip`. */
    private fun run(shell: String, command: String, services: Map<String, String>, proxied: Set<String> = emptySet(),
                    sudoFails: Boolean = false): Pair<String, List<String>> {
        val dir = Files.createTempDirectory("maccmd").toFile()
        val calls = File(dir, "calls")
        val list = services.keys.joinToString("\\n")
        val info = services.entries.joinToString(" ") { (n, r) -> "\"$n\") echo 'Router: $r';;" }
        val socks = proxied.joinToString(" ") { "\"$it\") echo 'Server: $ip';;" }
        File(dir, "networksetup").writeText("""
            #!/bin/sh
            case "${'$'}1" in
              -listallnetworkservices) printf 'An asterisk (*) denotes that a network service is disabled.\n$list\n';;
              -getinfo) case "${'$'}2" in $info *) echo 'Router: none';; esac;;
              -getsocksfirewallproxy) case "${'$'}2" in $socks *) echo 'Server: ';; esac;;
              *) echo "${'$'}*" >> "${calls.path}";;
            esac
        """.trimIndent() + "\n")
        File(dir, "sudo").writeText(if (sudoFails) "#!/bin/sh\nexit 1\n" else "#!/bin/sh\nexec \"\$@\"\n")
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
        assertTrue(sh, out.startsWith("More than one network service matches $ip"))
        assertEquals(sh, emptyList<String>(), calls)
    }
    @Test fun dotsInTheAddressAreLiteral() = shells().forEach { sh ->
        val (out, calls) = run(sh, macCommand(ip, 1080), linkedMapOf("Odd" to "192x168x42x129"))
        assertEquals(sh, "Couldn't find the phone's connection. Is USB tethering on?", out)
        assertEquals(sh, emptyList<String>(), calls)
    }
    @Test fun aFailedSudoIsReportedNotCalledSuccess() = shells().forEach { sh ->
        val (out, _) = run(sh, macCommand(ip, 1080), linkedMapOf("Pixel 9" to ip), sudoFails = true)
        assertEquals(sh, "Couldn't change the proxies on Pixel 9.", out)
    }
    @Test fun turnsTheProxiesOffByTheProxyNotTheRouter() = shells().forEach { sh ->
        // Unplugged: no router any more, but the service still has our proxy.
        val (out, calls) = run(sh, macOffCommand(ip, 1080), linkedMapOf("Wi-Fi" to "192.168.1.1", "Pixel 9" to "none"),
            proxied = setOf("Pixel 9"))
        assertEquals(sh, "Proxies turned off on Pixel 9", out)
        assertEquals(sh, listOf("-setwebproxystate Pixel 9 off", "-setsecurewebproxystate Pixel 9 off",
            "-setsocksfirewallproxystate Pixel 9 off"), calls)
    }
    @Test fun oneLineEach() {
        assertFalse(macCommand(ip, 1080).contains('\n'))
        assertFalse(macOffCommand(ip, 1080).contains('\n'))
    }
}
