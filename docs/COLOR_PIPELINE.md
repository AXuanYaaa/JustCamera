# Color Pipeline Contract

## Working representation

PH3 filters operate only on `RgbFloatFrame`:

- sRGB/BT.709 primaries;
- linear transfer function;
- display-referred normalized floats in `[0, 1]`;
- packed RGB or straight-alpha RGBA;
- finite values only; output is clamped after every reference operation;
- orientation, timestamp, and source metadata travel with the frame.

`ImageFrame` distinguishes encoded JPEG/DNG, RAW_SENSOR, YUV_420_888, RGBA_8888, RGB_F32, and
RGBA_F32, and records bit depth, channel layout, color primaries, transfer, alpha semantics, row
stride, and pixel stride. Only RGB_F32/RGBA_F32 explicitly marked linear sRGB can enter PH3.

```text
encoded JPEG / YUV / developed RAW
    ↓ explicit decoder + color conversion (integration outside PH3)
linear-sRGB RgbFloatFrame
    ↓ exposure → tonal/color adjustments
    ↓ linear-to-sRGB transfer
encoded-sRGB 3D LUT + strength blend
    ↓ sRGB-to-linear transfer
linear-sRGB output → future encoder/render adapter
```

RAW_SENSOR is mosaic sensor data, not RGB. PH3 performs no demosaic, camera matrix, sensor white
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
finite-clamped to `[0, 1]`. Later PH4 native/SIMD implementations must match these reference tests
or explicitly version a different algorithm.
