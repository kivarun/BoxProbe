package com.google.ai.edge.gallery.systeminfo

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Diagnostic facts returned by the dispatch `LiteRtDispatchGetApi` handshake.
 */
data class NpuDispatchHandshakeResult(
  val status: String = "",
  val major: Int = 0,
  val minor: Int = 0,
  val patch: Int = 0,
  @SerializedName("interface") val interfacePresent: Boolean = false,
  @SerializedName("async") val asyncPresent: Boolean = false,
  @SerializedName("graph") val graphPresent: Boolean = false,
  val errorCode: Int = 0,
  val error: String = "",
)

private val gson = Gson()

/**
 * Parses the native handshake JSON payload; returns null when it is not valid.
 * Deliberately top-level: touching this must not trigger native library load.
 */
fun parseNpuDispatchHandshakeJson(json: String): NpuDispatchHandshakeResult? =
  try {
    gson.fromJson(json, NpuDispatchHandshakeResult::class.java)
  } catch (e: Exception) {
    null
  }

/**
 * Diagnostic-only JNI bridge for the MediaTek dispatch API handshake.
 *
 * The native side (`npdispatchdiag.cpp`) dlopens the dispatch library,
 * resolves `LiteRtDispatchGetApi` and returns only diagnostic facts. It never
 * links the dispatch library statically and never calls initialize/Neuron.
 *
 * The native library is loaded lazily and only inside [handshake], so unit
 * tests and UI code paths that merely parse results stay JVM-safe.
 */
object NpuDispatchHandshakeBridge {

  @Volatile private var nativeLoaded = false

  /**
   * Runs the handshake against the dispatch library at [libraryPath].
   * Returns the diagnostic JSON produced by the native side; errors are
   * reported inside the JSON (`status = "ERROR"`).
   */
  fun handshake(libraryPath: String): String {
    synchronized(this) {
      if (!nativeLoaded) {
        System.loadLibrary("npdispatchdiag")
        nativeLoaded = true
      }
    }
    return handshakeNative(libraryPath)
  }

  private external fun handshakeNative(libraryPath: String): String
}
