package com.google.ai.edge.gallery.systeminfo

import java.util.Locale

/**
 * Classifies bundled native library file names and extracts vendor/generation metadata.
 *
 * Classification is purely name-based: scan the native library directory, then bucket by
 * prefix. The presence of a bundled library says nothing about runtime availability.
 */
object NativeLibClassifier {

  private const val DISPATCH_PREFIX = "liblitertdispatch_"
  private const val COMPILER_PLUGIN_PREFIX = "liblitertcompilerplugin_"
  private const val QNN_HTP_PREFIX = "libqnnhtp"
  private const val QNN_PREFIX = "libqnn"
  private const val HTP_GENERATION_REGEX_PATTERN = "V(\\d{2,3})"

  private val KNOWN_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

  /**
   * Extracts the library file name from an installed-package entry path.
   *
   * Accepts entries like `lib/arm64-v8a/libLiteRtDispatch_MediaTek.so` and
   * `lib/arm64-v8a/libQnnHtpV79Skel.so`. Returns null for anything else
   * (`assets/foo.so`, `META-INF/foo`, `lib/arm64-v8a/not-a-library.txt`, …).
   */
  fun parseApkLibEntry(entryPath: String): String? {
    val parts = entryPath.trim().split('/')
    if (parts.size != 3) return null
    if (parts[0] != "lib") return null
    if (parts[1].lowercase(Locale.US) !in KNOWN_ABIS) return null
    val fileName = parts[2]
    if (!fileName.lowercase(Locale.US).endsWith(".so")) return null
    return fileName
  }

  /** Classifies a single library file name (without directory). */
  fun classify(fileName: String): BundledNativeLib {
    val name = fileName.trim()
    val lower = name.lowercase(Locale.US)
    return when {
      lower.startsWith(DISPATCH_PREFIX) -> BundledNativeLib(
        fileName = name,
        kind = NativeLibKind.LITE_RT_DISPATCH,
        vendorLabel = vendorLabelAfterPrefix(name, "libLiteRtDispatch_"),
        htpGeneration = null,
      )
      lower.startsWith(COMPILER_PLUGIN_PREFIX) -> BundledNativeLib(
        fileName = name,
        kind = NativeLibKind.LITE_RT_COMPILER_PLUGIN,
        vendorLabel = vendorLabelAfterPrefix(name, "libLiteRtCompilerPlugin_"),
        htpGeneration = null,
      )
      lower.startsWith(QNN_HTP_PREFIX) -> BundledNativeLib(
        fileName = name,
        kind = NativeLibKind.QNN_HTP,
        vendorLabel = null,
        htpGeneration = extractHtpGeneration(name),
      )
      lower.startsWith(QNN_PREFIX) -> BundledNativeLib(
        fileName = name,
        kind = NativeLibKind.QNN_CORE,
        vendorLabel = null,
        htpGeneration = null,
      )
      else -> BundledNativeLib(
        fileName = name,
        kind = NativeLibKind.OTHER,
        vendorLabel = null,
        htpGeneration = null,
      )
    }
  }

  /**
   * Maps a LiteRT vendor label (e.g. "MediaTek") from a library name onto [RuntimeVendor].
   */
  fun vendorLabelToRuntimeVendor(label: String): RuntimeVendor =
    when (label.lowercase(Locale.US)) {
      "mediatek" -> RuntimeVendor.MEDIATEK
      "qualcomm" -> RuntimeVendor.QUALCOMM
      "googletensor", "google_tensor", "google" -> RuntimeVendor.GOOGLE_TENSOR
      else -> RuntimeVendor.OTHER
    }

  /**
   * Extracts the HTP hardware generation (e.g. "V69", "V73", "V79") from a QNN HTP
   * library file name, or null when the name carries no generation suffix.
   */
  fun extractHtpGeneration(fileName: String): String? {
    val match = Regex(HTP_GENERATION_REGEX_PATTERN).find(fileName) ?: return null
    val number = match.groupValues[1]
    return "V$number"
  }

  private fun vendorLabelAfterPrefix(fileName: String, prefix: String): String? {
    if (!fileName.startsWith(prefix, ignoreCase = true)) return null
    val rest = fileName.substring(prefix.length)
    // Strip ".so" (and any trailing qualifiers) to get the raw vendor label.
    val label = rest.substringBefore(".so").substringBefore("-")
    return label.ifEmpty { null }
  }
}
