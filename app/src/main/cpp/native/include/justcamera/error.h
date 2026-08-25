#ifndef JUSTCAMERA_NATIVE_ERROR_H
#define JUSTCAMERA_NATIVE_ERROR_H

#include <cstdint>

namespace justcamera::processing {

enum class NativeStatus : std::int32_t {
    kOk = 0,
    kInvalidArgument = 1,
    kUnsupportedFormat = 2,
    kInvalidBuffer = 3,
    kInvalidLut = 4,
    kCancelled = 5,
    kInternalError = 6,
};

}  // namespace justcamera::processing

#endif  // JUSTCAMERA_NATIVE_ERROR_H
