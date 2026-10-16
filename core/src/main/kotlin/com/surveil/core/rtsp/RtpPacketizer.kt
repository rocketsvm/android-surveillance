package com.surveil.core.rtsp

import com.surveil.core.h264.H264
import java.util.Base64

/** Builds RTP packets for one track. Not thread-safe: one instance per track per session. */
class RtpPacketizer(
    private val payloadType: Int,
    val clockRate: Int,
    val ssrc: Int,
    private var seq: Int,
    /** RTP timestamp corresponding to stream time 0. */
    private val tsBase: Long,
) {
    var packets = 0L
        private set
    var octets = 0L
        private set
    var lastRtpTs = 0L
        private set

    fun rtpTimestamp(ptsUs: Long, baseUs: Long): Long =
        (tsBase + (ptsUs - baseUs) * clockRate / 1_000_000L) and 0xffffffffL

    private fun header(marker: Boolean, ts: Long, payloadLen: Int): ByteArray {
        val p = ByteArray(12 + payloadLen)
        p[0] = 0x80.toByte()
        p[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
        p[2] = (seq ushr 8).toByte(); p[3] = seq.toByte()
        p[4] = (ts ushr 24).toByte(); p[5] = (ts ushr 16).toByte(); p[6] = (ts ushr 8).toByte(); p[7] = ts.toByte()
        p[8] = (ssrc ushr 24).toByte(); p[9] = (ssrc ushr 16).toByte(); p[10] = (ssrc ushr 8).toByte(); p[11] = ssrc.toByte()
        seq = (seq + 1) and 0xffff
        packets++
        octets += payloadLen
        lastRtpTs = ts
        return p
    }

    /**
     * H.264 (RFC 6184, packetization-mode=1): single NAL units or FU-A fragments; the marker bit
     * closes the access unit. [prefix] NAL units (SPS/PPS before keyframes) go first.
     */
    fun h264(avcc: ByteArray, ts: Long, prefix: List<ByteArray>, emit: (ByteArray) -> Unit) {
        val nals = ArrayList<Pair<Int, Int>>()
        H264.forEachAvccNal(avcc) { s, e -> nals += s to e }
        val total = prefix.size + nals.size
        var index = 0
        for (nal in prefix) {
            index++
            nalPackets(nal, 0, nal.size, ts, index == total, emit)
        }
        for ((s, e) in nals) {
            index++
            nalPackets(avcc, s, e, ts, index == total, emit)
        }
    }

    private fun nalPackets(buf: ByteArray, s: Int, e: Int, ts: Long, last: Boolean, emit: (ByteArray) -> Unit) {
        val len = e - s
        if (len <= MAX_PAYLOAD) {
            val p = header(last, ts, len)
            System.arraycopy(buf, s, p, 12, len)
            emit(p)
            return
        }
        val nalHeader = buf[s].toInt() and 0xff
        val fuIndicator = (nalHeader and 0xe0) or 28
        val type = nalHeader and 0x1f
        var off = s + 1
        var first = true
        while (off < e) {
            val chunk = minOf(MAX_PAYLOAD - 2, e - off)
            val end = off + chunk == e
            val p = header(last && end, ts, chunk + 2)
            p[12] = fuIndicator.toByte()
            p[13] = ((if (first) 0x80 else 0) or (if (end) 0x40 else 0) or type).toByte()
            System.arraycopy(buf, off, p, 14, chunk)
            emit(p)
            off += chunk
            first = false
        }
    }

    /** AAC (RFC 3640 AAC-hbr): one access unit per packet with a 16-bit AU header. */
    fun aac(frame: ByteArray, ts: Long, emit: (ByteArray) -> Unit) {
        val p = header(true, ts, 4 + frame.size)
        p[12] = 0; p[13] = 16 // AU-headers-length in bits
        p[14] = (frame.size ushr 5).toByte()
        p[15] = ((frame.size and 0x1f) shl 3).toByte()
        System.arraycopy(frame, 0, p, 16, frame.size)
        emit(p)
    }

    /** RTCP sender report mapping [rtpTs] to wall-clock [wallMs]. */
    fun senderReport(rtpTs: Long, wallMs: Long): ByteArray {
        val r = ByteArray(28)
        r[0] = 0x80.toByte(); r[1] = 200.toByte(); r[2] = 0; r[3] = 6
        putInt(r, 4, ssrc)
        val ntpSec = wallMs / 1000 + NTP_EPOCH_OFFSET
        val ntpFrac = ((wallMs % 1000) shl 32) / 1000
        putInt(r, 8, ntpSec.toInt()); putInt(r, 12, ntpFrac.toInt())
        putInt(r, 16, rtpTs.toInt())
        putInt(r, 20, packets.toInt()); putInt(r, 24, octets.toInt())
        return r
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    companion object {
        const val MAX_PAYLOAD = 1400
        private const val NTP_EPOCH_OFFSET = 2_208_988_800L

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    }
}
