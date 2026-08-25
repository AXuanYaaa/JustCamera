# Camera Pipeline

## Current PH2

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

## Processing contract established in PH1

```text
ImageFrame → ProcessingNode[0] → … → ProcessingNode[n] → ImageFrame
```

`ImageFrame` records dimensions, format, timestamp, rotation, metadata, plane buffers, row/pixel
stride, and buffer ownership. A pipeline is sequential and suspendable. Preview and final-capture
intent are explicit so later filters can provide latency-first and quality-first implementations.

## Future processing pipeline (not implemented)

```text
Camera2 RAW/YUV
    ↓
owned native/image buffer
    ↓
input conversion / demosaic / white balance
    ↓
denoise / HDR / multi-frame fusion
    ↓
color science / tone mapping
    ↓
filter / sharpen / grain / vignette
    ↓
encoder
    ↓
MediaStore
```

Each future stage must execute on a processing worker or native worker and carry capture metadata
explicitly. PH2 only stores sensor RAW as DNG; it does not demosaic, denoise, merge, filter, or
render RAW pixels.
