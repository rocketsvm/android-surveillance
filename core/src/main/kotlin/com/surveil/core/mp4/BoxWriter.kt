package com.surveil.core.mp4

/** Growable big-endian byte buffer with ISO-BMFF box helpers. */
class BoxWriter(initialCapacity: Int = 1024) {
    private var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun u8(v: Int) = apply { ensure(1); buf[size++] = v.toByte() }
    fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
    fun u24(v: Int) = apply { u8(v ushr 16); u8(v ushr 8); u8(v) }
    fun u32(v: Int) = apply { u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v) }
    fun u32(v: Long) = u32(v.toInt())
    fun u64(v: Long) = apply { u32((v ushr 32).toInt()); u32(v.toInt()) }
    fun zeros(n: Int) = apply { ensure(n); repeat(n) { buf[size++] = 0 } }
    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off) = apply {
        ensure(len); System.arraycopy(b, off, buf, size, len); size += len
    }
    fun fourcc(s: String) = apply {
        require(s.length == 4)
        for (c in s) u8(c.code)
    }

    fun box(type: String, body: BoxWriter.() -> Unit) = apply {
        val start = size
        u32(0); fourcc(type)
        body()
        patchU32(start, size - start)
    }

    fun fullBox(type: String, version: Int, flags: Int, body: BoxWriter.() -> Unit) =
        box(type) { u8(version); u24(flags); body() }

    fun patchU32(at: Int, v: Int) {
        buf[at] = (v ushr 24).toByte(); buf[at + 1] = (v ushr 16).toByte()
        buf[at + 2] = (v ushr 8).toByte(); buf[at + 3] = v.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    /** Unity transformation matrix used by mvhd/tkhd. */
    fun matrix() = apply {
        u32(0x00010000); u32(0); u32(0)
        u32(0); u32(0x00010000); u32(0)
        u32(0); u32(0); u32(0x40000000)
    }
}
