package com.surveil.core.dev

import com.surveil.core.CameraStatus
import com.surveil.core.CoreEngine
import com.surveil.core.DeviceStatus
import com.surveil.core.NetAddress
import com.surveil.core.Platform
import com.surveil.core.StorageRoot
import com.surveil.core.h264.H264
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import com.surveil.core.media.VideoFormat
import com.surveil.core.settings.Keys
import com.surveil.core.settings.Option
import com.surveil.core.settings.Settings
import com.surveil.core.settings.SettingsStore
import com.surveil.core.web.WebApi
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/**
 * Desktop harness: runs the complete core (web UI, live stream, recorder, motion detector)
 * with a looping H.264 file instead of a camera.
 *
 *   tools/gw.sh :core:runDev -PdevArgs="<dataDir> <port>"
 * The data dir must contain test.h264 (Annex B with AUDs) and snapshot.jpg.
 */
fun main(args: Array<String>) {
    val dataDir = File(args.getOrNull(0) ?: (System.getProperty("user.home") + "/.build/SurveillanceCamera/devdata"))
    val port = args.getOrNull(1)?.toIntOrNull() ?: 8080
    File("core/src/main/resources/web").takeIf { it.isDirectory }?.let { WebApi.devWebRoot = it.absoluteFile }
    val platform = FakePlatform(dataDir)
    val store = SettingsStore(File(dataDir, "settings.json"))
    if (store.current.port != port) store.set(Keys.PORT, port)
    val engine = CoreEngine(platform, store, dataDir)
    platform.engine = engine
    engine.start()
    FakeCamera(engine, File(dataDir, "test.h264")).start()
    MotionSimulator(engine).start()
    println("Dev server: http://localhost:${engine.serverPort}/  (data: $dataDir)")
    Thread.currentThread().join()
}

class FakePlatform(private val dataDir: File) : Platform {
    lateinit var engine: CoreEngine
    override val appVersion = "dev"

    override fun storageRoots() = listOf(
        StorageRoot("internal", "Память компьютера", File(dataDir, "recordings").apply { mkdirs() }),
    )

    override fun networkAddresses(): List<NetAddress> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { NetAddress(ni.name, "wifi", it.hostAddress, false) } }

    override fun deviceStatus() = DeviceStatus(87, true, 31.5f, "Desktop", System.getProperty("os.name"))

    override fun dynamicOptions(key: String, settings: Settings): List<Option>? = when (key) {
        Keys.CAMERA_ID -> listOf(Option("0", "Задняя камера (тест)"), Option("1", "Фронтальная (тест)"))
        Keys.RESOLUTION -> listOf(Option("1280x720", "1280×720"))
        Keys.REC_STORAGE -> listOf(Option("internal", "Память компьютера"))
        else -> null
    }

    override fun snapshot(maxWidth: Int, callback: (ByteArray?) -> Unit) {
        callback(File(dataDir, "snapshot.jpg").takeIf { it.exists() }?.readBytes())
    }

    override fun requestKeyframe() {}
    override fun restartCamera() { println("restartCamera requested") }
    override fun log(message: String) = println("[core] $message")
}

/** Feeds access units from an Annex B file in real time, looping forever. */
class FakeCamera(private val engine: CoreEngine, private val file: File) : Thread("fake-camera") {
    init { isDaemon = true }

    override fun run() {
        val data = file.readBytes()
        val units = ArrayList<ByteArray>()
        var auStart = -1
        H264.forEachAnnexBNal(data, 0, data.size) { s, _ ->
            if (H264.nalType(data[s]) == H264.NAL_AUD) {
                // start code precedes the NAL; back up over it
                var p = s - 3
                if (p > 0 && data[p - 1].toInt() == 0) p--
                if (auStart >= 0) units += data.copyOfRange(auStart, p)
                auStart = p
            }
        }
        if (auStart >= 0) units += data.copyOfRange(auStart, data.size)
        val nals = H264.splitAnnexB(units[0])
        val sps = nals.first { H264.nalType(it[0]) == H264.NAL_SPS }
        val pps = nals.first { H264.nalType(it[0]) == H264.NAL_PPS }
        val format = VideoFormat(sps, pps)
        engine.onVideoFormat(format, null)
        engine.setCameraStatus(CameraStatus("running", null, "0", format.width, format.height, 20, "x264 (file)"))
        println("Fake camera: ${units.size} frames, $format")
        val frameUs = 50_000L
        val t0 = System.nanoTime()
        var i = 0L
        while (true) {
            val au = units[(i % units.size).toInt()]
            val avcc = H264.annexBToAvcc(au, 0, au.size)
            val pts = i * frameUs
            engine.onFrame(MediaFrame(Track.VIDEO, avcc, pts, H264.avccContainsIdr(avcc), System.currentTimeMillis()))
            i++
            val wait = t0 + i * frameUs * 1000 - System.nanoTime()
            if (wait > 0) sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }
}

/** Synthetic 160×90 luma frames: a noisy static scene with a box walking through every 40 s. */
class MotionSimulator(private val engine: CoreEngine) : Thread("motion-sim") {
    init { isDaemon = true }

    override fun run() {
        val w = 160
        val h = 90
        val rnd = Random(1)
        val base = ByteArray(w * h) { i -> (60 + (i % w) / 2 + rnd.nextInt(-3, 4)).toByte() }
        val t0 = System.currentTimeMillis()
        while (true) {
            val t = System.currentTimeMillis() - t0
            val frame = ByteArray(w * h) { i -> ((base[i].toInt() and 0xff) + rnd.nextInt(-2, 3)).coerceIn(0, 255).toByte() }
            val phase = (t / 1000) % 40
            if (phase in 10..17) {
                val x0 = ((phase - 10) * 18).toInt()
                for (y in 30 until 70) for (x in x0 until minOf(w, x0 + 25)) frame[y * w + x] = 220.toByte()
            }
            engine.onLumaFrame(frame, w, h, t)
            sleep(200)
        }
    }
}
