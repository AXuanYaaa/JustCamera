# HDR Computational Photography Pipeline

## Scope and domains

PH5 reconstructs an application-level HDR still from multiple Camera2 `YUV_420_888` exposures.
It is not `DynamicRangeProfiles`, HLG10/HDR10, a 10-bit display/output mode, or RAW radiance
development. YUV has already passed the camera ISP, so the result is useful and deterministic but
cannot be described as physically calibrated sensor radiance.

The mandatory domain boundary is:

```text
YUV bracket + actual TotalCaptureResult metadata
    → ISP-derived scene-linear approximation (`SceneLinearFrame`, finite, >=0, may exceed 1)
    → alignment / merge
    → global tone map
    → display-referred linear sRGB (`RgbFloatFrame`, finite [0,1])
    → existing PH3/PH4 FilterEngine
    → future encoder
```

`SceneLinearFrame` uses Float32 packed RGB, explicit row stride/channel layout, sRGB/BT.709
primaries, timestamp, project metadata, and normalization metadata. Negative/NaN/Inf samples are
rejected. Nothing clips the upper range before tone mapping; e.g. a 0.4 sample at 0.25 relative
exposure becomes 1.6.

## Capability and topology policy

The capability assessment reports `UNSUPPORTED`, `BASIC_SEQUENTIAL`, `BURST_CAPABLE`, or
`MANUAL_BRACKET_CAPABLE`, plus selected YUV size, min frame duration, sync latency, burst/manual
flags, enablement, and a reason. Burst is preferred, not treated as universal. PH5 capture is
enabled only when YUV, manual sensor, ISO/time ranges, and max frame duration support a
deterministic actual-metadata bracket. AE-compensation fallback is reported but disabled because
its convergence sequence is not implemented yet.

Normal capture owns Preview + JPEG + optional RAW. HDR mode owns Preview + YUV only. This avoids
assuming a four-output stream combination. If the HAL rejects HDR configuration, the common
failure boundary invalidates callbacks, cancels HDR work, retires both RAW/HDR readers, closes the
session/device/Surface wrapper, retains selected camera and TextureView `SurfaceTexture`, reports
the reason, switches HDR off, and reopens standard capture with bounded retry.

## Bracket and ownership

The default offsets are -2, 0, +2 EV, supplied as data rather than embedded in processing. The
entry nearest zero is explicitly the geometric reference. Planning starts from actual preview
shutter/ISO, changes shutter first, applies a 100 ms PH5 motion-risk cap, compensates with ISO at
sensor/frame-duration bounds, respects the selected YUV minimum frame duration, and
records requested and achievable EV. Manual requests turn AE off, set shutter/ISO/frame duration,
lock AWB when supported, preserve focus/control state, target only HDR YUV, and carry a token/frame
tag. Burst-capable devices use one `captureBurst`; other manual devices submit the ordered sequence.

The Android Image is never retained for HDR processing. `HdrYuvImageLeaseRegistry` transfers one
lease, copies each plane from its own buffer with row and pixel strides, and closes the Image.
Reader retirement waits only for in-progress copies. `HdrFramePairingQueue` matches image/result
timestamps in either arrival order, rejects stale generations, replaces duplicates explicitly,
and bounds pending timestamps by age and count. `HdrCaptureCoordinator` permits one job, owns a
10-second capture timeout, cancels on lifecycle/reconfigure, and publishes no stale output.

## Conversion and normalization

The reference converter accepts arbitrary valid Y, U, and V row/pixel strides, including
pixel-stride-2 chroma and odd dimensions. It interprets Camera2 YUV as BT.601 limited range, maps
to encoded RGB, clamps only the encoded channel representation to [0,1], then applies inverse sRGB.
Actual `SENSOR_EXPOSURE_TIME × SENSOR_SENSITIVITY` defines the exposure scalar. Each linear sample
is divided by its ratio to the reference exposure. ISO is only an approximate sensor-gain proxy,
which is a documented limitation.

## Alignment, ghost confidence, and merge

Alignment extracts Rec.709 luminance, builds up to four 2× pyramids, and performs coarse-to-fine
integer translational mean-absolute-difference search within ±12 full-resolution pixels. Flat
images return identity with zero confidence. `Translation(dx,dy)` means candidate(x+dx,y+dy)
corresponds to reference(x,y); every frame carries an intersection `ValidRegion`, so merge never
samples outside its buffer. Affine/homography/dense flow and subpixel refinement are future work.

The exposure weight is the product of smoothstep shadow acceptance (0.005→0.08) and inverse
smoothstep highlight acceptance (0.85→0.98), with a finite 1e-4 floor. Non-reference samples are
also multiplied by alignment confidence. In ordinary tones, normalized luminance disagreement
from 10% to 35% smoothly reduces motion confidence to zero, preferring the reference and reducing
double images. A correctly exposed short frame may still rescue a clipped reference highlight,
and a long frame may rescue a reference deep shadow. Weighted RGB sums divide by checked weight;
invalid/zero totals fall back to the reference. Output is deterministic, finite, non-negative, and
may exceed 1.

## Tone mapping, memory, and diagnostics

Global Reinhard maps luminance `L` to `L/(1+L)` (configurable positive key and optional white
point), scales RGB by mapped/original luminance, finite-checks, and only then clamps into the PH3
working range. This preserves neutral/chromatic ratios until an individual output channel reaches
the display boundary. The resulting immutable `RgbFloatFrame` can enter the existing FilterEngine.
PH5 retains it in memory but does not yet encode a processed JPEG or modify standard JPEG/DNG saves.

The selector chooses the largest YUV size at or below 1,100,000 pixels. Three copied YUV frames and
three normalized RGB Float32 frames are bounded; conversion discards each temporary decoded frame,
the coordinator accepts no overlapping job, and only the tone-mapped output survives completion.
This intentionally trades full sensor resolution for predictable mobile memory. Stage timings are
collected for conversion, alignment, merge, and tone map; no benchmark threshold is a correctness
assertion. All PH5 HDR algorithms are Kotlin CPU references—there is no native HDR backend claim.

## Known limits and future work

There is no HDR Auto heuristic, AE-convergence fallback, processed JPEG save, RAW HDR, camera color
calibration, WB gains/matrices, subpixel/non-rigid alignment, optical-flow deghosting, native HDR,
GPU, night mode, denoise, or super resolution. Real-camera exposure accuracy, motion artifacts,
color, memory pressure, and output quality require physical-device validation before production use.
