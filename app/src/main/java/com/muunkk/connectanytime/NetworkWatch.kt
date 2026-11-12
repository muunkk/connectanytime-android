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
            if (Build.VERSION.SDK_INT >= 31) synchronized(links) { links.clear() }
            update(network, cm.getLinkProperties(network))
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(network, properties)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        override fun onLost(network: Network) { synchronized(links) { links.remove(network) }; publish() }
    }
    private fun update(n: Network, p: LinkProperties?) { synchronized(links) { if (p != null) links[n] = p }; publish() }
    private fun publish() = synchronized(links) {
        // API 31+ supplies only the best NOT_VPN match. On 29/30 prefer the
        // system default when physical, otherwise validated Wi-Fi over cellular.
        val active = cm.activeNetwork
        val n = links.keys.firstOrNull { it == active } ?: links.keys.maxByOrNull {
            val caps = cm.getNetworkCapabilities(it)
            (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 10 else 0) +
                (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 2 else 1)
        }
        val p = links[n]
        changed(n, if (n == null) "offline" else "$n,${p?.interfaceName},${p?.routes?.mapNotNull { it.gateway?.hostAddress }?.sorted()}")
    }
    fun start() {
        if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(request, callback, android.os.Handler(android.os.Looper.getMainLooper()))
        else cm.registerNetworkCallback(request, callback)
        publish()
    }
    fun close() { runCatching { cm.unregisterNetworkCallback(callback) } }
}
