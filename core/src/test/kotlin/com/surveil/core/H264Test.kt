package com.surveil.core

import com.surveil.core.h264.H264
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class H264Test {
    @Test
    fun parsesRealSps() {
        val info = H264.parseSps(TestMedia.SPS)
        assertEquals(100, info.profileIdc)
        assertEquals(31, info.levelIdc)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertEquals("avc1.64001f", info.codecString)
    }

    @Test
    fun removesEmulationPrevention() {
        val raw = TestMedia.hex("0000030100000302")
        assertContentEquals(TestMedia.hex("000001000002"), H264.unescapeRbsp(raw))
    }

    @Test
    fun annexBToAvccDropsParameterSetsAndAud() {
        val au = TestMedia.hex("00000001" + "0910" + "00000001") + TestMedia.SPS +
            TestMedia.hex("000001") + TestMedia.PPS + TestMedia.hex("00000001" + "65aabbcc" + "000001" + "4111")
        val avcc = H264.annexBToAvcc(au, 0, au.size)
        assertContentEquals(TestMedia.hex("00000004" + "65aabbcc" + "00000002" + "4111"), avcc)
        assertTrue(H264.avccContainsIdr(avcc))
    }

    @Test
    fun avcCHasHighProfileExtension() {
        val c = H264.buildAvcC(TestMedia.SPS, TestMedia.PPS)
        assertEquals(1, c[0].toInt())
        assertEquals(0x64, c[1].toInt() and 0xff)
        assertEquals(0xff, c[4].toInt() and 0xff)
        assertEquals(0xe1, c[5].toInt() and 0xff)
        // 6 header + 2+sps + 1 + 2+pps + 4 high-profile bytes
        assertEquals(6 + 2 + TestMedia.SPS.size + 1 + 2 + TestMedia.PPS.size + 4, c.size)
    }
}
