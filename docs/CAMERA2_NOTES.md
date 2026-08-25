# Camera2 Notes

## Capability interpretation

Camera IDs and characteristics are device/HAL truth, not marketing labels. Hardware level does
not replace individual capability checks. RAW, manual sensor, manual post-processing, burst,
reprocessing, logical multi-camera, and depth are derived independently from
`REQUEST_AVAILABLE_CAPABILITIES`.

Every optional characteristic is nullable in the raw mapping. Missing ISO/exposure/EV ranges,
lock flags, zoom range, metering counts, apertures, focus distance, stabilization modes, physical
IDs, and stream sizes become safe domain defaults or “not reported” values. A failure reading one
camera does not discard successfully mapped cameras.

## Preview

- `TextureView` is used because it gives reliable Camera2 `Surface` interop plus a transform matrix.
- Preview sizes come from `SurfaceTexture` output sizes. Selection prefers the closest view aspect
  ratio and bounds normal preview work to 1080p when the HAL exposes such a choice.
- The HAL buffer size is set before the `Surface` and capture session are created.
- The transform center-crops the buffer for the display rotation. Front preview remains naturally
  mirrored by `TextureView`; capture orientation is set independently.
- A configuration change recreates the activity and closes/reopens Camera2. Background/foreground
  transitions close in `onStop` and reopen in `onStart` when permission and a surface are ready.

## Session, requests, and capture

The session contains preview, maximum-size JPEG, and (only with RAW capability plus RAW_SENSOR
sizes) maximum-size RAW surfaces. `CameraRequestController` applies one logical requested state to
preview and still requests. Ordinary control changes replace the repeating request without
recreating the session. Manual exposure sets AE off, sensitivity, exposure time, and a bounded
frame duration; auto exposure applies compensation using the reported rational EV step.

AF lock is not modeled as a nonexistent Camera2 boolean. A requested lock issues
`CONTROL_AF_TRIGGER_START` in AUTO mode and observed `CONTROL_AF_STATE` reports convergence/lock;
unlock issues CANCEL and restores the selected mode. Tap metering maps display coordinates through
rotation, facing, and current crop, and only writes AF/AE/AWB regions reported by the HAL.

API 30+ uses `CONTROL_ZOOM_RATIO` only when a ratio range is reported. Older/fallback paths use a
centered active-array crop. Fixed-focus, legacy, limited, and cameras missing optional keys retain
automatic JPEG preview/capture.

Camera device errors, disconnects, access exceptions, invalid surfaces, configure failures, and
capture failures are converted into project errors and logged by category. There are no empty
catch blocks. Reopening after a device/service error should be validated on hardware because HAL
recovery behavior varies by vendor.

## Error recovery and ownership

- `CameraSessionController` exclusively owns the live `CameraDevice`, capture session, readers,
  and preview `Surface`; only the camera looper may mutate device/session ownership.
- TextureView owns `SurfaceTexture`. Normal close and failure recovery release the `Surface`
  wrapper but preserve `SurfaceTexture` while the view remains available.
- Session configure failure, synchronous session-creation failure, preview request/repeating
  failure, disconnect, and current-generation fatal device errors all use the same
  close-before-error path.
- Each open attempt has a generation token. Late device/session callbacks close only their stale
  object and cannot tear down or transition a newer attempt.
- Recoverable errors retain selected camera and surface prerequisites. Up to three retries use
  short exponential backoff; preview success clears the retry budget. Missing prerequisites wait
  for the next permission/surface/start event rather than spinning.
- Policy-disabled and unsupported-capability errors are non-recoverable. Camera switch or another
  explicit lifecycle action can establish a new clean state.
- A capture-request/output timeout does not close an otherwise healthy repeating preview session.
  Its current-generation terminal callback returns both capture status and overall camera state to
  a preview-ready result; stale-generation callbacks are ignored.
- A live RAW reader belongs to `CameraSessionController`. Shutdown transfers it to the capture
  coordinator as retired: no new leases are accepted, unpaired images close, and physical reader
  close waits only for existing DNG leases. Each writer closes `Image` in `finally`, then the last
  lease closes the retired reader. Camera shutdown never waits for DNG disk I/O.

## RAW and DNG

- RAW is enabled only when both `REQUEST_AVAILABLE_CAPABILITIES_RAW` and RAW_SENSOR sizes exist.
- A single still request targets JPEG, RAW, or both according to the effective capture mode.
- Sensor timestamps pair RAW `Image` and `TotalCaptureResult` in either arrival order. The queue is
  bounded by count and age; stale images are closed, and lifecycle close drains unpaired leases.
- `DngCreator` runs on the I/O dispatcher with matching characteristics/result/image. The acquired
  image lease closes in the coordinator's `finally`, including encoding and MediaStore failures.
- JPEG and DNG use `DCIM/JustCamera`; Android 10+ items remain pending until the publish update
  succeeds. A failed publish is deleted and reported as storage failure.
- Only `onConfigureFailed` or a synchronous invalid-output-combination rejection proves RAW
  topology incompatibility. It invalidates that generation and retries JPEG-only for the current
  camera-selection tenure. `CameraAccessException`, camera-in-use/service errors, and lifecycle
  interruption do not disable RAW. Switching away and back begins a new RAW attempt.
- Started saves finish safely through switch/stop/error/destroy; after engine release no new save
  jobs are accepted, and the I/O scope ends after existing jobs publish or clean their rows.

## Device validation checklist

1. First permission grant, denial, “don’t ask again,” and settings re-grant.
2. Rear/front/external switch where present; rapid repeated switch.
3. Rotate at 0/90/180/270 degrees and verify preview crop plus JPEG EXIF orientation.
4. Background during preview and during save; return and capture again.
5. Ten or more captures, including rapid shutter attempts, while monitoring memory.
6. Camera-in-use conflict and system privacy/policy disable behavior.
7. API 26–28 storage permission/save and API 29+ scoped storage save.
8. Capability screen on legacy, limited, full, level-3, logical, and RAW-capable devices.
9. Validate every shown ISO/shutter/EV/focus/WB/lock/zoom control against observed metadata.
10. Tap center/corners at each rotation, front/rear, and multiple zoom ratios.
11. Capture JPEG, RAW, and JPEG + RAW; open DNG in an independent decoder and inspect metadata.
12. Force one output/storage failure and confirm partial-success status plus no pending MediaStore
    item or leaked `Image`.
