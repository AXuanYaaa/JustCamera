#ifndef JUSTCAMERA_NATIVE_PROCESSING_H
#define JUSTCAMERA_NATIVE_PROCESSING_H

#include <cstddef>
#include <cstdint>

#include "justcamera/error.h"
#include "justcamera/image_frame.h"
#include "justcamera/lut.h"

namespace justcamera::processing {

enum class NativeOperationType : std::int32_t {
    kExposure = 1,
    kContrast = 2,
    kSaturation = 3,
    kLut3d = 4,
};

struct NativeOperation {
    NativeOperationType type = NativeOperationType::kExposure;
    float parameter = 0.0F;
    float strength = 1.0F;
    Lut3dView lut{};
};

NativeStatus ValidateOperation(const NativeOperation& operation) noexcept;
NativeStatus ProcessOperations(
    ImageFrameView& frame,
    const NativeOperation* operations,
    std::size_t operation_count) noexcept;

}  // namespace justcamera::processing

#endif  // JUSTCAMERA_NATIVE_PROCESSING_H
