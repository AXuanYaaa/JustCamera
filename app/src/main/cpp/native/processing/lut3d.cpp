#include "justcamera/filters.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace justcamera::processing {
namespace {

float ClampUnit(const float value) noexcept {
    return std::isfinite(value) ? std::clamp(value, 0.0F, 1.0F) : 0.0F;
}

float Lerp(const float left, const float right, const float fraction) noexcept {
    return left + (right - left) * fraction;
}

float Coordinate(
    const float value,
    const float minimum,
    const float maximum,
    const std::int32_t size) noexcept {
    const float finite = std::isfinite(value) ? value : minimum;
    return std::clamp((finite - minimum) / (maximum - minimum), 0.0F, 1.0F) *
        static_cast<float>(size - 1);
}

std::size_t LutIndex(
    const std::int32_t red,
    const std::int32_t green,
    const std::int32_t blue,
    const std::int32_t size,
    const std::int32_t channel) noexcept {
    return ((static_cast<std::size_t>(blue) * static_cast<std::size_t>(size) +
             static_cast<std::size_t>(green)) *
                static_cast<std::size_t>(size) +
            static_cast<std::size_t>(red)) *
        3U + static_cast<std::size_t>(channel);
}

}  // namespace

NativeStatus ValidateLut3d(const Lut3dView& lut) noexcept {
    if (lut.size < 2 || lut.samples == nullptr) return NativeStatus::kInvalidLut;
    std::size_t cube = 0U;
    std::size_t expected = 0U;
    if (!CheckedMultiply(static_cast<std::size_t>(lut.size), static_cast<std::size_t>(lut.size), &cube) ||
        !CheckedMultiply(cube, static_cast<std::size_t>(lut.size), &cube) ||
        !CheckedMultiply(cube, 3U, &expected) || expected != lut.sample_count) {
        return NativeStatus::kInvalidLut;
    }
    for (std::int32_t channel = 0; channel < 3; ++channel) {
        if (!std::isfinite(lut.domain_min[channel]) ||
            !std::isfinite(lut.domain_max[channel]) ||
            lut.domain_min[channel] >= lut.domain_max[channel]) {
            return NativeStatus::kInvalidLut;
        }
    }
    for (std::size_t index = 0; index < lut.sample_count; ++index) {
        if (!std::isfinite(lut.samples[index])) return NativeStatus::kInvalidLut;
    }
    return NativeStatus::kOk;
}

float LinearToSrgb(const float value) noexcept {
    const float channel = ClampUnit(value);
    const float encoded = channel <= 0.0031308F
        ? 12.92F * channel
        : 1.055F * static_cast<float>(std::pow(static_cast<double>(channel), 1.0 / 2.4)) - 0.055F;
    return ClampUnit(encoded);
}

float SrgbToLinear(const float value) noexcept {
    const float channel = ClampUnit(value);
    const float linear = channel <= 0.04045F
        ? channel / 12.92F
        : static_cast<float>(std::pow(
              static_cast<double>((channel + 0.055F) / 1.055F), 2.4));
    return ClampUnit(linear);
}

void SampleLut3d(
    const Lut3dView& lut,
    const float red,
    const float green,
    const float blue,
    float output[3]) noexcept {
    const float coordinates[3] = {
        Coordinate(red, lut.domain_min[0], lut.domain_max[0], lut.size),
        Coordinate(green, lut.domain_min[1], lut.domain_max[1], lut.size),
        Coordinate(blue, lut.domain_min[2], lut.domain_max[2], lut.size),
    };
    const std::int32_t low[3] = {
        static_cast<std::int32_t>(std::floor(coordinates[0])),
        static_cast<std::int32_t>(std::floor(coordinates[1])),
        static_cast<std::int32_t>(std::floor(coordinates[2])),
    };
    const std::int32_t high[3] = {
        std::min(low[0] + 1, lut.size - 1),
        std::min(low[1] + 1, lut.size - 1),
        std::min(low[2] + 1, lut.size - 1),
    };
    const float fraction[3] = {
        coordinates[0] - static_cast<float>(low[0]),
        coordinates[1] - static_cast<float>(low[1]),
        coordinates[2] - static_cast<float>(low[2]),
    };
    for (std::int32_t channel = 0; channel < 3; ++channel) {
        const float c000 = lut.samples[LutIndex(low[0], low[1], low[2], lut.size, channel)];
        const float c100 = lut.samples[LutIndex(high[0], low[1], low[2], lut.size, channel)];
        const float c010 = lut.samples[LutIndex(low[0], high[1], low[2], lut.size, channel)];
        const float c110 = lut.samples[LutIndex(high[0], high[1], low[2], lut.size, channel)];
        const float c001 = lut.samples[LutIndex(low[0], low[1], high[2], lut.size, channel)];
        const float c101 = lut.samples[LutIndex(high[0], low[1], high[2], lut.size, channel)];
        const float c011 = lut.samples[LutIndex(low[0], high[1], high[2], lut.size, channel)];
        const float c111 = lut.samples[LutIndex(high[0], high[1], high[2], lut.size, channel)];
        const float c00 = Lerp(c000, c100, fraction[0]);
        const float c10 = Lerp(c010, c110, fraction[0]);
        const float c01 = Lerp(c001, c101, fraction[0]);
        const float c11 = Lerp(c011, c111, fraction[0]);
        const float c0 = Lerp(c00, c10, fraction[1]);
        const float c1 = Lerp(c01, c11, fraction[1]);
        output[channel] = ClampUnit(Lerp(c0, c1, fraction[2]));
    }
}

void ProcessLut3d(ImageFrameView& frame, const Lut3dView& lut, const float strength) noexcept {
    for (std::int32_t y = 0; y < frame.height; ++y) {
        float* row = frame.samples + static_cast<std::size_t>(y) * frame.row_stride_floats;
        for (std::int32_t x = 0; x < frame.width; ++x) {
            float* pixel = row + static_cast<std::size_t>(x) * frame.pixel_stride_floats;
            const float encoded[3] = {
                LinearToSrgb(pixel[0]),
                LinearToSrgb(pixel[1]),
                LinearToSrgb(pixel[2]),
            };
            float mapped[3] = {};
            SampleLut3d(lut, encoded[0], encoded[1], encoded[2], mapped);
            for (std::int32_t channel = 0; channel < 3; ++channel) {
                pixel[channel] = SrgbToLinear(
                    encoded[channel] + (mapped[channel] - encoded[channel]) * strength);
            }
        }
    }
}

}  // namespace justcamera::processing
