#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

#include "justcamera/lut.h"
#include "justcamera/processing.h"

namespace {

using justcamera::processing::ColorContract;
using justcamera::processing::ImageFrameView;
using justcamera::processing::Lut3dView;
using justcamera::processing::NativeOperation;
using justcamera::processing::NativeOperationType;
using justcamera::processing::NativeStatus;

constexpr std::uint32_t kDescriptorMagic = 0x4A435034U;  // "JCP4" in native order.
constexpr std::uint32_t kDescriptorVersion = 1U;
constexpr std::size_t kHeaderBytes = 16U;
constexpr std::size_t kRecordBytes = 48U;
constexpr std::size_t kMaximumOperations = 1024U;

template <typename Value>
bool ReadValue(
    const std::byte* const bytes,
    const std::size_t capacity,
    const std::size_t offset,
    Value* const output) noexcept {
    if (output == nullptr || offset > capacity || sizeof(Value) > capacity - offset) return false;
    std::memcpy(output, bytes + offset, sizeof(Value));
    return true;
}

bool CapacityToSize(const jlong capacity, std::size_t* const output) noexcept {
    if (output == nullptr || capacity < 0) return false;
    const auto unsigned_capacity = static_cast<unsigned long long>(capacity);
    if (unsigned_capacity > static_cast<unsigned long long>(
                                std::numeric_limits<std::size_t>::max())) {
        return false;
    }
    *output = static_cast<std::size_t>(unsigned_capacity);
    return true;
}

NativeStatus ParseOperations(
    const std::byte* const descriptor_bytes,
    const std::size_t descriptor_capacity,
    const std::int32_t expected_count,
    const float* const lut_samples,
    const std::size_t lut_sample_capacity,
    std::vector<NativeOperation>* const output) {
    if (descriptor_bytes == nullptr || output == nullptr || expected_count < 0 ||
        static_cast<std::size_t>(expected_count) > kMaximumOperations) {
        return NativeStatus::kInvalidArgument;
    }
    std::uint32_t magic = 0U;
    std::uint32_t version = 0U;
    std::uint32_t record_bytes = 0U;
    std::uint32_t count = 0U;
    if (!ReadValue(descriptor_bytes, descriptor_capacity, 0U, &magic) ||
        !ReadValue(descriptor_bytes, descriptor_capacity, 4U, &version) ||
        !ReadValue(descriptor_bytes, descriptor_capacity, 8U, &record_bytes) ||
        !ReadValue(descriptor_bytes, descriptor_capacity, 12U, &count) ||
        magic != kDescriptorMagic || version != kDescriptorVersion ||
        record_bytes != kRecordBytes || count != static_cast<std::uint32_t>(expected_count)) {
        return NativeStatus::kInvalidBuffer;
    }
    std::size_t records_bytes = 0U;
    std::size_t required_bytes = 0U;
    if (!justcamera::processing::CheckedMultiply(count, kRecordBytes, &records_bytes) ||
        !justcamera::processing::CheckedAdd(kHeaderBytes, records_bytes, &required_bytes) ||
        required_bytes > descriptor_capacity) {
        return NativeStatus::kInvalidBuffer;
    }

    output->clear();
    output->reserve(count);
    for (std::size_t index = 0; index < count; ++index) {
        const std::size_t base = kHeaderBytes + index * kRecordBytes;
        std::int32_t type = 0;
        std::int32_t lut_size = 0;
        std::uint64_t lut_offset = 0U;
        float parameter = 0.0F;
        float strength = 0.0F;
        NativeOperation operation{};
        if (!ReadValue(descriptor_bytes, descriptor_capacity, base, &type) ||
            !ReadValue(descriptor_bytes, descriptor_capacity, base + 4U, &lut_size) ||
            !ReadValue(descriptor_bytes, descriptor_capacity, base + 8U, &lut_offset) ||
            !ReadValue(descriptor_bytes, descriptor_capacity, base + 16U, &parameter) ||
            !ReadValue(descriptor_bytes, descriptor_capacity, base + 20U, &strength)) {
            return NativeStatus::kInvalidBuffer;
        }
        operation.type = static_cast<NativeOperationType>(type);
        operation.parameter = parameter;
        operation.strength = strength;

        for (std::size_t channel = 0; channel < 3U; ++channel) {
            if (!ReadValue(
                    descriptor_bytes,
                    descriptor_capacity,
                    base + 24U + channel * sizeof(float),
                    &operation.lut.domain_min[channel]) ||
                !ReadValue(
                    descriptor_bytes,
                    descriptor_capacity,
                    base + 36U + channel * sizeof(float),
                    &operation.lut.domain_max[channel])) {
                return NativeStatus::kInvalidBuffer;
            }
        }

        if (operation.type == NativeOperationType::kLut3d) {
            if (lut_size < 2 || lut_samples == nullptr ||
                lut_offset > static_cast<std::uint64_t>(lut_sample_capacity)) {
                return NativeStatus::kInvalidLut;
            }
            std::size_t cube = 0U;
            std::size_t sample_count = 0U;
            if (!justcamera::processing::CheckedMultiply(
                    static_cast<std::size_t>(lut_size),
                    static_cast<std::size_t>(lut_size),
                    &cube) ||
                !justcamera::processing::CheckedMultiply(
                    cube, static_cast<std::size_t>(lut_size), &cube) ||
                !justcamera::processing::CheckedMultiply(cube, 3U, &sample_count) ||
                sample_count > lut_sample_capacity - static_cast<std::size_t>(lut_offset)) {
                return NativeStatus::kInvalidLut;
            }
            operation.lut.size = lut_size;
            operation.lut.samples = lut_samples + static_cast<std::size_t>(lut_offset);
            operation.lut.sample_count = sample_count;
        } else if (lut_size != 0 || lut_offset != 0U) {
            return NativeStatus::kInvalidArgument;
        }
        output->push_back(operation);
    }
    return NativeStatus::kOk;
}

jint StatusCode(const NativeStatus status) noexcept {
    return static_cast<jint>(status);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_top_r2dblog_justcamera_filter_processing_backend_NativeProcessingJni_nativeProcessInPlace(
    JNIEnv* const env,
    jobject /* this */,
    jobject const frame_buffer,
    const jint width,
    const jint height,
    const jint channels,
    const jint row_stride_floats,
    const jint pixel_stride_floats,
    jobject const operation_buffer,
    const jint operation_count,
    jobject const lut_buffer) noexcept {
    try {
        if (env == nullptr || frame_buffer == nullptr || operation_buffer == nullptr ||
            row_stride_floats < 0 || pixel_stride_floats < 0) {
            return StatusCode(NativeStatus::kInvalidArgument);
        }
        void* const frame_address = env->GetDirectBufferAddress(frame_buffer);
        void* const descriptor_address = env->GetDirectBufferAddress(operation_buffer);
        const jlong frame_capacity_raw = env->GetDirectBufferCapacity(frame_buffer);
        const jlong descriptor_capacity_raw = env->GetDirectBufferCapacity(operation_buffer);
        std::size_t frame_capacity_bytes = 0U;
        std::size_t descriptor_capacity_bytes = 0U;
        if (frame_address == nullptr || descriptor_address == nullptr ||
            !CapacityToSize(frame_capacity_raw, &frame_capacity_bytes) ||
            !CapacityToSize(descriptor_capacity_raw, &descriptor_capacity_bytes) ||
            frame_capacity_bytes % sizeof(float) != 0U ||
            reinterpret_cast<std::uintptr_t>(frame_address) % alignof(float) != 0U) {
            return StatusCode(NativeStatus::kInvalidBuffer);
        }

        const float* lut_samples = nullptr;
        std::size_t lut_capacity_bytes = 0U;
        if (lut_buffer != nullptr) {
            void* const lut_address = env->GetDirectBufferAddress(lut_buffer);
            const jlong lut_capacity_raw = env->GetDirectBufferCapacity(lut_buffer);
            if (lut_address == nullptr || !CapacityToSize(lut_capacity_raw, &lut_capacity_bytes) ||
                lut_capacity_bytes % sizeof(float) != 0U ||
                reinterpret_cast<std::uintptr_t>(lut_address) % alignof(float) != 0U) {
                return StatusCode(NativeStatus::kInvalidBuffer);
            }
            lut_samples = static_cast<const float*>(lut_address);
        }

        std::vector<NativeOperation> operations;
        const NativeStatus parse_status = ParseOperations(
            static_cast<const std::byte*>(descriptor_address),
            descriptor_capacity_bytes,
            operation_count,
            lut_samples,
            lut_capacity_bytes / sizeof(float),
            &operations);
        if (parse_status != NativeStatus::kOk) return StatusCode(parse_status);

        ImageFrameView frame{
            .width = width,
            .height = height,
            .channels = channels,
            .row_stride_floats = static_cast<std::size_t>(row_stride_floats),
            .pixel_stride_floats = static_cast<std::size_t>(pixel_stride_floats),
            .samples = static_cast<float*>(frame_address),
            .sample_capacity = frame_capacity_bytes / sizeof(float),
            .has_alpha = channels == 4,
            .color_contract = ColorContract::kLinearSrgbFloat32,
        };
        return StatusCode(justcamera::processing::ProcessOperations(
            frame, operations.data(), operations.size()));
    } catch (const std::bad_alloc&) {
        return StatusCode(NativeStatus::kInternalError);
    } catch (...) {
        return StatusCode(NativeStatus::kInternalError);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_top_r2dblog_justcamera_filter_processing_backend_NativeProcessingJni_nativeColorTransferInPlace(
    JNIEnv* const env,
    jobject /* this */,
    jobject const buffer,
    const jint sample_count,
    const jint direction) noexcept {
    if (env == nullptr || buffer == nullptr || sample_count < 0 || (direction != 0 && direction != 1)) {
        return StatusCode(NativeStatus::kInvalidArgument);
    }
    void* const address = env->GetDirectBufferAddress(buffer);
    const jlong capacity_raw = env->GetDirectBufferCapacity(buffer);
    std::size_t capacity_bytes = 0U;
    if (address == nullptr || !CapacityToSize(capacity_raw, &capacity_bytes) ||
        capacity_bytes % sizeof(float) != 0U ||
        reinterpret_cast<std::uintptr_t>(address) % alignof(float) != 0U) {
        return StatusCode(NativeStatus::kInvalidBuffer);
    }
    std::size_t required = 0U;
    if (!justcamera::processing::CheckedMultiply(
            static_cast<std::size_t>(sample_count), sizeof(float), &required) ||
        required > capacity_bytes) {
        return StatusCode(NativeStatus::kInvalidBuffer);
    }
    float* const samples = static_cast<float*>(address);
    for (jint index = 0; index < sample_count; ++index) {
        samples[index] = direction == 0
            ? justcamera::processing::LinearToSrgb(samples[index])
            : justcamera::processing::SrgbToLinear(samples[index]);
    }
    return StatusCode(NativeStatus::kOk);
}
