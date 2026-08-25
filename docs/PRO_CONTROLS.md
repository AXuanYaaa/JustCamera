# Professional Controls

## Requested state versus observed state

`CameraControlState` is the user's requested configuration. `CameraRequestController` validates it
against `CameraControlCapabilities`, clamps numeric values to reported ranges, rejects unsupported
mode/lock combinations, and applies the accepted state to both preview and still builders.

`CameraCaptureMetadata` is independent observed state derived from `TotalCaptureResult`. The UI can
therefore show requested ISO 400 beside observed ISO 385 without claiming that a request value was
accepted by the sensor before metadata confirms it.

Control validation failures are published separately from lifecycle `CameraState`. An invalid ISO,
focus mode, or lock request leaves the current repeating session alive.

## Exposure

- Auto mode keeps AE enabled. Compensation is stored as Camera2 integer steps; display EV equals
  `steps × CONTROL_AE_COMPENSATION_STEP`, including non-third-stop devices.
- Manual mode is exposed only with `MANUAL_SENSOR`, sensitivity/exposure ranges, and usable values.
  It sets AE off, ISO, nanosecond exposure time, and a frame duration at least as long as the
  exposure and no greater than the reported maximum.
- Shutter UI uses a logarithmic continuous slider over the real nanosecond range. Fraction/seconds
  labels are presentation only; the internal value remains nanoseconds.
- Preview and still capture never switch between different logical exposure states.

## Focus and metering

Only reported AF modes are selectable. Manual focus requires AF OFF plus a positive
`LENS_INFO_MINIMUM_FOCUS_DISTANCE`; its value is diopters, where 0 D is infinity and `1 / D` is the
approximate distance in meters.

AF lock is a requested workflow, not a Camera2 key. Lock sends AF START in AUTO mode and reports
the observed AF state; unlock sends CANCEL and restores the selected mode. Tap-to-focus maps a
normalized preview point through rotation, lens facing, and the current sensor crop. AF/AE/AWB
regions are set only when the corresponding maximum region count is positive. A non-locking tap
cancels its AF hold after a short interval.

## White balance, locks, and zoom

AWB mode choices come directly from the reported modes. Manual color temperature/gains are outside
PH2. AE lock is meaningful only in auto exposure; AWB lock is meaningful only in AUTO AWB, so the
validator clears incompatible lock state.

API 30+ uses `CONTROL_ZOOM_RATIO` when the camera reports a zoom-ratio range, including minimum
ratios below 1. Older devices use a centered `SCALER_CROP_REGION` derived from the active array and
maximum digital zoom. Both paths expose observed zoom/crop metadata where available.

## Hardware behavior

No control is inferred from hardware level or lens facing. A fixed-focus/LEGACY device with no RAW
or manual sensor support continues to run automatic JPEG preview/capture. Real-device validation
must compare every enabled control with observed metadata because vendors may quantize values or
reject otherwise legal combinations.
