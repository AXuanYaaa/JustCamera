# Camera Pipeline

## Current PH1

```text
Camera2 device
    ↓ repeating request
TextureView Surface (preview)

Camera2 still request
    ↓
JPEG ImageReader
    ↓ acquire + copy + close Image (image acquisition thread)
ByteArray
    ↓ I/O dispatcher
MediaStore insert/write/finalize
    ↓
DCIM/JustCamera
```

The preview and JPEG surfaces share one capture session. Capture continues without stopping the
repeating preview request. `JPEG_ORIENTATION` is computed from sensor orientation, display
rotation, and lens facing. The image listener owns the `Image` for the shortest practical time;
MediaStore work cannot block the camera callback thread.

JPEG is the only capture output wired in PH1. Capability discovery already reports
`YUV_420_888` and `RAW_SENSOR`, but it does not create their readers or promise RAW output on
unsupported devices.

## Processing contract established in PH1

```text
ImageFrame → ProcessingNode[0] → … → ProcessingNode[n] → ImageFrame
```

`ImageFrame` records dimensions, format, timestamp, rotation, metadata, plane buffers, row/pixel
stride, and buffer ownership. A pipeline is sequential and suspendable. Preview and final-capture
intent are explicit so later filters can provide latency-first and quality-first implementations.

## Future pipeline (not implemented)

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
explicitly. Adding RAW in PH2 must not destabilize the PH1 preview/session lifecycle.
