# Architecture

## PH5 shape

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
request/control policy   capability mapping + still capture coordination
(`CameraRequestController`)  (`StillCaptureCoordinator` + RAW leases)
    ↓ platform adapters
Camera2 / ImageReader / DngCreator / MediaStore

HDR mode: CameraEngine request/session orchestration
    ↓ Preview + bounded YUV session
HdrCaptureCoordinator → copied owned YUV + actual result metadata
    ↓
HdrProcessingPipeline → normalize → align → merge → tone map
    ↓
SceneLinearFrame (>1 allowed) → RgbFloatFrame ([0,1]) → PH3/PH4 FilterEngine

RGB working frame
    ↓
ProcessingPipeline → FilterEngine → ordered FilterChain
                         ↓
        registry + built-ins + LUT + presets
                         ↓ compatible operation runs
              Processing backend selection
                  ├─ PH3 Kotlin oracle
                  └─ direct buffers → JNI → C++ scalar core

Future decoded JPEG/YUV/developed RAW adapter
    ↓ explicit owned RGB conversion
ImageFrame → ProcessingPipeline → native processing / encoder

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
   orchestration/state owner, while the camera-thread-confined `CameraSessionController` owns the
   live `CameraDevice`, `CameraCaptureSession`, readers, and preview `Surface`.
3. `camera/control` owns the requested project control state, pure validation/conversion logic,
   and the only adapter that applies settings to `CaptureRequest.Builder`. Preview and still
   capture both call this adapter; UI never sees Camera2 request keys.
4. `camera/capability` is the only mapping boundary from `CameraCharacteristics` to
   `CameraCapabilities`. Its mapper consumes a pure raw-value model so it can be tested on the JVM.
5. `camera/raw` owns pure timestamp-pairing, RAW topology, and in-flight ownership policy.
   `StillCaptureCoordinator` owns capture token/generation, expected outputs, pairing, timeout,
   partial completion, RAW leases, and save-job lifecycle; callbacks never perform disk I/O.
6. `imaging` and `filter` do not depend on Compose or Camera2. Android `Image` closes at the camera
   boundary. PH3/PH4 filters accept only immutable normalized linear-sRGB `RgbFloatFrame`; RAW, YUV,
   JPEG, and DNG require an explicit future conversion adapter. `FilterEngine.processingNode()` is
   the single bridge into the original pipeline.
7. `plugin/host` depends on descriptors and the stable API version, never on a concrete plugin.
   External loading stays disabled until its trust/install model is implemented.
8. `FilterEngine` remains the processing orchestrator. Compatible filters provide a small native
   operation description; the selected backend executes a whole compatible run or invokes the
   same filter's PH3 Kotlin implementation. UI and pipeline callers see one stable filter ID and
   parameter schema regardless of backend.
9. Kotlin calls the built-in C++ core through a narrow internal JNI/direct-buffer boundary.
   `nativecore` owns load/version/capability diagnostics. This protocol is not the C plugin ABI,
   and built-ins are linked normally rather than routed through `dlopen`.
10. `camera/hdr/HdrCaptureCoordinator` owns only one HDR token, bounded timestamp pairing,
    timeout, copied-frame collection, cancellation, processing progress, and terminal publication.
    `CameraEngine` still owns the Camera2 state and submits requests; HDR processing classes have
    no Camera2 dependency.
11. `SceneLinearFrame` is a distinct scene-referred approximation with finite non-negative Float32
    RGB and no upper clamp. It cannot enter PH3/PH4. Only `ReinhardToneMapper` produces the
    normalized display-referred `RgbFloatFrame` accepted by the existing FilterEngine.
12. Preview geometry is a pure camera-domain contract. Camera2/SurfaceTexture owns producer
    orientation exactly once. `PreviewTransformCalculator` owns only front mirroring, uniform
    center-crop scale, centering, and inverse focus mapping; it never rotates. Compose receives
    only project models, and the Android `Matrix` is an axis-aligned TextureView stretch adapter,
    not the source of geometry truth.
13. `AppSettingsRepository` owns non-camera preferences. `AppLanguage` defaults to `ZH_CN`, and
    `AppCompatDelegate` applies the persisted application locale. Settings persistence stays on
    the UI/application side and never enters the camera thread. Camera, Filters, Settings, and the
    developer-only capability view use a small explicit destination state rather than a new
    navigation framework.

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
  are copied promptly; a paired RAW `Image` transfers to the DNG writer. HDR YUV planes and their
  strides are copied immediately into immutable owned buffers, then the Android `Image` closes.
- **I/O dispatcher:** MediaStore insert/write/finalization and `DngCreator.writeImage`.
- **Processing dispatcher:** HDR conversion/alignment/merge/tone map, filter-chain validation,
  Kotlin reference loops, direct-buffer preparation, and synchronous native filter processing.
  It never runs on the UI or camera thread.

This separation keeps Camera2 callback latency independent from storage and future processing.

## Resource ownership

`CameraSessionController.closeAll()` is the live-resource boundary for capture session, device,
JPEG/RAW/HDR `ImageReader`, and preview `Surface`. Every device/session mutation checks the camera
looper. During shutdown, the RAW reader is logically detached and transferred to
`StillCaptureCoordinator` for retirement. A generation-aware lease count prevents physical reader
close while an acquired image is paired or being consumed by `DngCreator`; unpaired images drain
immediately, and the final DNG `finally` closes `Image` before closing its retired reader. Thus a
long save never blocks the camera thread and cannot use an image invalidated by reader close. The
TextureView-owned `SurfaceTexture` never enters either ownership boundary and recovery never
releases it.

Lifecycle close invalidates outstanding callback generations, cancels scheduled retries, closes
owned resources, and emits `Closed` at most once. A device disconnect, fatal device callback,
session configure failure, or preview startup failure uses `failAndClose`: invalidate stale
callbacks, close everything, emit one `Error`, and schedule a bounded backoff retry when the error
and current permission/surface/selection prerequisites allow it. Selected camera and
`SurfaceTexture` survive this path, so retry creates fresh wrappers/resources without an Activity
restart. Successful preview resets the retry budget. Explicit `onStop` and surface destruction do
not retry.

Ordinary ISO, shutter, focus, WB, lock, metering, and zoom changes rebuild only the repeating
preview request. The session topology is unchanged. A confirmed HAL rejection of preview + JPEG +
RAW downgrades only the current selected-camera tenure; transient access/service/lifecycle failures
preserve RAW through normal retry. Switching away and back creates a new tenure and retries RAW.

Already-started MediaStore saves finish safely across switch, stop, error recovery, and engine
release. The coordinator accepts no new jobs after release and cancels its scope only after all
started jobs complete, ensuring every pending MediaStore row is published or cleaned up.

HDR uses a parallel lease rule specialized for prompt copying. An acquired YUV `Image` has exactly
one copy lease. Closing or reconfiguring the camera logically retires its reader; physical close is
deferred only until outstanding copies close their images. Copied byte arrays, not Android Images,
enter pairing/processing. Generation checks reject late callbacks, pairing is count- and age-bounded,
one timeout owns terminal cleanup, and lifecycle cancellation invalidates the token and prevents
processed output publication. Normal sessions remain Preview + JPEG + optional RAW. HDR sessions
are deliberately Preview + bounded YUV; if this topology is rejected, all resources close, HDR is
disabled for that attempt, and the standard topology reopens through the existing bounded retry.

PH4 processing has a separate call-scoped ownership rule. Kotlin owns the immutable source frame
and allocates one native-order direct working buffer plus optional flattened validated LUT data for
each fused native run. JNI borrows their addresses only until the call returns; C++ retains no
pointer, handle, LUT, scratch buffer, or mutable global state. C++ mutates only the owned working
copy, so failure leaves the source frame intact for Kotlin fallback. Direct-buffer backing storage
is VM-owned; no C++ allocation/release API crosses JNI. Local C++ vectors use RAII and all
exceptions are caught before returning a status.
