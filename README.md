# JustCamera

JustCamera is an Android Camera2 foundation for a long-lived professional camera and
computational-photography platform. The repository is currently at **PH3 — Filter Engine + LUT
Foundation**. It is not yet a replacement for a production system camera.

## Current status

PH3 provides:

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
- validated compact 1D/3D LUT models, strict `.cube` parsing, trilinear interpolation, and encoded
  sRGB LUT strength blending;
- a descriptor-driven filter configuration UI that explicitly does not claim live pixel rendering;
- a real C++ JNI native-core version call;
- a versioned C plugin ABI, registry, validation loader foundation, and a deliberately disabled
  external plugin install path;
- host-side unit tests for control validation/math, zoom/metering, RAW policy, DNG pairing,
  capability mapping, state, geometry, pipeline, filter, and ABI logic.

PH3 does **not** provide sensor white-balance color science, RAW development/demosaic, Camera2 live
filter rendering, automatic captured-JPEG recompression, HDR, night mode, denoise, super resolution,
external plugin installation, native filter kernels, or GPU processing. Those remain later phases.

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
filter/api + registry + builtin + lut + preset → imaging/frame + imaging/pipeline
plugin/host → plugin/api + plugin/model → native C ABI
nativecore → JNI → C++
```

Android framework types stop at platform adapters. UI reads `CameraCapabilities`, never
`CameraCharacteristics`; processing reads `ImageFrame`, never retains `android.media.Image`.
See [Architecture](docs/ARCHITECTURE.md), [Filter engine](docs/FILTER_ENGINE.md),
[Color pipeline](docs/COLOR_PIPELINE.md), and [LUT format](docs/LUT_FORMAT.md).

## Roadmap

The phased plan is in [ROADMAP.md](docs/ROADMAP.md). Real-device camera/RAW validation and future
owned RGB preview/capture adapters remain required. See [Pro controls](docs/PRO_CONTROLS.md) and
[RAW capture](docs/RAW_CAPTURE.md).

## License status

No open-source license has been selected yet. Until a license file is added, the source is not
granted for redistribution or reuse outside its copyright owner's permissions.
