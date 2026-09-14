package com.google.ai.edge.gallery.systeminfo

import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Verdict about the actual backend that executed the last inference.
 *
 * The verdict is derived from the LiteRT partitioning logs of the current process:
 * a transformer graph fully replaced by the vendor dispatch delegate means the NPU
 * path really executed on the accelerator, while XNNPACK on those graphs means the
 * engine silently fell back to CPU.
 */
enum class NpuDelegateVerdict {
  /** Transformer graphs fully partitioned to the vendor dispatch delegate (HTP). */
  DISPATCH_DELEGATED,

  /** No dispatch delegation; graphs ran through the CPU delegate (XNNPACK). */
  CPU_FALLBACK,

  /** No partitioning evidence found in the captured window. */
  UNKNOWN,
}

/** Parsed delegate partitioning evidence for one probe/benchmark window. */
data class NpuDelegateEvidence(
  val verdict: NpuDelegateVerdict,
  /** Raw partitioning lines that produced the verdict, newest capture first. */
  val partitionLines: List<String>,
  /** Fully-delegated subgraphs: name -> "replaced/total", e.g. "subgraph 0" -> "1/1". */
  val delegatedSubgraphs: Map<String, String>,
)

private val REPLACING_REGEX =
  Regex(
    "Replacing\\s+(\\d+)\\s+out\\s+of\\s+(\\d+)\\s+node\\(s\\)\\s+with\\s+delegate\\s+\\(([^)]+)\\)",
  )

private val SUBGRAPH_REGEX = Regex("for subgraph (\\d+)")

private const val DISPATCH_DELEGATE_NAME = "DispatchDelegate"
private val CPU_DELEGATE_NAMES = listOf("TfLiteXNNPackDelegate", "XNNPackDelegate")

/**
 * Classifies LiteRT partitioning log lines into an [NpuDelegateEvidence].
 *
 * Rule set (deliberately conservative — validation must be provable):
 *  - any delegate line that fully replaces its subgraph (`R == T`, `R > 0`) with
 *    `DispatchDelegate` → [NpuDelegateVerdict.DISPATCH_DELEGATED];
 *  - dispatch lines exist but never fully replace a subgraph (mixed partitioning)
 *    → [NpuDelegateVerdict.UNKNOWN];
 *  - CPU delegate lines only → [NpuDelegateVerdict.CPU_FALLBACK];
 *  - nothing → [NpuDelegateVerdict.UNKNOWN].
 */
fun classifyDelegateLines(lines: List<String>): NpuDelegateEvidence {
  val delegatedSubgraphs = mutableMapOf<String, String>()
  var dispatchFull = false
  var dispatchAny = false
  var cpuAny = false
  val kept = mutableListOf<String>()
  for (line in lines) {
    val match = REPLACING_REGEX.find(line) ?: continue
    val replaced = match.groupValues[1].toInt()
    val total = match.groupValues[2].toInt()
    val delegateName = match.groupValues[3]
    val subgraph = SUBGRAPH_REGEX.find(line)?.groupValues?.get(1)
    when {
      delegateName == DISPATCH_DELEGATE_NAME -> {
        dispatchAny = true
        if (replaced > 0 && replaced == total && subgraph != null) {
          dispatchFull = true
          delegatedSubgraphs.putIfAbsent("subgraph $subgraph", "$replaced/$total")
        }
        if (subgraph != null) {
          kept.add(line)
        }
      }
      CPU_DELEGATE_NAMES.any { delegateName.startsWith(it) } -> {
        cpuAny = true
        if (subgraph != null) {
          kept.add(line)
        }
      }
    }
  }
  val verdict =
    when {
      dispatchFull -> NpuDelegateVerdict.DISPATCH_DELEGATED
      dispatchAny -> NpuDelegateVerdict.UNKNOWN
      cpuAny -> NpuDelegateVerdict.CPU_FALLBACK
      else -> NpuDelegateVerdict.UNKNOWN
    }
  return NpuDelegateEvidence(
    verdict = verdict,
    partitionLines = kept.takeLast(MAX_DELEGATE_EVIDENCE_LINES),
    delegatedSubgraphs = delegatedSubgraphs.toSortedMap(),
  )
}

/** At most this many raw partitioning lines are kept as evidence. */
const val MAX_DELEGATE_EVIDENCE_LINES = 12

/** Raw log lines captured from this process within one probe/benchmark window. */
fun captureSelfLogcatLines(sinceMs: Long, maxLines: Int = 4000): List<String> {
  val pid = android.os.Process.myPid()
  val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceMs))
  val process =
    try {
      Runtime.getRuntime()
        .exec(arrayOf("logcat", "-d", "-v", "raw", "-T", since, "--pid=$pid"))
    } catch (t: Throwable) {
      return emptyList()
    }
  val lines = mutableListOf<String>()
  try {
    process.inputStream.bufferedReader().use { reader ->
      while (lines.size < maxLines) {
        val line = reader.readLine() ?: break
        if (line.isNotBlank()) {
          lines.add(line)
        }
      }
    }
  } catch (t: Throwable) {
    // A failed capture yields no evidence rather than a wrong verdict.
  } finally {
    process.destroy()
  }
  return lines
}

/** Human-readable verdict label for diagnostics/UI. */
fun npuDelegateVerdictLabel(verdict: NpuDelegateVerdict): String =
  when (verdict) {
    NpuDelegateVerdict.DISPATCH_DELEGATED -> "DispatchDelegate (HTP)"
    NpuDelegateVerdict.CPU_FALLBACK -> "XNNPACK (CPU fallback)"
    NpuDelegateVerdict.UNKNOWN -> "UNKNOWN"
  }

/** "delegated ops: subgraph 0: 1/1; subgraph 1: 1/1" or null when not measurable. */
fun npuDelegateOpsLabel(evidence: NpuDelegateEvidence?): String? =
  evidence?.delegatedSubgraphs
    ?.takeIf { it.isNotEmpty() }
    ?.entries
    ?.joinToString("; ") { "${it.key}: ${it.value}" }
