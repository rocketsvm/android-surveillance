package com.surveil.core.web

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * QR code as PNG. zxing gives the bit matrix; the PNG is assembled here so `:core` needs no AWT
 * (Android has no java.awt.image), only zxing-core.
 */
object Qr {
    fun png(text: String, size: Int = 480): ByteArray {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        // 1-bit greyscale, rows byte-aligned, MSB = leftmost pixel; black = bit 1.
        val stride = (w + 7) / 8
        val raw = ByteArray(h * (stride + 1))
        for (y in 0 until h) {
            val row = y * (stride + 1)
            for (x in 0 until w) if (m.get(x, y)) raw[row + 1 + x / 8] = (raw[row + 1 + x / 8].toInt() or (1 shl (7 - x % 8))).toByte()
        }
        return pngBytes(w, h, raw)
    }

    private fun pngBytes(w: Int, h: Int, raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteBuffer.allocate(13).apply {
            putInt(w); putInt(h)
            put(byteArrayOf(1, 0, 0, 0, 0))
        }.array()
        chunk(out, "IHDR", ihdr)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed, deflater).use { it.write(raw) }
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteBuffer.allocate(4).putInt(data.size).array()
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(len); out.write(typeBytes); out.write(data)
        val crc = CRC32()
        crc.update(typeBytes); crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}
