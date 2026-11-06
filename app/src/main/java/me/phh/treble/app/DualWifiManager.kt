package me.phh.treble.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class DualWifiManager(private val context: Context) {

    companion object {
        private const val TAG = "DualWifi_Manager"
        private const val SOCKET_PATH = "/data/vendor/wifi/wpa/sockets"

        fun getSystemProperty(key: String, default: String = ""): String {
            return try {
                val spClass = Class.forName("android.os.SystemProperties")
                val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
                getMethod.invoke(null, key, default) as String
            } catch (_: Exception) {
                default
            }
        }
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    data class InterfaceInfo(
        val name: String,
        val isUp: Boolean,
        val ip: String?,
        val ssid: String? = null,
        val freq: Int = 0,
        val linkSpeed: Int = 0
    ) {
        val is5GHz: Boolean get() = freq > 4000
    }

    data class SlaInfo(
        val isEnabled: Boolean,
        val daemonRunning: Boolean,
        val bytesWlan0: Long = 0,
        val bytesWlan1: Long = 0
    )

    data class ScannedNetwork(
        val ssid: String,
        val bssid: String,
        val freq: Int,
        val level: Int,
        val flags: String
    ) {
        val is5GHz: Boolean get() = freq > 4000
        val bandLabel: String get() = if (is5GHz) "5 GHz" else "2.4 GHz"
        val isOpen: Boolean get() = !flags.contains("WPA") && !flags.contains("WEP") && !flags.contains("PSK") && !flags.contains("EAP") && !flags.contains("SAE")
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    // ---------------------------------------------------------------------------
    // Saved Networks Management
    // ---------------------------------------------------------------------------
    fun getSavedPassword(ssid: String): String? {
        val netPrefs = context.getSharedPreferences("dual_wifi_networks", Context.MODE_PRIVATE)
        val saved = netPrefs.getString("pwd_$ssid", null)
        if (!saved.isNullOrEmpty()) return saved

        val legacyPrefs = context.getSharedPreferences("dual_wifi_prefs", Context.MODE_PRIVATE)
        if (legacyPrefs.getString("saved_ssid", null) == ssid) {
            val legacyPass = legacyPrefs.getString("saved_pass", null)
            if (!legacyPass.isNullOrEmpty()) return legacyPass
        }
        return null
    }

    fun saveNetworkCredentials(ssid: String, pass: String) {
        val netPrefs = context.getSharedPreferences("dual_wifi_networks", Context.MODE_PRIVATE)
        netPrefs.edit().putString("pwd_$ssid", pass).apply()
        val legacyPrefs = context.getSharedPreferences("dual_wifi_prefs", Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("saved_ssid", ssid).putString("saved_pass", pass).apply()
        DualWifiLogger.i(TAG, "Saved credentials for network: '$ssid'")
    }

    fun forgetNetworkCredentials(ssid: String) {
        val netPrefs = context.getSharedPreferences("dual_wifi_networks", Context.MODE_PRIVATE)
        netPrefs.edit().remove("pwd_$ssid").apply()
        val legacyPrefs = context.getSharedPreferences("dual_wifi_prefs", Context.MODE_PRIVATE)
        if (legacyPrefs.getString("saved_ssid", null) == ssid) {
            legacyPrefs.edit().remove("saved_ssid").remove("saved_pass").apply()
        }
        DualWifiLogger.i(TAG, "Removed credentials for network: '$ssid'")
    }

    fun isNetworkSaved(ssid: String): Boolean {
        return getSavedPassword(ssid) != null
    }

    // ---------------------------------------------------------------------------
    // Scanning — uses Android WifiManager directly
    // ---------------------------------------------------------------------------
    suspend fun scanNetworks(): List<ScannedNetwork> = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Starting network scan via WifiManager API...")
        val networks = mutableListOf<ScannedNetwork>()

        @Suppress("DEPRECATION")
        val triggered = wifiManager.startScan()
        DualWifiLogger.d(TAG, "WifiManager.startScan() returned: $triggered")

        delay(800)

        val results: List<ScanResult> = wifiManager.scanResults ?: emptyList()
        DualWifiLogger.i(TAG, "WifiManager.getScanResults() returned ${results.size} raw entries")

        for (sr in results) {
            val ssid = sr.SSID?.trim('"') ?: continue
            if (ssid.isEmpty()) continue
            val flags = sr.capabilities ?: ""
            networks.add(ScannedNetwork(ssid, sr.BSSID ?: "", sr.frequency, sr.level, flags))
            DualWifiLogger.v(TAG, "  Found: '$ssid' ${sr.frequency}MHz ${sr.level}dBm")
        }

        val wpaRes = RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan0 scan_results")
        if (wpaRes.isSuccess && wpaRes.output.contains("\t")) {
            var wpaExtra = 0
            for (line in wpaRes.output.lines()) {
                val parts = line.split("\t")
                if (parts.size >= 5) {
                    val bssid = parts[0].trim()
                    val freq = parts[1].trim().toIntOrNull() ?: 0
                    val level = parts[2].trim().toIntOrNull() ?: 0
                    val flags = parts[3].trim()
                    val ssid = parts[4].trim()
                    if (ssid.isNotEmpty() && networks.none { it.bssid == bssid }) {
                        networks.add(ScannedNetwork(ssid, bssid, freq, level, flags))
                        wpaExtra++
                    }
                }
            }
            if (wpaExtra > 0) DualWifiLogger.d(TAG, "wpa_cli added $wpaExtra extra networks not in framework cache")
        }

        val deduped = networks
            .distinctBy { "${it.ssid}_${it.is5GHz}" }
            .sortedWith(compareByDescending<ScannedNetwork> { it.is5GHz }.thenByDescending { it.level })

        val n5 = deduped.count { it.is5GHz }
        val n24 = deduped.count { !it.is5GHz }
        DualWifiLogger.i(TAG, "Scan done: ${deduped.size} unique networks ($n5 on 5 GHz, $n24 on 2.4 GHz)")
        deduped
    }

    // ---------------------------------------------------------------------------
    // Enable Multi-STA / Dual Wi-Fi mode
    // ---------------------------------------------------------------------------
    suspend fun enableMultiInternetMode(mode: Int = 2): Boolean = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Enabling Multi-Internet mode=$mode (ASUS Stock & AOSP Multi-STA)")

        var ok = false

        try {
            val method = wifiManager.javaClass.getMethod(
                "setStaConcurrencyForMultiInternetMode", Int::class.javaPrimitiveType
            )
            val res = method.invoke(wifiManager, mode)
            DualWifiLogger.i(TAG, "setStaConcurrencyForMultiInternetMode($mode) = $res")
            ok = true
        } catch (t: Throwable) {
            DualWifiLogger.w(TAG, "Reflection setStaConcurrencyForMultiInternetMode failed: ${t.message}")
        }

        try {
            android.provider.Settings.Global.putInt(
                context.contentResolver, "wifi_multi_internet_mode", mode
            )
            DualWifiLogger.d(TAG, "Settings.Global wifi_multi_internet_mode=$mode written")
        } catch (t: Throwable) {
            DualWifiLogger.w(TAG, "Settings.Global write failed: ${t.message}")
        }

        // Applied as root by /system/bin/dualwifi.sh (TrebleApp can't use su under KernelSU).
        Misc.safeSetprop("sys.phh.dualwifi.multista", mode.toString())
        Misc.safeSetprop("persist.sys.rog.dual_wifi_mode", mode.toString())

        ok
    }

    // ---------------------------------------------------------------------------
    // Spawn secondary wlan1 interface (ASUS Stock / Qualcomm WCN6850)
    // ---------------------------------------------------------------------------
    suspend fun spawnWlan1(logger: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Ensuring secondary DBS STA interface is ready...")
        logger("Configuring ASUS DBS antenna switch & Wi-Fi HAL...")

        RootShell.run("echo 1 > /sys/devices/platform/soc/b0000000.qcom,cnss-qca6490/do_wifi_antenna_switch 2>/dev/null")
        RootShell.run("setprop vendor.asus.netutild.enabled 1")

        val check = RootShell.run("ip link show wlan1 2>/dev/null")
        var exists = check.isSuccess && !check.output.contains("does not exist") && check.output.isNotEmpty()

        if (!exists) {
            val awareCheck = RootShell.run("ip link show wifi-aware0 2>/dev/null")
            if (awareCheck.isSuccess && awareCheck.output.contains("wifi-aware0")) {
                DualWifiLogger.i(TAG, "Reallocating secondary RF chain from wifi-aware0 to wlan1")
                RootShell.run("ip link set dev wifi-aware0 name wlan1 2>/dev/null")
                exists = true
            }
        }

        if (exists) {
            DualWifiLogger.i(TAG, "wlan1 interface is present, bringing UP")
            RootShell.run("ip link set dev wlan1 up 2>/dev/null")
            logger("Secondary STA interface (wlan1) is active!")
            return@withContext true
        }

        DualWifiLogger.d(TAG, "Requesting additional STA interface from Wi-Fi framework...")
        RootShell.run("service call wifinl80211 2 s16 'wlan1' 2>/dev/null")
        RootShell.run("cmd wifi start-scan 2>/dev/null")

        val checkAfter = RootShell.run("ip link show wlan1 2>/dev/null")
        val success = checkAfter.isSuccess && !checkAfter.output.contains("does not exist") && checkAfter.output.isNotEmpty()
        if (success) {
            DualWifiLogger.i(TAG, "wlan1 successfully spawned, bringing UP")
            RootShell.run("ip link set dev wlan1 up 2>/dev/null")
            logger("wlan1 created and activated successfully!")
        } else {
            DualWifiLogger.d(TAG, "wlan1 will be dynamically bound upon network association")
            logger("Multi-STA ready: Dynamic binding active.")
        }
        success
    }

    // ---------------------------------------------------------------------------
    // Interface status — uses WifiManager for wlan0, root for wlan1
    // ---------------------------------------------------------------------------
    suspend fun getInterfaceStatus(iface: String): InterfaceInfo = withContext(Dispatchers.IO) {
        if (iface == "wlan0") {
            val connInfo = @Suppress("DEPRECATION") wifiManager.connectionInfo
            val ssid = connInfo?.ssid?.trim('"').takeIf { !it.isNullOrEmpty() && it != "<unknown ssid>" }
            val freq = connInfo?.frequency ?: 0
            val ip = intToIp(connInfo?.ipAddress ?: 0)
            val isUp = connInfo != null && connInfo.ipAddress != 0
            DualWifiLogger.v(TAG, "Status [wlan0]: up=$isUp, ip=$ip, ssid=$ssid, freq=${freq}MHz")
            InterfaceInfo(iface, isUp, ip?.takeIf { isUp }, ssid, freq)
        } else {
            // No root: find the network on this interface through ConnectivityManager.
            var info = InterfaceInfo(iface, false, null)
            for (n in connectivityManager.allNetworks) {
                val lp = connectivityManager.getLinkProperties(n) ?: continue
                if (lp.interfaceName != iface) continue
                val ip = lp.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }
                    ?.address?.hostAddress
                val wi = connectivityManager.getNetworkCapabilities(n)?.transportInfo
                        as? android.net.wifi.WifiInfo
                val ssid = wi?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                info = InterfaceInfo(iface, true, ip, ssid, wi?.frequency ?: 0)
                break
            }
            DualWifiLogger.v(TAG, "Status [$iface]: up=${info.isUp}, ip=${info.ip ?: "none"}, ssid=${info.ssid ?: "none"}, freq=${info.freq}MHz")
            info
        }
    }

    private fun intToIp(i: Int): String? {
        if (i == 0) return null
        return "${i and 0xff}.${(i shr 8) and 0xff}.${(i shr 16) and 0xff}.${(i shr 24) and 0xff}"
    }

    // ---------------------------------------------------------------------------
    // SLA status — reads kernel node and interface traffic statistics
    // ---------------------------------------------------------------------------
    suspend fun getSlaStatus(): SlaInfo = withContext(Dispatchers.IO) {
        // "SLA" here is our own per-connection traffic split (DualWifiSplitter); the vendor
        // sla.ko only counts traffic and the stock steering daemons don't exist on the GSI.
        val active = DualWifiSplitter.isActive
        val w0 = getInterfaceTraffic("wlan0")
        val w1 = getInterfaceTraffic("wlan1")
        DualWifiLogger.v(TAG, "Split status: active=$active, w0=$w0, w1=$w1")
        SlaInfo(active, active, w0, w1)
    }

    private fun getInterfaceTraffic(iface: String): Long {
        try {
            val file = java.io.File("/proc/net/dev")
            if (file.exists()) {
                for (line in file.readLines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("$iface:")) {
                        val stats = trimmed.removePrefix("$iface:").trim().split(Regex("\\s+"))
                        if (stats.size >= 9) {
                            val rx = stats[0].toLongOrNull() ?: 0L
                            val tx = stats[8].toLongOrNull() ?: 0L
                            return rx + tx
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        fun stat(name: String) = try {
            java.io.File("/sys/class/net/$iface/statistics/$name").readText().trim().toLong()
        } catch (_: Exception) { 0L }
        val rx = stat("rx_bytes")
        val tx = stat("tx_bytes")
        return rx + tx
    }

    // ---------------------------------------------------------------------------
    // Connect secondary — uses Multi-Internet mode + Network Suggestions + SLA
    // ---------------------------------------------------------------------------
    suspend fun connectSecondary(ssid: String, psk: String, logger: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Connecting secondary to '$ssid' via Multi-Internet & STA+STA Concurrency...")
        logger("Initializing ASUS DBS Antenna & Multi-Internet overlays...")

        enableMultiInternetMode(2)

        // The framework's multi-internet path connects wlan1 to a saved network on the other
        // band, so save it (system app API; no root needed).
        try {
            @Suppress("DEPRECATION")
            val conf = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (psk.isNotEmpty()) {
                    preSharedKey = "\"$psk\""
                } else {
                    setSecurityParams(android.net.wifi.WifiConfiguration.SECURITY_TYPE_OPEN)
                }
            }
            @Suppress("DEPRECATION")
            val id = wifiManager.addNetwork(conf)
            DualWifiLogger.i(TAG, "Saved '$ssid' as Wi-Fi network id=$id")
        } catch (t: Throwable) {
            DualWifiLogger.w(TAG, "addNetwork failed: ${t.message}")
        }

        try {
            val suggestionBuilder = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsInitialAutojoinEnabled(true)
            if (psk.isNotEmpty()) {
                suggestionBuilder.setWpa2Passphrase(psk)
            }
            val suggestionList = listOf(suggestionBuilder.build())
            val status = wifiManager.addNetworkSuggestions(suggestionList)
            DualWifiLogger.i(TAG, "WifiManager.addNetworkSuggestions status=$status")
            logger("Network suggestion registered (status=$status)")
        } catch (t: Throwable) {
            DualWifiLogger.w(TAG, "addNetworkSuggestions warning: ${t.message}")
        }

        try {
            activeNetworkCallback?.let {
                try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            }

            val primaryStatus = getInterfaceStatus("wlan0")
            val targetBand = if (primaryStatus.is5GHz) ScanResult.WIFI_BAND_24_GHZ else ScanResult.WIFI_BAND_5_GHZ
            DualWifiLogger.i(TAG, "Multi-Internet target band: $targetBand (Primary freq=${primaryStatus.freq} MHz, is5GHz=${primaryStatus.is5GHz})")

            val specifier: NetworkSpecifier? = try {
                val specifierBuilder = WifiNetworkSpecifier.Builder()
                try {
                    val method = specifierBuilder.javaClass.getMethod("setBand", Int::class.javaPrimitiveType)
                    method.invoke(specifierBuilder, targetBand)
                } catch (t: Throwable) {
                    DualWifiLogger.w(TAG, "setBand reflection: ${t.message}")
                }
                specifierBuilder.build()
            } catch (t: Throwable) {
                DualWifiLogger.w(TAG, "WifiNetworkSpecifier without SSID failed: ${t.message}, adding SSID $ssid")
                try {
                    val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
                    try {
                        val method = specifierBuilder.javaClass.getMethod("setBand", Int::class.javaPrimitiveType)
                        method.invoke(specifierBuilder, targetBand)
                    } catch (_: Throwable) {}
                    specifierBuilder.build()
                } catch (t2: Throwable) {
                    DualWifiLogger.w(TAG, "WifiNetworkSpecifier fallback failed: ${t2.message}")
                    null
                }
            }

            val requestBuilder = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)

            if (specifier != null) {
                requestBuilder.setNetworkSpecifier(specifier)
            }
            val request = requestBuilder.build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    DualWifiLogger.i(TAG, "Secondary network callback onAvailable: $network")
                    logger("Secondary network connected: $network")
                }
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                    DualWifiSplitter.setSource(connectivityManager, "wifi", network)
                }
                override fun onLost(network: Network) {
                    DualWifiLogger.w(TAG, "Secondary network callback onLost: $network")
                    logger("Secondary network connection lost: $network")
                    DualWifiSplitter.removeSource(connectivityManager, "wifi")
                }
                override fun onUnavailable() {
                    DualWifiLogger.d(TAG, "Secondary network callback onUnavailable")
                }
            }

            activeNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)
            DualWifiLogger.i(TAG, "ConnectivityManager.requestNetwork registered for $ssid (Multi-Internet band $targetBand)")
            logger("Multi-Internet network request active for $ssid")
        } catch (t: Throwable) {
            DualWifiLogger.w(TAG, "ConnectivityManager requestNetwork warning: ${t.message}")
        }

        @Suppress("DEPRECATION")
        wifiManager.startScan()
        logger("Dual Wi-Fi requested; new app connections are split once wlan1 is up.")

        true
    }

    suspend fun disconnectSecondary(logger: (String) -> Unit) = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "Disconnecting secondary and disabling SLA...")
        logger("Disabling Dual Wi-Fi & SLA...")

        activeNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                DualWifiLogger.d(TAG, "Unregistered active network callback")
            } catch (_: Exception) {}
            activeNetworkCallback = null
        }

        try {
            wifiManager.removeNetworkSuggestions(emptyList())
        } catch (_: Exception) {}

        RootShell.run("cmd wifi remove-all-suggestions 2>/dev/null")
        enableMultiInternetMode(0)
        DualWifiSplitter.removeSource(connectivityManager, "wifi")
        RootShell.run("wpa_cli -p $SOCKET_PATH -i wlan1 disconnect 2>/dev/null")
        logger("Secondary interface disconnected.")
    }

    // ---------------------------------------------------------------------------
    // Self-test
    // ---------------------------------------------------------------------------
    suspend fun runSelfTest(): String = withContext(Dispatchers.IO) {
        DualWifiLogger.i(TAG, "=== RUNNING SYSTEM SELF-TEST ===")
        val sb = StringBuilder()

        val scanResults = wifiManager.scanResults ?: emptyList()
        sb.appendLine("1. WifiManager.getScanResults(): ${scanResults.size} networks")
        scanResults.take(4).forEach { sb.appendLine("   ${it.SSID} ${it.frequency}MHz ${it.level}dBm") }

        @Suppress("DEPRECATION")
        val connInfo = wifiManager.connectionInfo
        sb.appendLine("2. Primary wlan0: ssid=${connInfo?.ssid} freq=${connInfo?.frequency}MHz ip=${intToIp(connInfo?.ipAddress ?: 0)}")

        val multiMode = try {
            android.provider.Settings.Global.getInt(context.contentResolver, "wifi_multi_internet_mode", 0)
        } catch (_: Exception) { -1 }
        sb.appendLine("3. wifi_multi_internet_mode: $multiMode")

        val antRes = RootShell.run("cat /sys/devices/platform/soc/b0000000.qcom,cnss-qca6490/do_wifi_antenna_switch 2>/dev/null")
        sb.appendLine("4. ASUS Antenna Switch: ${if (antRes.isSuccess) antRes.output.trim() else "N/A"}")

        val netutilRes = RootShell.run("ps -ef | grep netutil 2>/dev/null")
        val netutilActive = netutilRes.output.contains("netutild")
        sb.appendLine("5. ASUS netutild: ${if (netutilActive) "Running" else "Stopped"}")

        val targets = DualWifiSplitter.activeTargets
        sb.appendLine("6. Traffic split: ${if (targets.isEmpty()) "Off" else targets.joinToString {
            "${it.iface} ${(it.share * 100).toInt()}%" } + " of new app connections (IPv4)"}")
        sb.appendLine("   sys.phh.dualwifi.split = ${getSystemProperty("sys.phh.dualwifi.split", "(unset)")}")

        val rootRes = RootShell.run("id")
        sb.appendLine("7. Root: exit=${rootRes.exitCode} -> ${rootRes.output.lines().firstOrNull() ?: "no output"}")

        val w1Res = RootShell.run("ip -br link show wlan1 2>/dev/null")
        val w0Traffic = getInterfaceTraffic("wlan0")
        val w1Traffic = getInterfaceTraffic("wlan1")
        sb.appendLine("8. wlan1 Link: ${if (w1Res.isSuccess && w1Res.output.isNotEmpty()) w1Res.output.trim() else "Dynamic (Multi-STA Ready)"}")
        sb.appendLine("   Traffic: wlan0 = ${w0Traffic / 1024} KB | wlan1 = ${w1Traffic / 1024} KB")

        val report = sb.toString().trim()
        DualWifiLogger.i(TAG, "Self-Test:\n$report")
        report
    }
}
