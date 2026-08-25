# Architecture

## PH1 shape

JustCamera starts as one Android application module. Boundaries are packages with concrete
behavior rather than empty Gradle modules; each top-level boundary can later move to a separate
module without reversing dependencies.

```text
Compose UI
    ↓ domain state + user intents
Camera Application (`CameraController`)
    ↓
Camera Core (`CameraEngine`, discovery, session, capture)
    ↓ platform adapters
Camera2 / ImageReader / MediaStore

Camera2 image boundary (future RAW/YUV, JPEG today)
    ↓
ImageFrame
    ↓
ProcessingPipeline
    ↓
ImageFilter
    ↓
Native processing / encoder

PluginHost
    ↓ validated descriptors
stable C Plugin ABI
    ↓
private-storage `.so`
```

## Dependency rules

1. `ui` depends on application controllers and project-owned models only. It does not construct
   Camera2 requests or consume `CameraCharacteristics`.
2. `camera/application` exposes a small lifecycle/intent facade. `camera/device` owns all
   `CameraDevice` and `CameraCaptureSession` objects.
3. `camera/capability` is the only mapping boundary from `CameraCharacteristics` to
   `CameraCapabilities`. Its mapper consumes a pure raw-value model so it can be tested on the JVM.
4. `camera/capture` owns MediaStore persistence; capture callbacks never perform disk I/O.
5. `imaging` and `filter` do not depend on Compose. Android `Image` is acquired and closed at the
   camera boundary; the future processing adapter must copy or transfer planes into `ImageFrame`
   with explicit ownership.
6. `plugin/host` depends on descriptors and the stable API version, never on a concrete plugin.
   External loading stays disabled until its trust/install model is implemented.
7. Kotlin calls native code through `nativecore`. C plugins expose only the documented C ABI.

## State and errors

Camera2 callbacks are reduced to the sealed `CameraState` domain model and exposed as a
`StateFlow`. JPEG work has a separate `CaptureStatus`, so persistence progress does not leak
callback details. Expected failures use `CameraErrorCode` for permission, availability,
disconnect, access, session, capture, storage, surface, and unsupported-capability cases.

## Thread model

- **UI thread:** activity lifecycle integration, Compose state collection, and view callbacks.
- **Camera thread (`JustCamera-Camera`):** device/session creation, requests, state callbacks, and
  deterministic camera resource ownership.
- **Image acquisition thread (`JustCamera-ImageAcquire`):** promptly acquires JPEG output, copies
  encoded bytes, and closes `Image`; it does not write to storage.
- **I/O dispatcher:** MediaStore insert/write/finalization and future metadata/file operations.
- **Future processing worker:** RAW/YUV conversion, HDR and multi-frame pipeline work.
- **Future native worker:** CPU-intensive C++ processing. JNI calls must not occupy the camera
  callback thread.

This separation keeps Camera2 callback latency independent from storage and future processing.

## Resource ownership

`CameraEngine` has a single close path for capture session, device, `ImageReader`, and preview
`Surface`. Activity `onStop` closes resources while preserving the available `SurfaceTexture` so
`onStart` can reopen. Surface destruction closes camera resources before the texture is forgotten.
Device disconnect and device/session errors go through the same close/error model.
