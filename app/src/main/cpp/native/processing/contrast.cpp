#include "justcamera/filters.h"

#include <algorithm>
#include <cmath>

namespace justcamera::processing {
namespace {

constexpr float kContrastPivot = 0.18F;

float ClampUnit(const float value) noexcept {
    return std::isfinite(value) ? std::clamp(value, 0.0F, 1.0F) : 0.0F;
}

}  // namespace

void ProcessContrast(ImageFrameView& frame, const float contrast, const float strength) noexcept {
    for (std::int32_t y = 0; y < frame.height; ++y) {
        float* row = frame.samples + static_cast<std::size_t>(y) * frame.row_stride_floats;
        for (std::int32_t x = 0; x < frame.width; ++x) {
            float* pixel = row + static_cast<std::size_t>(x) * frame.pixel_stride_floats;
            for (std::int32_t channel = 0; channel < 3; ++channel) {
                const float original = pixel[channel];
                const float adjusted = ClampUnit((original - kContrastPivot) * contrast + kContrastPivot);
                pixel[channel] = original + (adjusted - original) * strength;
            }
        }
    }
}

}  // namespace justcamera::processing
