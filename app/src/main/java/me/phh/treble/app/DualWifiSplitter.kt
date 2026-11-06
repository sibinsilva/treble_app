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
    private const val MANGLE_CHAIN = "dw_out"
    private const val NAT_CHAIN = "dw_post"
    private const val EXPLICIT_BIT = 0x10000
    private const val APP_UIDS = "10000-99999"

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

    private fun hex(v: Int) = "0x" + Integer.toHexString(v)

    private fun writeRules(defaultNetId: Int, targets: List<Target>) {
        val cmds = mutableListOf<String>()
        // Tear down any previous state (also cleans up after a crash).
        cmds += "while iptables -w -t mangle -D OUTPUT -j $MANGLE_CHAIN 2>/dev/null; do :; done"
        cmds += "iptables -w -t mangle -F $MANGLE_CHAIN 2>/dev/null"
        cmds += "iptables -w -t mangle -X $MANGLE_CHAIN 2>/dev/null"
        cmds += "while iptables -w -t nat -D POSTROUTING -j $NAT_CHAIN 2>/dev/null; do :; done"
        cmds += "iptables -w -t nat -F $NAT_CHAIN 2>/dev/null"
        cmds += "iptables -w -t nat -X $NAT_CHAIN 2>/dev/null"

        if (defaultNetId != 0 && targets.isNotEmpty()) {
            cmds += "iptables -w -t mangle -N $MANGLE_CHAIN"
            cmds += "iptables -w -t nat -N $NAT_CHAIN"
            // Later packets of a moved connection: restore the target mark from conntrack.
            for (t in targets) {
                cmds += "iptables -w -t mangle -A $MANGLE_CHAIN -m conntrack ! --ctstate NEW" +
                        " -m connmark --mark ${hex(t.netId)}/0xffff" +
                        " -j MARK --set-xmark ${hex(t.netId or EXPLICIT_BIT)}/0x1ffff"
            }
            // New connections: pick a target. Each rule only sees what earlier rules left
            // on the default network, so scale its probability accordingly.
            var remaining = 1.0
            for (t in targets) {
                val p = (t.share / remaining).coerceIn(0.0, 1.0)
                remaining -= t.share
                cmds += "iptables -w -t mangle -A $MANGLE_CHAIN -m conntrack --ctstate NEW" +
                        " -m owner --uid-owner $APP_UIDS -m mark --mark ${hex(defaultNetId)}/0xffff" +
                        " -m statistic --mode random --probability ${String.format(Locale.US, "%.4f", p)}" +
                        " -j MARK --set-xmark ${hex(t.netId or EXPLICIT_BIT)}/0x1ffff"
                cmds += "iptables -w -t nat -A $NAT_CHAIN -o ${t.iface}" +
                        " -m connmark --mark ${hex(t.netId)}/0xffff -j MASQUERADE"
            }
            cmds += "iptables -w -t mangle -I OUTPUT 1 -j $MANGLE_CHAIN"
            cmds += "iptables -w -t nat -I POSTROUTING 1 -j $NAT_CHAIN"
        }

        val res = RootShell.run(cmds.joinToString("; "))
        if (targets.isEmpty()) {
            DualWifiLogger.i(TAG, "Traffic split off (exit=${res.exitCode})")
        } else {
            DualWifiLogger.i(TAG, "Traffic split on: default netId=$defaultNetId, " +
                    targets.joinToString { "${it.key}=${it.iface}/netId ${it.netId} " +
                            String.format(Locale.US, "%.0f%%", it.share * 100) } +
                    " (exit=${res.exitCode})")
            if (!res.isSuccess) DualWifiLogger.w(TAG, "iptables output: ${res.output}")
        }
    }
}
