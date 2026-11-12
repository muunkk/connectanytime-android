package com.muunkk.connectanytime

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.content.ComponentName
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ConnectVpnService : VpnService() {
    private val queue = Executors.newSingleThreadScheduledExecutor()
    // Deadline timers cannot use queue: it blocks waiting for Duo or TLS.
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val sessionLock = Any()
    private var session = 0L
    private var generation = 0
    @Volatile private var stopping = false
    @Volatile private var logout = false
    @Volatile private var manualRequested = false
    private var launched = false // on Android's main thread only
    private val native = NativeBridge()
    private val app get() = application as ConnectApp
    private val store get() = app.store
    private fun log(s: String) = app.log.add(s)
    private var tun: ParcelFileDescriptor? = null
    private var installed: TunnelConfig? = null
    @Volatile private var tetherAddress: String? = null
    @Volatile private var tetherProxy: TetherProxy? = null
    internal fun tetherVpnInterface(): String? = tetherAddress?.let { address ->
        java.net.NetworkInterface.getByInetAddress(android.net.InetAddresses.parseNumericAddress(address))?.name
    }
    private var watch: NetworkWatch? = null
    private var underlying: Network? = null
    private var networkBaseline = NetworkBaseline()
    private var online = false
    private var watchdog: ScheduledFuture<*>? = null
    private var debounce: ScheduledFuture<*>? = null
    private var pendingRejoin: ScheduledFuture<*>? = null
    private val backoff = RejoinBackoff()
    private var down = false
    private var downOnline = 0L
    private var lastCheck = SystemClock.uptimeMillis()
    private var lastElapsed = SystemClock.elapsedRealtime()
    private var lastAwake = SystemClock.uptimeMillis()
    @Volatile private var destroyed = false
    @Volatile private var finished = false
    /** Latest Connect waiting for cleanup; published on main before queueing it. */
    @Volatile private var pendingConnect: Long? = null
    private val commands = CommandGeneration()
    @Volatile private var latestStartId = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastKick = 0L
    private var givingUp = false
    private var server = ""
    private var user = ""
    private var replacingConfig = false
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ARM && !launched) { stopSelfResult(startId); return START_NOT_STICKY }
        val manual = intent?.action == CONNECT
        val commandToken = if (manual || intent?.action == DISCONNECT) commands.next() else null
        if (intent?.action == DISCONNECT) {
            // Durable intent first: a crash or concurrent successful login must not
            // resurrect a session after Disconnect.
            synchronized(sessionLock) { store.disconnected = true; store.clearSession() }
            requestStop(true)
            // Also when this run already finished: a Disconnect delivered after finish()'s
            // stop (which used an older start ID) would otherwise leave a started service.
            if (!launched || finished) { stopSelfResult(startId); return START_NOT_STICKY }
            return START_NOT_STICKY
        }
        if (manual) manualRequested = true
        if (launched) {
            // Connect right after Disconnect (or a failure) reaches this instance while it's
            // still winding down. Ignoring it would drop the tap; run it once this one has
            // finished. `queue` is serial, so this lands either before finish (which then
            // restarts) or after it.
            if (manual && (stopping || finished)) {
                pendingConnect = commandToken
                queue.execute {
                    val token = checkNotNull(commandToken)
                    if (commands.isCurrent(token) && finished) main.post { restart(token) }
                }
            }
            // A late ARM (or other non-Connect start) after this run finished would leave a
            // started, non-foreground service behind; stop for it instead.
            if (finished && pendingConnect == null) { stopSelfResult(startId); return START_NOT_STICKY }
            return if (store.stayConnected && store.hasSession && !stopping) START_STICKY else START_NOT_STICKY
        }
        // Android re-creating the service after its process was killed (sticky). That
        // only stands for Stay Connected; once you turn it off, the earlier sticky return
        // mustn't bring the VPN back. Always-on VPN starts carry an intent and go ahead.
        if (intent == null && !store.stayConnected) {
            log(getString(R.string.stay_off))
            stopSelfResult(latestStartId)
            return START_NOT_STICKY
        }
        stopping = false
        logout = false
        finished = false
        if (manual) store.disconnected = false
        val refusal = StartRule.refusal(manual, store.hasSession, store.disconnected)
        if (refusal != null) {
            RefusedStarts(this, store, app.log).note()
            ConnectApp.mutableState.value = VpnState(error = refusal)
            stopSelfResult(latestStartId)
            return START_NOT_STICKY
        }
        launched = true
        foreground = true
        val notification = Notices.ongoing(this, "Connecting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notices.ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(Notices.ONGOING, notification)
        queue.execute { start(manual) }
        // Not sticky until a successful login saved a session. Android remembers
        // a later onStartCommand return; arm that with an internal action below.
        return if (store.stayConnected && store.hasSession) START_STICKY else START_NOT_STICKY
    }

    private fun state(status: String, connected: Boolean = false, error: String? = null) {
        if (tetherProxy == null && connected) tetherProxy = TetherProxy(this, app)
        tetherProxy?.state(connected && !stopping, status == "Reconnecting…" && !stopping)
        ConnectApp.mutableState.value = VpnState(status, status != "Not connected", connected, error)
        if (foreground) getSystemService(android.app.NotificationManager::class.java).notify(Notices.ONGOING, Notices.ongoing(this, status))
        TileService.requestListeningState(this, ComponentName(this, ConnectTileService::class.java))
    }
    private fun start(manual: Boolean) {
        try {
            RefusedStarts(this, store, app.log).end()
            log(BuildConfig.BUILD_DESCRIPTION)
            if (store.running) log(getString(when (RunStamp.cause(store.runStamp, runStamp())) {
                RunStamp.Cause.Reboot -> R.string.was_on_reboot
                RunStamp.Cause.Update -> R.string.was_on_update
                RunStamp.Cause.Unexpected -> R.string.unclean
            }))
            store.running = false
            server = store.server
            user = store.user
            require(user.isNotBlank()) { getString(R.string.enter_account) }
            log(getString(R.string.connecting_server, server, if (manual) "manual" else "auto"))
            state("Connecting… approve Duo on your phone")
            var saved = savedSession()
            if (saved != null) {
                for (attempt in 1..4) {
                    refreshNetwork()
                    val result = resume(checkNotNull(saved))
                    if (result == 0) break
                    checkNotStopping()
                    if (result == NativeBridge.SESSION_ENDED) { store.clearSession(); saved = null; break }
                    if (manual || manualRequested) break
                    if (attempt == 4) error(getString(R.string.unreachable))
                    pause(attempt * 5_000L)
                }
            }
            if (session == 0L) {
                check(StartRule.mayLogin(manual || manualRequested, store.disconnected)) { StartRule.REFUSAL }
                val password = store.password() ?: error(getString(R.string.save_account))
                for (attempt in 1..3) {
                    checkNotStopping()
                    refreshNetwork()
                    val s = newSession(password)
                    val token = generation
                    val start = SystemClock.uptimeMillis()
                    var timedOut = false
                    val timer = deadlines.scheduleWithFixedDelay({
                        synchronized(sessionLock) {
                            if (session == s && generation == token) {
                                val aborted = native.abortBeforePassword(s)
                                if (!aborted && SystemClock.uptimeMillis() - start >= 180_000) {
                                    timedOut = true
                                    native.command(s, NativeBridge.CANCEL)
                                }
                            }
                        }
                    }, 20, 1, TimeUnit.SECONDS)
                    val result = try { native.connect(s) } finally { timer.cancel(false) }
                    if (result == 0) break
                    val message = native.error(s)
                    freeSession()
                    checkNotStopping()
                    if (synchronized(sessionLock) { timedOut }) error(getString(R.string.login_timeout))
                    if (result != NativeBridge.UNREACHABLE || attempt == 3) error(message)
                    log(getString(R.string.try_again, message))
                    pause(attempt * 3_000L)
                }
                saved = null
            }
            // Save before checking an OS stop: a completed login can still be
            // rejoined next time. A user's Disconnect wins under sessionLock.
            saveSession(saved)
            checkNotStopping()
            applyConfig(native.config(session)?.let(TunnelConfig::from) ?: error(getString(R.string.no_settings)))
            checkNotStopping()
            synchronized(sessionLock) { if (!stopping) { store.runStamp = runStamp(); store.running = true } }
            log(getString(R.string.connected))
            Notices.clear(this)
            state("Connected", true)
            // Re-deliver an internal (automatic) start only to update Android's
            // sticky return value; it cannot start a second login.
            startService(Intent(this, ConnectVpnService::class.java).setAction(ARM))
            watchNetwork()
            run()
        } catch (e: Exception) {
            finish(if (stopping) null else safeMessage(e))
        }
    }

    private fun safeMessage(e: Exception): String = when (e) {
        // Keystore failures Store.recover kept the data through; ProviderException is
        // Android Keystore's unchecked one, so it isn't a GeneralSecurityException.
        is java.security.GeneralSecurityException, is java.security.ProviderException -> getString(R.string.credentials_error)
        else -> e.message ?: getString(R.string.start_error)
    }
    private fun savedSession(): SavedSession? {
        val s = store.session() ?: return null
        if (s.server == server && s.user == user) return s
        store.clearSession()
        return null
    }
    private fun saveSession(previous: SavedSession?) = synchronized(sessionLock) {
        if (logout || store.disconnected) { store.clearSession(); return@synchronized }
        val values = native.saved(session)
        check(values[0].isNotBlank() && values[1].isNotBlank()) { getString(R.string.no_session) }
        store.saveSession(SavedSession(server, user, values[0], values[1], previous?.dnsName ?: values[2], previous?.ip ?: values[3]))
    }
    private fun newSession(password: String): Long = synchronized(sessionLock) {
        checkNotStopping()
        val token = ++generation
        val cb = object : NativeBridge.Callbacks {
            // C caps logs at 1023 bytes; replace any truncated UTF-8 tail.
            override fun onLog(line: ByteArray) = log(line.toString(Charsets.UTF_8))
            override fun onEvent(event: Int, config: Array<String>?) {
                val cfg = config?.let(TunnelConfig::from)
                queue.execute {
                    if (token != generation || stopping) return@execute
                    if (event == 2) {
                        lastKick = SystemClock.uptimeMillis()
                        if (cfg != null && cfg.changedFrom(installed)) {
                            // Builder can't mutate an established interface. Detach this
                            // connection, then rejoin and establish its newest config.
                            // Keep the old fd until the new interface is established:
                            // queued traffic must wait, never bypass during reconnect.
                            replacingConfig = true
                            reconnecting()
                            command(NativeBridge.DETACH)
                        } else connected()
                    } else {
                        reconnecting()
                        if (event == 1) lastKick = SystemClock.uptimeMillis()
                    }
                }
            }
            override fun verify(chain: Array<ByteArray>, hostname: String) = PlatformTrust.verify(chain, hostname)
            override fun protectSocket(fd: Int) = protect(fd)
        }
        val bytes = password.toByteArray(Charsets.UTF_8)
        try {
            native.create(server.toByteArray(Charsets.UTF_8), user.toByteArray(Charsets.UTF_8), bytes, cb)
                .also { check(it != 0L) { getString(R.string.client_error) }; session = it }
        } finally { bytes.fill(0) }
    }
    private fun freeSession() = synchronized(sessionLock) {
        if (session != 0L) native.free(session)
        session = 0L
        generation++ // discard any callback queued by the connection just freed
    }
    private fun command(cmd: Int) = synchronized(sessionLock) { if (session != 0L) native.command(session, cmd) }
    private fun checkNotStopping() { check(!stopping) { getString(R.string.cancelled) } }
    private fun pause(ms: Long) {
        val end = SystemClock.uptimeMillis() + ms
        while (!stopping && SystemClock.uptimeMillis() < end) Thread.sleep(minOf(100, end - SystemClock.uptimeMillis()).coerceAtLeast(1))
        checkNotStopping()
    }
    private fun resume(saved: SavedSession): Int {
        val s = newSession("")
        val token = generation
        val timer = deadlines.scheduleWithFixedDelay({
            synchronized(sessionLock) { if (session == s && generation == token) native.command(s, NativeBridge.DETACH) }
        }, 30, 1, TimeUnit.SECONDS)
        val result = try { native.resume(s, saved.native()) } finally { timer.cancel(false) }
        if (result != 0) { log(getString(R.string.rejoin_error, native.error(s))); freeSession() }
        return result
    }
    private fun run() {
        val s = session
        val fd = checkNotNull(tun).fd
        Thread({
            val result = native.run(s, fd)
            queue.execute {
                freeSession()
                if (stopping) finish(null)
                else if (givingUp) finish(getString(R.string.long_unreachable))
                else {
                    // OpenConnect returns -errno; say what it means rather than a bare number.
                    log(getString(R.string.connection_ended, if (result < 0) android.system.Os.strerror(-result) else "closed by the server"))
                    scheduleRejoin(if (replacingConfig) 0 else null)
                    replacingConfig = false
                }
            }
        }, "connectanytime.openconnect").start()
    }
    private fun scheduleRejoin(delay: Long? = null) {
        if (stopping) return finish(null)
        reconnecting()
        pendingRejoin?.cancel(false)
        val wait = delay ?: backoff.next(SystemClock.uptimeMillis())
        if (wait > 0) log(getString(R.string.rejoin_wait, wait / 1000))
        pendingRejoin = queue.schedule({ rejoin() }, wait, TimeUnit.MILLISECONDS)
    }
    private fun rejoin() {
        pendingRejoin = null
        try {
            checkNotStopping()
            val saved = savedSession() ?: return sessionEnded()
            val result = resume(checkNotNull(saved))
            if (stopping) return finish(null)
            if (result == NativeBridge.SESSION_ENDED) return sessionEnded()
            if (result != 0) return scheduleRejoin()
            saveSession(saved)
            val cfg = native.config(session)?.let(TunnelConfig::from) ?: error(getString(R.string.no_settings))
            applyConfig(cfg)
            connected()
            run()
        } catch (e: Exception) {
            freeSession()
            if (stopping) finish(null)
            else { log(getString(R.string.rejoin_failed, safeMessage(e))); scheduleRejoin() }
        }
    }
    private fun sessionEnded() {
        store.clearSession()
        Notices.ended(this)
        finish(StartRule.REFUSAL)
    }
    private fun connected() {
        if (stopping) return
        if (down) log(getString(R.string.back_up))
        down = false; downOnline = 0; lastCheck = SystemClock.uptimeMillis()
        state("Connected", true)
    }
    private fun reconnecting() {
        if (!down) { down = true; downOnline = 0; lastCheck = SystemClock.uptimeMillis(); lastKick = lastCheck }
        state("Reconnecting…")
    }
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        watch = NetworkWatch(cm) { n, id ->
            native.setNetwork(n?.networkHandle ?: 0)
            queue.execute {
                val changed = networkBaseline.changed(id)
                underlying = n
                setUnderlyingNetworks(n?.let { arrayOf(it) } ?: emptyArray())
                accountDown(SystemClock.uptimeMillis())
                online = n != null
                if (changed && !stopping) {
                    log(if (n == null) getString(R.string.network_lost) else getString(R.string.network_changed))
                    reconnecting()
                    debounce?.cancel(false)
                    if (n != null) debounce = queue.schedule({
                        if (!stopping) {
                            if (session == 0L) { backoff.reset(); scheduleRejoin(0) }
                            else { lastKick = SystemClock.uptimeMillis(); command(NativeBridge.RECONNECT) }
                        }
                    }, 2, TimeUnit.SECONDS)
                }
            }
        }.also { it.start() }
        watchdog = queue.scheduleWithFixedDelay({ checkReconnect() }, 10, 10, TimeUnit.SECONDS)
    }
    private fun accountDown(now: Long) {
        if (down && online) downOnline += now - lastCheck
        lastCheck = now
    }
    private fun checkReconnect() {
        val now = SystemClock.uptimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val slept = (elapsed - lastElapsed) - (now - lastAwake)
        if (slept >= 60_000) log(getString(R.string.woke, slept / 60_000))
        lastAwake = now
        lastElapsed = elapsed
        accountDown(now)
        if (!down || !online || stopping || givingUp) return
        if (downOnline >= 1_800_000) {
            givingUp = true
            log(getString(R.string.stopping_unreachable))
            if (session == 0L) finish(getString(R.string.long_unreachable))
            else command(NativeBridge.DETACH)
        } else if (session != 0L && now - lastKick >= 60_000) {
            log(getString(R.string.slow_reconnect))
            lastKick = now
            command(NativeBridge.RECONNECT)
        }
    }
    /** This boot and this install, to tell a restart or update from a real crash. */
    private fun runStamp() = RunStamp.of(
        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1),
        packageManager.getPackageInfo(packageName, 0).lastUpdateTime)
    /** The network watch only starts once connected, so each startup attempt picks up
     *  the current physical network itself: one that came back (or went) during the
     *  retry waits would otherwise leave DNS on a stale handle. */
    private fun refreshNetwork() {
        underlying = physicalNetwork(getSystemService(ConnectivityManager::class.java))
        native.setNetwork(underlying?.networkHandle ?: 0)
    }
    @Suppress("DEPRECATION")
    private fun physicalNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        fun physical(n: Network) = cm.getNetworkCapabilities(n)?.let {
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
        return active?.takeIf(::physical) ?: cm.allNetworks.firstOrNull(::physical)
    }
    private fun applyConfig(cfg: TunnelConfig) {
        if (!cfg.changedFrom(installed)) return
        val builder = Builder().setSession(getString(R.string.app_name)).setBlocking(false).setMetered(false)
        val addr = Prefix.parse("${cfg.address}/${cfg.mask}")
        builder.addAddress(cfg.address, addr.bits)
        val full = cfg.includes.isEmpty()
        val includes = if (full) listOf(Prefix.parse("0.0.0.0/0")) else cfg.includes.map(Prefix::parse)
        val excludes = cfg.excludes.map(Prefix::parse)
        val routes = if (Build.VERSION.SDK_INT >= 33) includes else Prefix.complement(includes, excludes)
        routes.forEach { builder.addRoute(InetAddress.getByName(it.text.substringBefore("/")), it.bits) }
        if (Build.VERSION.SDK_INT >= 33) excludes.forEach { builder.excludeRoute(IpPrefix(InetAddress.getByName(it.text.substringBefore("/")), it.bits)) }
        if (cfg.address6.isNotBlank()) {
            val parts = cfg.address6.split("/")
            require(parts.size == 2)
            builder.addAddress(InetAddress.getByName(parts[0]), parts[1].toInt())
            if (full) builder.addRoute("::", 0)
        } else if (full) {
            // Cisco's Drop All Traffic policy: consume IPv6 in the tunnel even
            // without a server IPv6 address, instead of leaking it outside.
            builder.addAddress("fd00:ca::1", 128).addRoute("::", 0)
        }
        cfg.dns.forEach { builder.addDnsServer(it) }
        cfg.domain.split(Regex("\\s+")).filter(String::isNotBlank).forEach { builder.addSearchDomain(it) }
        if (cfg.mtu > 0) builder.setMtu(cfg.mtu)
        // Set the physical network before establish, then keep it up to date.
        if (underlying == null) {
            underlying = physicalNetwork(getSystemService(ConnectivityManager::class.java))
        }
        builder.setUnderlyingNetworks(underlying?.let { arrayOf(it) } ?: emptyArray())
        // Never allowBypass. Establish the replacement before closing the old fd.
        val replacement = builder.establish() ?: error(getString(R.string.need_consent))
        val old = tun
        tun = replacement
        installed = cfg
        tetherAddress = cfg.address
        old?.close()
        log(getString(R.string.applied_settings))
    }
    private fun requestStop(logOut: Boolean) {
        synchronized(sessionLock) {
            stopping = true
            logout = logout || logOut
            if (session != 0L) native.command(session, if (logout) NativeBridge.CANCEL else NativeBridge.DETACH)
            store.running = false
        }
        tetherProxy?.state(false, false)
        // When the queue is idle (between connections), no native run will
        // deliver connectionEnded. Cancel the scheduled rejoin and finish here.
        queue.execute { if (session == 0L) finish(null) }
    }
    private fun finish(error: String?) {
        if (finished) return
        finished = true
        stopping = true
        pendingRejoin?.cancel(false); debounce?.cancel(false); watchdog?.cancel(false)
        watch?.close(); watch = null
        synchronized(sessionLock) {
            if (session != 0L) {
                native.command(session, if (logout) NativeBridge.CANCEL else NativeBridge.DETACH)
                freeSession()
            }
            if (logout) store.clearSession()
            store.running = false
        }
        runCatching { tetherProxy?.close() }; tetherProxy = null
        tetherAddress = null
        tun?.close(); tun = null
        if (error != null) log(getString(R.string.failed, error)) else log(if (logout) getString(R.string.disconnected) else getString(R.string.stopped_session))
        app.log.flush()
        foreground = false
        state("Not connected", error = error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Leave nothing from this run for a restart in the same instance.
        installed = null; networkBaseline = NetworkBaseline(); online = false; underlying = null
        down = false; downOnline = 0; givingUp = false; replacingConfig = false
        backoff.reset()
        main.post {
            val pending = pendingConnect
            if (pending != null && !destroyed && commands.isCurrent(pending)) restart(pending)
            else stopSelfResult(latestStartId)
        }
        if (destroyed) { deadlines.shutdown(); queue.shutdown() }
    }

    /** Runs a Connect that waited for the previous run to finish (main thread). */
    private fun restart(token: Long) {
        if (destroyed || !commands.isCurrent(token)) return
        pendingConnect = null
        launched = false
        manualRequested = false
        onStartCommand(Intent(this, ConnectVpnService::class.java).setAction(CONNECT), 0, latestStartId)
    }
    override fun onRevoke() { requestStop(false); super.onRevoke() }
    override fun onDestroy() {
        destroyed = true
        if (!queue.isShutdown) requestStop(false)
        // Don't free native state here: the blocking call must return first.
        if (!queue.isShutdown) queue.execute { if (session == 0L) { deadlines.shutdown(); queue.shutdown() } }
        super.onDestroy()
    }
    companion object {
        const val CONNECT = "com.muunkk.connectanytime.CONNECT"
        const val DISCONNECT = "com.muunkk.connectanytime.DISCONNECT"
        const val AUTOMATIC = "com.muunkk.connectanytime.AUTOMATIC"
        const val ARM = "com.muunkk.connectanytime.ARM"
    }
}
