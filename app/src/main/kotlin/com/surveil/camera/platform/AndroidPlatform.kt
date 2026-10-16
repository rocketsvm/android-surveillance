package com.surveil.camera.platform

import android.content.Context
import android.util.Log
import com.surveil.camera.BuildConfig
import com.surveil.camera.capture.CameraCatalog
import com.surveil.camera.capture.CapturePipeline
import com.surveil.core.DeviceStatus
import com.surveil.core.NetAddress
import com.surveil.core.Platform
import com.surveil.core.StorageRoot
import com.surveil.core.settings.Keys
import com.surveil.core.settings.Option
import com.surveil.core.settings.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bridges the device-independent core to Android services. */
class AndroidPlatform(private val context: Context, private val catalog: CameraCatalog) : Platform {
    lateinit var pipeline: CapturePipeline

    override val appVersion: String = BuildConfig.VERSION_NAME

    override fun storageRoots(): List<StorageRoot> = DeviceInfo.storageRoots(context)

    override fun networkAddresses(): List<NetAddress> = DeviceInfo.networkAddresses(context)

    override fun deviceStatus(): DeviceStatus = DeviceInfo.deviceStatus(context)

    override fun dynamicOptions(key: String, settings: Settings): List<Option>? = when (key) {
        Keys.CAMERA_ID -> catalog.cameraOptions()
        Keys.RESOLUTION -> catalog.resolutionOptions(catalog.resolve(settings.string(Keys.CAMERA_ID)))
        Keys.REC_STORAGE -> storageRoots().map { r ->
            Option(r.id, "${r.label} (свободно ${r.freeBytes / 1_000_000_000} ГБ)")
        }
        else -> null
    }

    override fun cameraInfo(): JsonObject? {
        val cam = catalog.resolve(pipeline.settings.string(Keys.CAMERA_ID)) ?: return null
        return buildJsonObject {
            put("id", cam.id)
            put("label", cam.label)
            put("hasFlash", cam.hasFlash)
            put("maxZoom", cam.maxZoom)
            put("evMin", cam.evRange.lower * cam.evStep.toDouble())
            put("evMax", cam.evRange.upper * cam.evStep.toDouble())
        }
    }

    override fun snapshot(maxWidth: Int, callback: (ByteArray?) -> Unit) = pipeline.snapshotJpeg(maxWidth, callback = callback)

    override fun requestKeyframe() = pipeline.requestKeyframe()

    override fun restartCamera() {
        pipeline.restart()
    }

    override fun log(message: String) {
        Log.i("Core", message)
    }
}
