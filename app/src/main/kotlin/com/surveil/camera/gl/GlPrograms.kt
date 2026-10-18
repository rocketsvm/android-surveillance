package com.surveil.camera.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Shader programs and geometry used by [GlRenderer]. Must be created on the GL thread. */
internal class GlPrograms {
    /** Camera frame (external OES texture) with a texture-coordinate transform. */
    val camera = Program(VS_TEX_MATRIX, FS_OES)

    /** 4×4 box-filtered luma of the camera frame, for downscaled motion analysis. */
    val luma = Program(VS_UV, FS_OES_LUMA)

    /** Plain RGBA 2D texture with premultiplied alpha (overlay text). */
    val overlay = Program(VS_UV, FS_2D)

    /** Processed frame (RGBA 2D texture), drawn as-is. */
    val frame = Program(VS_UV, FS_2D)

    /** Draws the previous frame over the current one with strength uMix (temporal denoise). */
    val blend = Program(VS_UV, FS_2D_MIX)

    /** 4×4 box-filtered luma of a processed frame texture, for downscaled motion analysis. */
    val luma2d = Program(VS_UV, FS_2D_LUMA)

    private val quad: FloatBuffer = floatBuffer(
        // x, y, u, v
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )
    private val scratch: FloatBuffer = floatBuffer(*FloatArray(16))

    /** Draws the full viewport with [p] (attributes aPos/aUv). */
    fun drawFullQuad(p: Program) = draw(p, quad)

    /** Draws a rectangle given in NDC; the texture's first row ends up at the top. */
    fun drawRect(p: Program, x0: Float, y0: Float, x1: Float, y1: Float) {
        scratch.clear()
        scratch.put(floatArrayOf(
            x0, y0, 0f, 1f,
            x1, y0, 1f, 1f,
            x0, y1, 0f, 0f,
            x1, y1, 1f, 0f,
        ))
        scratch.position(0)
        draw(p, scratch)
    }

    private fun draw(p: Program, buf: FloatBuffer) {
        buf.position(0)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(p.aPos)
        buf.position(2)
        GLES20.glVertexAttribPointer(p.aUv, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(p.aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPos)
        GLES20.glDisableVertexAttribArray(p.aUv)
    }

    fun release() {
        camera.release(); luma.release(); overlay.release(); frame.release(); blend.release(); luma2d.release()
    }

    class Program(vs: String, fs: String) {
        val id: Int = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs))
        val aPos = GLES20.glGetAttribLocation(id, "aPos")
        val aUv = GLES20.glGetAttribLocation(id, "aUv")
        val uTex = GLES20.glGetUniformLocation(id, "uTex")
        val uStep = GLES20.glGetUniformLocation(id, "uStep")
        val uMix = GLES20.glGetUniformLocation(id, "uMix")
        val sTex = GLES20.glGetUniformLocation(id, "sTex")

        fun use() = GLES20.glUseProgram(id)
        fun release() = GLES20.glDeleteProgram(id)

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                error("shader compile failed: $log")
            }
            return s
        }

        private fun link(vs: Int, fs: Int): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            if (ok[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(p)
                GLES20.glDeleteProgram(p)
                error("program link failed: $log")
            }
            return p
        }
    }

    companion object {
        fun createOesTexture(): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        fun create2dTexture(): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

        private const val VS_TEX_MATRIX = """
attribute vec4 aPos;
attribute vec2 aUv;
uniform mat4 uTex;
varying vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTex * vec4(aUv, 0.0, 1.0)).xy;
}
"""

        private const val VS_UV = """
attribute vec4 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    gl_Position = aPos;
    vUv = aUv;
}
"""

        private const val FS_OES = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES sTex;
varying vec2 vTex;
void main() {
    gl_FragColor = texture2D(sTex, vTex);
}
"""

        private const val FS_OES_LUMA = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES sTex;
uniform mat4 uTex;
uniform vec2 uStep;
varying vec2 vUv;
void main() {
    vec3 acc = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            vec2 uv = vUv + (vec2(float(x), float(y)) - 1.5) * uStep;
            acc += texture2D(sTex, (uTex * vec4(uv, 0.0, 1.0)).xy).rgb;
        }
    }
    float l = dot(acc * 0.0625, vec3(0.299, 0.587, 0.114));
    gl_FragColor = vec4(l, l, l, 1.0);
}
"""

        private const val FS_2D = """
precision mediump float;
uniform sampler2D sTex;
varying vec2 vUv;
void main() {
    gl_FragColor = texture2D(sTex, vUv);
}
"""

        private const val FS_2D_MIX = """
precision mediump float;
uniform sampler2D sTex;
uniform float uMix;
varying vec2 vUv;
void main() {
    vec4 c = texture2D(sTex, vUv);
    gl_FragColor = vec4(c.rgb, c.a * uMix);
}
"""

        private const val FS_2D_LUMA = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D sTex;
uniform vec2 uStep;
varying vec2 vUv;
void main() {
    vec3 acc = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            acc += texture2D(sTex, vUv + (vec2(float(x), float(y)) - 1.5) * uStep).rgb;
        }
    }
    float l = dot(acc * 0.0625, vec3(0.299, 0.587, 0.114));
    gl_FragColor = vec4(l, l, l, 1.0);
}
"""
    }
}
