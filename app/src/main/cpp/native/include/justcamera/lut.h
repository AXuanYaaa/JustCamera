#ifndef JUSTCAMERA_NATIVE_LUT_H
#define JUSTCAMERA_NATIVE_LUT_H

#include <cstddef>
#include <cstdint>

#include "justcamera/error.h"

namespace justcamera::processing {

struct Lut3dView {
    std::int32_t size = 0;
    float domain_min[3] = {0.0F, 0.0F, 0.0F};
    float domain_max[3] = {1.0F, 1.0F, 1.0F};
    const float* samples = nullptr;
    std::size_t sample_count = 0;
};

NativeStatus ValidateLut3d(const Lut3dView& lut) noexcept;
float LinearToSrgb(float value) noexcept;
float SrgbToLinear(float value) noexcept;
void SampleLut3d(
    const Lut3dView& lut,
    float red,
    float green,
    float blue,
    float output[3]) noexcept;

}  // namespace justcamera::processing

#endif  // JUSTCAMERA_NATIVE_LUT_H
