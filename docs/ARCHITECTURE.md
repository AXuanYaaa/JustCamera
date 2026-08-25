# Architecture

## PH2 shape

JustCamera starts as one Android application module. Boundaries are packages with concrete
behavior rather than empty Gradle modules; each top-level boundary can later move to a separate
module without reversing dependencies.

```text
Compose UI
    ↓ domain state + user intents
Camera Application (`CameraController`)
    ↓
Camera orchestration (`CameraEngine`)
    ↓                    ↘
request/control policy   capability mapping + RAW/capture coordinators
(`CameraRequestController`)  (`DngPairingQueue`, MediaStore adapters)
    ↓ platform adapters
Camera2 / ImageReader / DngCreator / MediaStore

Camera2 image boundary (JPEG + RAW/DNG today, future YUV processing)
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
2. `camera/application` exposes a small lifecycle/intent facade. `CameraEngine` remains the
   orchestration/state owner, while the camera-thread-confined `CameraSessionController` is the
   sole owner of `CameraDevice`, `CameraCaptureSession`, `ImageReader`, and preview `Surface`.
3. `camera/control` owns the requested project control state, pure validation/conversion logic,
   and the only adapter that applies settings to `CaptureRequest.Builder`. Preview and still
   capture both call this adapter; UI never sees Camera2 request keys.
4. `camera/capability` is the only mapping boundary from `CameraCharacteristics` to
   `CameraCapabilities`. Its mapper consumes a pure raw-value model so it can be tested on the JVM.
5. `camera/raw` owns the pure bounded timestamp-pairing policy. `camera/capture` owns multi-output
   completion tracking and MediaStore/DNG persistence; callbacks never perform disk I/O.
6. `imaging` and `filter` do not depend on Compose. Android `Image` is acquired and closed at the
   camera boundary; the future processing adapter must copy or transfer planes into `ImageFrame`
   with explicit ownership.
7. `plugin/host` depends on descriptors and the stable API version, never on a concrete plugin.
   External loading stays disabled until its trust/install model is implemented.
8. Kotlin calls native code through `nativecore`. C plugins expose only the documented C ABI.

## State and errors

Camera2 callbacks are reduced to the sealed `CameraState` domain model and exposed as a
`StateFlow`. Requested controls and observed capture metadata use separate `StateFlow` values;
requested ISO/shutter/focus is never presented as observed hardware state. Multi-output
`CaptureStatus` distinguishes complete, partial, and failed JPEG/DNG saves. Expected failures use
`CameraErrorCode` for lifecycle, validation, RAW pairing/capture, DNG encoding, and storage cases.
For device/session failures, `CameraState.Error` is published only after all owned Camera2
resources have been closed; a recoverable Error is therefore also a valid source state for Open.

## Thread model

- **UI thread:** activity lifecycle integration, Compose state collection, and view callbacks.
- **Camera thread (`JustCamera-Camera`):** device/session creation, all request mutations, state
  callbacks, result metadata mapping, and deterministic camera resource ownership.
- **Image acquisition thread (`JustCamera-ImageAcquire`):** acquires JPEG/RAW outputs. JPEG bytes
  are copied promptly; a paired RAW `Image` transfers to the DNG writer. No storage I/O runs here.
- **I/O dispatcher:** MediaStore insert/write/finalization and `DngCreator.writeImage`.
- **Future processing worker:** RAW/YUV conversion, HDR and multi-frame pipeline work.
- **Future native worker:** CPU-intensive C++ processing. JNI calls must not occupy the camera
  callback thread.

This separation keeps Camera2 callback latency independent from storage and future processing.

## Resource ownership

`CameraSessionController.closeAll()` is the one ownership boundary for capture session, device,
JPEG/RAW `ImageReader`, and preview `Surface`. Every mutation checks that it runs on the camera
looper. Acquired RAW images have explicit transient ownership: the bounded pairing queue owns an
unpaired image, then transfers it either to the DNG I/O job or to immediate close/eviction.
The TextureView-owned `SurfaceTexture` is never passed into that ownership boundary and is never
released by camera recovery.

Lifecycle close invalidates outstanding callback generations, cancels scheduled retries, closes
owned resources, and emits `Closed` at most once. A device disconnect, fatal device callback,
session configure failure, or preview startup failure uses `failAndClose`: invalidate stale
callbacks, close everything, emit one `Error`, and schedule a bounded backoff retry when the error
and current permission/surface/selection prerequisites allow it. Selected camera and
`SurfaceTexture` survive this path, so retry creates fresh wrappers/resources without an Activity
restart. Successful preview resets the retry budget. Explicit `onStop` and surface destruction do
not retry.

Ordinary ISO, shutter, focus, WB, lock, metering, and zoom changes rebuild only the repeating
preview request. The session topology is unchanged. A RAW-capable camera attempts preview + JPEG +
RAW once; if the HAL rejects that topology, the generation is closed through the PH1.1 failure
path and the camera retries JPEG-only without presenting RAW as available for that engine lifetime.
