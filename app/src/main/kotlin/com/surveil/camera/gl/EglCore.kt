package com.surveil.camera.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Minimal EGL 1.4 wrapper: one GLES2 context that can render into MediaCodec and display surfaces. */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "no EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        return s
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        return s
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun querySize(surface: EGLSurface): Pair<Int, Int> {
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, h, 0)
        return w[0] to h[0]
    }

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    /** The display itself is not terminated: it is shared with the UI renderer of the same process. */
    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
