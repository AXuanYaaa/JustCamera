#include <array>
#include <cassert>
#include <cmath>
#include <limits>

#include "justcamera/lut.h"
#include "justcamera/processing.h"

namespace {

using justcamera::processing::ColorContract;
using justcamera::processing::ImageFrameView;
using justcamera::processing::Lut3dView;
using justcamera::processing::NativeOperation;
using justcamera::processing::NativeOperationType;
using justcamera::processing::NativeStatus;

constexpr float kTolerance = 1.0e-5F;

bool Near(const float left, const float right) {
    return std::abs(left - right) <= kTolerance;
}

ImageFrameView Frame(float* samples, const std::size_t count, const std::int32_t channels) {
    return ImageFrameView{
        .width = 1,
        .height = 1,
        .channels = channels,
        .row_stride_floats = static_cast<std::size_t>(channels),
        .pixel_stride_floats = static_cast<std::size_t>(channels),
        .samples = samples,
        .sample_capacity = count,
        .has_alpha = channels == 4,
        .color_contract = ColorContract::kLinearSrgbFloat32,
    };
}

void TestExposureAndAlpha() {
    std::array<float, 4> samples{0.25F, 0.5F, 0.75F, 0.37F};
    ImageFrameView frame = Frame(samples.data(), samples.size(), 4);
    const NativeOperation operation{NativeOperationType::kExposure, 1.0F, 1.0F, {}};
    assert(justcamera::processing::ProcessOperations(frame, &operation, 1U) == NativeStatus::kOk);
    assert(Near(samples[0], 0.5F));
    assert(Near(samples[1], 1.0F));
    assert(Near(samples[2], 1.0F));
    assert(samples[3] == 0.37F);
}

void TestContrastAndSaturation() {
    std::array<float, 3> samples{0.2F, 0.4F, 0.6F};
    ImageFrameView frame = Frame(samples.data(), samples.size(), 3);
    const std::array<NativeOperation, 2> operations{{
        {NativeOperationType::kContrast, 0.0F, 1.0F, {}},
        {NativeOperationType::kSaturation, 0.0F, 1.0F, {}},
    }};
    assert(justcamera::processing::ProcessOperations(frame, operations.data(), operations.size()) ==
           NativeStatus::kOk);
    assert(Near(samples[0], 0.18F));
    assert(Near(samples[1], 0.18F));
    assert(Near(samples[2], 0.18F));
}

void TestReferenceVectors() {
    {
        std::array<float, 3> samples{0.0F, 0.5F, 1.0F};
        ImageFrameView frame = Frame(samples.data(), samples.size(), 3);
        const NativeOperation identity{NativeOperationType::kExposure, 0.0F, 1.0F, {}};
        assert(justcamera::processing::ProcessOperations(frame, &identity, 1U) == NativeStatus::kOk);
        assert(Near(samples[0], 0.0F) && Near(samples[1], 0.5F) && Near(samples[2], 1.0F));
        const NativeOperation lower{NativeOperationType::kExposure, -1.0F, 1.0F, {}};
        assert(justcamera::processing::ProcessOperations(frame, &lower, 1U) == NativeStatus::kOk);
        assert(Near(samples[1], 0.25F) && Near(samples[2], 0.5F));
    }
    {
        std::array<float, 3> samples{0.0F, 0.18F, 1.0F};
        ImageFrameView frame = Frame(samples.data(), samples.size(), 3);
        const NativeOperation contrast{NativeOperationType::kContrast, 2.0F, 1.0F, {}};
        assert(justcamera::processing::ProcessOperations(frame, &contrast, 1U) == NativeStatus::kOk);
        assert(Near(samples[0], 0.0F) && Near(samples[1], 0.18F) && Near(samples[2], 1.0F));
    }
    {
        std::array<float, 3> samples{0.4F, 0.4F, 0.4F};
        ImageFrameView frame = Frame(samples.data(), samples.size(), 3);
        const NativeOperation saturation{NativeOperationType::kSaturation, 2.0F, 1.0F, {}};
        assert(justcamera::processing::ProcessOperations(frame, &saturation, 1U) == NativeStatus::kOk);
        assert(Near(samples[0], 0.4F) && Near(samples[1], 0.4F) && Near(samples[2], 0.4F));
    }
}

void TestIdentityLutAndTransferThresholds() {
    constexpr std::array<float, 24> identity{
        0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F,
        0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F,
        0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F,
        0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
    };
    Lut3dView lut{.size = 2, .samples = identity.data(), .sample_count = identity.size()};
    std::array<float, 3> samples{0.0031308F, 0.18F, 0.8F};
    const std::array<float, 3> original = samples;
    ImageFrameView frame = Frame(samples.data(), samples.size(), 3);
    NativeOperation operation{NativeOperationType::kLut3d, 0.0F, 1.0F, lut};
    assert(justcamera::processing::ProcessOperations(frame, &operation, 1U) == NativeStatus::kOk);
    for (std::size_t index = 0; index < samples.size(); ++index) {
        assert(Near(samples[index], original[index]));
    }
    assert(Near(justcamera::processing::LinearToSrgb(0.0031308F), 0.040449936F));
    assert(Near(justcamera::processing::SrgbToLinear(0.04045F), 0.003130805F));
}

void TestLutInterpolationOrderingAndDomain() {
    std::array<float, 24> inversion{};
    std::array<float, 24> channel_swap{};
    std::array<float, 24> constant{};
    std::size_t offset = 0U;
    for (std::int32_t blue = 0; blue <= 1; ++blue) {
        for (std::int32_t green = 0; green <= 1; ++green) {
            for (std::int32_t red = 0; red <= 1; ++red) {
                inversion[offset] = 1.0F - static_cast<float>(red);
                channel_swap[offset] = static_cast<float>(blue);
                constant[offset++] = 0.25F;
                inversion[offset] = 1.0F - static_cast<float>(green);
                channel_swap[offset] = static_cast<float>(red);
                constant[offset++] = 0.5F;
                inversion[offset] = 1.0F - static_cast<float>(blue);
                channel_swap[offset] = static_cast<float>(green);
                constant[offset++] = 0.75F;
            }
        }
    }
    float output[3] = {};
    Lut3dView inversion_lut{.size = 2, .samples = inversion.data(), .sample_count = inversion.size()};
    justcamera::processing::SampleLut3d(inversion_lut, 0.5F, 0.25F, 0.75F, output);
    assert(Near(output[0], 0.5F) && Near(output[1], 0.75F) && Near(output[2], 0.25F));

    Lut3dView swap_lut{.size = 2, .samples = channel_swap.data(), .sample_count = channel_swap.size()};
    justcamera::processing::SampleLut3d(swap_lut, 0.2F, 0.4F, 0.8F, output);
    assert(Near(output[0], 0.8F) && Near(output[1], 0.2F) && Near(output[2], 0.4F));

    Lut3dView constant_lut{.size = 2, .samples = constant.data(), .sample_count = constant.size()};
    justcamera::processing::SampleLut3d(constant_lut, 0.0F, 1.0F, 0.5F, output);
    assert(Near(output[0], 0.25F) && Near(output[1], 0.5F) && Near(output[2], 0.75F));

    inversion_lut.domain_min[0] = 0.2F;
    inversion_lut.domain_min[1] = 0.2F;
    inversion_lut.domain_min[2] = 0.2F;
    inversion_lut.domain_max[0] = 0.8F;
    inversion_lut.domain_max[1] = 0.8F;
    inversion_lut.domain_max[2] = 0.8F;
    justcamera::processing::SampleLut3d(inversion_lut, 0.2F, 0.5F, 0.8F, output);
    assert(Near(output[0], 1.0F) && Near(output[1], 0.5F) && Near(output[2], 0.0F));
}

void TestInvalidInputBeforeMutation() {
    std::array<float, 3> samples{0.2F, std::numeric_limits<float>::quiet_NaN(), 0.4F};
    ImageFrameView frame = Frame(samples.data(), samples.size(), 3);
    const NativeOperation operation{NativeOperationType::kExposure, 1.0F, 1.0F, {}};
    assert(justcamera::processing::ProcessOperations(frame, &operation, 1U) ==
           NativeStatus::kInvalidArgument);
    assert(samples[0] == 0.2F);

    frame.sample_capacity = 2U;
    assert(justcamera::processing::ProcessOperations(frame, &operation, 1U) ==
           NativeStatus::kInvalidBuffer);

    frame = Frame(samples.data(), samples.size(), 3);
    frame.width = 0;
    assert(justcamera::processing::ProcessOperations(frame, &operation, 1U) ==
           NativeStatus::kInvalidArgument);

    constexpr std::array<float, 3> bad_lut{0.0F, 0.0F, 0.0F};
    NativeOperation bad_lut_operation{
        NativeOperationType::kLut3d,
        0.0F,
        1.0F,
        Lut3dView{.size = 2, .samples = bad_lut.data(), .sample_count = bad_lut.size()},
    };
    samples = {0.2F, 0.3F, 0.4F};
    frame = Frame(samples.data(), samples.size(), 3);
    assert(justcamera::processing::ProcessOperations(frame, &bad_lut_operation, 1U) ==
           NativeStatus::kInvalidLut);
}

}  // namespace

int main() {
    TestExposureAndAlpha();
    TestContrastAndSaturation();
    TestReferenceVectors();
    TestIdentityLutAndTransferThresholds();
    TestLutInterpolationOrderingAndDomain();
    TestInvalidInputBeforeMutation();
    return 0;
}
