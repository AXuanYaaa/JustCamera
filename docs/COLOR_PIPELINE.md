# Color Pipeline Contract

## Working representation

PH3/PH4 filters operate only on `RgbFloatFrame`:

- sRGB/BT.709 primaries;
- linear transfer function;
- display-referred normalized floats in `[0, 1]`;
- packed RGB or straight-alpha RGBA;
- finite values only; output is clamped after every reference operation;
- orientation, timestamp, and source metadata travel with the frame.

`ImageFrame` distinguishes encoded JPEG/DNG, RAW_SENSOR, YUV_420_888, RGBA_8888, RGB_F32, and
RGBA_F32, and records bit depth, channel layout, color primaries, transfer, alpha semantics, row
stride, and pixel stride. Only RGB_F32/RGBA_F32 explicitly marked linear sRGB can enter PH4.

```text
encoded JPEG / YUV / developed RAW
    ↓ explicit decoder + color conversion (integration outside PH4)
linear-sRGB RgbFloatFrame
    ↓ exposure → tonal/color adjustments
    ↓ linear-to-sRGB transfer
encoded-sRGB 3D LUT + strength blend
    ↓ sRGB-to-linear transfer
linear-sRGB output → future encoder/render adapter
```

RAW_SENSOR is mosaic sensor data, not RGB. PH4 performs no demosaic, camera matrix, sensor white
balance, or RAW tone mapping. DNG remains an archival sensor-domain output.

## Reference formulas

- Exposure uses linear-light EV semantics: `channel × 2^EV`.
- Contrast uses the fixed linear pivot `0.18`: `(channel - 0.18) × contrast + 0.18`.
- Saturation uses Rec.709 linear luma weights `0.2126, 0.7152, 0.0722`.
- Temperature/tint uses small RGB scale offsets. It is a stable UI/processing foundation, not a
  replacement for chromatic adaptation or RAW white balance.
- Highlights/shadows use luma-weighted bounded lifts/cuts.
- Fade lifts black and gently compresses range.
- Vignette uses a radial smoothstep multiplier and preserves alpha.
- Built-in adjustment `strength` blends original and adjusted channels in linear sRGB.

Intermediate invalid values are never propagated; inputs are validated and each output channel is
finite-clamped to `[0, 1]`. PH4 scalar C++ uses the same constants, thresholds, encoded-sRGB LUT
blend, R-fast/G-next/B-slowest indexing, and straight-alpha preservation. JVM/device parity uses an
absolute tolerance of `2e-5`; standalone core tests use `1e-5`. Any later SIMD implementation must
pass the same oracle vectors or explicitly version a deliberate formula change.

PH5 does not reinterpret this display-referred bounded frame. It adds `SceneLinearFrame` with
sRGB/BT.709 primaries, linear-light Float32 RGB, explicit packed row stride, timestamp/metadata,
and exposure-normalization metadata. All values must be finite and non-negative; negative values
are rejected, and positive values above 1 are preserved through normalization and merge.

```text
Camera2 YUV_420_888 (default JFIF/Rec.601 full-range transform)
    ↓ Rec.601 YUV matrix + full-byte quantization → encoded sRGB channels
inverse sRGB transfer
    ↓ ISP-derived linear RGB
divide by actual (shutter × ISO) exposure ratio
    ↓ SceneLinearFrame, values may exceed 1
alignment + motion-aware radiance merge
    ↓ scene-referred HDR approximation
luminance-aware global Reinhard tone map
    ↓ clamp only here
PH3/PH4 display-referred linear-sRGB RgbFloatFrame [0,1]
```

The Rec.601 YUV matrix and full quantization range, the sRGB/Rec.709 RGB primaries, and the sRGB
transfer function are separate parts of this contract. Default Camera2 YUV uses the JFIF/Rec.601
full-range transform and the resulting encoded RGB is interpreted in the sRGB color space. Camera
YUV has already passed vendor ISP color, denoise, sharpening, and tone behavior, so inverse sRGB
followed by division by shutter × ISO produces an ISP-derived scene-linear approximation rather
than calibrated raw-domain radiance. ISO gain is not perfectly linear across cameras. Future RAW
HDR needs demosaic, black/white levels, camera matrices, white balance, and a distinct calibrated
path; DNG behavior remains untouched.
