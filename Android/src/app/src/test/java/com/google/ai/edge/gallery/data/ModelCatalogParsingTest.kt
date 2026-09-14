package com.google.ai.edge.gallery.data

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses the version-controlled BoxProbe model catalog shipped in
 * `src/main/assets/model_catalog.json` and verifies the curated artifacts exactly.
 */
class ModelCatalogParsingTest {

  private val gson = Gson()

  private fun loadCatalog(): ModelAllowlist {
    val file = File("src/main/assets/model_catalog.json")
    assertTrue("catalog asset must exist: ${file.absolutePath}", file.exists())
    return gson.fromJson(file.readText(), ModelAllowlist::class.java)
  }

  @Test
  fun catalog_containsExactlyTheFourCuratedEntries() {
    val models = loadCatalog().models
    assertEquals(4, models.size)
    assertEquals(
      setOf(
        "Qwen2.5-1.5B-Instruct",
        "Gemma3-1B-IT",
        "Gemma3-1B-IT-MT6991-NPU",
        "Gemma3-270M-IT-SM8850-NPU",
      ),
      models.map { it.name }.toSet(),
    )
  }

  @Test
  fun qwen_generic_artifactFileNameExact() {
    val qwen = loadCatalog().models.first { it.name == "Qwen2.5-1.5B-Instruct" }
    assertEquals("litert-community/Qwen2.5-1.5B-Instruct", qwen.modelId)
    assertEquals(
      "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
      qwen.modelFile,
    )
    // Existing downloaded Qwen keeps its storage identity.
    assertEquals("19edb84c69a0212f29a6ef17ba0d6f278b6a1614", qwen.commitHash)
    assertEquals(1597931520L, qwen.sizeInBytes)
    val model = qwen.toModel()
    assertEquals(listOf(Accelerator.GPU, Accelerator.CPU), model.accelerators)
    assertNull(model.targetSoc)
  }

  @Test
  fun gemma_generic_artifactFileNameExact() {
    val gemma = loadCatalog().models.first { it.name == "Gemma3-1B-IT" }
    assertEquals("litert-community/Gemma3-1B-IT", gemma.modelId)
    assertEquals(
      "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
      gemma.modelFile,
    )
    assertEquals("a6306a4e292016480083b73b8dc6f3f939ae04c3", gemma.commitHash)
    assertEquals(584417280L, gemma.sizeInBytes)
    val model = gemma.toModel()
    assertEquals(listOf(Accelerator.GPU, Accelerator.CPU), model.accelerators)
    assertNull(model.targetSoc)
  }

  @Test
  fun gemmaMt6991_artifactFileNameExact_andNpuTarget() {
    val mt6991 = loadCatalog().models.first { it.name == "Gemma3-1B-IT-MT6991-NPU" }
    assertEquals("litert-community/Gemma3-1B-IT", mt6991.modelId)
    assertEquals("Gemma3-1B-IT_q4_ekv1280_mt6991.litertlm", mt6991.modelFile)
    assertEquals("mt6991", mt6991.targetSoc)
    assertEquals(1033814016L, mt6991.sizeInBytes)
    val model = mt6991.toModel()
    assertEquals(listOf(Accelerator.NPU), model.accelerators)
    assertEquals("mt6991", model.targetSoc)
    assertEquals("Gemma3-1B-IT — MT6991 NPU", model.displayName)
  }

  @Test
  fun distinctIdentities_genericAndSocSpecificAreDifferentModels() {
    val models = loadCatalog().models
    val generic = models.first { it.name == "Gemma3-1B-IT" }.toModel()
    val mt6991 = models.first { it.name == "Gemma3-1B-IT-MT6991-NPU" }.toModel()
    assertTrue(generic.name != mt6991.name)
    assertTrue(generic.downloadFileName != mt6991.downloadFileName)
  }

  @Test
  fun compatibility_matchesOnlyForGenericOrTargetSocDevice() {
    val catalog = loadCatalog().models.associateBy { it.name }.mapValues { it.value.toModel() }
    val deviceSoc = "mt6991"
    assertTrue(artifactMatchesDeviceSoc(catalog.getValue("Qwen2.5-1.5B-Instruct"), deviceSoc))
    assertTrue(artifactMatchesDeviceSoc(catalog.getValue("Gemma3-1B-IT"), deviceSoc))
    assertTrue(artifactMatchesDeviceSoc(catalog.getValue("Gemma3-1B-IT-MT6991-NPU"), deviceSoc))
    assertFalse(artifactMatchesDeviceSoc(catalog.getValue("Gemma3-1B-IT-MT6991-NPU"), "sm8650"))
  }

  @Test
  fun gemmaSm8850_artifactFileNameExact_andNpuTarget() {
    val sm8850 = loadCatalog().models.first { it.name == "Gemma3-270M-IT-SM8850-NPU" }
    assertEquals("mlboydaisuke/gemma-3-270m-it-NPU-LiteRT", sm8850.modelId)
    assertEquals("gemma-3-270m-it_npu-srq_c896.litertlm", sm8850.modelFile)
    assertEquals("sm8850", sm8850.targetSoc)
    assertEquals("46cc76a8a2adaa41e0a441ad44ee57cd7a9c6754", sm8850.commitHash)
    assertEquals(456853286L, sm8850.sizeInBytes)
    val model = sm8850.toModel()
    assertEquals(listOf(Accelerator.NPU), model.accelerators)
    assertEquals("sm8850", model.targetSoc)
    assertEquals("Gemma3-270M-IT — SM8850 NPU", model.displayName)
  }

  @Test
  fun compatibility_sm8850Model_matchesOnlySm8850Devices() {
    val sm8850Model =
      loadCatalog().models.first { it.name == "Gemma3-270M-IT-SM8850-NPU" }.toModel()
    assertTrue(artifactMatchesDeviceSoc(sm8850Model, "sm8850"))
    assertTrue(artifactMatchesDeviceSoc(sm8850Model, "SM8850"))
    // Other SoCs (including MediaTek and unknown Qualcomm) must not see it as compatible.
    assertFalse(artifactMatchesDeviceSoc(sm8850Model, "mt6991"))
    assertFalse(artifactMatchesDeviceSoc(sm8850Model, "sm8750"))
    assertFalse(artifactMatchesDeviceSoc(sm8850Model, "sm8851"))
  }
}
