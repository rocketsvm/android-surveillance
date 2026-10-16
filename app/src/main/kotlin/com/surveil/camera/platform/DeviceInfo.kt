package com.surveil.camera.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import com.surveil.core.DeviceStatus
import com.surveil.core.NetAddress
import com.surveil.core.StorageRoot
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

object DeviceInfo {

    /** Addresses the phone can be reached at, most useful first (Wi-Fi, Ethernet, hotspot, VPN...). */
    fun networkAddresses(context: Context): List<NetAddress> {
        val kinds = HashMap<String, String>()
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val lp = cm.getLinkProperties(n) ?: continue
                val nc = cm.getNetworkCapabilities(n) ?: continue
                val kind = when {
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    else -> "other"
                }
                lp.interfaceName?.let { kinds[it] = kind }
            }
        }
        val out = ArrayList<NetAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name
                val kind = kinds[name] ?: guessKind(name)
                for (a in ni.inetAddresses.toList()) {
                    if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                    val host = a.hostAddress?.substringBefore('%') ?: continue
                    when (a) {
                        is Inet4Address -> out += NetAddress(name, kind, host, false)
                        is Inet6Address -> if (kind != "mobile") out += NetAddress(name, kind, host, true)
                    }
                }
            }
        }
        val order = listOf("wifi", "ethernet", "hotspot", "usb", "vpn", "other", "mobile")
        return out.sortedWith(compareBy({ it.ipv6 }, { order.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }))
    }

    private fun guessKind(iface: String): String = when {
        iface.startsWith("swlan") || iface.startsWith("ap") || iface == "wlan1" -> "hotspot"
        iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("ncm") -> "usb"
        iface.startsWith("tun") || iface.startsWith("wg") || iface.startsWith("ppp") -> "vpn"
        iface.startsWith("rmnet") || iface.startsWith("ccmni") || iface.startsWith("v4-rmnet") -> "mobile"
        iface.startsWith("eth") -> "ethernet"
        iface.startsWith("wlan") -> "wifi"
        else -> "other"
    }

    fun deviceStatus(context: Context): DeviceStatus {
        val i: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return DeviceStatus(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = if (i != null) plugged != 0 else null,
            batteryTempC = if (temp != Int.MIN_VALUE) temp / 10f else null,
            model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            os = "Android ${Build.VERSION.RELEASE}",
        )
    }

    /** Recording locations: app-specific folders on internal storage and on SD cards. */
    fun storageRoots(context: Context): List<StorageRoot> {
        val dirs = context.getExternalFilesDirs(null)
        val roots = ArrayList<StorageRoot>()
        val primary = dirs.getOrNull(0) ?: context.filesDir
        roots += StorageRoot("internal", "Память телефона", File(primary, "recordings").apply { mkdirs() })
        for (i in 1 until dirs.size) {
            val d = dirs[i] ?: continue
            if (Environment.getExternalStorageState(d) != Environment.MEDIA_MOUNTED) continue
            roots += StorageRoot("sd$i", if (dirs.size > 2) "SD-карта $i" else "SD-карта", File(d, "recordings").apply { mkdirs() })
        }
        return roots
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    val isXiaomi: Boolean get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
    val isHuawei: Boolean get() = Build.MANUFACTURER.lowercase() in setOf("huawei", "honor")
}
