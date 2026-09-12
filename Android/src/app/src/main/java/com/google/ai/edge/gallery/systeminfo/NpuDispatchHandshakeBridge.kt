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

/**
 * Diagnostic facts from the `LiteRtDispatchInitialize` stage.
 */
data class NpuDispatchInitializeResult(
  val status: String = "",
  val initStatus: Int = 0,
  val optionsCreated: Boolean = false,
  val statusString: String = "",
  val error: String = "",
  val adapterProbe: List<NpuAdapterProbeEntry> = emptyList(),
)

/** One adapter candidate dlopen probe: name, success flag, raw dlerror. */
data class NpuAdapterProbeEntry(
  val name: String = "",
  val ok: Boolean = false,
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
 * Parses the native dispatch-initialize JSON payload; returns null when it is
 * not valid. Deliberately top-level: touching this must not trigger native
 * library load.
 */
fun parseNpuDispatchInitializeJson(json: String): NpuDispatchInitializeResult? =
  try {
    gson.fromJson(json, NpuDispatchInitializeResult::class.java)
  } catch (e: Exception) {
    null
  }

/**
 * Diagnostic-only JNI bridge for the MediaTek dispatch stages.
 *
 * The native side (`npdispatchdiag.cpp`) dlopens the dispatch/core libraries,
 * resolves the C APIs and returns only diagnostic facts. It never links the
 * dispatch libraries statically and never creates Engine/model/device contexts.
 *
 * The native library is loaded lazily and only inside the calls, so unit
 * tests and UI code paths that merely parse results stay JVM-safe.
 */
object NpuDispatchHandshakeBridge {

  @Volatile private var nativeLoaded = false

  private fun ensureNativeLoaded() {
    synchronized(this) {
      if (!nativeLoaded) {
        System.loadLibrary("npdispatchdiag")
        nativeLoaded = true
      }
    }
  }

  /**
   * Runs the handshake against the dispatch library at [libraryPath].
   * Returns the diagnostic JSON produced by the native side; errors are
   * reported inside the JSON (`status = "ERROR"`).
   */
  fun handshake(libraryPath: String): String {
    ensureNativeLoaded()
    return handshakeNative(libraryPath)
  }

  /**
   * Runs `LiteRtDispatchInitialize` against the already-loaded libraries with
   * a fresh `LiteRtEnvironment` (single `DispatchLibraryDir` string option =
   * [nativeLibraryDir]) and an empty `LiteRtOptions`. Never destroys the
   * environment/options: the MediaTek dispatch keeps references to both
   * beyond initialize (upstream source contract at revision 0b1b17f).
   */
  fun initializeDispatch(coreLibraryPath: String, dispatchLibraryPath: String, nativeLibraryDir: String): String {
    ensureNativeLoaded()
    return initializeDispatchNative(coreLibraryPath, dispatchLibraryPath, nativeLibraryDir)
  }

  private external fun handshakeNative(libraryPath: String): String

  private external fun initializeDispatchNative(
    coreLibraryPath: String,
    dispatchLibraryPath: String,
    nativeLibraryDir: String,
  ): String
}
