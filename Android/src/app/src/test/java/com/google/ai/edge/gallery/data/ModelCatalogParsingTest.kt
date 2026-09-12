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
  fun catalog_containsExactlyTheThreeCuratedEntries() {
    val models = loadCatalog().models
    assertEquals(3, models.size)
    assertEquals(
      setOf(
        "Qwen2.5-1.5B-Instruct",
        "Gemma3-1B-IT",
        "Gemma3-1B-IT-MT6991-NPU",
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
}
