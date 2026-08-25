#ifndef JUSTCAMERA_PLUGIN_H
#define JUSTCAMERA_PLUGIN_H

#include <stddef.h>
#include <stdint.h>

#if defined(__cplusplus)
extern "C" {
#endif

#define JUSTCAMERA_PLUGIN_API_VERSION_MAJOR 1u
#define JUSTCAMERA_PLUGIN_API_VERSION_MINOR 0u
#define JUSTCAMERA_PLUGIN_API_VERSION_PATCH 0u
#define JUSTCAMERA_PLUGIN_API_VERSION \
    ((JUSTCAMERA_PLUGIN_API_VERSION_MAJOR << 24u) | \
     (JUSTCAMERA_PLUGIN_API_VERSION_MINOR << 16u) | \
     JUSTCAMERA_PLUGIN_API_VERSION_PATCH)

typedef struct JustCameraPluginHandle JustCameraPluginHandle;

typedef uint32_t JustCameraPluginType;
enum {
    JUSTCAMERA_PLUGIN_DENOISE = 1u << 0u,
    JUSTCAMERA_PLUGIN_HDR = 1u << 1u,
    JUSTCAMERA_PLUGIN_NIGHT = 1u << 2u,
    JUSTCAMERA_PLUGIN_TONEMAP = 1u << 3u,
    JUSTCAMERA_PLUGIN_SHARPEN = 1u << 4u,
    JUSTCAMERA_PLUGIN_FILTER = 1u << 5u,
    JUSTCAMERA_PLUGIN_SUPER_RESOLUTION = 1u << 6u,
};

typedef uint32_t JustCameraPixelFormat;
enum {
    JUSTCAMERA_PIXEL_FORMAT_UNKNOWN = 0u,
    JUSTCAMERA_PIXEL_FORMAT_YUV_420_888 = 1u,
    JUSTCAMERA_PIXEL_FORMAT_RAW_SENSOR_16 = 2u,
    JUSTCAMERA_PIXEL_FORMAT_RGBA_8888 = 3u,
    JUSTCAMERA_PIXEL_FORMAT_RGB_16F = 4u,
};

typedef int32_t JustCameraPluginResult;
enum {
    JUSTCAMERA_PLUGIN_OK = 0,
    JUSTCAMERA_PLUGIN_ERROR_INVALID_ARGUMENT = -1,
    JUSTCAMERA_PLUGIN_ERROR_UNSUPPORTED_FORMAT = -2,
    JUSTCAMERA_PLUGIN_ERROR_BUFFER_TOO_SMALL = -3,
    JUSTCAMERA_PLUGIN_ERROR_OUT_OF_MEMORY = -4,
    JUSTCAMERA_PLUGIN_ERROR_INTERNAL = -5,
    JUSTCAMERA_PLUGIN_ERROR_API_MISMATCH = -6,
};

typedef struct JustCameraStringView {
    uint32_t struct_size;
    const char* data;
    uint64_t length;
} JustCameraStringView;

typedef struct JustCameraPluginInfo {
    uint32_t struct_size;
    uint32_t required_api_version;
    uint32_t plugin_version;
    JustCameraPluginType type_flags;
    JustCameraStringView identifier;
    JustCameraStringView display_name;
    JustCameraStringView vendor;
    uint32_t thread_safe; /* 0: serialize process calls, 1: concurrent calls allowed. */
    uint32_t reserved[7];
} JustCameraPluginInfo;

typedef struct JustCameraImagePlane {
    uint32_t struct_size;
    uint8_t* data;
    uint64_t data_size;
    uint32_t row_stride_bytes;
    uint32_t pixel_stride_bytes;
    uint32_t reserved[4];
} JustCameraImagePlane;

typedef struct JustCameraImageBuffer {
    uint32_t struct_size;
    JustCameraPixelFormat pixel_format;
    uint32_t width;
    uint32_t height;
    uint32_t bit_depth;
    uint32_t plane_count;
    JustCameraImagePlane* planes;
    uint64_t timestamp_nanos;
    uint32_t rotation_degrees;
    uint32_t reserved[7];
} JustCameraImageBuffer;

typedef struct JustCameraProcessingParams {
    uint32_t struct_size;
    const uint8_t* encoded_data; /* Host-owned immutable data, valid only during process(). */
    uint64_t encoded_data_size;
    uint32_t encoding_version;
    uint32_t reserved[7];
} JustCameraProcessingParams;

uint32_t justcamera_plugin_api_version(void);
const JustCameraPluginInfo* justcamera_plugin_get_info(void);
JustCameraPluginHandle* justcamera_plugin_create(void);
JustCameraPluginResult justcamera_plugin_process(
    JustCameraPluginHandle* plugin,
    const JustCameraImageBuffer* input,
    JustCameraImageBuffer* output,
    const JustCameraProcessingParams* params);
void justcamera_plugin_destroy(JustCameraPluginHandle* plugin);

#if defined(__cplusplus)
} /* extern "C" */
#endif

#endif /* JUSTCAMERA_PLUGIN_H */
