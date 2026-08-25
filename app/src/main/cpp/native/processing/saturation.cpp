#include "justcamera/filters.h"

#include <algorithm>
#include <cmath>

namespace justcamera::processing {
namespace {

float ClampUnit(const float value) noexcept {
    return std::isfinite(value) ? std::clamp(value, 0.0F, 1.0F) : 0.0F;
}

}  // namespace

void ProcessSaturation(ImageFrameView& frame, const float saturation, const float strength) noexcept {
    for (std::int32_t y = 0; y < frame.height; ++y) {
        float* row = frame.samples + static_cast<std::size_t>(y) * frame.row_stride_floats;
        for (std::int32_t x = 0; x < frame.width; ++x) {
            float* pixel = row + static_cast<std::size_t>(x) * frame.pixel_stride_floats;
            const float red = pixel[0];
            const float green = pixel[1];
            const float blue = pixel[2];
            const float luma = red * 0.2126F + green * 0.7152F + blue * 0.0722F;
            const float originals[3] = {red, green, blue};
            for (std::int32_t channel = 0; channel < 3; ++channel) {
                const float adjusted = ClampUnit(
                    luma + (originals[channel] - luma) * saturation);
                pixel[channel] = originals[channel] + (adjusted - originals[channel]) * strength;
            }
        }
    }
}

}  // namespace justcamera::processing
