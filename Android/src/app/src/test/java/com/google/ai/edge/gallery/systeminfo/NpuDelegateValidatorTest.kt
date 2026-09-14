package com.google.ai.edge.gallery.systeminfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the delegate verdict classifier: the probe/benchmark "NPU validated"
 * claim may only be made when the partitioning logs prove DispatchDelegate took
 * the transformer graphs.
 */
class NpuDelegateValidatorTest {

  @Test
  fun fullDispatchDelegation_isValidated() {
    val lines =
      listOf(
        "I tflite: Replacing 1 out of 1 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 0.",
        "I tflite: Replacing 1 out of 1 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 1.",
      )
    val evidence = classifyDelegateLines(lines)
    assertEquals(NpuDelegateVerdict.DISPATCH_DELEGATED, evidence.verdict)
    assertEquals(mapOf("subgraph 0" to "1/1", "subgraph 1" to "1/1"), evidence.delegatedSubgraphs)
  }

  @Test
  fun largeGraphOpCounts_recorded() {
    val lines =
      listOf(
        "I tflite: Replacing 888 out of 888 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 0.",
        "I tflite: Replacing 869 out of 869 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 1.",
      )
    val evidence = classifyDelegateLines(lines)
    assertEquals(NpuDelegateVerdict.DISPATCH_DELEGATED, evidence.verdict)
    assertEquals(mapOf("subgraph 0" to "888/888", "subgraph 1" to "869/869"), evidence.delegatedSubgraphs)
  }

  @Test
  fun xnnpackOnly_isCpuFallback() {
    val lines =
      listOf(
        "I tflite: Created TensorFlow Lite XNNPACK delegate for CPU.",
        "I tflite: Replacing 12 out of 24 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 3 partitions for subgraph 2.",
      )
    val evidence = classifyDelegateLines(lines)
    assertEquals(NpuDelegateVerdict.CPU_FALLBACK, evidence.verdict)
  }

  @Test
  fun partialDispatch_neverValidates() {
    val lines =
      listOf(
        "I tflite: Replacing 400 out of 888 node(s) with delegate (DispatchDelegate) node, yielding 2 partitions for subgraph 0.",
      )
    val evidence = classifyDelegateLines(lines)
    assertEquals(NpuDelegateVerdict.UNKNOWN, evidence.verdict)
    assertTrue(evidence.delegatedSubgraphs.isEmpty())
  }

  @Test
  fun noEvidence_isUnknown() {
    assertEquals(NpuDelegateVerdict.UNKNOWN, classifyDelegateLines(emptyList()).verdict)
    assertEquals(
      NpuDelegateVerdict.UNKNOWN,
      classifyDelegateLines(listOf("I native: some unrelated line")).verdict,
    )
  }

  @Test
  fun auxSubgraphXnnpack_alongsideFullDispatch_stillValidated() {
    // Aux/other graphs legitimately run on XNNPACK next to a fully dispatched model.
    val lines =
      listOf(
        "I tflite: Replacing 1 out of 1 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 0.",
        "I tflite: Replacing 12 out of 24 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 3 partitions for subgraph 2.",
      )
    val evidence = classifyDelegateLines(lines)
    assertEquals(NpuDelegateVerdict.DISPATCH_DELEGATED, evidence.verdict)
  }

  @Test
  fun verdictLabels() {
    assertEquals("DispatchDelegate (HTP)", npuDelegateVerdictLabel(NpuDelegateVerdict.DISPATCH_DELEGATED))
    assertEquals("XNNPACK (CPU fallback)", npuDelegateVerdictLabel(NpuDelegateVerdict.CPU_FALLBACK))
    assertEquals("UNKNOWN", npuDelegateVerdictLabel(NpuDelegateVerdict.UNKNOWN))
  }

  @Test
  fun opsLabel_formatting() {
    val evidence =
      classifyDelegateLines(
        listOf(
          "I tflite: Replacing 888 out of 888 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 0.",
        ),
      )
    assertEquals("subgraph 0: 888/888", npuDelegateOpsLabel(evidence))
    assertEquals(null, npuDelegateOpsLabel(null))
  }
}
