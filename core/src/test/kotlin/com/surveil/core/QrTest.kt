package com.surveil.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.surveil.core.web.Qr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Gray pixels of a BufferedImage as a zxing LuminanceSource (zxing-core has no AWT helper). */
private class GraySource(w: Int, h: Int, private val pixels: ByteArray) : LuminanceSource(w, h) {
    override fun getRow(left: Int, row: ByteArray?): ByteArray =
        pixels.copyOfRange(left, left + width)

    override fun getMatrix(): ByteArray = pixels

    // The PNG encodes dark modules as index 0; the decoded gray map may come out inverted.
    override fun invert(): LuminanceSource {
        val inv = ByteArray(pixels.size)
        for (i in pixels.indices) inv[i] = (255 - (pixels[i].toInt() and 0xFF)).toByte()
        return GraySource(width, height, inv)
    }
}

class QrTest {
    private fun gray(png: ByteArray): Triple<Int, Int, ByteArray> {
        val img = ImageIO.read(ByteArrayInputStream(png))
        val w = img.width
        val h = img.height
        val px = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v: Int = (img.getRGB(x, y) shr 16) and 0xFF
                px.set(y * w + x, v.toByte())
            }
        }
        return Triple(w, h, px)
    }

    @Test
    fun `png is decodable and has the requested size`() {
        val png = Qr.png("http://192.168.1.110:8080/", 240)
        assertTrue(png.size > 100, "png too small")
        val (w, h, _) = gray(png)
        assertEquals(240, w)
        assertEquals(240, h)
    }

    @Test
    fun `qr encodes the text back`() {
        val text = "http://10.0.0.5:8080/"
        val (w, h, px) = gray(Qr.png(text, 240))
        val source = GraySource(w, h, px)
        var decoded: com.google.zxing.Result? = null
        for (src in listOf(source, source.invert())) {
            val ok = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(src))) }
            if (ok.isSuccess) { decoded = ok.getOrNull(); break }
        }
        assertTrue(decoded != null, "QR not decoded")
        assertEquals(text, decoded!!.text)
    }
}
