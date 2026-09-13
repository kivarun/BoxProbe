package com.google.ai.edge.gallery.systeminfo

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.file.Files

private const val TAG = "VendorDispatchRuntime"

/**
 * Vendor-isolated LiteRT dispatch runtime.
 *
 * LiteRT resolves vendor dispatch libraries relative to `nativeLibraryDir`. When the
 * plain app `nativeLibraryDir` contains dispatch runtimes of several vendors, LiteRT
 * may pick an unrelated one. To make the NPU path deterministic, the probe points
 * `Backend.NPU(nativeLibraryDir = …)` at an app-private directory that exposes
 * symlinks to only the required vendor libraries extracted into
 * `applicationInfo.nativeLibraryDir`.
 */

/** Vendors with distinct LiteRT dispatch runtimes. */
enum class NpuDispatchVendor(val dirName: String, val label: String) {
  MEDIATEK("mediatek", "MediaTek"),
  QUALCOMM("qualcomm", "Qualcomm"),
  GOOGLE_TENSOR("google_tensor", "Google Tensor"),
}

/**
 * Non-system libraries required by LiteRT to initialize the vendor NPU dispatch path.
 *
 * Determined from the actual DT_NEEDED requirements of the bundled vendor libraries.
 * Implementation is deliberately minimal: only MediaTek is wired for now.
 */
fun npuRequiredLibNames(vendor: NpuDispatchVendor): List<String> =
  when (vendor) {
    NpuDispatchVendor.MEDIATEK ->
      listOf("libLiteRtDispatch_MediaTek.so", "libLiteRtCompilerPlugin_MediaTek.so")
    NpuDispatchVendor.QUALCOMM -> emptyList()
    NpuDispatchVendor.GOOGLE_TENSOR -> emptyList()
  }

/** Maps the existing device vendor classification to an NPU dispatch vendor, or null. */
fun npuDispatchVendorForDevice(socVendor: SocVendor): NpuDispatchVendor? =
  when (socVendor) {
    SocVendor.MEDIATEK -> NpuDispatchVendor.MEDIATEK
    SocVendor.QUALCOMM -> NpuDispatchVendor.QUALCOMM
    SocVendor.GOOGLE_TENSOR -> NpuDispatchVendor.GOOGLE_TENSOR
    SocVendor.UNKNOWN -> null
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
): VendorDispatchPreparation {  val vendorDispatchDir = File(context.filesDir, "runtime-dispatch/${vendor.dirName}")

  val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir ?: ""
  if (nativeLibraryDir.isEmpty()) {
    return VendorDispatchPreparation(
      vendor = vendor,
      vendorDispatchDir = vendorDispatchDir,
      dirExists = false,
      visibleSoNames = emptyList(),
      missingRequired = emptyList(),
      symlinkMode = false,
      errors = listOf("applicationInfo.nativeLibraryDir is null or empty"),
    )
  }

  val required = npuRequiredLibNames(vendor)
  if (required.isEmpty()) {
    return VendorDispatchPreparation(
      vendor = vendor,
      vendorDispatchDir = vendorDispatchDir,
      dirExists = false,
      visibleSoNames = emptyList(),
      missingRequired = emptyList(),
      symlinkMode = false,
      errors = listOf("${vendor.label} NPU dispatch isolation is not implemented yet"),
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
        if (entry.exists() && !entry.isDirectory) {
          if (!entry.delete()) {
            errors.add("Failed to replace entry: ${action.entryName}")
            continue
          }
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
    visibleSoNames = visibleSoNames.take(NpuRuntimeProbe.MAX_LISTED_SO_NAMES),
    missingRequired = emptyList(),
    symlinkMode = symlinkMode,
    errors = errors,
  )
}

/**
 * Native library directory for the production NPU backend.
 *
 * Returns the vendor-isolated dispatch directory for this device's SoC when it can
 * be prepared, falling back to the installer `nativeLibraryDir` otherwise. LiteRT
 * discovers vendor dispatch runtimes inside the directory passed via
 * `Backend.NPU(nativeLibraryDir = …)`, so a failed vendor isolation must never
 * block the NPU path.
 */
fun npuNativeLibraryDirForDevice(context: Context): String {
  val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
  val vendor =
    npuDispatchVendorForDevice(
      SocVendorDetector.detect(
        socManufacturer = Build.SOC_MANUFACTURER ?: "",
        socModel = Build.SOC_MODEL ?: "",
      ),
    )
  if (vendor == null) {
    Log.w(TAG, "SoC vendor not recognized for NPU dispatch isolation, using nativeLibraryDir")
    return nativeLibraryDir
  }
  val preparation = prepareVendorDispatchRuntime(context, vendor)
  if (!preparation.ok) {
    Log.w(
      TAG,
      "Failed to prepare $vendor dispatch runtime, using nativeLibraryDir: " +
        (preparation.errors + preparation.missingRequired).joinToString("; "),
    )
    return nativeLibraryDir
  }
  return preparation.vendorDispatchDir.absolutePath
}
