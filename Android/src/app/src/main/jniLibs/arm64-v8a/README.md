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
