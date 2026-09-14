package com.google.ai.edge.gallery.runtime.npu

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.device.SocVendor
import com.google.ai.edge.gallery.device.SocVendorDetector
import java.io.File
import java.nio.file.Files

private const val TAG = "VendorDispatchRuntime"

/**
 * Production vendor-isolated LiteRT dispatch runtime.
 *
 * LiteRT resolves vendor dispatch libraries relative to `nativeLibraryDir`. When the
 * plain app `nativeLibraryDir` contains dispatch runtimes of several vendors, LiteRT
 * may pick an unrelated one. To make the NPU path deterministic, production points
 * `Backend.NPU(nativeLibraryDir = …)` at an app-private directory that exposes
 * symlinks to only the required vendor libraries extracted into
 * `applicationInfo.nativeLibraryDir`.
 *
 * The SoC classification itself ([SocVendorDetector]) lives in the neutral device layer
 * and stays shared with the System Info screen; this layer owns only the production
 * dispatch runtime built on top of it.
 */

/**
 * Vendors with a production LiteRT dispatch runtime. A vendor exists here only once
 * its dispatch runtime is actually implemented and shipped by the build; devices of
 * other vendors map to null instead of a fake vendor entry.
 */
enum class NpuDispatchVendor(val dirName: String, val label: String) {
  MEDIATEK("mediatek", "MediaTek"),
  QUALCOMM("qualcomm", "Qualcomm"),
}

/**
 * Hexagon HTP generations with a production Qualcomm runtime set. A generation exists
 * here only once its required library set is verified; other SoCs map to null instead
 * of a speculative entry.
 */
enum class NpuHtpGeneration(val dirSuffix: String, val label: String) {
  V81("V81", "V81"),
}

/** Qualcomm SoCs with a production HTP generation, matched on `Build.SOC_MODEL`. */
private val QUALCOMM_SOC_TO_HTP: Map<String, NpuHtpGeneration> = mapOf(
  "sm8850" to NpuHtpGeneration.V81,
)

/**
 * Maps a Qualcomm `Build.SOC_MODEL` value onto its production HTP generation, or null
 * when the SoC has no verified production runtime set. Matching is case-insensitive
 * and trimmed; no device model/brand is consulted.
 */
fun qualcommHtpGenerationForSoc(socModel: String): NpuHtpGeneration? =
  QUALCOMM_SOC_TO_HTP[socModel.trim().lowercase()]

/**
 * Non-system libraries required by LiteRT to initialize the vendor NPU dispatch path.
 *
 * Determined from the actual DT_NEEDED requirements of the bundled vendor libraries.
 *
 * The MediaTek set matches the production MediaTek dispatch; the Qualcomm set is the
 * SM8850/HTP V81 runtime (LiteRT v2.1.5 JIT dispatch/plugin + QAIRT 2.44.0.260225).
 * A Qualcomm SoC without a verified HTP generation maps to an empty set: nothing is
 * prepared instead of guessing a partial runtime.
 */
fun npuRequiredLibNames(vendor: NpuDispatchVendor, socModel: String = ""): List<String> =
  when (vendor) {
    NpuDispatchVendor.MEDIATEK ->
      listOf("libLiteRtDispatch_MediaTek.so", "libLiteRtCompilerPlugin_MediaTek.so")
    NpuDispatchVendor.QUALCOMM ->
      when (val generation = qualcommHtpGenerationForSoc(socModel)) {
        null -> emptyList()
        else ->
          listOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libLiteRtCompilerPlugin_Qualcomm.so",
            "libQnnSystem.so",
            "libQnnHtp.so",
            "libQnnHtpPrepare.so",
            "libQnnIr.so",
            "libQnnSaver.so",
            "libQnnHtp${generation.dirSuffix}Stub.so",
            "libQnnHtp${generation.dirSuffix}Skel.so",
          )
      }
  }

/**
 * Maps the device's SoC vendor onto the production NPU dispatch vendor, or null when
 * the device has no production-supported NPU dispatch runtime. Vendors remain known to
 * the System Info layer but do not exist in the production runtime until their dispatch
 * runtime is implemented.
 */
fun npuDispatchVendorForDevice(socVendor: SocVendor): NpuDispatchVendor? =
  when (socVendor) {
    SocVendor.MEDIATEK -> NpuDispatchVendor.MEDIATEK
    SocVendor.QUALCOMM -> NpuDispatchVendor.QUALCOMM
    else -> null
  }

/** Required vendor libraries that are not visible in `nativeLibraryDir`. */
fun missingVendorDispatchLibs(
  requiredLibNames: List<String>,
  availableSoNames: List<String>,
): List<String> = requiredLibNames.filter { !availableSoNames.contains(it) }

/** One planned sync action for the vendor dispatch directory. */
enum class VendorDispatchSyncKind { CREATE, REPAIR, REMOVE, KEEP }

data class VendorDispatchSyncAction(
  val entryName: String,
  val kind: VendorDispatchSyncKind,
  val detail: String? = null,
)

/**
 * Deterministic sync plan for the vendor dispatch directory.
 *
 * [existingEntries] maps entry names of the vendor dir to the resolved symlink target
 * path when the entry is a healthy symlink pointing at the expected source, or null
 * when the entry is a regular file, a broken/stale symlink, or is absent (a key with a
 * null value is only produced for entries that exist but need repair).
 */
fun planVendorDirSync(
  requiredLibNames: List<String>,
  existingEntries: Map<String, String?>,
  sourceDirPath: String,
): List<VendorDispatchSyncAction> {
  val actions = mutableListOf<VendorDispatchSyncAction>()
  // Foreign vendor / stale .so entries must not leak into the vendor dir.
  for ((name, _) in existingEntries) {
    if (name.endsWith(".so") && !requiredLibNames.contains(name)) {
      actions.add(
        VendorDispatchSyncAction(
          entryName = name,
          kind = VendorDispatchSyncKind.REMOVE,
          detail = "not part of this vendor's required set",
        ),
      )
    }
  }
  for (required in requiredLibNames) {
    val existing = existingEntries[required]
    val expectedTarget = sourceDirPath + File.separator + required
    when {
      // Healthy symlink pointing at the extracted source: keep it.
      existing != null -> {
        if (existing == expectedTarget) {
          actions.add(VendorDispatchSyncAction(required, VendorDispatchSyncKind.KEEP))
        } else {
          actions.add(
            VendorDispatchSyncAction(
              required,
              VendorDispatchSyncKind.REPAIR,
              "stale symlink target: $existing",
            ),
          )
        }
      }
      // Entry exists but is not a healthy symlink (regular file or broken symlink).
      existingEntries.containsKey(required) ->
        actions.add(
          VendorDispatchSyncAction(
            required,
            VendorDispatchSyncKind.REPAIR,
            "not a healthy symlink",
          ),
        )
      // Absent.
      else -> actions.add(VendorDispatchSyncAction(required, VendorDispatchSyncKind.CREATE))
    }
  }
  return actions
}

/** Result of preparing the vendor-isolated dispatch directory. */
data class VendorDispatchPreparation(
  val vendor: NpuDispatchVendor,
  val vendorDispatchDir: File,
  val dirExists: Boolean,
  val visibleSoNames: List<String>,
  val missingRequired: List<String>,
  val symlinkMode: Boolean,
  val errors: List<String>,
  /** The raw device SoC model the preparation was computed for, if known. */
  val socModel: String = "",
  /** The production HTP generation selected for this SoC, empty when not applicable. */
  val htpGeneration: String = "",
) {
  val ok: Boolean
    get() = errors.isEmpty() && missingRequired.isEmpty()
}

/**
 * Prepares (deterministically and idempotently) an app-private directory exposing
 * symlinks to only the required vendor libraries. Never modifies
 * `applicationInfo.nativeLibraryDir` and never deletes anything outside the
 * app-private vendor directory.
 *
 * Copy is used as a fallback only when the filesystem refuses symlinks; it is never
 * the default mode.
 */
fun prepareVendorDispatchRuntime(
  context: Context,
  vendor: NpuDispatchVendor,
  socModel: String = "",
): VendorDispatchPreparation {
  val vendorDispatchDir = File(context.filesDir, "runtime-dispatch/${vendor.dirName}")
  val required = npuRequiredLibNames(vendor, socModel)
  if (required.isEmpty()) {
    return VendorDispatchPreparation(
      vendor = vendor,
      vendorDispatchDir = vendorDispatchDir,
      socModel = socModel,
      htpGeneration = qualcommHtpGenerationForSoc(socModel)?.label ?: "",
      dirExists = false,
      visibleSoNames = emptyList(),
      missingRequired = emptyList(),
      symlinkMode = false,
      errors = listOf("No production library set for ${vendor.label} SoC '$socModel'"),
    )
  }

  val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir ?: ""
  if (nativeLibraryDir.isEmpty()) {
    return VendorDispatchPreparation(
      vendor = vendor,
      vendorDispatchDir = vendorDispatchDir,
      socModel = socModel,
      htpGeneration = qualcommHtpGenerationForSoc(socModel)?.label ?: "",
      dirExists = false,
      visibleSoNames = emptyList(),
      missingRequired = emptyList(),
      symlinkMode = false,
      errors = listOf("applicationInfo.nativeLibraryDir is null or empty"),
    )
  }

  val nativeLibraryDirFile = File(nativeLibraryDir)
  val availableSoNames =
    nativeLibraryDirFile.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }
      ?.map { it.name }?.sorted() ?: emptyList()
  val missing = missingVendorDispatchLibs(required, availableSoNames)
  if (missing.isNotEmpty()) {
    return VendorDispatchPreparation(
      vendor = vendor,
      vendorDispatchDir = vendorDispatchDir,
      socModel = socModel,
      htpGeneration = qualcommHtpGenerationForSoc(socModel)?.label ?: "",
      dirExists = false,
      visibleSoNames = emptyList(),
      missingRequired = missing,
      symlinkMode = false,
      errors = emptyList(),
    )
  }

  val errors = mutableListOf<String>()
  var symlinkMode = true

  vendorDispatchDir.mkdirs()
  if (!vendorDispatchDir.isDirectory) {
    errors.add("Failed to create vendor dispatch directory: ${vendorDispatchDir.absolutePath}")
    return VendorDispatchPreparation(
      vendor = vendor,
      vendorDispatchDir = vendorDispatchDir,
      socModel = socModel,
      htpGeneration = qualcommHtpGenerationForSoc(socModel)?.label ?: "",
      dirExists = false,
      visibleSoNames = emptyList(),
      missingRequired = emptyList(),
      symlinkMode = false,
      errors = errors,
    )
  }

  val existingEntries = mutableMapOf<String, String?>()
  for (entry in vendorDispatchDir.listFiles() ?: emptyArray()) {
    val isHealthySymlink =
      runCatching {
        Files.isSymbolicLink(entry.toPath()) &&
          File(Files.readSymbolicLink(entry.toPath()).toString()).exists()
      }.getOrDefault(false)
    existingEntries[entry.name] =
      if (isHealthySymlink) Files.readSymbolicLink(entry.toPath()).toString() else null
  }

  val plan = planVendorDirSync(required, existingEntries, nativeLibraryDir)
  for (action in plan) {
    val entry = File(vendorDispatchDir, action.entryName)
    when (action.kind) {
      VendorDispatchSyncKind.REMOVE -> {
        if (entry.isDirectory) {
          errors.add("Refusing to remove non-file entry: ${entry.name}")
        } else if (!entry.delete()) {
          errors.add("Failed to remove stale entry: ${entry.name}")
        }
      }
      VendorDispatchSyncKind.CREATE, VendorDispatchSyncKind.REPAIR -> {
        val source = File(nativeLibraryDir, action.entryName)
        // A dangling symlink reports exists() == false but still occupies the entry:
        // delete() removes the link itself and is a harmless no-op when absent.
        if (entry.isDirectory) {
          errors.add("Refusing to replace non-file entry: ${action.entryName}")
          continue
        }
        if (!entry.delete() && entry.exists()) {
          errors.add("Failed to replace entry: ${action.entryName}")
          continue
        }
        try {
          Files.createSymbolicLink(entry.toPath(), source.toPath())
        } catch (t: Throwable) {
          // Symlinks are not supported on this filesystem: fall back to a copy.
          symlinkMode = false
          runCatching { source.copyTo(entry, overwrite = true) }
            .onFailure {
              errors.add(
                "Failed to expose ${action.entryName} (symlink: ${t.javaClass.simpleName}, copy: ${it.message})",
              )
            }
        }
      }
      VendorDispatchSyncKind.KEEP -> {}
    }
  }

  val visibleSoNames =
    vendorDispatchDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }
      ?.map { it.name }?.sorted() ?: emptyList()

  return VendorDispatchPreparation(
    vendor = vendor,
    vendorDispatchDir = vendorDispatchDir,
    dirExists = vendorDispatchDir.isDirectory,
    visibleSoNames = visibleSoNames,
    missingRequired = emptyList(),
    symlinkMode = symlinkMode,
    errors = errors,
    socModel = socModel,
    htpGeneration = qualcommHtpGenerationForSoc(socModel)?.label ?: "",
  )
}

/**
 * Directory passed to `Backend.NPU(nativeLibraryDir = …)` for a prepared vendor
 * runtime: the vendor-isolated dispatch directory when the preparation succeeded, the
 * installer `nativeLibraryDir` otherwise. LiteRT discovers vendor dispatch runtimes
 * inside that directory, so a failed vendor isolation must never block the NPU path.
 */
fun resolveNpuNativeLibraryDir(
  nativeLibraryDir: String?,
  preparation: VendorDispatchPreparation?,
): String =
  if (preparation != null && preparation.ok) preparation.vendorDispatchDir.absolutePath
  else nativeLibraryDir ?: ""

/**
 * Detects the device's production NPU dispatch vendor and prepares its runtime, or
 * null when the device has no production-supported NPU dispatch vendor.
 */
fun prepareVendorDispatchRuntimeForDevice(context: Context): VendorDispatchPreparation? {
  val socManufacturer = Build.SOC_MANUFACTURER ?: ""
  val socModel = Build.SOC_MODEL ?: ""
  val vendor =
    npuDispatchVendorForDevice(
      SocVendorDetector.detect(
        socManufacturer = socManufacturer,
        socModel = socModel,
      ),
    ) ?: return null
  return prepareVendorDispatchRuntime(context, vendor, socModel = socModel)
}

/**
 * Native library directory for the production NPU backend.
 *
 * Returns the vendor-isolated dispatch directory for this device's SoC when it can
 * be prepared, falling back to the installer `nativeLibraryDir` otherwise.
 */
fun npuNativeLibraryDirForDevice(context: Context): String {
  val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
  val preparation = prepareVendorDispatchRuntimeForDevice(context)
  val resolved = resolveNpuNativeLibraryDir(nativeLibraryDir, preparation)
  when {
    preparation == null ->
      Log.w(TAG, "SoC vendor not recognized for NPU dispatch isolation, using nativeLibraryDir")
    resolved != preparation.vendorDispatchDir.absolutePath ->
      Log.w(
        TAG,
        "Failed to prepare ${preparation.vendor} dispatch runtime, using nativeLibraryDir: " +
          (preparation.errors + preparation.missingRequired).joinToString("; "),
      )
  }
  return resolved
}
