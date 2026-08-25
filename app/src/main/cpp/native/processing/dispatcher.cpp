#include "justcamera/processing.h"

#include <cmath>

#include "justcamera/filters.h"

namespace justcamera::processing {

NativeStatus ValidateOperation(const NativeOperation& operation) noexcept {
    if (!std::isfinite(operation.parameter) || !std::isfinite(operation.strength) ||
        operation.strength < 0.0F || operation.strength > 1.0F) {
        return NativeStatus::kInvalidArgument;
    }
    switch (operation.type) {
        case NativeOperationType::kExposure:
        case NativeOperationType::kContrast:
        case NativeOperationType::kSaturation:
            return NativeStatus::kOk;
        case NativeOperationType::kLut3d:
            return ValidateLut3d(operation.lut);
        default:
            return NativeStatus::kInvalidArgument;
    }
}

NativeStatus ProcessOperations(
    ImageFrameView& frame,
    const NativeOperation* const operations,
    const std::size_t operation_count) noexcept {
    if (operation_count > 0U && operations == nullptr) return NativeStatus::kInvalidArgument;
    const NativeStatus frame_status = ValidateFrameSamples(frame);
    if (frame_status != NativeStatus::kOk) return frame_status;
    for (std::size_t index = 0; index < operation_count; ++index) {
        const NativeStatus operation_status = ValidateOperation(operations[index]);
        if (operation_status != NativeStatus::kOk) return operation_status;
    }
    for (std::size_t index = 0; index < operation_count; ++index) {
        const NativeOperation& operation = operations[index];
        switch (operation.type) {
            case NativeOperationType::kExposure:
                ProcessExposure(frame, operation.parameter, operation.strength);
                break;
            case NativeOperationType::kContrast:
                ProcessContrast(frame, operation.parameter, operation.strength);
                break;
            case NativeOperationType::kSaturation:
                ProcessSaturation(frame, operation.parameter, operation.strength);
                break;
            case NativeOperationType::kLut3d:
                ProcessLut3d(frame, operation.lut, operation.strength);
                break;
            default:
                return NativeStatus::kInternalError;
        }
    }
    return NativeStatus::kOk;
}

}  // namespace justcamera::processing
