# Filter Engine

## Boundaries

PH3 keeps color processing independent from Camera2, Compose, and Android `Image`. The project-owned
`ImageFilter` contract consumes an immutable `RgbFloatFrame`, validated `FilterParameters`, and a
`FilterExecutionContext`. Descriptors expose stable ID, category, implementation type, supported
PREVIEW/FINAL_CAPTURE modes, version, determinism, preview safety, final-quality capability, and a
typed parameter schema.

`FilterRegistry` accepts any implementation of that contract, rejects duplicate IDs, and exposes
descriptors by category/mode. This is the logical integration point for a future versioned native
FILTER plugin adapter; PH3 does not load external executable code and does not change the PH1 ABI.

## Parameters and chains

Schemas support float, int, boolean, and enum values. Numeric values declare default, range, and
step. Validation supplies missing defaults, clamps finite out-of-range values, replaces wrong or
non-finite values with defaults, rejects unknown keys, and returns structured issues. A malformed
preset therefore cannot inject NaN or crash a built-in processing loop.

`FilterChain.operations` is ordered and serializable. Each operation binds a stable filter ID,
parameters, enabled state, and optional app-private LUT reference. Disabled operations remain in
the model but do not run. An empty chain is identity. `FilterEngine` resolves the chain once, then
runs enabled filters in declaration order on `Dispatchers.Default`, checking coroutine cancellation
between operations; filter loops also check cancellation by row.

The engine is connected to the original `ProcessingPipeline` through one `ProcessingNode`. That
adapter accepts only declared linear-sRGB RGB_F32/RGBA_F32 frames, runs the chain, and records
applied IDs/validation issues in output metadata. It does not create a second pipeline system.

## Built-ins and presets

The CPU reference filters are exposure, contrast, saturation, temperature/tint, highlights/shadows,
fade, vignette, and a parameterized 3D LUT filter. They allocate one output buffer per operation
and no objects per pixel. These algorithms are correctness foundations, not claims of professional
RAW color science.

Five project-authored looks demonstrate deterministic chains: Neutral, Soft Contrast, Warm Film,
Clean B&W, and Cinematic Soft. The versioned `PresetCodec` round-trips preset metadata, operation
order, typed parameters, enable state, and LUT references without Android/UI objects.

## Preview and final integration

PREVIEW and FINAL_CAPTURE use the same API and are descriptor capabilities, not unrelated paths.
The PH3 UI browses descriptors and generates controls from schemas, but clearly labels that the live
Camera2 `TextureView` is not pixel-filtered yet. Final JPEG interception is also deliberately not
enabled: silently decoding/re-encoding every capture would add quality and lifecycle risk. A later
owned RGB conversion/encode stage can insert the existing filter node. RAW/DNG remains untouched.
