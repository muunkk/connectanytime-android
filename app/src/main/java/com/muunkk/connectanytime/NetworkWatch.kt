package com.muunkk.connectanytime

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/** Watch the best physical network, not our VPN. Identity includes gateways so
 * Wi-Fi to Wi-Fi is visible even when both use wlan0. */
class NetworkWatch(private val cm: ConnectivityManager, private val changed: (Network?, String) -> Unit) {
    private val links = LinkedHashMap<Network, LinkProperties>()
    private val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(links) { links.clear() }
            // Wait for link properties: onAvailable alone has no stable identity.
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(network, properties)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(links) { if (links.containsKey(network)) publish() }
        }
        override fun onLost(network: Network) { synchronized(links) { if (links.remove(network) != null) publish() } }
    }
    private fun update(n: Network, p: LinkProperties?) { synchronized(links) { if (p != null) links[n] = p }; publish() }
    private fun publish() = synchronized(links) {
        // Both registrations report the system's best NOT_VPN match. Never use
        // activeNetwork here: on API 29/30 it can be this VPN itself.
        val n = links.keys.firstOrNull()
        val p = links[n]
        changed(n, if (n == null) "offline" else "$n,${p?.interfaceName},${p?.routes?.mapNotNull { it.gateway?.hostAddress }?.sorted()}")
    }
    fun start() {
        if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(request, callback, android.os.Handler(android.os.Looper.getMainLooper()))
        // Before API 31, requestNetwork supplies best-match semantics; a passive
        // registerNetworkCallback lists all networks without their system ranking.
        else cm.requestNetwork(request, callback)
    }
    fun close() { runCatching { cm.unregisterNetworkCallback(callback) } }
}
