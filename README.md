# JustCamera

JustCamera is an Android Camera2 foundation for a long-lived professional camera and
computational-photography platform. The repository is currently at **PH2 — Pro Controls + RAW
Capture Foundation**. It is not yet a replacement for a production system camera.

## Current status

PH2 provides:

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
- minimal `ImageFrame`, processing pipeline, and data-driven filter contracts;
- a real C++ JNI native-core version call;
- a versioned C plugin ABI, registry, validation loader foundation, and a deliberately disabled
  external plugin install path;
- host-side unit tests for control validation/math, zoom/metering, RAW policy, DNG pairing,
  capability mapping, state, geometry, pipeline, filter, and ABI logic.

PH2 does **not** provide manual color temperature/gains, RAW development, YUV processing,
filters/LUT rendering, HDR, night mode, denoise, super resolution, external plugin installation,
or GPU processing. Those remain later roadmap work.

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
filter/api → imaging/frame ← imaging/pipeline
plugin/host → plugin/api + plugin/model → native C ABI
nativecore → JNI → C++
```

Android framework types stop at platform adapters. UI reads `CameraCapabilities`, never
`CameraCharacteristics`; processing reads `ImageFrame`, never retains `android.media.Image`.
See [Architecture](docs/ARCHITECTURE.md) and [Camera pipeline](docs/CAMERA_PIPELINE.md).

## Roadmap

The phased plan is in [ROADMAP.md](docs/ROADMAP.md). PH3 remains gated on PH2 real-device
validation. See [Pro controls](docs/PRO_CONTROLS.md) and [RAW capture](docs/RAW_CAPTURE.md).

## License status

No open-source license has been selected yet. Until a license file is added, the source is not
granted for redistribution or reuse outside its copyright owner's permissions.
