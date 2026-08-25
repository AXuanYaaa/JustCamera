#include "justcamera_plugin.h"

#include <dlfcn.h>
#include <stddef.h>
#include <stdint.h>

namespace justcamera {

using ApiVersionFn = uint32_t (*)();
using GetInfoFn = const JustCameraPluginInfo* (*)();
using CreateFn = JustCameraPluginHandle* (*)();
using ProcessFn = JustCameraPluginResult (*)(
    JustCameraPluginHandle*,
    const JustCameraImageBuffer*,
    JustCameraImageBuffer*,
    const JustCameraProcessingParams*);
using DestroyFn = void (*)(JustCameraPluginHandle*);

struct LoadedPlugin {
    void* library = nullptr;
    ApiVersionFn api_version = nullptr;
    GetInfoFn get_info = nullptr;
    CreateFn create = nullptr;
    ProcessFn process = nullptr;
    DestroyFn destroy = nullptr;
};

enum class LoadResult : int32_t {
    kOk = 0,
    kOpenFailed = -1,
    kMissingSymbol = -2,
    kApiMismatch = -3,
    kInvalidInfo = -4,
};

template <typename Function>
Function resolve(void* library, const char* symbol) noexcept {
    return reinterpret_cast<Function>(dlsym(library, symbol));
}

LoadResult load_validated_plugin(const char* private_path, LoadedPlugin* output) noexcept {
    if (private_path == nullptr || output == nullptr) return LoadResult::kInvalidInfo;
    void* library = dlopen(private_path, RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return LoadResult::kOpenFailed;

    LoadedPlugin candidate{};
    candidate.library = library;
    candidate.api_version = resolve<ApiVersionFn>(library, "justcamera_plugin_api_version");
    candidate.get_info = resolve<GetInfoFn>(library, "justcamera_plugin_get_info");
    candidate.create = resolve<CreateFn>(library, "justcamera_plugin_create");
    candidate.process = resolve<ProcessFn>(library, "justcamera_plugin_process");
    candidate.destroy = resolve<DestroyFn>(library, "justcamera_plugin_destroy");
    if (candidate.api_version == nullptr || candidate.get_info == nullptr ||
        candidate.create == nullptr || candidate.process == nullptr ||
        candidate.destroy == nullptr) {
        dlclose(library);
        return LoadResult::kMissingSymbol;
    }

    const uint32_t plugin_api = candidate.api_version();
    if ((plugin_api >> 24u) != (JUSTCAMERA_PLUGIN_API_VERSION >> 24u) ||
        ((plugin_api >> 16u) & 0xffu) >
            ((JUSTCAMERA_PLUGIN_API_VERSION >> 16u) & 0xffu)) {
        dlclose(library);
        return LoadResult::kApiMismatch;
    }
    const JustCameraPluginInfo* info = candidate.get_info();
    if (info == nullptr || info->struct_size < offsetof(JustCameraPluginInfo, reserved) ||
        info->identifier.data == nullptr || info->identifier.length == 0u) {
        dlclose(library);
        return LoadResult::kInvalidInfo;
    }

    *output = candidate;
    return LoadResult::kOk;
}

void unload_plugin(LoadedPlugin* plugin) noexcept {
    if (plugin == nullptr || plugin->library == nullptr) return;
    dlclose(plugin->library);
    *plugin = LoadedPlugin{};
}

}  // namespace justcamera
