package com.google.ai.edge.gallery.ui.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** LazyColumn item index of a newly imported model card (promo + built-ins + label + imported). */
class ImportedModelListIndexTest {

  @Test
  fun index_firstImportedModel() {
    assertEquals(0 + 2 + 0, importedModelListItemIndex(builtInCount = 0, importedIndex = 0))
  }

  @Test
  fun index_afterBuiltInsAndLabel() {
    // 1 promo + 5 built-ins, then the imported label at index 6, first imported at 7.
    assertEquals(7, importedModelListItemIndex(builtInCount = 5, importedIndex = 0))
    // Second imported card follows the first.
    assertEquals(8, importedModelListItemIndex(builtInCount = 5, importedIndex = 1))
  }

  @Test
  fun index_negativeImportedIndexRejected() {
    assertNull(importedModelListItemIndex(builtInCount = 5, importedIndex = -1))
  }
}
