# RAW and DNG Capture

## Capability and session topology

RAW is usable only when the camera advertises `REQUEST_AVAILABLE_CAPABILITIES_RAW` and exposes at
least one RAW_SENSOR size. PH2 chooses the largest reported RAW size and adds its `ImageReader` to
the preview/JPEG session. `CameraSessionController` owns and closes both readers with the session,
device, and preview `Surface` on stop, switch, error, or retry.

Some vendor HALs reject an otherwise advertised preview + JPEG + RAW topology. The first configure
failure disables RAW topology for that camera for the current engine lifetime, closes all resources
through the PH1.1 generation-safe recovery path, and retries JPEG-only. Basic capture therefore
survives a broken RAW combination without an infinite retry loop.

## Timestamp pairing and ownership

A DNG is not raw plane bytes with a different extension. `DngCreator` requires the RAW `Image`,
the selected camera's `CameraCharacteristics`, and the matching `TotalCaptureResult`.

The pairing queue accepts result-first and image-first delivery using `SENSOR_TIMESTAMP` and
`Image.timestamp`. It is synchronized, bounded by timestamp count and age, and returns evicted
images to the caller for immediate close. Lifecycle teardown drains and closes every unpaired
image. Once paired, ownership transfers to the I/O DNG job, whose `finally` closes the image.
JPEG bytes use the same timestamp gate so a late frame from a timed-out capture cannot be assigned
to the next shutter request.

## Multi-output completion

`JPEG_ONLY`, `RAW_ONLY`, and `JPEG_AND_RAW` select the targets on one still request. The requested
control state is applied once to that builder, matching preview settings. One active capture batch
tracks JPEG and DNG independently:

- every output saved: `Saved`;
- one saved and one failed: `PartialSuccess`;
- no output saved: `Failed`;
- a missing image/result expires at a bounded timeout.

## Storage

JPEG uses `image/jpeg`; DNG uses `image/x-adobe-dng`. Both write to `DCIM/JustCamera` through the
same small MediaStore output abstraction. Android 10+ inserts with `IS_PENDING=1`, writes and
flushes on `Dispatchers.IO`, then requires exactly one row to clear pending. Any insert, encoding,
write, or publish failure deletes the incomplete item. Android 8–9 uses the legacy MediaStore DATA
path and the existing scoped permission gate.

## PH2 limitations and device verification

PH2 stores sensor data but does not develop/demosaic RAW or implement manual color gains. Validate
on real devices that the triple-output session configures, requested capture modes produce readable
files, DNG metadata matches observed results, long exposure remains stable, lifecycle interruption
does not leak images, and partial failure leaves no pending MediaStore item.
