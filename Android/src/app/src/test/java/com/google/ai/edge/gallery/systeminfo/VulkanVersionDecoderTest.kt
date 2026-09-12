package com.google.ai.edge.gallery.systeminfo

import org.junit.Assert.assertEquals
import org.junit.Test

class VulkanVersionDecoderTest {

  @Test
  fun uatRegression_0x00403000_decodesToVulkan1_3_0() {
    val decoded = VulkanVersionDecoder.decodeVersion(0x00403000L)
    // variant 0, Vulkan 1.3.0 (was previously displayed as 1.1027.0)
    assertEquals("variant 0, Vulkan 1.3.0", decoded)
  }

  @Test
  fun knownVersions_decodeCorrectly() {
    assertEquals("variant 0, Vulkan 1.1.0", VulkanVersionDecoder.decodeVersion(0x00401000L))
    assertEquals("variant 0, Vulkan 1.2.3", VulkanVersionDecoder.decodeVersion(0x00402003L))
  }

  @Test
  fun levels_decodeCorrectly() {
    assertEquals("0 (no Vulkan requirements)", VulkanVersionDecoder.decodeLevel(0L))
    assertEquals("1 (Vulkan 1.x baseline)", VulkanVersionDecoder.decodeLevel(1L))
    assertEquals("20150324 (compute)", VulkanVersionDecoder.decodeLevel(20150324L))
  }

  @Test
  fun toHex_formatsPayload() {
    assertEquals("0x00403000", VulkanVersionDecoder.toHex(0x00403000L))
  }
}
