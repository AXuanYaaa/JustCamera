# Camera and Processing Pipeline

## Camera capture path (unchanged by PH4)

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

## Future capture/preview integration (not implemented)

```text
Camera2 RAW/YUV
    ↓
owned native/image buffer
    ↓
input conversion / demosaic / white balance
    ↓
future denoise / HDR / multi-frame fusion using a distinct scene-linear representation
    ↓
color science / tone mapping
    ↓
PH4 FilterEngine node
    ↓
encoder
    ↓
MediaStore
```

Each future stage must execute on a processing/native worker and carry metadata explicitly. PH4
does not intercept current JPEG saves, filter the live `TextureView`, or alter DNG. A future JPEG/YUV
decoder or RAW developer must produce the declared RGB working frame before calling FilterEngine.
