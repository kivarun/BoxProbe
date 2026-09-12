// Minimal diagnostic-only JNI bridge for the NPU probe.
//
// Purpose: after `System.load` of libLiteRt.so and libLiteRtDispatch_MediaTek.so,
// call `LiteRtDispatchGetApi` in the already-loaded MediaTek dispatch library
// via `dlopen`/`dlsym` and report ONLY diagnostic facts (API version, which
// interface pointers are non-null). It must NOT link against the dispatch
// library, must NOT call `initialize`, `LiteRtDispatchInitialize`, or anything
// Neuron-related, and must NOT unload the library (the core/dispatch pair is
// kept loaded for later diagnostic stages).
//
// The dispatch API struct layout mirrors upstream at LiteRT revision
// 0b1b17fab0fea4c19006f12fc74f53c1d5c6dff7
// (litert/vendors/c/litert_dispatch_api.h):
//   typedef struct LiteRtApiVersion { int major, minor, patch; } LiteRtApiVersion;
//   typedef struct LiteRtDispatchApi {
//     LiteRtApiVersion version;
//     LiteRtDispatchInterface* interface;
//     LiteRtDispatchAsyncInterface* async_interface;
//     LiteRtDispatchGraphInterface* graph_interface;
//   } LiteRtDispatchApi;
// Fields are kept as void* on purpose: only null-ness is inspected, never
// dereferenced, so the bridge stays API-layout minimal and diagnostic-only.

#include <dlfcn.h>
#include <jni.h>
#include <android/log.h>
#include <string>

namespace {

constexpr const char* kTag = "NpuDispatchDiag";

typedef struct {
  int major;
  int minor;
  int patch;
} NpdApiVersion;

typedef struct {
  NpdApiVersion version;
  void* interface;
  void* async_interface;
  void* graph_interface;
} NpdDispatchApi;

typedef int (*NpdGetApiFn)(NpdDispatchApi* api);

// Upstream C API signatures (LiteRT revision 0b1b17f):
//   litert/c/litert_environment.h, litert_options.h, litert_common.h,
//   litert/vendors/c/litert_dispatch_api.h.
// Opaque handles are carried as void*; only the diagnostic facts are read.
typedef void* NpdOpaque;
typedef int (*NpdCreateEnvironmentFn)(int num_options, const void* options,
                                      NpdOpaque* environment);
typedef void (*NpdDestroyEnvironmentFn)(NpdOpaque environment);
typedef int (*NpdCreateOptionsFn)(NpdOpaque* options);
typedef void (*NpdDestroyOptionsFn)(NpdOpaque options);
typedef int (*NpdDispatchInitializeFn)(NpdOpaque environment, NpdOpaque options);
typedef const char* (*NpdGetStatusStringFn)(int status);

// Layout mirror of LiteRtEnvOption { LiteRtEnvOptionTag tag; LiteRtAny value; }
// (24 bytes: 4B tag + 4B pad + 16B LiteRtAny) and LiteRtAny
// { LiteRtAnyType type; union {...}; } (16 bytes: 4B type + 4B pad + 8B slot).
// Only kLiteRtAnyTypeString (= 8) is constructed here.
typedef struct {
  int32_t type;
  int32_t pad;
  union {
    const char* str_value;
    const void* ptr_value;
    int64_t int_value;
  } value;
} NpdAny;

typedef struct {
  int32_t tag;
  int32_t pad;
  NpdAny value;
} NpdEnvOption;

std::string jstringToStd(JNIEnv* env, jstring s) {
  if (s == nullptr) return "";
  const char* chars = env->GetStringUTFChars(s, nullptr);
  std::string out = chars == nullptr ? "" : chars;
  if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
  return out;
}

std::string escapeJson(const std::string& in) {
  std::string out;
  for (char c : in) {
    if (c == '"' || c == '\\') {
      out.push_back('\\');
    }
    if (static_cast<unsigned char>(c) < 0x20) {
      continue;
    }
    out.push_back(c);
  }
  return out;
}

std::string handshakeJson(const std::string& libraryPath) {
  void* handle = dlopen(libraryPath.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (handle == nullptr) {
    const char* err = dlerror();
    std::string msg = err == nullptr ? "dlopen failed" : err;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen failed: %s", msg.c_str());
    return std::string("{\"status\":\"ERROR\",\"error\":\"") + escapeJson(msg) + "\"}";
  }

  void* sym = dlsym(handle, "LiteRtDispatchGetApi");
  if (sym == nullptr) {
    const char* err = dlerror();
    std::string msg = err == nullptr ? "dlsym LiteRtDispatchGetApi failed" : err;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "dlsym failed: %s", msg.c_str());
    return std::string("{\"status\":\"ERROR\",\"error\":\"") + escapeJson(msg) + "\"}";
  }

  NpdGetApiFn getApi = reinterpret_cast<NpdGetApiFn>(sym);
  NpdDispatchApi api = {};
  int status = getApi(&api);
  __android_log_print(
      ANDROID_LOG_INFO, kTag, "GetApi status=%d version=%d.%d.%d interface=%d async=%d graph=%d",
      status, api.version.major, api.version.minor, api.version.patch,
      api.interface != nullptr, api.async_interface != nullptr, api.graph_interface != nullptr);

  // Diagnostic-only: do not dlclose; the probe keeps the library loaded.

  std::string json = "{\"status\":\"";
  json += status == 0 ? "OK" : "ERROR";
  json += "\",\"major\":" + std::to_string(api.version.major);
  json += ",\"minor\":" + std::to_string(api.version.minor);
  json += ",\"patch\":" + std::to_string(api.version.patch);
  json += ",\"interface\":";
  json += api.interface != nullptr ? "true" : "false";
  json += ",\"async\":";
  json += api.async_interface != nullptr ? "true" : "false";
  json += ",\"graph\":";
  json += api.graph_interface != nullptr ? "true" : "false";
  json += ",\"errorCode\":" + std::to_string(status);
  if (status != 0) {
    json += ",\"error\":\"GetApi returned status " + std::to_string(status) + "\"";
  }
  json += "}";
  return json;
}

}  // namespace

// ---------------------------------------------------------------------------
// DISPATCH_INITIALIZE stage (diagnostic-only).
//
// Resolve the required C APIs via dlopen/dlsym (no static linkage):
//   from libLiteRt.so:
//     LiteRtCreateEnvironment(num_options, const LiteRtEnvOption*,
//                             LiteRtEnvironment*)
//     LiteRtCreateOptions(LiteRtOptions*)
//     LiteRtGetStatusString(LiteRtStatus)
//   from libLiteRtDispatch_MediaTek.so:
//     LiteRtDispatchInitialize(LiteRtEnvironment, LiteRtOptions)
//
// Environment carries exactly one option:
//   tag = kLiteRtEnvOptionTagDispatchLibraryDir (1), type = kLiteRtAnyTypeString
//   (8), value = installer-managed nativeLibraryDir.
// LiteRtOptions stays empty (no MediaTek opaque options).
//
// IMPORTANT (source contract, exact revision 0b1b17f): MediaTek
// `LiteRtInitialize` stores `static_options = options` and
// `static_environment_options = environment_options` (which retain pointers to
// strings owned by the environment), plus `static_neuron_adapter`. Therefore
// the environment/options are deliberately NOT destroyed after the call:
// destroying them would leave the dispatch holding dangling references, and
// later diagnostic stages will reuse these handles.
// ---------------------------------------------------------------------------

namespace {

void* npdOpen(const char* path) {
  void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (handle == nullptr) {
    const char* err = dlerror();
    __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen %s failed: %s", path,
                        err == nullptr ? "?" : err);
  }
  return handle;
}

void* npdSym(void* handle, const char* name, std::string& error) {
  void* sym = dlsym(handle, name);
  if (sym == nullptr) {
    const char* err = dlerror();
    error = std::string("dlsym ") + name + " failed: " + (err == nullptr ? "?" : err);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", error.c_str());
  }
  return sym;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_systeminfo_NpuDispatchHandshakeBridge_handshakeNative(
    JNIEnv* env, jclass /*clazz*/, jstring libraryPath) {
  std::string json = handshakeJson(jstringToStd(env, libraryPath));
  return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_systeminfo_NpuDispatchHandshakeBridge_initializeDispatchNative(
    JNIEnv* env, jclass /*clazz*/, jstring coreLibraryPath, jstring dispatchLibraryPath,
    jstring nativeLibraryDir) {
  std::string corePath = jstringToStd(env, coreLibraryPath);
  std::string dispatchPath = jstringToStd(env, dispatchLibraryPath);
  std::string libraryDir = jstringToStd(env, nativeLibraryDir);

  std::string error;
  void* core = npdOpen(corePath.c_str());
  if (core == nullptr) {
    error = "core dlopen failed";
  }
  void* dispatch = core == nullptr ? nullptr : npdOpen(dispatchPath.c_str());
  if (dispatch == nullptr && error.empty()) {
    error = "dispatch dlopen failed";
  }

  NpdCreateEnvironmentFn createEnvironment = nullptr;
  NpdCreateOptionsFn createOptions = nullptr;
  NpdGetStatusStringFn getStatusString = nullptr;
  NpdDispatchInitializeFn dispatchInitialize = nullptr;
  std::string optionalError;
  if (error.empty()) {
    createEnvironment = reinterpret_cast<NpdCreateEnvironmentFn>(
        npdSym(core, "LiteRtCreateEnvironment", error));
  }
  if (error.empty()) {
    // LiteRtCreateOptions / LiteRtGetStatusString are compiler-side APIs and
    // are NOT exported by the runtime libLiteRt.so. Their absence is not an
    // error: an empty options list is represented as nullptr (the MediaTek
    // initialize at revision 0b1b17f treats nullptr options and empty opaque
    // options identically, and only assigns static_options without reading it),
    // and the numeric status is reported without a string mapping.
    createOptions =
        reinterpret_cast<NpdCreateOptionsFn>(npdSym(core, "LiteRtCreateOptions", optionalError));
    optionalError.clear();
  }
  if (error.empty()) {
    getStatusString = reinterpret_cast<NpdGetStatusStringFn>(
        npdSym(core, "LiteRtGetStatusString", optionalError));
    optionalError.clear();
  }
  if (error.empty()) {
    dispatchInitialize = reinterpret_cast<NpdDispatchInitializeFn>(
        npdSym(dispatch, "LiteRtDispatchInitialize", error));
  }

  NpdOpaque environment = nullptr;
  NpdOpaque options = nullptr;
  bool optionsCreated = false;
  int initStatus = -1;
  if (error.empty()) {
    NpdEnvOption envOption = {};
    envOption.tag = 1;          // kLiteRtEnvOptionTagDispatchLibraryDir
    envOption.value.type = 8;   // kLiteRtAnyTypeString
    envOption.value.value.str_value = libraryDir.c_str();
    int envStatus =
        createEnvironment(1, &envOption, &environment);
    if (envStatus != 0) {
      error = std::string("LiteRtCreateEnvironment status ") + std::to_string(envStatus);
    }
  }
  if (error.empty() && createOptions != nullptr) {
    int optsStatus = createOptions(&options);
    if (optsStatus != 0) {
      error = std::string("LiteRtCreateOptions status ") + std::to_string(optsStatus);
    } else {
      optionsCreated = true;
    }
  }
  if (error.empty()) {
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "LiteRtDispatchInitialize: env=%p options=%p (created=%d) dispatchLibraryDir=%s",
                        environment, options, optionsCreated ? 1 : 0, libraryDir.c_str());
    initStatus = dispatchInitialize(environment, options);
    const char* statusStr = getStatusString == nullptr ? nullptr : getStatusString(initStatus);
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "LiteRtDispatchInitialize status=%d string=%s", initStatus,
                        statusStr == nullptr ? "?" : statusStr);
    // Diagnostic-only: do NOT destroy environment/options here — the MediaTek
    // dispatch keeps references to both beyond initialize (see source note).
  }

  std::string json = "{\"status\":\"";
  json += (error.empty() && initStatus == 0) ? "OK" : "ERROR";
  json += "\",\"initStatus\":" + std::to_string(initStatus);
  json += ",\"optionsCreated\":";
  json += optionsCreated ? "true" : "false";
  json += ",\"statusString\":\"";
  if (error.empty() && getStatusString != nullptr) {
    const char* s = getStatusString(initStatus);
    json += s == nullptr ? "" : escapeJson(s);
  }
  json += "\",\"error\":\"";
  json += escapeJson(error);
  json += "\"}";
  return env->NewStringUTF(json.c_str());
}
