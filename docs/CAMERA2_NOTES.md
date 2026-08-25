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
- Preview sizes come from `SurfaceTexture` output sizes. Selection is independent of the screen
  ratio: prefer the largest 16:9 stream whose long/short edges fit 1920x1080, otherwise the
  largest bounded stream. If the HAL offers no bounded stream, use its smallest advertised size.
  A 20:9 phone is therefore a center-crop destination, not a requested producer aspect ratio.
- `PreviewBufferSize` is the exact unswapped Camera2 stream size passed once to
  `SurfaceTexture.setDefaultBufferSize` before the same `SurfaceTexture` is wrapped and used by
  the capture session. `PreviewViewportSize` is always the TextureView/window coordinate space.
- Preview and JPEG orientation are independent contracts. Preview relative rotation follows
  `(sensorOrientation - displayRotation * sign + 360) % 360`, where `sign` is `1` for front and
  `-1` for other cameras. A 90/270 result swaps effective buffer dimensions for scale/crop math.
- `PreviewTransformCalculator` owns one producer-buffer-to-viewport mapping: relative rotation,
  one uniform `CENTER_CROP` scale, centering, and the effective producer mirror. The full-screen
  TextureView remains the viewport. Its producer transform already applies sensor orientation,
  front mirroring, and an intermediate full-view stretch; the one application matrix is derived
  as `final * inverse(TextureView intrinsic)`. Tests compose those exact matrices and prove equal
  basis-vector lengths, zero dot product, full coverage, centered output, and circle invariance.
- Front preview mirroring belongs to the producer. API 33+ preview `OutputConfiguration` is
  explicitly `MIRROR_MODE_AUTO`; earlier versions retain their SurfaceTexture default behavior.
  The application transform does not mirror again.
- On API 31+, preview requests opt out of HAL rotate-and-crop only after `NONE` appears in
  `SCALER_AVAILABLE_ROTATE_AND_CROP_MODES`. Capture results record the actual selected mode, so an
  unexpected HAL rotation cannot remain hidden or be double-applied by the geometry model.
- Tap focus uses the inverse of the same final preview matrix, then center-crops the
  active-array/zoom region to the preview stream aspect before creating metering rectangles.
  Display crop, sensor-stream crop, and front-camera mirroring therefore cannot silently offset
  the metering region.
- `JC_PREVIEW_GEOMETRY` logs only configuration changes (never frames) and includes camera/facing,
  sensor/display/relative rotation, view/window/screen geometry, selected and applied buffer size,
  SurfaceTexture identity agreement, mirror owner, available/requested/observed rotate-and-crop,
  zoom crop, intrinsic producer matrix, TextureView matrix, and final matrix. Validate with
  `adb logcat -s JC_PREVIEW_GEOMETRY`.
- The TextureView registers a display listener while attached so a 180-degree rotation, which can
  leave view dimensions and the Android configuration unchanged, still recalculates geometry.
- Capture orientation remains independent from preview display geometry.
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
