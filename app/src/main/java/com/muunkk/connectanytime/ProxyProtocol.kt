package com.muunkk.connectanytime

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.NetworkInterface
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.Locale

internal class ProxyFailure(val socksCode: Int = 1, message: String = "Proxy request refused") : IOException(message)
internal data class ProxyTarget(val host: String, val port: Int)
internal interface ProxyNetwork {
    fun resolve(host: String): List<InetAddress>
    fun connect(address: InetAddress, port: Int, register: (Socket) -> Unit): Socket
}
/** No default network, default resolver, or unbound connect exists on this path. */
internal fun connectViaVpn(network: ProxyNetwork?, target: ProxyTarget, register: (Socket) -> Unit): Socket {
    val vpn = network ?: throw ProxyFailure()
    val addresses = try { vpn.resolve(target.host) } catch (e: UnknownHostException) { throw ProxyFailure(4) }
    if (addresses.isEmpty()) throw ProxyFailure(4)
    var failure: IOException = ProxyFailure()
    for (address in addresses) {
        if (!allowedDestination(address, phoneOwnsAddress(address))) throw ProxyFailure(2)
        try { return vpn.connect(address, target.port, register) } catch (e: IOException) { failure = e }
    }
    throw when (failure) {
        is ProxyFailure -> failure
        is ConnectException -> ProxyFailure(if (failure.message.orEmpty().contains("refused", ignoreCase = true)) 5 else 1)
        else -> ProxyFailure()
    }
}
/** Pure policy: assignment is supplied by the caller, never discovered inside the predicate. */
internal fun allowedDestination(address: InetAddress, assignedToPhone: Boolean): Boolean {
    val normalized = normalizedAddress(address)
    return !assignedToPhone && !normalized.isLoopbackAddress && !normalized.isAnyLocalAddress &&
        !normalized.isLinkLocalAddress && !normalized.isMulticastAddress
}
internal fun phoneOwnsAddress(address: InetAddress) = NetworkInterface.getByInetAddress(normalizedAddress(address)) != null
private fun normalizedAddress(address: InetAddress): InetAddress {
    val bytes = address.address
    // Inet6Address can retain mapped IPv4 bytes, unlike InetAddress.getByAddress.
    return if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte()) InetAddress.getByAddress(bytes.copyOfRange(12, 16)) else address
}
internal fun allowedPeer(local: InetAddress, prefix: Int, peer: InetAddress, assignedToPhone: Boolean) =
    !assignedToPhone && peer != local && sameSubnet(local, prefix, peer)

internal fun uploadProxyRequest(input: InputStream, remote: Socket, head: ProxyProtocol.Head?) {
    if (head == null) {
        input.copyTo(remote.getOutputStream(), 16384)
        remote.shutdownOutput()
    } else ProxyProtocol.body(input, remote.getOutputStream(), head)
}
internal object ProxyProtocol {
    private fun byte(input: InputStream): Int = input.read().also { if (it < 0) throw EOFException() }
    private fun bytes(input: InputStream, n: Int): ByteArray = ByteArray(n).also {
        for (i in it.indices) it[i] = byte(input).toByte()
    }
    fun socks(input: InputStream, output: OutputStream): ProxyTarget {
        val count = byte(input)
        val methods = bytes(input, count)
        if (count == 0 || 0.toByte() !in methods) {
            output.write(byteArrayOf(5, -1)); output.flush(); throw EOFException()
        }
        output.write(byteArrayOf(5, 0)); output.flush()
        if (byte(input) != 5) throw ProxyFailure()
        val command = byte(input)
        if (byte(input) != 0) throw ProxyFailure()
        if (command != 1) throw ProxyFailure(7)
        val host = when (byte(input)) {
            1 -> InetAddress.getByAddress(bytes(input, 4)).hostAddress!!
            4 -> InetAddress.getByAddress(bytes(input, 16)).hostAddress!!
            3 -> {
                val name = bytes(input, byte(input))
                if (name.isEmpty() || name.any { (it.toInt() and 255) !in 33..126 }) throw ProxyFailure(8)
                name.toString(Charsets.US_ASCII)
            }
            else -> throw ProxyFailure(8)
        }
        val port = byte(input) * 256 + byte(input)
        if (port == 0) throw ProxyFailure()
        return ProxyTarget(host, port)
    }
    fun socksReply(output: OutputStream, code: Int) {
        output.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
    }
    // Byte-at-a-time header parsing deliberately leaves tunnel/body bytes untouched.
    fun line(input: InputStream, max: Int = 8192, obsText: Boolean = false): String {
        val out = StringBuilder()
        while (out.length <= max) {
            val b = byte(input)
            if (b == 13) {
                if (byte(input) != 10) throw ProxyFailure()
                return out.toString()
            }
            if (b !in 32..126 && b != 9 && !(obsText && b in 128..255)) throw ProxyFailure()
            out.append(b.toChar())
        }
        throw ProxyFailure()
    }
    data class Head(val first: String, val headers: List<Pair<String, String>>) {
        fun values(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }
        fun length(): Long? {
            val lengths = values("content-length")
            if (lengths.size > 1 || (lengths.isNotEmpty() && !lengths.single().matches(Regex("[0-9]+")))) throw ProxyFailure()
            return lengths.singleOrNull()?.toLongOrNull()?.also { if (it < 0) throw ProxyFailure() }
                ?: if (lengths.isEmpty()) null else throw ProxyFailure()
        }
        fun chunked(): Boolean {
            val te = values("transfer-encoding")
            if (te.isEmpty()) return false
            if (te.size != 1 || !te.single().equals("chunked", true) || length() != null) throw ProxyFailure()
            return true
        }
        fun cleaned(): List<Pair<String, String>> {
            val remove = setOf("connection", "proxy-connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade") +
                values("connection").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
            // Framing headers nominated by Connection would make the body ambiguous.
            if ("content-length" in remove || values("connection").any { it.contains("transfer-encoding", true) }) throw ProxyFailure()
            return headers.filter { it.first.lowercase(Locale.ROOT) !in remove }
        }
    }
    fun head(input: InputStream): Head {
        val first = line(input)
        val headers = mutableListOf<Pair<String, String>>()
        var size = first.length
        while (true) {
            val line = line(input, obsText = true)
            size += line.length + 2
            if (size > 32768 || headers.size > 100) throw ProxyFailure()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0 || !line.substring(0, colon).matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) throw ProxyFailure()
            headers += line.substring(0, colon) to line.substring(colon + 1).trim(' ', '\t')
        }
        return Head(first, headers).also { it.length(); it.chunked(); it.cleaned() }
    }
    data class Http(val target: ProxyTarget, val head: Head, val connect: Boolean)
    fun http(input: InputStream): Http {
        val h = head(input)
        val parts = h.first.split(' ')
        if (parts.size != 3 || parts[2] !in listOf("HTTP/1.0", "HTTP/1.1") || !parts[0].matches(Regex("[A-Z]+"))) throw ProxyFailure()
        val connect = parts[0] == "CONNECT"
        val uri = try { URI(if (connect) "http://${parts[1]}" else parts[1]) } catch (_: Exception) { throw ProxyFailure() }
        if (uri.scheme != "http" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawFragment != null || uri.port == 0 || uri.port > 65535) throw ProxyFailure()
        if (connect && (uri.port < 1 || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null)) throw ProxyFailure()
        val host = uri.host.removePrefix("[").removeSuffix("]")
        val port = if (uri.port < 0) 80 else uri.port
        val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val authority = (if (':' in host) "[$host]" else host) + if (port == 80) "" else ":$port"
        val forward = Head("${parts[0]} $path ${parts[2]}", h.cleaned().filterNot { it.first.equals("host", true) } + ("Host" to authority) +
            (if (h.chunked()) listOf("Transfer-Encoding" to "chunked") else emptyList()))
        return Http(ProxyTarget(host, port), if (connect) h else forward, connect)
    }
    fun writeHead(output: OutputStream, head: Head, chunked: Boolean = head.chunked()) {
        val text = head.first + "\r\n" + head.cleaned().joinToString("") { "${it.first}: ${it.second}\r\n" } +
            (if (chunked) "Transfer-Encoding: chunked\r\n" else "") + "Connection: close\r\n\r\n"
        output.write(text.toByteArray(Charsets.ISO_8859_1)); output.flush()
    }
    fun copyCount(input: InputStream, output: OutputStream, count: Long) {
        var left = count
        val buffer = ByteArray(16384)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw EOFException()
            output.write(buffer, 0, n); left -= n
        }
        output.flush()
    }
    fun body(input: InputStream, output: OutputStream, head: Head) {
        if (!head.chunked()) { copyCount(input, output, head.length() ?: 0); return }
        while (true) {
            val chunk = line(input)
            val digits = chunk.substringBefore(';')
            if (!digits.matches(Regex("[0-9a-fA-F]+"))) throw ProxyFailure()
            val size = digits.toLongOrNull(16) ?: throw ProxyFailure()
            if (size < 0) throw ProxyFailure()
            output.write("${size.toString(16)}\r\n".toByteArray())
            if (size == 0L) {
                var total = 0
                while (true) { val trailer = line(input); total += trailer.length + 2; if (total > 32768) throw ProxyFailure(); if (trailer.isEmpty()) break }
                output.write("\r\n".toByteArray()); output.flush(); return
            }
            copyCount(input, output, size)
            if (byte(input) != 13 || byte(input) != 10) throw ProxyFailure()
            output.write("\r\n".toByteArray()); output.flush()
        }
    }
}
// AOSP Tethering/res/values/config.xml defaults: usb\\d, rndis\\d.
// Include numbered NCM gadget interfaces, but no generic Ethernet/Wi-Fi patterns.
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/res/values/config.xml
internal fun usbTetherName(name: String) = name.matches(Regex("(?:rndis|ncm|usb)[0-9]+"))
internal fun sameSubnet(local: InetAddress, prefix: Int, peer: InetAddress): Boolean {
    val a = local.address; val b = peer.address
    if (a.size != 4 || b.size != 4 || prefix !in 1..32 || peer.isAnyLocalAddress || peer.isMulticastAddress || peer.isLoopbackAddress) return false
    return a.indices.all { i -> val bits = (prefix - i * 8).coerceIn(0, 8); val mask = (255 shl (8 - bits)) and 255; (a[i].toInt() and mask) == (b[i].toInt() and mask) }
}
