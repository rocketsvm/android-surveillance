package com.surveil.core

import com.surveil.core.rtsp.RtpPacketizer
import com.surveil.core.rtsp.RtspServer
import com.surveil.core.stream.StreamHub
import java.io.DataInputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RtspTest {
    private val hub = StreamHub()
    private var password: String? = null
    private val server = RtspServer(hub, { h -> password == null || h == "Basic $password" }).apply { start(0) }

    @AfterTest
    fun stop() = server.stop()

    @Test
    fun fuAFragmentationKeepsPayloadAndMarksLastPacket() {
        val p = RtpPacketizer(96, 90000, 1234, 100, 0)
        val frame = TestMedia.frame(0, size = 5000)
        val packets = ArrayList<ByteArray>()
        p.h264(frame.data, 9000, listOf(TestMedia.SPS, TestMedia.PPS)) { packets += it }
        // SPS, PPS as single NAL units, then FU-A fragments of the IDR
        assertEquals(0x67, packets[0][12].toInt() and 0xff)
        assertEquals(0x68, packets[1][12].toInt() and 0xff)
        val fus = packets.drop(2)
        assertTrue(fus.all { (it[12].toInt() and 0x1f) == 28 })
        assertTrue(fus.first()[13].toInt() and 0x80 != 0, "start bit")
        assertTrue(fus.last()[13].toInt() and 0x40 != 0, "end bit")
        assertTrue(packets.dropLast(1).none { it[1].toInt() and 0x80 != 0 }, "marker only on the last packet")
        assertTrue(packets.last()[1].toInt() and 0x80 != 0)
        // Reassembled NAL equals the original (minus its header byte, which FU headers carry)
        val body = fus.flatMap { it.copyOfRange(14, it.size).toList() }
        assertEquals(4999, body.size)
        assertTrue(packets.all { it.size <= 12 + RtpPacketizer.MAX_PAYLOAD })
        // Sequence numbers increase by one
        val seqs = packets.map { ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff) }
        assertEquals((100 until 100 + packets.size).toList(), seqs)
    }

    @Test
    fun aacAuHeader() {
        val p = RtpPacketizer(97, 48000, 1, 0, 0)
        var pkt = ByteArray(0)
        p.aac(ByteArray(300), 0) { pkt = it }
        assertEquals(16, pkt[13].toInt())                                  // AU-headers-length (bits)
        val size = ((pkt[14].toInt() and 0xff) shl 5) or ((pkt[15].toInt() and 0xff) ushr 3)
        assertEquals(300, size)
        assertEquals(12 + 4 + 300, pkt.size)
    }

    @Test
    fun describeSetupPlayOverTcp() {
        hub.setFormat(TestMedia.format, null)
        val feeder = thread(isDaemon = true) {
            var i = 0
            while (!Thread.currentThread().isInterrupted) {
                hub.push(TestMedia.frame(i++))
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }
        try {
            Socket("127.0.0.1", server.port).use { s ->
                s.soTimeout = 5000
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())
                fun request(text: String): String {
                    out.write(text.toByteArray()); out.flush()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(inp.readUnsignedByte().toChar())
                    val len = Regex("Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(len).also { inp.readFully(it) }
                    return head.toString() + String(body)
                }
                val url = "rtsp://127.0.0.1:${server.port}/live"
                val describe = request("DESCRIBE $url RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                assertTrue(describe.startsWith("RTSP/1.0 200"), describe)
                assertTrue(describe.contains("sprop-parameter-sets=" + RtpPacketizer.b64(TestMedia.SPS)))
                val setup = request("SETUP $url/trackID=0 RTSP/1.0\r\nCSeq: 2\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
                assertTrue(setup.contains("interleaved=0-1"), setup)
                val play = request("PLAY $url RTSP/1.0\r\nCSeq: 3\r\nSession: x\r\n\r\n")
                assertTrue(play.startsWith("RTSP/1.0 200"), play)
                var rtp = 0
                var rtcp = 0
                while (rtp < 10) {
                    assertEquals('$'.code, inp.readUnsignedByte())
                    val ch = inp.readUnsignedByte()
                    val len = inp.readUnsignedShort()
                    val pkt = ByteArray(len).also { inp.readFully(it) }
                    if (ch == 0) {
                        assertEquals(0x80, pkt[0].toInt() and 0xc0)       // RTP version 2
                        assertEquals(96, pkt[1].toInt() and 0x7f)
                        rtp++
                    } else {
                        assertEquals(200, pkt[1].toInt() and 0xff)        // RTCP sender report
                        rtcp++
                    }
                }
                assertTrue(rtcp >= 1, "a sender report precedes the media")
                assertEquals(1, server.clients)
            }
        } finally {
            feeder.interrupt()
        }
    }

    @Test
    fun requiresPasswordWhenSet() {
        password = "secret"
        hub.setFormat(TestMedia.format, null)
        Socket("127.0.0.1", server.port).use { s ->
            s.getOutputStream().write("DESCRIBE rtsp://x/live RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            val line = s.getInputStream().bufferedReader().readLine()
            assertEquals("RTSP/1.0 401 Unauthorized", line)
        }
    }
}
