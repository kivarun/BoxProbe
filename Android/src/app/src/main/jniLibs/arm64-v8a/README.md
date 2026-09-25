# Native library provenance

## Iteration-2 runtime target (runtime modernization phase)

Coordinated upgrade set (see report: litertlm 0.17.1 cannot run against the
2.1.5-era vendor binaries — the dispatch `LiteRtDispatchApi` struct grew from
48 to 56 bytes (`LiteRtAbiHeader` prepended, `get_hooks` appended) and the GPU
`LiteRtAcceleratorDef` was reshaped the same way, so old vendor binaries fill
the struct at the wrong offsets):

- `litertlm-android 0.17.1` (AAR; no longer bundles LiteRT core — self-contained JNI only)
- `com.google.ai.edge.litert:litert 2.2.0` (provides `libLiteRt.so` core of
  record for the MediaTek compiler plugin's `DT_NEEDED`, plus the new-ABI
  `libLiteRtClGlAccelerator.so` for the GPU backend)
- MediaTek dispatch: rebuilt from the LiteRT commit pinned by litertlm 0.17.1
- Qualcomm: official LiteRT v2.2.0 release-asset prebuilts + QAIRT 2.47 set

## libLiteRtDispatch_MediaTek.so

Self-contained MediaTek LiteRT dispatch runtime, rebuilt from public sources
with the same recipe as the v2.1.5-era repro (no patches). It does not link
against `libLiteRt.so` (no `DT_NEEDED` for it, no unresolved `LiteRt*`/`Neuron*`
symbols); the Neuron runtime is loaded at runtime via `dlopen`/`dlsym` from the
preinstalled system libraries. `LiteRtDispatchGetApi` now fills the 56-byte
new-ABI `LiteRtDispatchApi` struct (verified by disassembly).

- size: 559,968 bytes
- SHA256: `eb46cac73f53e278a59513ece69517d0127618734376badc9b16e2d7de2ce317`
- ELF BuildID: `599a17131c21b35c2dbb8e5ec40d7111`
- ABI: new `LiteRtDispatchApi` (sizeof == 56, `abi_header` at offset 0,
  `interface` at offset 24, `get_hooks` appended)

Build recipe (public inputs only, no patches):

- source: `google-ai-edge/LiteRT` commit `9fe5be45564c868408e6514c8aabb83e211a0911`
  (the `LITERT_REF` pinned by LiteRT-LM 0.17.1's WORKSPACE)
- NeuroPilot SDK pin from `third_party/neuro_pilot/workspace.bzl`
  (`@neuro_pilot//:v8_latest_host_headers` -> `v8_0_10` headers, no link-time
  SDK): unchanged from v2.1.5 —
  `https://s3.ap-southeast-1.amazonaws.com/mediatek.neuropilot.com/66f2c33a-2005-4f0b-afef-2053c8654e4f.gz`
  SHA256 `f69434d45856964627c750e716b835988a1f07511b6196d7f070fdde26027994`
- Android NDK r28b (28.1.13356709), target platform android-28
  (build shell env: `ANDROID_NDK_HOME`, `ANDROID_NDK_API_LEVEL=28`,
  `ANDROID_NDK_VERSION=28` — the last one is REQUIRED: without it TF's
  android_configure falls back to the legacy built-in NDK repository whose
  crosstool emits `-gcc-toolchain`, rejected by r28b clang)
- Bazel 7.7.0 (the pin's own `.bazelversion`)
- command:
  `bazel --output_user_root=<scratch> build --config=android_arm64 //litert/vendors/mediatek/dispatch:dispatch_api_so`
- the build ran in a glibc container (host is Alpine/musl; bazel 7.7.0's
  embedded JDK is glibc-only) — see `.litert-mt/docker/Dockerfile`

## libLiteRtCompilerPlugin_MediaTek.so

Upstream jniLibs library carried over from the Box source tree; loaded by the
LiteRT-LM runtime from the vendor dispatch directory only when a MediaTek
partition is JIT-compiled on device. The proven MT6991 chain uses
restore-from-compiled-network artifacts (A5), which never JIT-compile, so this
library is inert on that path. It predates the new core and still requires
`LiteRtMediatekOptions*@VERS_1.0` providers that no shipped library exports;
JIT-compiling MediaTek partitions on device was already non-functional in the
0.12.0 baseline and remains unchanged by this migration. A rebuild from the
new pin is a possible follow-up, not part of the Iteration-2 gate.

## Qualcomm SM8850 / HTP V81 production set (Iteration-2)

Single coherent release chain, no version mixing:

- `libLiteRt.so` core: Maven `com.google.ai.edge.litert:litert:2.2.0`.
- LiteRT dispatch/plugin: official prebuilts from the LiteRT release asset
  `litert_npu_runtime_libraries_jit.zip` of the **v2.2.0 release**
  (`qualcomm_runtime_v81/` folder), which pins QAIRT `2.47.0.260601` via its
  own `fetch_qualcomm_library.sh`.
- QNN libraries: QAIRT SDK **2.47.0.260601**, downloaded from the public URL
  pinned by that script (`softwarecenter.qualcomm.com`), files copied exactly
  as the script does (`libQnnHtp.so`, `libQnnSystem.so`,
  `libQnnHtpV<69|73|75|79|81>Stub.so`, `libQnnHtpV<gen>Skel.so` from
  `hexagon-v<gen>/unsigned`, plus the JIT closure files `libQnnHtpPrepare.so`,
  `libQnnIr.so`, `libQnnSaver.so`). `libQnnHtpV81CalculatorStub.so` also ships
  in QAIRT 2.47 (`aarch64-android`) and is kept for the same reason as in the
  2.44 set (verified SM8850 JIT runtime closure).

| file | from | SHA256 |
|---|---|---|
| libLiteRtDispatch_Qualcomm.so | LiteRT v2.2.0 jit zip | `c4abfff6c99ec218f545415a81a2a03a3ee3e21df2ea911902d6b7bbfeda80bf` |
| libLiteRtCompilerPlugin_Qualcomm.so | LiteRT v2.2.0 jit zip | `425e5caf007f834748c6bf67aff265d7e21512a01910f219fab6b7749ef57732` |
| libQnnSystem.so | QAIRT 2.47.0.260601 | `077a8b20a53b216d006b85b58dd754a9e958e02f98e9c79d46619db6f8edfec9` |
| libQnnHtp.so | QAIRT 2.47.0.260601 | `c0488f2df87932a42ca0a563883e6fba190896bca439ad0fdaa2428358ab5092` |
| libQnnHtpPrepare.so | QAIRT 2.47.0.260601 | `9988ce10ffee6813ffd218df308478f44dbc758cc89aed9cb98cfebb030ca72e` |
| libQnnIr.so | QAIRT 2.47.0.260601 | `79043536d4ac324782c953c9092066d3418ec88626ac2384b2e81883a3f8f644` |
| libQnnSaver.so | QAIRT 2.47.0.260601 | `d26d8002da0fbe829f58370465dc40b4e5a0fc2c2830c0608972817ac5835026` |
| libQnnHtpV81Stub.so | QAIRT 2.47.0.260601 | `479da62bd52bb7cb5791d5898442457272d5403e42244af690a137b52d67f939` |
| libQnnHtpV81CalculatorStub.so | QAIRT 2.47.0.260601 | `3567bc848ae1c9169ad38879f9f75e4ead3e959a5258e370d9c8e46e58a76176` |
| libQnnHtpV81Skel.so | QAIRT 2.47.0.260601 (hexagon-v81/unsigned) | `0b4fa7419e7265ae33d5e57f214d49cd11abbf7302149bb7ff78d9b602903899` |

The v69/v73/v75/v79 Skel/Stub/CalculatorStub files are the same QAIRT
2.47.0.260601 release (kept so the APK carries exactly one QNN release; the
SM8850 vendor directory itself only ever contains the v81 set). Both LiteRT
Qualcomm binaries are statically self-contained (no `DT_NEEDED`/`UND` LiteRT
symbols), so they cannot fail to load against the bundled core; the new
compiler plugin additionally exports the optional `LiteRtGetCompiledResultHandle`
symbol that the 0.17.x core resolves. The QNN runtime is `dlopen`-ed by name
at runtime.

## libLiteRt.so / libLiteRtClGlAccelerator.so

NOT checked in here: provided by the Maven artifact
`com.google.ai.edge.litert:litert:2.2.0` (new-ABI core of the 0.17.x era; the
GPU accelerator registers through the reshaped `LiteRtAcceleratorDef` with the
`LiteRtAbiHeader` first member). Any remaining duplicate core copies are
deduplicated via `packagingOptions.jniLibs.pickFirsts` in `app/build.gradle.kts`.
