package com.google.ai.edge.gallery.systeminfo

import com.google.gson.Gson

/**
 * Diagnostic-only JNI bridge for the MediaTek dispatch API handshake.
 *
 * The native side (`npdispatchdiag.cpp`) dlopens the dispatch library,
 * resolves `LiteRtDispatchGetApi` and returns only diagnostic facts. It never
 * links the dispatch library statically and never calls initialize/Neuron.
 */
object NpuDispatchHandshakeBridge {

  /** Diagnostic facts returned by `LiteRtDispatchGetApi`. */
  data class HandshakeResult(
    val status: String = "",
    val major: Int = 0,
    val minor: Int = 0,
    val patch: Int = 0,
    val interfacePresent: Boolean = false,
    val asyncPresent: Boolean = false,
    val graphPresent: Boolean = false,
    val errorCode: Int = 0,
    val error: String = "",
  )

  private val gson = Gson()

  init {
    System.loadLibrary("npdispatchdiag")
  }

  /**
   * Runs the handshake against the dispatch library at [libraryPath].
   * Returns the diagnostic JSON produced by the native side; errors are
   * reported inside the JSON (`status = "ERROR"`) and also as thrown
   * `UnsatisfiedLinkError` if the bridge library itself fails to load.
   */
  external fun handshake(libraryPath: String): String

  /** Parses the native JSON payload; returns null when it is not valid. */
  fun parse(json: String): HandshakeResult? =
    try {
      gson.fromJson(json, HandshakeResult::class.java)
    } catch (e: Exception) {
      null
    }
}
