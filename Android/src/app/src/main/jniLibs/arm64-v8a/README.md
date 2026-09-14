# Native library provenance

## libLiteRtDispatch_MediaTek.so

Self-contained MediaTek LiteRT dispatch runtime, byte-for-byte reproduced from
public sources. It does not link against `libLiteRt.so` (no `DT_NEEDED` for it,
no unresolved `LiteRt*`/`Neuron*` symbols); the Neuron runtime is loaded at
runtime via `dlopen`/`dlsym` from the preinstalled system libraries.

- size: 559,832 bytes
- SHA256: `d62fb58d7d0140524d7a72b8f5633201d86de63a1be57ff8f2d7eac4c69be53c`
- ELF BuildID: `4e360bbc68860a9e9e9b7c0d932c4c6e`
- identical to the library shipped in Box 3.4.5 (`lib/arm64-v8a/libLiteRtDispatch_MediaTek.so`)

Build recipe (public inputs only, no patches):

- source: `google-ai-edge/LiteRT` tag `v2.1.5`
  (`9d26e89d88ef8785b6a1e54ec41ac8add215a125`)
- NeuroPilot SDK pin from
  `third_party/neuro_pilot/workspace.bzl`
  (`@neuro_pilot//:v8_latest_host_headers` -> `v8_0_10` headers, no link-time SDK):
  `https://s3.ap-southeast-1.amazonaws.com/mediatek.neuropilot.com/66f2c33a-2005-4f0b-afef-2053c8654e4f.gz`
  SHA256 `f69434d45856964627c750e716b835988a1f07511b6196d7f070fdde26027994`
- Android NDK r28b (28.1.13356709), target platform android-28
- Bazel 7.7.0
- command:
  `bazel --output_user_root=<scratch> build --config=android_arm64 //litert/vendors/mediatek/dispatch:dispatch_api_so`
  plus a `configure`-style `.litert_configure.bazelrc` with
  `ANDROID_NDK_HOME` / `ANDROID_NDK_API_LEVEL=28` action environments

The `libLiteRt.so` / `libLiteRtClGlAccelerator.so` runtime core is NOT checked in
here: it is provided by the Maven artifact
`com.google.ai.edge.litert:litert:2.1.5` (the same build that ships in Box 3.4.5),
and both LiteRT artifacts' bundled core copies are deduplicated via
`packagingOptions.jniLibs.pickFirsts` in `app/build.gradle.kts`.

## libLiteRtCompilerPlugin_MediaTek.so

Upstream jniLibs library carried over from the Box source tree; loaded by the
LiteRT-LM runtime from the vendor dispatch directory to compile NPU partitions.

## Qualcomm SM8850 / HTP V81 production set

Single coherent release chain, no version mixing:

- `libLiteRt.so` core: Maven `com.google.ai.edge.litert:litert:2.1.5` (byte-identical
  to the core shipped in Box 3.4.5).
- LiteRT dispatch/plugin: official prebuilts from the LiteRT release asset
  `litert_npu_runtime_libraries_jit.zip` of the **same release** (`v2.1.5`,
  `qualcomm_runtime_v81/` folder), which pins QAIRT `2.44.0.260225` via its own
  `fetch_qualcomm_library.sh`.
- QNN libraries: QAIRT SDK **2.44.0.260225**, downloaded from the public URL pinned by
  that script (`softwarecenter.qualcomm.com`), files copied exactly as the script does.

The v81 module is the upstream LiteRT mapping for `Qualcomm_SM8850` (the dynamic-feature
manifest inside the zip names that device group), and QAIRT 2.44's
`libQnnHtpV81Skel.so` lists `SM8850` in its supported-SoC table.

| file | from | size (B) | SHA256 |
|---|---|---:|---|
| libLiteRtDispatch_Qualcomm.so | LiteRT v2.1.5 jit zip | 462,528 | `f8ee14eb9cad99a1fc8522478d1db6712417dffeced287e180a0f84d17b6acfb` |
| libLiteRtCompilerPlugin_Qualcomm.so | LiteRT v2.1.5 jit zip | 691,744 | `c7fe5ee3ac5b89b9e903989a90d1584158b3db889f6c85330495b338951f735d` |
| libQnnSystem.so | QAIRT 2.44.0.260225 | 2,983,560 | `7e69258e1278cc9b2bb62dbc6e2a52c227a100d6505a13fd6324a87993d0bba8` |
| libQnnHtp.so | QAIRT 2.44.0.260225 | 2,778,176 | `090e993822564851eab1405aff171643b21e644e3f696c95c96f2732aaed813a` |
| libQnnHtpPrepare.so | QAIRT 2.44.0.260225 | 85,539,184 | `09b1c15c62b6875af49ffd3d841961c098b85c367f584fee370f986c62511298` |
| libQnnIr.so | QAIRT 2.44.0.260225 | 1,741,288 | `982d7e403eec3de800219bf8de7039e7fa020749618e0505f831fcecfd2bd85d` |
| libQnnSaver.so | QAIRT 2.44.0.260225 | 788,048 | `5dbe2eb7f17c217d035ce288b75b6cc9445df551739dba787ccb55097af48b0e` |
| libQnnHtpV81Stub.so | QAIRT 2.44.0.260225 | 755,464 | `f85cc467bda23b8253f085c4fa2680a1428e5b94c6c5a3e572abaa20c5b13ba1` |
| libQnnHtpV81CalculatorStub.so | QAIRT 2.44.0.260225 | 250,712 | `d8e1b289614e6285dafb460949e7a318c74ba656875dd8f1d4a1b80b31078aad` |
| libQnnHtpV81Skel.so | QAIRT 2.44.0.260225 (hexagon-v81/unsigned) | 11,797,220 | `66719325303f22562679edfed509d540cbff53ac59127b6e4ead84b62f1dc655` |

The v69/v73/v75/v79 Skel/Stub files are the same QAIRT 2.44.0.260225 release (kept so the
APK carries exactly one QNN release; the SM8850 vendor directory itself only ever
contains the v81 set). Both LiteRT Qualcomm binaries are statically self-contained
(no `DT_NEEDED`/`UND` LiteRT symbols), so they cannot fail to load against the bundled
core; the QNN runtime is `dlopen`-ed by name at runtime. JIT compile additionally
requires `libQnnIr.so` + `libQnnSaver.so` (referenced by both the dispatch and the
compiler plugin); `libQnnHtpV81CalculatorStub.so` carries the same `AISW_VERSION:
2.44.0` stamp and ELF release family as the rest of the QNN set and is part of the
verified working SM8850 JIT runtime closure (present in the reference working set),
so it ships with the production required set.
