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
    /** Neither USB nor hotspot sharing is turned on. */
    data object Off : TetherShare
    data object VpnNotConnected : TetherShare
    data object WaitingForVpn : TetherShare
    /** The VPN is up; how each kind of link is doing. */
    data class Active(val port: Int, val usb: Link = Link.Off, val hotspot: Link = Link.Off) : TetherShare
    sealed interface Link {
        /** Sharing over this link is turned off. */
        data object Off : Link
        /** Turned on, but that tethering isn't. */
        data object Waiting : Link
        data object PortInUse : Link
        /** Android doesn't say which Wi-Fi is its hotspot, so the hotspot isn't guessed at. */
        data object Unknown : Link
        data class Sharing(val address: String) : Link
    }
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
    private val bindFailures = mutableSetOf<Binding>()
    // Atomic, not the controller monitor: reconcile and close hold that while joining this
    // accept thread, which would then wait out the join timeout to log a refusal.
    private val lastRefusal = java.util.concurrent.atomic.AtomicLong(0)
    /** Who is using each link, for the status card (ConnectApp.tetherUsage). */
    private val meter = UsageMeter()
    @Volatile private var sharingLinks = emptySet<TetherLink>()
    private val usagePending = AtomicBoolean()
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in ConnectApp.SHARE_KEYS && !closed.get()) runCatching { timer.execute { reconcile() } }
    }
    /** Interfaces Android says it is tethering on, from its sticky broadcast; null if unknown. */
    @Volatile private var tethered: Set<String>? = null
    // Registration returns the sticky intent on this thread while broadcasts arrive on the main
    // thread, so a newer broadcast may land first; once one has, the older return value is dropped.
    private val tetherLock = Any()
    private var heardBroadcast = false
    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(tetherLock) { tethered = activeTethers(intent); heardBroadcast = true }
            if (!closed.get()) runCatching { timer.execute { reconcile() } }
        }
    }
    init {
        app.store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Only the system can send this protected broadcast, so exporting the receiver is safe;
        // the sender is the Tethering module, which isn't the system UID that NOT_EXPORTED admits.
        val filter = IntentFilter(TETHER_STATE_CHANGED)
        val sticky = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) service.registerReceiver(tetherReceiver, filter, Context.RECEIVER_EXPORTED)
            else service.registerReceiver(tetherReceiver, filter)
        }.getOrNull()
        synchronized(tetherLock) { if (!heardBroadcast) tethered = activeTethers(sticky) }
        timer.scheduleWithFixedDelay({ reconcile() }, 0, 2, TimeUnit.SECONDS)
    }
    fun state(connected: Boolean, reconnecting: Boolean) {
        this.connected = connected; this.reconnecting = reconnecting
        if (!closed.get()) runCatching { timer.execute { reconcile() } }
    }
    private data class Binding(val name: String, val link: TetherLink, val address: InetAddress, val prefix: Int)
    /** Interfaces of the phone's own networks. Throws if unreadable: then nothing is shared. */
    private fun upstream(): Set<String> {
        val cm = service.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
    }
    private fun bindings(): Set<Binding> {
        val before = upstream()
        val found = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .mapNotNull { iface -> tetherLink(iface.name, tethered, before)?.let { iface to it } }
            .flatMap { (iface, link) -> iface.interfaceAddresses.filter { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress && it.networkPrefixLength in 1..32 }
                .map { Binding(iface.name, link, it.address, it.networkPrefixLength.toInt()) } }
        // Check again after reading the addresses: if the hotspot's interface became the phone's
        // own Wi-Fi meanwhile, its new address must not be taken for the hotspot's.
        val upstream = upstream(); val tethered = tethered
        return found.filter { tetherLink(it.name, tethered, upstream) == it.link }.toSet()
    }
    /** With no list from Android: whether a Wi-Fi tether interface that isn't the phone's own
     *  network is up with an address, so the hotspot is probably on but can't be confirmed.
     *  Only chooses the message; nothing is shared on it. */
    private fun hotspotSuspected(): Boolean = runCatching {
        val upstream = upstream()
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { iface ->
            iface.isUp && wifiTetherName(iface.name) && iface.name !in upstream &&
                iface.interfaceAddresses.any { it.address is Inet4Address && !it.address.isLinkLocalAddress }
        }
    }.getOrDefault(false)
    /** Whether a listener's link is still what it was bound as, checked for every new client. */
    private fun stillServes(b: Binding): Boolean = runCatching {
        !closed.get() && app.store.shares(b.link) && tetherLink(b.name, tethered, upstream()) == b.link &&
            NetworkInterface.getByName(b.name)?.let { iface -> iface.isUp && iface.interfaceAddresses.any {
                it.address == b.address && it.networkPrefixLength.toInt() == b.prefix } } == true
    }.getOrDefault(false)
    @Synchronized private fun reconcile() {
        if (closed.get()) return
        sessions.filter { System.nanoTime() - it.activity > TimeUnit.SECONDS.toNanos(if (it.ready) 300 else 15) }.forEach { it.close() }
        val usbOn = app.store.shareWithTether
        val hotspotOn = app.store.shareOverHotspot
        val enabled = usbOn || hotspotOn
        val wanted = if (enabled && (connected || reconnecting))
            runCatching { bindings() }.getOrDefault(emptySet()).filter { app.store.shares(it.link) }.toSet() else emptySet()
        for (key in listeners.keys.toList()) if (key !in wanted) listeners.remove(key)?.close()
        bindFailures.retainAll(wanted)
        // A reconnect may retain existing listeners but must not start sharing before first Connected.
        if (connected) for (binding in wanted) if (binding !in listeners) {
            try {
                val listener = Listener(binding)
                listeners[binding] = listener
                bindFailures.remove(binding)
                app.log.add(service.getString(if (binding.link == TetherLink.Usb) R.string.tether_started else R.string.tether_started_hotspot,
                    binding.address.hostAddress, port))
                listener.thread.start()
            } catch (e: Exception) {
                // Only "address in use" means another app has the port. Anything else, such as
                // EADDRNOTAVAIL when the address went away between the scan and the bind (also a
                // BindException), is retried quietly on the next rescan without blaming an app.
                if (portInUse(e) && bindFailures.add(binding))
                    app.log.add(service.getString(R.string.tether_port_in_use, binding.address.hostAddress, port))
            }
        }
        fun link(kind: TetherLink, on: Boolean): TetherShare.Link {
            if (!on) return TetherShare.Link.Off
            // One address per kind, stable across rescans when a link has several.
            val shared = listeners.keys.filter { it.link == kind }.minByOrNull { "${it.name} ${it.address.hostAddress}" }
            return when {
                shared != null -> TetherShare.Link.Sharing(shared.address.hostAddress!!)
                bindFailures.any { it.link == kind } -> TetherShare.Link.PortInUse
                // Before any tethering since boot Android may not have said anything yet, so
                // only call the hotspot undetectable when one seems to be on.
                kind == TetherLink.Hotspot && tethered == null && hotspotSuspected() -> TetherShare.Link.Unknown
                else -> TetherShare.Link.Waiting
            }
        }
        ConnectApp.mutableTetherShare.value = when {
            !enabled -> TetherShare.Off
            !connected && !reconnecting -> TetherShare.VpnNotConnected
            !connected -> TetherShare.WaitingForVpn
            else -> TetherShare.Active(port, link(TetherLink.Usb, usbOn), link(TetherLink.Hotspot, hotspotOn))
        }
        sharingLinks = listeners.keys.map { it.link }.toSet()
        publishUsage()
    }
    /** On the timer thread only, so snapshots (which also reset links that stopped) don't interleave. */
    private fun publishUsage() {
        if (!closed.get()) ConnectApp.mutableTetherUsage.value = meter.snapshot(System.nanoTime(), sharingLinks)
    }
    /** A device connected or left: show it now rather than at the next rescan, at most one update queued. */
    private fun usageChanged() {
        if (closed.get() || !usagePending.compareAndSet(false, true)) return
        runCatching { timer.execute { usagePending.set(false); publishUsage() } }.onFailure { usagePending.set(false) }
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
                if (!stillServes(binding)) {
                    // The link changed under this listener; the rescan closes it.
                    client.close()
                    runCatching { timer.execute { reconcile() } }
                    continue
                }
                synchronized(sessions) {
                    if (closed.get() || server.isClosed || !app.store.shares(binding.link) || (!connected && !reconnecting) || sessions.size >= 256) client.close()
                    else {
                        val session = Session(this, client); sessions.add(session)
                        usageChanged()
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
    private fun vpn(link: TetherLink): ProxyNetwork? {
        // This link's own switch: a client accepted as its link was turned off doesn't get through.
        if (closed.get() || !app.store.shares(link) || (!connected && !reconnecting)) return null
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
        private val link = listener.binding.link
        private val device = client.inetAddress.hostAddress ?: "?"
        init { meter.opened(link, device, activity) }
        @Synchronized fun register(socket: Socket) { if (ended) { socket.close(); throw ProxyFailure() }; sockets.add(socket) }
        /** Counts what's read: from the client is upload, from the remote end download. */
        private fun input(socket: Socket, up: Boolean): InputStream = object : FilterInputStream(socket.getInputStream()) {
            private fun count(n: Int) { val now = System.nanoTime(); activity = now; meter.traffic(link, device, up, n, now) }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) count(it) }
        }
        fun run() {
            var socks = false; var tunnel = false; var replied = false
            try {
                val input = java.io.PushbackInputStream(input(client, up = true), 1)
                val first = input.read(); if (first < 0) return
                socks = first == 5
                val http: ProxyProtocol.Http?
                val target = if (socks) { http = null; ProxyProtocol.socks(input, client.getOutputStream()) }
                    else { input.unread(first); http = ProxyProtocol.http(input); http.target }
                val remote = connectViaVpn(vpn(listener.binding.link), target, ::register)
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
                val incoming = input(remote, up = false)
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
        @Synchronized override fun close() {
            val first = !ended
            ended = true; sockets.forEach { runCatching { it.close() } }; sessions.remove(this)
            if (first) { meter.closed(link, device, System.nanoTime()); usageChanged() }
        }
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
        ConnectApp.mutableTetherShare.value = if (app.store.sharing) TetherShare.VpnNotConnected else TetherShare.Off
        ConnectApp.mutableTetherUsage.value = TetherUsage()
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
