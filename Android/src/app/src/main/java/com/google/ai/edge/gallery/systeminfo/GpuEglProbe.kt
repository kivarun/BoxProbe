package com.google.ai.edge.gallery.systeminfo

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * Queries GL_VENDOR / GL_RENDERER / GL_VERSION / GLSL version through a small headless
 * EGL pbuffer context. Everything is created and destroyed here; on any failure a
 * structured error snapshot is returned instead of throwing.
 */
object GpuEglProbe {

  private const val PBUFFER_WIDTH = 16
  private const val PBUFFER_HEIGHT = 16

  fun probe(): GpuSnapshot {
    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    var context: EGLContext = EGL14.EGL_NO_CONTEXT
    var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    try {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (display === EGL14.EGL_NO_DISPLAY) {
        return GpuSnapshot.error("eglGetDisplay returned EGL_NO_DISPLAY")
      }
      val version = IntArray(2)
      if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
        return GpuSnapshot.error(
          "eglInitialize failed: " + EGL14.eglGetError().toHexString(),
        )
      }

      // Try OpenGL ES 3 first, fall back to ES 2.
      var config: EGLConfig? = chooseConfig(display, 3)
      var clientVersion = 3
      if (config == null) {
        config = chooseConfig(display, 2)
        clientVersion = 2
      }
      if (config == null) {
        return GpuSnapshot.error("eglChooseConfig found no suitable config")
      }

      val contextAttribs = intArrayOf(
        EGL14.EGL_CONTEXT_CLIENT_VERSION,
        clientVersion,
        EGL14.EGL_NONE,
      )
      context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
      if (context === EGL14.EGL_NO_CONTEXT) {
        return GpuSnapshot.error(
          "eglCreateContext failed: " + EGL14.eglGetError().toHexString(),
        )
      }

      val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, PBUFFER_WIDTH, EGL14.EGL_HEIGHT, PBUFFER_HEIGHT, EGL14.EGL_NONE)
      surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
      if (surface === EGL14.EGL_NO_SURFACE) {
        return GpuSnapshot.error(
          "eglCreatePbufferSurface failed: " + EGL14.eglGetError().toHexString(),
        )
      }

      if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
        return GpuSnapshot.error(
          "eglMakeCurrent failed: " + EGL14.eglGetError().toHexString(),
        )
      }

      val glVendor = GLES20.glGetString(GLES20.GL_VENDOR)
      val glRenderer = GLES20.glGetString(GLES20.GL_RENDERER)
      val glVersion = GLES20.glGetString(GLES20.GL_VERSION)
      val glslVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
      val glError = GLES20.glGetError()
      if (glError != GLES20.GL_NO_ERROR) {
        return GpuSnapshot.error("glGetString failed: $glError")
      }

      return GpuSnapshot(
        status = ProbeStatus.OK,
        glVendor = glVendor,
        glRenderer = glRenderer,
        glVersion = glVersion,
        glslVersion = glslVersion,
        errorDetail = null,
      )
    } catch (t: Throwable) {
      return GpuSnapshot.error("Exception: ${t.javaClass.simpleName}: ${t.message}")
    } finally {
      try {
        EGL14.eglMakeCurrent(
          display,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_CONTEXT,
        )
        if (surface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        if (display !== EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
      } catch (ignored: Throwable) {
        // Teardown is best effort; the structured result is already computed.
      }
    }
  }

  private fun chooseConfig(display: EGLDisplay, clientVersion: Int): EGLConfig? {
    // EGL_OPENGL_ES3_BIT (0x0040) is not exposed as a constant; use the Khronos value.
    val renderableType =
      if (clientVersion == 3) 0x0040 else EGL14.EGL_OPENGL_ES2_BIT
    val configAttribs = intArrayOf(
      EGL14.EGL_RENDERABLE_TYPE,
      renderableType,
      EGL14.EGL_SURFACE_TYPE,
      EGL14.EGL_PBUFFER_BIT,
      EGL14.EGL_RED_SIZE,
      8,
      EGL14.EGL_GREEN_SIZE,
      8,
      EGL14.EGL_BLUE_SIZE,
      8,
      EGL14.EGL_NONE,
    )
    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
      return null
    }
    return if (numConfigs[0] > 0) configs[0] else null
  }

  private fun Int.toHexString(): String = "0x" + this.toUInt().toString(16).uppercase()
}
