# JustCamera

JustCamera is an Android Camera2 foundation for a long-lived professional camera and
computational-photography platform. The repository is currently at **PH5 — HDR Computational
Photography Foundation**. It is not yet a replacement for a production system camera.

## Current status

PH5 provides everything from PH1–PH4 plus:

- runtime camera permission integration and Android 8–9 legacy MediaStore permission;
- Camera2 discovery with a project-owned capability model;
- lifecycle-aware `TextureView` preview, size selection, display transform, and camera switching;
- full-resolution JPEG capture through `ImageReader` and scoped-storage-compatible MediaStore save;
- capability-driven manual ISO/shutter, EV, focus, white-balance, zoom, AE/AWB lock, AF-lock
  semantics, and tap-metering controls;
- one requested control state shared by preview and still requests, plus observed Camera2 metadata;
- RAW capability/topology detection, timestamp-bounded RAW/result pairing, valid DNG creation,
  and JPEG-only, RAW-only, or combined JPEG + RAW capture with partial-success reporting;
- `StateFlow` camera/capture state and a unified error model;
- a Compose camera screen and a live Camera2 capability inspector;
- an explicit linear-sRGB float working frame and the existing deterministic processing pipeline;
- a project-owned Filter API, descriptors, typed/clamped parameters, registry, ordered chains,
  execution modes, versioned presets, and five small built-in reference looks;
- CPU reference exposure, contrast, saturation, temperature/tint, highlights/shadows, fade,
  vignette, and cancellation-aware processing;
- a C++20 scalar processing core for exposure, contrast, saturation, color transfer, and 3D LUTs
  that preserves the PH3 numerical contract;
- a narrow validated JNI boundary using call-scoped direct buffers, fused compatible operation
  runs, explicit status codes, AUTO/Kotlin/native selection, and deterministic Kotlin fallback;
- native version/ABI/NEON capability diagnostics without claiming an active SIMD kernel;
- validated compact 1D/3D LUT models, strict `.cube` parsing, trilinear interpolation, and encoded
  sRGB LUT strength blending;
- a descriptor-driven filter configuration UI that explicitly does not claim live pixel rendering;
- pure C++ algorithm tests, JVM backend/fallback tests, and device-side JNI parity test sources;
- a versioned C plugin ABI, registry, validation loader foundation, and a deliberately disabled
  external plugin install path;
- host-side unit tests for control validation/math, zoom/metering, RAW policy, DNG pairing,
  capability mapping, state, geometry, pipeline, filter, and ABI logic.
- graded HDR capability levels and a deterministic bounded YUV processing-size selector;
- a dedicated Camera2 HDR mode using a Preview + YUV topology, actual-result-based manual
  exposure brackets, burst/sequential submission, and clean fallback to standard capture;
- generation-safe, timestamp-bounded YUV/result pairing with prompt plane copies and deferred
  `ImageReader` retirement while an acquired image is being copied;
- a separate unbounded-above `SceneLinearFrame`, BT.601 limited-range YUV conversion followed by
  inverse sRGB, actual shutter × ISO normalization, translational pyramid alignment, motion-aware
  weighted radiance merge, and luminance-aware Reinhard tone mapping;
- an explicit handoff to the unchanged PH3/PH4 normalized linear-sRGB `RgbFloatFrame` contract;
- functional HDR Off/On control, processing progress, capability/fallback reasons, timing
  diagnostics, luminance histogram foundation, and deterministic synthetic HDR tests.

PH5 computational HDR is an application-level reconstruction from ISP-processed YUV, not RAW-domain
radiance and not Android 10-bit `DynamicRangeProfiles`. It does **not** yet encode/save the derived
HDR result, provide HDR Auto scene intelligence, RAW HDR, sensor white-balance color science, live
filter rendering, night mode, denoise, super resolution, external plugin installation, active
hand-written NEON kernels, or GPU processing.

## Technology and Android versions

- Kotlin 2.0.21, Jetpack Compose, Camera2, coroutines and `StateFlow`
- C++20, JNI, CMake, and Android NDK
- `minSdk 26`, `targetSdk 36`, `compileSdk 36`
- Android Gradle Plugin 8.12.2 and Gradle 8.13
- JDK 17 bytecode (the build can run with JDK 21)

API 26 keeps coverage for Android 8 while allowing a focused Camera2 implementation. API 29+
uses `RELATIVE_PATH` and `IS_PENDING`; API 26–28 uses MediaStore with its required legacy write
permission. Compile/target 36 match the stable SDK installed on the development machine.

## Build

Install Android SDK Platform 36, Build Tools 36, Android NDK r27d, and CMake 4.1 with Ninja.
Set `sdk.dir`, `cmake.dir`, and (for an NDK outside the SDK) `justcamera.ndkPath` in untracked
`local.properties`, then run:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

The native library is part of the normal Android build; a successful `assembleDebug` therefore
also verifies CMake/NDK compilation and JNI packaging.

## Architecture

The project intentionally uses one Gradle `app` module with module-ready package boundaries:

```text
ui → camera/application → camera/device → Camera2
                   ↘ camera/control + camera/capability
                   ↘ camera/raw + camera/capture → DngCreator + MediaStore
                   ↘ camera/hdr → owned YUV + hdr/capture
hdr/yuv → normalization → alignment → merge → SceneLinearFrame → tone map
                                                              ↓
filter/api + registry + builtin + lut + preset → FilterEngine → backend selection
                                                       ├─ PH3 Kotlin oracle
                                                       └─ direct buffers → JNI → C++ scalar core
plugin/host → plugin/api + plugin/model → native C ABI
nativecore → JNI diagnostics
```

Android framework types stop at platform adapters. UI reads `CameraCapabilities`, never
`CameraCharacteristics`; processing reads `ImageFrame`, never retains `android.media.Image`.
See [Architecture](docs/ARCHITECTURE.md), [Filter engine](docs/FILTER_ENGINE.md),
[Color pipeline](docs/COLOR_PIPELINE.md), [Native processing](docs/NATIVE_PROCESSING.md), and
[HDR pipeline](docs/HDR_PIPELINE.md).

## Roadmap

The phased plan is in [ROADMAP.md](docs/ROADMAP.md). Real-device camera/RAW validation and future
owned RGB preview/capture adapters remain required. See [Pro controls](docs/PRO_CONTROLS.md) and
[RAW capture](docs/RAW_CAPTURE.md).

## License status

No open-source license has been selected yet. Until a license file is added, the source is not
granted for redistribution or reuse outside its copyright owner's permissions.
