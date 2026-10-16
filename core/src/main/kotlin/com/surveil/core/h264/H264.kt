package com.surveil.core.h264

import java.io.ByteArrayOutputStream

/** Parsed subset of an H.264 sequence parameter set. */
data class SpsInfo(
    val profileIdc: Int,
    val constraintFlags: Int,
    val levelIdc: Int,
    val width: Int,
    val height: Int,
    val chromaFormatIdc: Int,
    val bitDepthLumaMinus8: Int,
    val bitDepthChromaMinus8: Int,
) {
    /** RFC 6381 codec string, e.g. `avc1.640028`. */
    val codecString: String get() = "avc1.%02x%02x%02x".format(profileIdc, constraintFlags, levelIdc)
}

object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    fun nalType(firstByte: Byte): Int = firstByte.toInt() and 0x1f

    /**
     * Calls [block] with `[start, end)` of every NAL unit in an Annex B buffer (start codes excluded).
     * Leading bytes before the first start code are ignored.
     */
    inline fun forEachAnnexBNal(buf: ByteArray, off: Int, len: Int, block: (start: Int, end: Int) -> Unit) {
        val limit = off + len
        var i = off
        var nalStart = -1
        while (i + 2 < limit) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                if (nalStart >= 0) {
                    var nalEnd = i
                    while (nalEnd > nalStart && buf[nalEnd - 1].toInt() == 0) nalEnd--
                    if (nalEnd > nalStart) block(nalStart, nalEnd)
                }
                i += 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until limit) block(nalStart, limit)
    }

    /** Splits an Annex B buffer into separate NAL unit arrays. */
    fun splitAnnexB(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        forEachAnnexBNal(buf, off, len) { s, e -> out += buf.copyOfRange(s, e) }
        return out
    }

    /**
     * Converts an Annex B access unit into AVCC (4-byte big-endian length prefixes).
     * Parameter sets and access unit delimiters are dropped: they live in the container's avcC.
     */
    fun annexBToAvcc(buf: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len + 16)
        forEachAnnexBNal(buf, off, len) { s, e ->
            when (nalType(buf[s])) {
                NAL_SPS, NAL_PPS, NAL_AUD -> Unit
                else -> {
                    val n = e - s
                    out.write(n ushr 24); out.write(n ushr 16); out.write(n ushr 8); out.write(n)
                    out.write(buf, s, n)
                }
            }
        }
        return out.toByteArray()
    }

    /** Iterates NAL units of an AVCC (4-byte length prefixed) buffer. */
    inline fun forEachAvccNal(buf: ByteArray, block: (start: Int, end: Int) -> Unit) {
        var i = 0
        while (i + 4 <= buf.size) {
            val n = ((buf[i].toInt() and 0xff) shl 24) or ((buf[i + 1].toInt() and 0xff) shl 16) or
                ((buf[i + 2].toInt() and 0xff) shl 8) or (buf[i + 3].toInt() and 0xff)
            i += 4
            if (n <= 0 || i + n > buf.size) return
            block(i, i + n)
            i += n
        }
    }

    fun avccContainsIdr(buf: ByteArray): Boolean {
        forEachAvccNal(buf) { s, _ -> if (nalType(buf[s]) == NAL_IDR) return true }
        return false
    }

    /** Removes emulation prevention bytes (00 00 03 -> 00 00). */
    fun unescapeRbsp(nal: ByteArray, off: Int = 0, len: Int = nal.size - off): ByteArray {
        val out = ByteArray(len)
        var o = 0
        var zeros = 0
        for (i in off until off + len) {
            val b = nal[i].toInt() and 0xff
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out[o++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.copyOf(o)
    }

    /** Parses an SPS NAL unit (including its 1-byte NAL header). */
    fun parseSps(sps: ByteArray): SpsInfo {
        require(sps.size >= 4 && nalType(sps[0]) == NAL_SPS) { "not an SPS" }
        val r = BitReader(unescapeRbsp(sps, 1, sps.size - 1))
        val profileIdc = r.u(8)
        val constraintFlags = r.u(8)
        val levelIdc = r.u(8)
        r.ue() // seq_parameter_set_id
        var chromaFormatIdc = 1
        var separateColourPlane = 0
        var bitDepthLuma = 0
        var bitDepthChroma = 0
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) separateColourPlane = r.u(1)
            bitDepthLuma = r.ue()
            bitDepthChroma = r.ue()
            r.u(1) // qpprime_y_zero_transform_bypass_flag
            if (r.u(1) == 1) { // seq_scaling_matrix_present_flag
                val lists = if (chromaFormatIdc != 3) 8 else 12
                for (i in 0 until lists) {
                    if (r.u(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.u(1)
                r.se(); r.se()
                val n = r.ue()
                repeat(n) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.u(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.u(1)
        if (frameMbsOnly == 0) r.u(1)
        r.u(1) // direct_8x8_inference_flag
        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (r.u(1) == 1) {
            cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue()
        }
        val chromaArrayType = if (separateColourPlane == 1) 0 else chromaFormatIdc
        val subWidthC = if (chromaFormatIdc == 3) 1 else 2
        val subHeightC = if (chromaFormatIdc == 1) 2 else 1
        val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
        val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnly) else subHeightC * (2 - frameMbsOnly)
        val width = widthMbs * 16 - (cropL + cropR) * cropUnitX
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropT + cropB) * cropUnitY
        return SpsInfo(profileIdc, constraintFlags, levelIdc, width, height, chromaFormatIdc, bitDepthLuma, bitDepthChroma)
    }

    /** Builds an AVCDecoderConfigurationRecord (the payload of an `avcC` box). */
    fun buildAvcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val info = parseSps(sps)
        val out = ByteArrayOutputStream(sps.size + pps.size + 16)
        out.write(1)
        out.write(sps[1].toInt()); out.write(sps[2].toInt()); out.write(sps[3].toInt())
        out.write(0xff) // reserved(6) + lengthSizeMinusOne=3
        out.write(0xe1) // reserved(3) + numOfSequenceParameterSets=1
        out.write(sps.size ushr 8); out.write(sps.size); out.write(sps)
        out.write(1)
        out.write(pps.size ushr 8); out.write(pps.size); out.write(pps)
        if (info.profileIdc in HIGH_PROFILES) {
            out.write(0xfc or (info.chromaFormatIdc and 3))
            out.write(0xf8 or (info.bitDepthLumaMinus8 and 7))
            out.write(0xf8 or (info.bitDepthChromaMinus8 and 7))
            out.write(0)
        }
        return out.toByteArray()
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}

/** MSB-first bit reader with Exp-Golomb support. */
class BitReader(private val data: ByteArray) {
    private var pos = 0 // in bits

    fun u(bits: Int): Int {
        var v = 0
        repeat(bits) {
            val byte = pos ushr 3
            if (byte >= data.size) throw IllegalArgumentException("bitstream overrun")
            v = (v shl 1) or ((data[byte].toInt() ushr (7 - (pos and 7))) and 1)
            pos++
        }
        return v
    }

    fun ue(): Int {
        var zeros = 0
        while (u(1) == 0) {
            zeros++
            if (zeros > 31) throw IllegalArgumentException("bad exp-golomb")
        }
        return if (zeros == 0) 0 else ((1 shl zeros) - 1 + u(zeros))
    }

    fun se(): Int {
        val k = ue()
        return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
    }
}
