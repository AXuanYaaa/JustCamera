# JustCamera Physical-Device Validation Checklist

Use this checklist with the local-only `JustCamera-ph5-device-test.apk`. It is signed for testing,
not production distribution. Record logs, output files, and exact reproduction steps for failures.

## Device record

- Date / tester:
- Phone manufacturer and model:
- Android version / build:
- SoC:
- App version:
- Camera ID(s) tested:
- Camera2 hardware level per ID:
- RAW capability:
- `MANUAL_SENSOR` capability:
- `BURST_CAPTURE` capability:
- HDR support assessment and reason:
- Available storage before test:
- Observed bugs / log references:

## A. Install and startup

- [ ] Remove an older validation build if its signing key is incompatible.
- [ ] APK installs without an invalid-signature or min-SDK error.
- [ ] App launches and remains open without an immediate crash or ANR.
- [ ] Camera permission prompt appears on first launch and the grant path succeeds.
- [ ] Denying permission produces a usable explanation; granting it later recovers.
- [ ] Android 8–9 storage permission behavior is correct, if applicable.
- [ ] Portrait/landscape orientation behavior is usable and preview geometry is not stretched.

## B. Camera foundation

- [ ] Preview opens on the expected default rear camera.
- [ ] Rear/front switching works and labels/capabilities update.
- [ ] Switch cameras repeatedly at least 20 times without a frozen preview or stuck state.
- [ ] Background and foreground the app repeatedly; preview closes and reopens reliably.
- [ ] Rotate the device/screen, if supported, and verify preview transform and recovery.
- [ ] Interrupt permission or lifecycle state and confirm the selected camera can reopen.
- [ ] Disconnect/failure recovery does not require force-stopping the Activity where practical.

## C. JPEG capture

- [ ] Capture a JPEG and confirm it appears under DCIM/JustCamera in the gallery.
- [ ] Portrait and landscape JPEG orientation are correct for rear and front cameras.
- [ ] Repeated captures complete without missing, zero-byte, or duplicated items.
- [ ] Capture status returns to ready and preview remains usable.
- [ ] Exercise low-storage/storage-denial handling where it can be done safely.

## D. Professional controls

- [ ] ISO changes are accepted within the device range and observed metadata changes.
- [ ] Manual shutter changes are accepted and observed exposure time changes.
- [ ] EV compensation affects Auto exposure and reports the observed compensation.
- [ ] Manual focus traverses the supported diopter range without a request failure.
- [ ] Supported AWB modes apply without inter-request flicker or a stuck preview.
- [ ] AE lock and AWB lock hold stable values where supported, then unlock cleanly.
- [ ] Zoom reaches advertised bounds without invalid crop or preview failure.
- [ ] Tap metering / autofocus works where supported and returns to normal behavior.
- [ ] Preview and still capture use consistent requested controls.

## E. RAW / DNG (only when the selected camera reports RAW)

- [ ] RAW capture option appears only when the effective session supports it.
- [ ] `RAW_ONLY` creates one valid DNG.
- [ ] `JPEG_AND_RAW` creates both outputs or accurately reports partial success.
- [ ] DNG opens in a compatible viewer/editor and contains plausible metadata/image data.
- [ ] Capture RAW, then immediately switch camera; no crash, corrupt ownership, or stuck camera.
- [ ] Capture RAW, then immediately background the app; save completes or fails cleanly.
- [ ] Repeat RAW capture and watch for reader exhaustion, leaked images, or frozen preview.
- [ ] A rejected RAW topology falls back to JPEG without permanently disabling the camera.

## F. HDR (only when HDR assessment enables capture)

- [ ] Enable HDR; Preview + YUV session reconfiguration completes and preview remains functional.
- [ ] Capture starts a complete exposure bracket and reports frame progress.
- [ ] Conversion, alignment, merge, and tone-mapping stages complete.
- [ ] Camera returns to a usable preview after processing.
- [ ] Compare HDR ON versus OFF for highlight detail and shadow visibility.
- [ ] Inspect neutral surfaces and skin/foliage for unexpected color casts.
- [ ] Inspect a moving subject for double edges or transparent ghosts.
- [ ] Repeat HDR at least 10 times without overlap, unbounded memory growth, or a stuck state.
- [ ] Background immediately after HDR capture; no stale result publishes and no crash occurs.
- [ ] Disable HDR and confirm standard JPEG/RAW topology and capture still work.
- [ ] If HDR topology is rejected, the reason is visible and standard capture recovers.

## G. Filter engine and native diagnostics

- [ ] Filter screen opens and returns to camera without lifecycle problems.
- [ ] Built-in filter selection and parameter adjustment remain responsive.
- [ ] LUT behavior works where currently exposed; invalid LUT input fails safely.
- [ ] Native core version/ABI diagnostics appear and match the installed device ABI.
- [ ] Diagnostics do not claim an active SIMD kernel when only scalar kernels are active.

## H. Stress pass

- [ ] Capture 20 JPEGs in succession and verify every completion/output.
- [ ] Repeat RAW capture where supported while monitoring memory and preview health.
- [ ] Repeat HDR capture where enabled while monitoring processing time and memory.
- [ ] Run a 20-iteration rear/front camera switch loop.
- [ ] Run a 20-iteration background/foreground loop.
- [ ] Combine capture, camera switch, and lifecycle changes without a crash or ANR.
- [ ] Check logcat for `FATAL EXCEPTION`, native crash, ANR, Camera2 error loops, and leaked Image warnings.

## Failure record template

- Checklist item:
- Camera ID / mode / controls:
- Expected result:
- Actual result:
- Reproduction frequency and steps:
- Output filename(s):
- Relevant logcat timestamp / excerpt location:
- Screenshot or sample-image location:
- Recovery required (none, switch, reopen, force-stop, reboot):
