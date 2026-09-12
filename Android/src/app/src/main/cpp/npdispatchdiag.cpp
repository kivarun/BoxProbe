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

extern "C" JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_systeminfo_NpuDispatchHandshakeBridge_handshakeNative(
    JNIEnv* env, jclass /*clazz*/, jstring libraryPath) {
  std::string json = handshakeJson(jstringToStd(env, libraryPath));
  return env->NewStringUTF(json.c_str());
}
