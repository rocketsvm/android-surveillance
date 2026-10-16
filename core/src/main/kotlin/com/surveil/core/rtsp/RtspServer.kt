package com.surveil.core.rtsp

import com.surveil.core.media.AudioFormat
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import com.surveil.core.media.VideoFormat
import com.surveil.core.stream.QueueSink
import com.surveil.core.stream.StreamHub
import com.surveil.core.stream.StreamItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Minimal RTSP 1.0 server for NVRs and players: `rtsp://<phone>:8554/live`.
 * H.264 (+ AAC when audio is on), RTP over TCP (interleaved) or UDP, RTCP sender reports.
 */
class RtspServer(
    private val hub: StreamHub,
    /** Checks an `Authorization` header value; true when access is allowed. */
    private val authorize: (String?) -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val sessionCount = AtomicInteger()

    @Volatile var port = 0
        private set
    val clients: Int get() = sessionCount.get()

    @Synchronized
    fun start(port: Int) {
        check(server == null)
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 16)
        server = ss
        this.port = ss.localPort
        thread(name = "rtsp-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: IOException) { continue }
                if (connections.size >= MAX_CONNECTIONS) { runCatching { s.close() }; continue }
                connections += s
                thread(name = "rtsp-conn", isDaemon = true) {
                    try { Connection(s).run() } catch (_: Exception) { } finally {
                        connections -= s
                        runCatching { s.close() }
                    }
                }
            }
        }
        log("RTSP server listening on :${this.port}")
    }

    @Synchronized
    fun stop() {
        val ss = server ?: return
        server = null
        runCatching { ss.close() }
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private class Transport(
        val tcp: Boolean,
        val channel: Int = 0,
        val clientAddr: InetAddress? = null,
        val clientRtpPort: Int = 0,
        val rtp: DatagramSocket? = null,
        val rtcp: DatagramSocket? = null,
    )

    private inner class Connection(private val socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream(), 8192)
        private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        private val writeLock = Any()
        private val sessionId = Random.nextLong(0x10000000L, 0x7fffffffL).toString(16)
        private val transports = HashMap<Int, Transport>()
        private var sender: Thread? = null
        private var sink: QueueSink? = null
        @Volatile private var playing = false

        fun run() {
            socket.tcpNoDelay = true
            socket.soTimeout = 75_000
            try {
                while (!socket.isClosed) {
                    val req = readRequest() ?: break
                    handle(req)
                }
            } finally {
                stopPlayback()
                transports.values.forEach { it.rtp?.close(); it.rtcp?.close() }
            }
        }

        private inner class Request(val method: String, val uri: String, val headers: Map<String, String>) {
            val cseq: String get() = headers["cseq"] ?: "0"
        }

        /** Reads the next request, skipping interleaved RTCP packets the client may send. */
        private fun readRequest(): Request? {
            while (true) {
                input.mark(1)
                val first = input.read()
                if (first < 0) return null
                if (first == '$'.code) {
                    input.read() // channel
                    val len = (input.read() shl 8) or input.read()
                    if (len < 0) return null
                    input.skipNBytesCompat(len)
                    continue
                }
                input.reset()
                break
            }
            val line = readLine(input) ?: return null
            val parts = line.split(' ')
            if (parts.size < 3) return null
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: return null
                if (h.isEmpty()) break
                val c = h.indexOf(':')
                if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
            }
            headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..65536 }?.let { input.skipNBytesCompat(it) }
            return Request(parts[0].uppercase(), parts[1], headers)
        }

        private fun handle(r: Request) {
            if (r.method != "OPTIONS" && !authorize(r.headers["authorization"])) {
                reply(r, 401, "Unauthorized", listOf("WWW-Authenticate" to "Basic realm=\"SurveillanceCam\""))
                return
            }
            when (r.method) {
                "OPTIONS" -> reply(r, 200, "OK", listOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER"))
                "DESCRIBE" -> describe(r)
                "SETUP" -> setup(r)
                "PLAY" -> play(r)
                "PAUSE", "GET_PARAMETER", "SET_PARAMETER" -> reply(r, 200, "OK", listOf("Session" to "$sessionId;timeout=60"))
                "TEARDOWN" -> {
                    stopPlayback()
                    reply(r, 200, "OK", listOf("Session" to sessionId))
                    socket.close()
                }
                else -> reply(r, 501, "Not Implemented")
            }
        }

        private fun describe(r: Request) {
            val v = hub.video
            if (v == null) {
                reply(r, 503, "Service Unavailable")
                return
            }
            val sdp = sdp(v, hub.audio, socket.localAddress.hostAddress ?: "0.0.0.0")
            val base = r.uri.trimEnd('/') + "/"
            reply(r, 200, "OK", listOf("Content-Type" to "application/sdp", "Content-Base" to base), sdp)
        }

        private fun setup(r: Request) {
            val track = Regex("trackID=(\\d+)").find(r.uri)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (track == 1 && hub.audio == null || track > 1) {
                reply(r, 404, "Not Found"); return
            }
            val t = r.headers["transport"] ?: ""
            val transport: Transport
            val replyTransport: String
            if (t.contains("TCP", ignoreCase = true)) {
                val ch = Regex("interleaved=(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: (track * 2)
                transport = Transport(tcp = true, channel = ch)
                replyTransport = "RTP/AVP/TCP;unicast;interleaved=$ch-${ch + 1}"
            } else {
                val m = Regex("client_port=(\\d+)(?:-(\\d+))?").find(t)
                val cport = m?.groupValues?.get(1)?.toIntOrNull()
                if (cport == null) {
                    reply(r, 461, "Unsupported Transport"); return
                }
                val (rtp, rtcp) = openUdpPair()
                transport = Transport(tcp = false, clientAddr = socket.inetAddress, clientRtpPort = cport, rtp = rtp, rtcp = rtcp)
                replyTransport = "RTP/AVP;unicast;client_port=$cport-${cport + 1};server_port=${rtp.localPort}-${rtcp.localPort}"
            }
            transports.remove(track)?.let { it.rtp?.close(); it.rtcp?.close() }
            transports[track] = transport
            reply(r, 200, "OK", listOf("Transport" to replyTransport, "Session" to "$sessionId;timeout=60"))
        }

        private fun play(r: Request) {
            if (transports.isEmpty() || hub.video == null) {
                reply(r, 455, "Method Not Valid In This State"); return
            }
            reply(r, 200, "OK", listOf("Session" to sessionId, "Range" to "npt=0.000-"))
            if (playing) return
            playing = true
            val s = QueueSink(8L * 1024 * 1024)
            sink = s
            sessionCount.incrementAndGet()
            // Over TCP the cached GOP gives an instant picture; over UDP that burst would overflow
            // the receiver's socket buffer, so UDP clients start at a fresh keyframe instead.
            val allTcp = transports.values.all { it.tcp }
            hub.addSink(s, replayGop = allTcp)
            hub.requestKeyframe()
            sender = thread(name = "rtsp-send", isDaemon = true) { sendLoop(s) }
        }

        private fun stopPlayback() {
            if (!playing) return
            playing = false
            sink?.let { hub.removeSink(it); it.queue.close() }
            sink = null
            sender?.interrupt()
            sender = null
            sessionCount.decrementAndGet()
        }

        private fun sendLoop(s: QueueSink) {
            var video: VideoFormat? = null
            var vp: RtpPacketizer? = null
            var ap: RtpPacketizer? = null
            var started = false
            var baseUs = 0L
            var lastSrMs = 0L
            fun packetizer(pt: Int, rate: Int) =
                RtpPacketizer(pt, rate, Random.nextInt(), Random.nextInt(0, 0xffff), Random.nextLong(0, 0xffffffffL))
            try {
                while (playing && !socket.isClosed) {
                    val item = s.queue.take(1000) ?: continue
                    when (item) {
                        is StreamItem.Format -> {
                            if (video != null) {
                                // Parameters changed mid-session: clients reconnect with a fresh SDP.
                                socket.close(); return
                            }
                            video = item.video
                            vp = packetizer(96, 90000)
                            ap = item.audio?.let { packetizer(97, it.sampleRate) }
                        }
                        StreamItem.Stop -> { socket.close(); return }
                        is StreamItem.Frame -> {
                            val v = video ?: continue
                            val f: MediaFrame = item.frame
                            if (!started) {
                                if (!f.isVideoKey) continue
                                started = true
                                baseUs = f.ptsUs
                            }
                            if (f.ptsUs < baseUs) continue
                            val now = System.currentTimeMillis()
                            if (now - lastSrMs > 5000) {
                                // Sender reports first (and then every 5 s): same instant on both clocks
                                // lets players line audio up with video from the very first packet.
                                lastSrMs = now
                                transports[0]?.let { t -> vp?.let { send(t, true, it.senderReport(it.rtpTimestamp(f.ptsUs, baseUs), f.wallMs)) } }
                                transports[1]?.let { t -> ap?.let { send(t, true, it.senderReport(it.rtpTimestamp(f.ptsUs, baseUs), f.wallMs)) } }
                            }
                            if (f.track == Track.VIDEO) {
                                val t = transports[0] ?: continue
                                val vpk = vp!!
                                val prefix = if (f.isKey) listOf(v.sps, v.pps) else emptyList()
                                vpk.h264(f.data, vpk.rtpTimestamp(f.ptsUs, baseUs), prefix) { send(t, false, it) }
                            } else {
                                val apk = ap ?: continue
                                val t = transports[1] ?: continue
                                apk.aac(f.data, apk.rtpTimestamp(f.ptsUs, baseUs)) { send(t, false, it) }
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
                runCatching { socket.close() }
            }
        }

        private fun send(t: Transport, rtcp: Boolean, packet: ByteArray) {
            if (t.tcp) {
                synchronized(writeLock) {
                    output.write('$'.code)
                    output.write(if (rtcp) t.channel + 1 else t.channel)
                    output.write(packet.size ushr 8); output.write(packet.size)
                    output.write(packet)
                    output.flush()
                }
            } else {
                val sock = (if (rtcp) t.rtcp else t.rtp) ?: return
                sock.send(DatagramPacket(packet, packet.size, t.clientAddr, if (rtcp) t.clientRtpPort + 1 else t.clientRtpPort))
            }
        }

        private fun reply(r: Request, code: Int, reason: String, headers: List<Pair<String, String>> = emptyList(), body: String? = null) {
            val sb = StringBuilder("RTSP/1.0 $code $reason\r\nCSeq: ${r.cseq}\r\nServer: SurveillanceCam\r\n")
            headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            val bytes = body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) sb.append("Content-Length: ${bytes.size}\r\n")
            sb.append("\r\n")
            synchronized(writeLock) {
                output.write(sb.toString().toByteArray(Charsets.UTF_8))
                if (bytes != null) output.write(bytes)
                output.flush()
            }
        }
    }

    private fun openUdpPair(): Pair<DatagramSocket, DatagramSocket> {
        repeat(20) {
            val p = 20000 + Random.nextInt(0, 20000) * 2
            try {
                val a = DatagramSocket(p)
                try {
                    return a to DatagramSocket(p + 1)
                } catch (e: IOException) {
                    a.close()
                }
            } catch (_: IOException) {
            }
        }
        throw IOException("no free UDP ports")
    }

    companion object {
        private const val MAX_CONNECTIONS = 16

        fun sdp(v: VideoFormat, a: AudioFormat?, host: String): String = buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} 1 IN IP4 $host\r\n")
            append("s=SurveillanceCam\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("a=control:*\r\n")
            append("a=range:npt=0-\r\n")
            append("m=video 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            val pli = "%02x%02x%02x".format(v.info.profileIdc, v.info.constraintFlags, v.info.levelIdc)
            append("a=fmtp:96 packetization-mode=1;profile-level-id=$pli;sprop-parameter-sets=${RtpPacketizer.b64(v.sps)},${RtpPacketizer.b64(v.pps)}\r\n")
            append("a=framesize:96 ${v.width}-${v.height}\r\n")
            append("a=control:trackID=0\r\n")
            if (a != null) {
                append("m=audio 0 RTP/AVP 97\r\n")
                append("a=rtpmap:97 MPEG4-GENERIC/${a.sampleRate}/${a.channels}\r\n")
                val config = a.asc.joinToString("") { "%02x".format(it) }
                append("a=fmtp:97 streamtype=5;profile-level-id=15;mode=AAC-hbr;config=$config;sizelength=13;indexlength=3;indexdeltalength=3\r\n")
                append("a=control:trackID=1\r\n")
            }
        }
    }
}

private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val b = input.read()
        if (b < 0) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(b.toChar())
        if (sb.length > 8192) throw IOException("line too long")
    }
}

private fun InputStream.skipNBytesCompat(n: Int) {
    var left = n.toLong()
    while (left > 0) {
        val s = skip(left)
        if (s <= 0) {
            if (read() < 0) throw IOException("eof")
            left--
        } else left -= s
    }
}
