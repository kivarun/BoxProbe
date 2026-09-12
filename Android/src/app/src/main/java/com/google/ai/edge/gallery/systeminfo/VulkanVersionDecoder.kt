package com.google.ai.edge.gallery.systeminfo

/**
 * Decodes Vulkan version/level feature payloads using the `VK_MAKE_API_VERSION` layout:
 *
 * ```
 * variant = version >> 29
 * major   = (version >> 22) & 0x7F
 * minor   = (version >> 12) & 0x3FF
 * patch   = version & 0xFFF
 * ```
 */
object VulkanVersionDecoder {

  fun decodeVersion(encoded: Long): String {
    val variant = (encoded shr 29) and 0x7L
    val major = (encoded shr 22) and 0x7FL
    val minor = (encoded shr 12) and 0x3FFL
    val patch = encoded and 0xFFFL
    return buildString {
      append("variant ").append(variant)
      append(", Vulkan ").append(major).append('.').append(minor).append('.').append(patch)
    }
  }

  fun decodeLevel(level: Long): String =
    when (level) {
      0L -> "0 (no Vulkan requirements)"
      1L -> "1 (Vulkan 1.x baseline)"
      20150324L -> "20150324 (compute)"
      else -> "raw $level"
    }

  fun toHex(value: Long): String = "0x%08X".format(value)
}
