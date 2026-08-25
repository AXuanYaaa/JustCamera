# Camera and Processing Pipeline

## Standard capture path (preserved in PH5)

```text
Camera2 device
    ↓ repeating request
TextureView Surface (preview)

Camera2 still request
    ├─ JPEG ImageReader → timestamp match → ByteArray → MediaStore
    └─ RAW ImageReader  → timestamp match with TotalCaptureResult
                               ↓ I/O dispatcher
                     DngCreator.writeImage → MediaStore
    ↓
DCIM/JustCamera
```

The preview, JPEG, and optional RAW surfaces share one capture session. Capture continues without
stopping the repeating preview request. The same validated `CameraControlState` is applied to
preview and still builders. `JPEG_ORIENTATION` is computed from sensor orientation, display
rotation, and lens facing.

JPEG bytes and RAW images are correlated with the still result sensor timestamp, preventing late
frames from a timed-out capture from being assigned to a newer request. JPEG `Image` is copied and
closed on the acquisition thread. RAW remains open only while in the bounded pairing queue or DNG
writer, then is closed deterministically. `DngCreator` receives the matching characteristics,
`TotalCaptureResult`, and RAW `Image`; raw plane bytes are never mislabeled as DNG.

`JPEG_ONLY`, `RAW_ONLY`, and `JPEG_AND_RAW` use one request with the required targets. Completion
tracks each output independently, so one successful file plus one failure becomes
`PartialSuccess`. Missing outputs expire after a bounded timeout.

## PH5 HDR capture path

```text
stable preview TotalCaptureResult (actual shutter + ISO)
    ↓ pure HdrBracketPlanner (-2/0/+2 EV default, configurable)
Preview + YUV_420_888 HDR session
    ↓ one tagged manual-sensor request per entry
captureBurst when advertised; otherwise ordered sequential capture
    ├─ TotalCaptureResult: timestamp + actual shutter/ISO/focus
    └─ YUV Image: timestamp + copied plane bytes/strides → Image closes
                         ↓ bounded generation-aware pairing
                    HdrFrameSet → processing dispatcher
```

HDR and JPEG/RAW output modes are independent concepts. Turning HDR on reconfigures to Preview +
YUV instead of assuming Preview + JPEG + RAW + YUV is universally supported. Turning it off
restores the unchanged standard topology. A rejected HDR topology closes its device, session,
surface wrapper, and readers before reopening standard capture; it never blacklists the camera for
the process. `SurfaceTexture` remains TextureView-owned throughout.

Manual brackets derive from observed preview `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY`, not
UI labels. Time changes first, ISO compensates only when shutter/frame-duration limits require it.
AE is off in bracket requests, actual result values drive normalization, AWB is locked where the
device supports it, and the existing focus/zoom/WB controls otherwise remain stable. PH5 reports
burst-capable and basic sequential devices as distinct support levels, but enables capture only
when the deterministic manual-sensor metadata contract is complete. The less deterministic AE
compensation/convergence path is explicitly future work.

## PH4 processing path

```text
decoded/converted RGB_F32 ImageFrame
    ↓ existing ProcessingPipeline
FilterEngine ProcessingNode
    ↓ validated ordered FilterChain on processing dispatcher
    ↓ AUTO / KOTLIN_REFERENCE / NATIVE selection
    ├─ PH3 Kotlin reference filters
    └─ one direct-buffer JNI call per compatible run
linear-sRGB ImageFrame
```

`ImageFrame` now also records bit depth, channel layout, primaries, transfer, and alpha semantics.
The filter adapter rejects RAW, YUV, encoded JPEG/DNG, and ambiguous RGB. Only packed normalized
RGB_F32/RGBA_F32 declared as linear sRGB is accepted. PREVIEW and FINAL_CAPTURE use one filter API.
The Kotlin implementation remains the deterministic oracle. PH4 can fuse adjacent native-capable
exposure, contrast, saturation, and 3D LUT operations into one synchronous C++ scalar call. The
input is copied once into an internally mutable direct buffer and copied once into a new immutable
output frame. Unsupported filters split runs and execute in declaration order through Kotlin.
Recoverable native failure discards the working copy and reruns that run with the Kotlin oracle.

## PH5 processing integration

```text
copied Camera2 YUV + result metadata
    ↓
owned native/image buffer
    ↓
input conversion / demosaic / white balance
    ↓
exposure normalization + translational alignment + motion-aware HDR merge
    ↓
color science / tone mapping
    ↓
PH4 FilterEngine node
    ↓
encoder
    ↓
MediaStore
```

Each stage executes on a processing worker and carries metadata explicitly. PH5 does not intercept
current JPEG saves, filter the live `TextureView`, or alter DNG. It retains the final tone-mapped
frame in memory for the existing FilterEngine/future encoder handoff; RAW HDR development remains
a separate future pipeline.
