#include "justcamera/image_frame.h"

#include <cmath>
#include <limits>

namespace justcamera::processing {

bool CheckedMultiply(
    const std::size_t left,
    const std::size_t right,
    std::size_t* const result) noexcept {
    if (result == nullptr || (right != 0U && left > std::numeric_limits<std::size_t>::max() / right)) {
        return false;
    }
    *result = left * right;
    return true;
}

bool CheckedAdd(
    const std::size_t left,
    const std::size_t right,
    std::size_t* const result) noexcept {
    if (result == nullptr || left > std::numeric_limits<std::size_t>::max() - right) {
        return false;
    }
    *result = left + right;
    return true;
}

NativeStatus ValidateFrameDescriptor(const ImageFrameView& frame) noexcept {
    if (frame.width <= 0 || frame.height <= 0 || frame.samples == nullptr) {
        return NativeStatus::kInvalidArgument;
    }
    if (frame.channels != 3 && frame.channels != 4) {
        return NativeStatus::kUnsupportedFormat;
    }
    if (frame.has_alpha != (frame.channels == 4) ||
        frame.color_contract != ColorContract::kLinearSrgbFloat32) {
        return NativeStatus::kUnsupportedFormat;
    }
    if (frame.pixel_stride_floats < static_cast<std::size_t>(frame.channels)) {
        return NativeStatus::kInvalidBuffer;
    }

    std::size_t minimum_row = 0U;
    if (!CheckedMultiply(
            static_cast<std::size_t>(frame.width), frame.pixel_stride_floats, &minimum_row) ||
        frame.row_stride_floats < minimum_row) {
        return NativeStatus::kInvalidBuffer;
    }

    std::size_t last_row = 0U;
    std::size_t last_pixel = 0U;
    std::size_t required = 0U;
    if (!CheckedMultiply(
            static_cast<std::size_t>(frame.height - 1), frame.row_stride_floats, &last_row) ||
        !CheckedMultiply(
            static_cast<std::size_t>(frame.width - 1), frame.pixel_stride_floats, &last_pixel) ||
        !CheckedAdd(last_row, last_pixel, &required) ||
        !CheckedAdd(required, static_cast<std::size_t>(frame.channels), &required) ||
        required > frame.sample_capacity) {
        return NativeStatus::kInvalidBuffer;
    }
    return NativeStatus::kOk;
}

NativeStatus ValidateFrameSamples(const ImageFrameView& frame) noexcept {
    const NativeStatus descriptor_status = ValidateFrameDescriptor(frame);
    if (descriptor_status != NativeStatus::kOk) return descriptor_status;
    for (std::int32_t y = 0; y < frame.height; ++y) {
        const float* row = frame.samples + static_cast<std::size_t>(y) * frame.row_stride_floats;
        for (std::int32_t x = 0; x < frame.width; ++x) {
            const float* pixel = row + static_cast<std::size_t>(x) * frame.pixel_stride_floats;
            for (std::int32_t channel = 0; channel < frame.channels; ++channel) {
                const float value = pixel[channel];
                if (!std::isfinite(value) || value < 0.0F || value > 1.0F) {
                    return NativeStatus::kInvalidArgument;
                }
            }
        }
    }
    return NativeStatus::kOk;
}

}  // namespace justcamera::processing
