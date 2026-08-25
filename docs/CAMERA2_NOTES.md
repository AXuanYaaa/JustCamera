# Camera2 Notes

## Capability interpretation

Camera IDs and characteristics are device/HAL truth, not marketing labels. Hardware level does
not replace individual capability checks. RAW, manual sensor, manual post-processing, burst,
reprocessing, logical multi-camera, and depth are derived independently from
`REQUEST_AVAILABLE_CAPABILITIES`.

Every optional characteristic is nullable in the raw mapping. Missing ISO/exposure ranges,
apertures, focus distance, stabilization modes, physical IDs, and stream sizes become safe domain
defaults or “not reported” values. A failure reading one camera does not discard successfully
mapped cameras.

## Preview

- `TextureView` is used because it gives reliable Camera2 `Surface` interop plus a transform matrix.
- Preview sizes come from `SurfaceTexture` output sizes. Selection prefers the closest view aspect
  ratio and bounds normal preview work to 1080p when the HAL exposes such a choice.
- The HAL buffer size is set before the `Surface` and capture session are created.
- The transform center-crops the buffer for the display rotation. Front preview remains naturally
  mirrored by `TextureView`; capture orientation is set independently.
- A configuration change recreates the activity and closes/reopens Camera2. Background/foreground
  transitions close in `onStop` and reopen in `onStart` when permission and a surface are ready.

## Session and capture

The session contains preview and maximum-size JPEG surfaces. Continuous-picture AF is requested
only when the characteristics report it. Other controls remain in Camera2 automatic mode in PH1.
JPEG capture is serialized while capture/save is active.

Camera device errors, disconnects, access exceptions, invalid surfaces, configure failures, and
capture failures are converted into project errors and logged by category. There are no empty
catch blocks. Reopening after a device/service error should be validated on hardware because HAL
recovery behavior varies by vendor.

## Device validation checklist

1. First permission grant, denial, “don’t ask again,” and settings re-grant.
2. Rear/front/external switch where present; rapid repeated switch.
3. Rotate at 0/90/180/270 degrees and verify preview crop plus JPEG EXIF orientation.
4. Background during preview and during save; return and capture again.
5. Ten or more captures, including rapid shutter attempts, while monitoring memory.
6. Camera-in-use conflict and system privacy/policy disable behavior.
7. API 26–28 storage permission/save and API 29+ scoped storage save.
8. Capability screen on legacy, limited, full, level-3, logical, and RAW-capable devices.
