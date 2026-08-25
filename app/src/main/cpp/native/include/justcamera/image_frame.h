#ifndef JUSTCAMERA_NATIVE_IMAGE_FRAME_H
#define JUSTCAMERA_NATIVE_IMAGE_FRAME_H

#include <cstddef>
#include <cstdint>

#include "justcamera/error.h"

namespace justcamera::processing {

enum class ColorContract : std::int32_t {
    kLinearSrgbFloat32 = 1,
};

struct ImageFrameView {
    std::int32_t width = 0;
    std::int32_t height = 0;
    std::int32_t channels = 0;
    std::size_t row_stride_floats = 0;
    std::size_t pixel_stride_floats = 0;
    float* samples = nullptr;
    std::size_t sample_capacity = 0;
    bool has_alpha = false;
    ColorContract color_contract = ColorContract::kLinearSrgbFloat32;
};

bool CheckedMultiply(std::size_t left, std::size_t right, std::size_t* result) noexcept;
bool CheckedAdd(std::size_t left, std::size_t right, std::size_t* result) noexcept;
NativeStatus ValidateFrameDescriptor(const ImageFrameView& frame) noexcept;
NativeStatus ValidateFrameSamples(const ImageFrameView& frame) noexcept;

}  // namespace justcamera::processing

#endif  // JUSTCAMERA_NATIVE_IMAGE_FRAME_H
