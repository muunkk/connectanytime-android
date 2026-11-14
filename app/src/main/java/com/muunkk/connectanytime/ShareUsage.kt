package com.muunkk.connectanytime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Who is using one link's proxy right now, and how much has gone through it since it started sharing. */
data class LinkUsage(val devices: List<String> = emptyList(), val connections: Int = 0, val down: Long = 0, val up: Long = 0)

data class TetherUsage(val usb: LinkUsage = LinkUsage(), val hotspot: LinkUsage = LinkUsage()) {
    internal operator fun get(link: TetherLink) = if (link == TetherLink.Usb) usb else hotspot
}

/**
 * Counts the proxy's use per link, for the status card. Thread-safe: sessions report from
 * their own threads, and the proxy's timer takes snapshots.
 *
 * A device is a client address. It counts as using the link while it has a connection open,
 * or had traffic within [recent]: browsers close idle connections, and the card shouldn't
 * flicker between pages. Counts start over whenever a link stops sharing.
 */
internal class UsageMeter(private val recent: Long = TimeUnit.SECONDS.toNanos(60)) {
    private class Device { val open = AtomicInteger(); @Volatile var last = 0L }
    private class Counts { val devices = ConcurrentHashMap<String, Device>(); val down = AtomicLong(); val up = AtomicLong() }
    private val links = mapOf(TetherLink.Usb to Counts(), TetherLink.Hotspot to Counts())

    fun opened(link: TetherLink, address: String, now: Long) {
        links.getValue(link).devices.computeIfAbsent(address) { Device() }.apply { open.incrementAndGet(); last = now }
    }
    fun closed(link: TetherLink, address: String, now: Long) {
        links.getValue(link).devices[address]?.apply { open.decrementAndGet(); last = now }
    }
    fun traffic(link: TetherLink, address: String, up: Boolean, bytes: Int, now: Long) {
        val counts = links.getValue(link)
        (if (up) counts.up else counts.down).addAndGet(bytes.toLong())
        counts.devices[address]?.last = now
    }
    /** [sharing]: the links with a listener now. Any other link is reported empty and starts over. */
    fun snapshot(now: Long, sharing: Set<TetherLink>): TetherUsage {
        fun of(link: TetherLink): LinkUsage {
            val counts = links.getValue(link)
            if (link !in sharing) {
                counts.devices.clear(); counts.down.set(0); counts.up.set(0)
                return LinkUsage()
            }
            counts.devices.entries.removeIf { it.value.open.get() <= 0 && now - it.value.last > recent }
            val devices = counts.devices.keys.sortedBy(::addressOrder)
            return LinkUsage(devices, counts.devices.values.sumOf { maxOf(0, it.open.get()) }, counts.down.get(), counts.up.get())
        }
        return TetherUsage(of(TetherLink.Usb), of(TetherLink.Hotspot))
    }
    /** IPv4 in numeric order, so .9 comes before .10. */
    private fun addressOrder(address: String): Long =
        address.split('.').fold(0L) { total, part -> total * 256 + (part.toIntOrNull() ?: 0) }
}
