package me.phh.treble.app

import android.net.ConnectivityManager
import android.net.Network
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Splits new app connections between the default network and secondary internet
 * networks (a second Wi-Fi STA on wlan1, and/or cellular for HyperFusion).
 *
 * The kernel has no multipath routing and the ASUS sla.ko only counts traffic, so this
 * steers per connection with iptables (IPv4 only):
 *  - mangle OUTPUT (before netd's connmark_mangle_OUTPUT): a NEW connection from an app
 *    (uid 10000-99999) on the default network is, with probability = the target's share,
 *    re-marked with the target's netd fwmark (netId | explicitly-selected bit). netd then
 *    saves that mark into the conntrack mark, and later packets of the connection get it
 *    back from the conntrack mark. A mark change in OUTPUT makes the kernel re-route.
 *  - nat POSTROUTING: the source address was picked for the default interface, so
 *    connections moved to a target interface are masqueraded to its address.
 * IPv6 connections stay on the default network.
 */
object DualWifiSplitter {
    private const val TAG = "DualWifi_Splitter"
    private const val SPLIT_PROP = "sys.phh.dualwifi.split"

    data class Target(val key: String, val netId: Int, val iface: String, val share: Double)

    // All rule changes run in order on one thread.
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var defaultNetId = 0
    private val sources = LinkedHashMap<String, Network>()
    @Volatile var activeTargets: List<Target> = emptyList()
        private set

    val isActive: Boolean get() = activeTargets.isNotEmpty()

    private fun netIdOf(network: Network?): Int = network?.toString()?.toIntOrNull() ?: 0

    @Synchronized
    fun setDefaultNetwork(cm: ConnectivityManager, network: Network?) {
        defaultNetId = netIdOf(network)
        DualWifiLogger.i(TAG, "Default network is now netId=$defaultNetId")
        reapply(cm)
    }

    /** Add or replace a secondary network ("wifi" or "cell"). */
    @Synchronized
    fun setSource(cm: ConnectivityManager, key: String, network: Network) {
        if (defaultNetId == 0) defaultNetId = netIdOf(cm.activeNetwork)
        sources[key] = network
        reapply(cm)
    }

    @Synchronized
    fun removeSource(cm: ConnectivityManager, key: String) {
        if (sources.remove(key) != null) reapply(cm)
    }

    @Synchronized
    fun reapply(cm: ConnectivityManager) {
        val def = defaultNetId
        val defaultBw = cm.allNetworks.firstOrNull { netIdOf(it) == def }
            ?.let { cm.getNetworkCapabilities(it)?.linkDownstreamBandwidthKbps } ?: 0

        val candidates = sources.mapNotNull { (key, network) ->
            val netId = netIdOf(network)
            val iface = cm.getLinkProperties(network)?.interfaceName
            if (netId == 0 || netId == def || iface.isNullOrEmpty()) {
                null
            } else {
                val bw = cm.getNetworkCapabilities(network)?.linkDownstreamBandwidthKbps ?: 0
                Triple(key, netId, iface) to bw
            }
        }

        // Share of new connections per target, from the link bandwidth estimates.
        val total = defaultBw + candidates.sumOf { it.second }
        val targets = candidates.map { (t, bw) ->
            val share = if (total > 0 && bw > 0 && defaultBw > 0) {
                bw.toDouble() / total
            } else {
                1.0 / (candidates.size + 1)
            }
            Target(t.first, t.second, t.third, share.coerceIn(0.05, 0.6))
        }

        val next = if (def == 0) emptyList() else targets
        // Bandwidth estimates move all the time; only rewrite rules on a real change.
        if (def == appliedDefaultNetId && sameTargets(next, activeTargets)) return
        activeTargets = next
        appliedDefaultNetId = def
        executor.execute { writeRules(def, next) }
    }

    private var appliedDefaultNetId = -1

    private fun sameTargets(a: List<Target>, b: List<Target>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.key == y.key && x.netId == y.netId && x.iface == y.iface &&
                    Math.abs(x.share - y.share) < 0.05
        }

    @Synchronized
    fun clear() {
        sources.clear()
        activeTargets = emptyList()
        appliedDefaultNetId = 0
        executor.execute { writeRules(0, emptyList()) }
    }

    // TrebleApp runs as the system uid and can't use su (KernelSU hides it), so the rules
    // are applied as root by /system/bin/dualwifi.sh, started by init on this property.
    private fun writeRules(defaultNetId: Int, targets: List<Target>) {
        val value = if (defaultNetId == 0 || targets.isEmpty()) {
            "off"
        } else {
            // Each rule only sees what earlier rules left on the default network,
            // so scale its probability accordingly.
            var remaining = 1.0
            defaultNetId.toString() + targets.joinToString("") { t ->
                val p = (t.share / remaining).coerceIn(0.0, 1.0)
                remaining -= t.share
                ";${t.netId},${t.iface},${String.format(Locale.US, "%.4f", p)}"
            }
        }
        Misc.safeSetprop(SPLIT_PROP, value)
        if (targets.isEmpty()) {
            DualWifiLogger.i(TAG, "Traffic split off")
        } else {
            DualWifiLogger.i(TAG, "Traffic split on: default netId=$defaultNetId, " +
                    targets.joinToString { "${it.key}=${it.iface}/netId ${it.netId} " +
                            String.format(Locale.US, "%.0f%%", it.share * 100) } + " ($SPLIT_PROP=$value)")
        }
    }
}
