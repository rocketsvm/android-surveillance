package com.surveil.core

import com.surveil.core.settings.Option
import com.surveil.core.settings.Settings
import kotlinx.serialization.json.JsonObject
import java.io.File

/** A place recordings can be stored. */
data class StorageRoot(val id: String, val label: String, val dir: File) {
    val freeBytes: Long get() = dir.usableSpace
    val totalBytes: Long get() = dir.totalSpace
}

/** A local address the phone can be reached at. [kind]: wifi | ethernet | hotspot | vpn | mobile | other. */
data class NetAddress(val iface: String, val kind: String, val address: String, val ipv6: Boolean)

data class DeviceStatus(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val batteryTempC: Float? = null,
    val model: String = "",
    val os: String = "",
)

/** State of the capture pipeline as reported by the platform. */
data class CameraStatus(
    /** stopped | starting | running | error */
    val state: String = "stopped",
    val error: String? = null,
    val cameraId: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val encoder: String? = null,
)

/** Everything the device-independent core needs from the host (Android or the desktop harness). */
interface Platform {
    val appVersion: String

    fun storageRoots(): List<StorageRoot>
    fun networkAddresses(): List<NetAddress>
    fun deviceStatus(): DeviceStatus

    /** Options for settings whose choices depend on the device (cameras, resolutions, storage). */
    fun dynamicOptions(key: String, settings: Settings): List<Option>?

    /** Extra camera capability info for the UI (zoom range, torch availability...). */
    fun cameraInfo(): JsonObject? = null

    /** JPEG of the current output frame, at most [maxWidth] wide; null if no frame is available. */
    fun snapshot(maxWidth: Int, callback: (ByteArray?) -> Unit)

    fun requestKeyframe()
    fun restartCamera()
    fun log(message: String)
}
