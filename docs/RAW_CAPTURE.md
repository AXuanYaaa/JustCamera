# RAW and DNG Capture

## Capability and session topology

RAW is usable only when the camera advertises `REQUEST_AVAILABLE_CAPABILITIES_RAW` and exposes at
least one RAW_SENSOR size. PH2 chooses the largest reported RAW size and adds its `ImageReader` to
the preview/JPEG session. `CameraSessionController` owns both live readers with the session, device,
and preview `Surface`. A live RAW reader is transferred to the capture coordinator's retirement
boundary during stop, switch, error, retry, or replacement.

Some vendor HALs reject an otherwise advertised preview + JPEG + RAW topology. Only an explicit
session `onConfigureFailed` callback or a synchronous invalid-output-combination rejection disables
RAW. `CameraAccessException`, camera-in-use/service errors, and lifecycle interruption retain RAW
and use normal recovery. A confirmed downgrade lasts for the current selected-camera tenure: retry
and stop/start remain JPEG-only, while switching away and selecting that camera again starts a new
RAW attempt. The rejected generation closes through the PH1.1 recovery path before JPEG-only retry.

## Timestamp pairing and ownership

A DNG is not raw plane bytes with a different extension. `DngCreator` requires the RAW `Image`,
the selected camera's `CameraCharacteristics`, and the matching `TotalCaptureResult`.

The pairing queue accepts result-first and image-first delivery using `SENSOR_TIMESTAMP` and
`Image.timestamp`. It is synchronized, bounded by timestamp count and age, and returns evicted
images to the caller for immediate close. The ownership chain is:

```text
RAW ImageReader
    ↓ acquireNextImage
generation-bound RawImageLease / timestamp pairing
    ↓ paired ownership transfer
DNG writer on Dispatchers.IO
    ↓ finally
Image.close
    ↓ final lease from a retired generation
ImageReader.close
```

The invariant is that a RAW reader cannot physically close while any acquired image lease from it
is live. Session teardown immediately detaches and retires the reader; it closes at once when there
are no leases, otherwise the final DNG job closes its `Image` first and then closes the retired
reader. The camera thread never waits for disk I/O. Unpaired, evicted, timed-out, and stale-generation
leases close immediately. JPEG bytes use the same generation and timestamp gate so a late frame
cannot be assigned to the next shutter request.

## Multi-output completion

`JPEG_ONLY`, `RAW_ONLY`, and `JPEG_AND_RAW` select the targets on one still request. The requested
control state is applied once to that builder, matching preview settings. One active capture batch
tracks JPEG and DNG independently:

- every output saved: `Saved`;
- one saved and one failed: `PartialSuccess`;
- no output saved: `Failed`;
- a missing image/result expires at a bounded timeout.

`StillCaptureCoordinator` owns this batch state, pairing, timeout, and save-job bookkeeping. A
terminal timeout/failure asks `CameraEngine` to return `CameraState.Capturing` to `Previewing` only
when the same generation still has a healthy device/session; stale generations cannot affect a
new session.

## Storage

JPEG uses `image/jpeg`; DNG uses `image/x-adobe-dng`. Both write to `DCIM/JustCamera` through the
same small MediaStore output abstraction. Android 10+ inserts with `IS_PENDING=1`, writes and
flushes on `Dispatchers.IO`, then requires exactly one row to clear pending. Any insert, encoding,
write, or publish failure deletes the incomplete item. Android 8–9 uses the legacy MediaStore DATA
path and the existing scoped permission gate.

An already-started JPEG/DNG save is allowed to finish across camera switch, Activity stop, camera
error recovery, and engine release. New save ownership is rejected after release. This policy keeps
the RAW lease valid until `DngCreator` returns and lets the MediaStore transaction either publish or
delete its row; it does not cancel a blocking encoder halfway through a pending item.

## PH2 limitations and device verification

PH2 stores sensor data but does not develop/demosaic RAW or implement manual color gains. Validate
on real devices that the triple-output session configures, requested capture modes produce readable
files, DNG metadata matches observed results, long exposure remains stable, lifecycle interruption
does not leak images, and partial failure leaves no pending MediaStore item.
