package com.muunkk.connectanytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import java.io.FilterInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface TetherShare {
    data object Off : TetherShare
    data object WaitingForTether : TetherShare
    data object WaitingForVpn : TetherShare
    data object PortInUse : TetherShare
    /** The proxy's address on USB tethering and on the hotspot; at least one is set. */
    data class Sharing(val port: Int, val usb: String? = null, val hotspot: String? = null) : TetherShare
    data object VpnNotConnected : TetherShare
}

/** One controller per service, serialized reconciliation; accepted sockets belong to a listener. */
internal class TetherProxy(private val service: ConnectVpnService, private val app: ConnectApp, private val port: Int = 1080) : AutoCloseable {
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val workers = ThreadPoolExecutor(0, 512, 30, TimeUnit.SECONDS, SynchronousQueue())
    private val listeners = mutableMapOf<Binding, Listener>()
    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Session>()
    private val closed = AtomicBoolean()
    @Volatile private var connected = false
    @Volatile private var reconnecting = false
    private val bindFailures = mutableSetOf<InetAddress>()
    // Atomic, not the controller monitor: reconcile and close hold that while joining this
    // accept thread, which would then wait out the join timeout to log a refusal.
    private val lastRefusal = java.util.concurrent.atomic.AtomicLong(0)
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shareWithTether" && !closed.get()) runCatching { timer.execute { reconcile() } }
    }
    /** Interfaces Android says it is tethering on, from its sticky broadcast; null if unknown. */
    @Volatile private var tethered: Set<String>? = null
    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            tethered = activeTethers(intent)
            if (!closed.get()) runCatching { timer.execute { reconcile() } }
        }
    }
    init {
        app.store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Only the system can send this protected broadcast, so exporting the receiver is safe;
        // the sender is the Tethering module, which isn't the system UID that NOT_EXPORTED admits.
        val filter = IntentFilter(TETHER_STATE_CHANGED)
        tethered = activeTethers(runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) service.registerReceiver(tetherReceiver, filter, Context.RECEIVER_EXPORTED)
            else service.registerReceiver(tetherReceiver, filter)
        }.getOrNull())
        timer.scheduleWithFixedDelay({ reconcile() }, 0, 2, TimeUnit.SECONDS)
    }
    fun state(connected: Boolean, reconnecting: Boolean) {
        this.connected = connected; this.reconnecting = reconnecting
        if (!closed.get()) runCatching { timer.execute { reconcile() } }
    }
    private data class Binding(val name: String, val link: TetherLink, val address: InetAddress, val prefix: Int)
    private fun bindings(): Set<Binding> {
        val cm = service.getSystemService(ConnectivityManager::class.java)
        // The phone's own networks. If this can't be read, share nothing rather than guess.
        @Suppress("DEPRECATION")
        val upstream = cm.allNetworks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        val tethered = tethered
        return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .mapNotNull { iface -> tetherLink(iface.name, tethered, upstream)?.let { iface to it } }
            .flatMap { (iface, link) -> iface.interfaceAddresses.filter { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress && it.networkPrefixLength in 1..32 }
                .map { Binding(iface.name, link, it.address, it.networkPrefixLength.toInt()) } }.toSet()
    }
    @Synchronized private fun reconcile() {
        if (closed.get()) return
        sessions.filter { System.nanoTime() - it.activity > TimeUnit.SECONDS.toNanos(if (it.ready) 300 else 15) }.forEach { it.close() }
        val enabled = app.store.shareWithTether
        val wanted = if (enabled && (connected || reconnecting)) runCatching { bindings() }.getOrDefault(emptySet()) else emptySet()
        for (key in listeners.keys.toList()) if (key !in wanted) listeners.remove(key)?.close()
        bindFailures.retainAll(wanted.map { it.address }.toSet())
        // A reconnect may retain existing listeners but must not start sharing before first Connected.
        if (connected) for (binding in wanted) if (binding !in listeners) {
            try {
                val listener = Listener(binding)
                listeners[binding] = listener
                bindFailures.remove(binding.address)
                app.log.add(service.getString(if (binding.link == TetherLink.Usb) R.string.tether_started else R.string.tether_started_hotspot,
                    binding.address.hostAddress, port))
                listener.thread.start()
            } catch (e: Exception) {
                // Only "address in use" means another app has the port. Anything else, such as
                // EADDRNOTAVAIL when the address went away between the scan and the bind (also a
                // BindException), is retried quietly on the next rescan without blaming an app.
                if (portInUse(e) && bindFailures.add(binding.address))
                    app.log.add(service.getString(R.string.tether_port_in_use, binding.address.hostAddress, port))
            }
        }
        ConnectApp.mutableTetherShare.value = when {
            !enabled -> TetherShare.Off
            !connected && !reconnecting -> TetherShare.VpnNotConnected
            wanted.isEmpty() -> TetherShare.WaitingForTether
            !connected -> TetherShare.WaitingForVpn
            listeners.isEmpty() && bindFailures.isNotEmpty() -> TetherShare.PortInUse
            listeners.isEmpty() -> TetherShare.WaitingForTether
            else -> {
                // One address per kind, stable across rescans when a link has several.
                fun first(link: TetherLink) = listeners.keys.filter { it.link == link }
                    .minByOrNull { "${it.name} ${it.address.hostAddress}" }?.address?.hostAddress
                TetherShare.Sharing(port, usb = first(TetherLink.Usb), hotspot = first(TetherLink.Hotspot))
            }
        }
    }
    private fun portInUse(e: Exception) = e is java.net.BindException &&
        (e.message.orEmpty().contains("EADDRINUSE") || e.message.orEmpty().contains("in use", ignoreCase = true))
    private inner class Listener(val binding: Binding) : AutoCloseable {
        val server = ServerSocket().also { socket ->
            try { socket.reuseAddress = true; socket.bind(InetSocketAddress(binding.address, port), 32) }
            catch (e: Exception) { socket.close(); throw e }
        }
        val thread = Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                if (!allowedPeer(binding.address, binding.prefix, client.inetAddress,
                        runCatching { phoneOwnsAddress(client.inetAddress) }.getOrDefault(true))) {
                    client.close()
                    val now = System.nanoTime()
                    val last = lastRefusal.get()
                    if ((last == 0L || now - last > TimeUnit.SECONDS.toNanos(10)) && lastRefusal.compareAndSet(last, now))
                        app.log.add(service.getString(R.string.tether_refused))
                    continue
                }
                synchronized(sessions) {
                    if (closed.get() || server.isClosed || !app.store.shareWithTether || (!connected && !reconnecting) || sessions.size >= 256) client.close()
                    else {
                        val session = Session(this, client); sessions.add(session)
                        try { workers.execute { session.run() } } catch (_: Exception) { session.close() }
                    }
                }
            }
        }, "Tether proxy accept")
        override fun close() {
            server.close()
            sessions.filter { it.listener === this }.forEach { it.close() }
            thread.join(1000)
            app.log.add(service.getString(if (binding.link == TetherLink.Usb) R.string.tether_stopped else R.string.tether_stopped_hotspot))
        }
    }
    private fun vpn(): ProxyNetwork? {
        if (closed.get() || !app.store.shareWithTether || (!connected && !reconnecting)) return null
        val cm = service.getSystemService(ConnectivityManager::class.java)
        // Match our actual tun, not another VPN visible during a handover/revocation.
        val iface = service.tetherVpnInterface() ?: return null
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true && cm.getLinkProperties(it)?.interfaceName == iface
        } ?: return null
        return AndroidProxyNetwork(network)
    }
    private inner class Session(val listener: Listener, val client: Socket) : AutoCloseable {
        private val sockets = mutableSetOf(client)
        private var ended = false
        @Volatile var ready = false
        @Volatile var activity = System.nanoTime()
        @Synchronized fun register(socket: Socket) { if (ended) { socket.close(); throw ProxyFailure() }; sockets.add(socket) }
        private fun input(socket: Socket): InputStream = object : FilterInputStream(socket.getInputStream()) {
            override fun read(): Int = super.read().also { if (it >= 0) activity = System.nanoTime() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) activity = System.nanoTime() }
        }
        fun run() {
            var socks = false; var tunnel = false; var replied = false
            try {
                val input = java.io.PushbackInputStream(input(client), 1)
                val first = input.read(); if (first < 0) return
                socks = first == 5
                val http: ProxyProtocol.Http?
                val target = if (socks) { http = null; ProxyProtocol.socks(input, client.getOutputStream()) }
                    else { input.unread(first); http = ProxyProtocol.http(input); http.target }
                val remote = connectViaVpn(vpn(), target, ::register)
                ready = true
                tunnel = socks || http!!.connect
                if (socks) ProxyProtocol.socksReply(client.getOutputStream(), 0)
                else if (tunnel) client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                else ProxyProtocol.writeHead(remote.getOutputStream(), http!!.head)
                replied = true
                val uploadDone = CountDownLatch(1)
                workers.execute {
                    try {
                        uploadProxyRequest(input, remote, if (tunnel) null else http!!.head)
                    } catch (_: Exception) { close() } finally { uploadDone.countDown() }
                }
                val incoming = input(remote)
                if (tunnel) incoming.copyTo(client.getOutputStream(), 16384)
                else {
                    var response: ProxyProtocol.Head
                    do {
                        response = ProxyProtocol.head(incoming)
                        if (!response.first.matches(Regex("HTTP/1\\.[01] [0-9]{3}( .*)?"))) throw ProxyFailure()
                        val code = response.first.substring(9, 12).toInt()
                        if (code == 101) throw ProxyFailure()
                        ProxyProtocol.writeHead(client.getOutputStream(), response)
                    } while (code in 100..199)
                    val code = response.first.substring(9, 12).toInt()
                    if (!http!!.head.first.startsWith("HEAD ") && code != 204 && code != 304) {
                        if (response.chunked() || response.length() != null) ProxyProtocol.body(incoming, client.getOutputStream(), response)
                        else incoming.copyTo(client.getOutputStream(), 16384)
                    }
                }
                client.shutdownOutput()
                // Preserve client-to-server half-close after server EOF. Idle/stop closes both.
                while (!uploadDone.await(1, TimeUnit.SECONDS)) { synchronized(this) { if (ended) break } }
            } catch (e: Exception) {
                if (!replied) runCatching {
                    if (socks && e !is java.io.EOFException) ProxyProtocol.socksReply(client.getOutputStream(), (e as? ProxyFailure)?.socksCode ?: 1)
                    else if (!socks) client.getOutputStream().write("HTTP/1.1 ${if ((e as? ProxyFailure)?.socksCode == 2) "403 Forbidden" else "503 Service Unavailable"}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            } finally { close() }
        }
        @Synchronized override fun close() { ended = true; sockets.forEach { runCatching { it.close() } }; sessions.remove(this) }
    }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        app.store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { service.unregisterReceiver(tetherReceiver) }
        timer.shutdown()
        // reconcile uses this same monitor; a timed executor wait alone cannot protect the map.
        listeners.values.toList().forEach { runCatching { it.close() } }; listeners.clear()
        synchronized(sessions) { sessions.toList().forEach { it.close() } }
        workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS)
        ConnectApp.mutableTetherShare.value = if (app.store.shareWithTether) TetherShare.VpnNotConnected else TetherShare.Off
    }
}

/** Explicit network DNS with cancellation: stopping the proxy interrupts the bounded wait. */
internal class AndroidProxyNetwork(private val network: Network) : ProxyNetwork {
    override fun resolve(host: String): List<InetAddress> {
        if (android.net.InetAddresses.isNumericAddress(host)) return listOf(android.net.InetAddresses.parseNumericAddress(host))
        val done = CountDownLatch(1); val cancel = CancellationSignal()
        var result: List<InetAddress> = emptyList()
        try {
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, { it.run() }, cancel,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) { if (rcode == 0) result = answer; done.countDown() }
                    override fun onError(error: DnsResolver.DnsException) { done.countDown() }
                })
            if (!done.await(15, TimeUnit.SECONDS)) throw java.net.UnknownHostException(host)
            return result
        } finally { cancel.cancel() }
    }
    override fun connect(address: InetAddress, port: Int, register: (Socket) -> Unit): Socket {
        if (!allowedDestination(address, phoneOwnsAddress(address))) throw ProxyFailure(2)
        val socket = Socket()
        try {
            register(socket)
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port), 15000)
            return socket
        } catch (e: Exception) { socket.close(); throw e }
    }
}
