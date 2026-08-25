# Native Processing

## Boundary and operation flow

PH4 extends the existing `FilterEngine`; it does not add a parallel native pipeline.

```text
validated FilterChain
    ↓ FilterEngine groups adjacent NativeOperationProvider filters
NativeFilterOperation list (exposure / contrast / saturation / LUT_3D)
    ↓ one native-order DirectByteBuffer descriptor + optional flattened LUT buffer
JNI nativeProcessInPlace
    ↓ validate every descriptor before mutation
C++ ProcessOperations on one owned working frame
    ↓
new immutable RgbFloatFrame
```

The binary operation descriptor is an internal implementation protocol, not a public ABI. Its
native-order header carries a magic value, version, record size, and operation count. Fixed records
carry an integer operation type, primary parameter, strength, and optional LUT size/domain/sample
offset. No reflection, JSON, UI object, STL type, or C++ exception crosses JNI.

## Frame and memory ownership

The supported native frame is Float32, normalized finite `[0,1]`, display-referred linear sRGB,
packed RGB or straight-alpha RGBA. JNI validates direct-buffer addresses and capacities, float
alignment, positive dimensions, channel count, row/pixel strides, checked sample arithmetic, and
all input samples. RAW_SENSOR, YUV, encoded data, non-direct buffers, and ambiguous formats are
rejected.

`RgbFloatFrame` remains externally immutable. For a fused run Kotlin allocates one VM-owned direct
working buffer and copies source samples into it without an intermediate `FloatArray`. C++ mutates
this private copy in place; exposure, contrast, saturation, and LUT are safe because each pixel is
read before its RGB channels are overwritten and alpha is never touched. On success Kotlin copies
the result once into a newly owned immutable frame.

Validated LUT samples are flattened into a second call-scoped direct buffer in IRIDAS order (red
fastest, then green, then blue). Operation records carry float offsets, never memory addresses.
JNI borrows both addresses only for the synchronous call and retains nothing. C++ owns no frame or
LUT allocation across calls, uses no mutable global scratch, and cleans local vectors through RAII.
The VM owns direct-buffer deallocation after call-scoped references become unreachable; there is no
native handle that can dangle or require a cancellation cleanup path.

## Error and crash boundary

Native code returns `Ok`, `InvalidArgument`, `UnsupportedFormat`, `InvalidBuffer`, `InvalidLut`,
`Cancelled`, or `InternalError`. It validates the frame and every operation/LUT before processing,
so ordinary validation failure cannot partially modify the working copy. Checked add/multiply
protect dimension, capacity, offset, and LUT cube calculations. JNI catches allocation and unknown
C++ exceptions; no exception crosses into Kotlin. Memory corruption itself cannot be caught, which
is why the native API stays small and arbitrary external libraries remain disabled.

`AUTO` tries native only when capabilities load. `KOTLIN_REFERENCE` always uses PH3 code. `NATIVE`
requests native but still preserves app operation through the same Kotlin fallback. Any native
failure discards the private buffer, records a backend event/chain issue and log entry, then reruns
the entire compatible run against the untouched source. Internal/unknown failures are marked as
errors rather than silently hidden.

## Numerical contract

- exposure: `clamp(channel × 2^EV)` in linear sRGB;
- contrast: `clamp((channel - 0.18) × contrast + 0.18)`;
- saturation: Rec.709 luma `0.2126 R + 0.7152 G + 0.0722 B`;
- adjustment strength: blend original and clamped result in linear sRGB;
- LUT: linear-to-sRGB, domain-mapped trilinear interpolation, strength blend in encoded sRGB,
  then sRGB-to-linear;
- LUT index: `(((blue × size + green) × size + red) × 3) + channel`;
- RGBA alpha is copied through bit-for-bit and remains straight alpha.

The PH3 Kotlin code is the oracle and was not formula-changed. Device parity tests use `2e-5`
absolute tolerance, including branch thresholds and tiny identity/inversion/channel-swap/constant
LUTs. Standalone C++ tests use `1e-5` and also cover invalid dimensions/capacity, NaN, bad LUTs,
clipping, and alpha.

The core algorithms have no JNI dependency. A host CMake build can enable
`JUSTCAMERA_BUILD_NATIVE_TESTS`, build the `justcamera_processing_tests` target, and run it through
CTest. `assembleDebugAndroidTest` compiles the JNI/Kotlin parity suite; `connectedDebugAndroidTest`
executes it when a compatible device or emulator is attached.

## Threading and cancellation

The backend is stateless and may be called concurrently from processing workers. Each call owns
its buffers and local operation vector; read-only LUT data is shared only for that call. No native
work runs on Camera2 callbacks.

Coroutine cancellation is checked before and after every native run and between Kotlin/native run
boundaries. PH4's single fused JNI call is synchronous and not interruptible once entered. This is
an explicit limitation: a future tiled kernel can add a native atomic cancellation token without
changing the filter or pipeline API. No native memory survives cancellation, so cancellation after
return cannot leak a handle or expose a freed pointer.

## Capabilities, SIMD, and diagnostics

`NativeCore` exposes native-core version, processing version, build ABI, NEON availability, and an
independent active-SIMD-kernel flag. Arm64 reports NEON availability because it is architectural;
armv7 reports it only when compiled with NEON. PH4 kernels are scalar on every ABI, so the active
SIMD flag is false and the UI says “NEON available / scalar kernels” rather than claiming speedup.
The scalar core builds for arm64-v8a, armeabi-v7a, x86, and x86_64.

An ignored instrumentation benchmark provides manual Kotlin-reference versus native-scalar timing
at 640×480, 1920×1080, and 4000×3000. Its values are diagnostic and never correctness assertions.
Optimization—including a first NEON kernel—must follow profiling and retain the scalar fallback.

## PH5 HDR native status

PH5 HDR uses deterministic Kotlin CPU reference algorithms. No HDR operation is encoded into the
PH4 display-filter descriptor, no HDR JNI entry point was added, and there is therefore no native
HDR parity claim. This keeps the scene-linear multi-frame domain separate from normalized filter
operations while reusing PH4 principles: checked dimensions, immutable inputs, bounded owned
buffers, stage cancellation, no pointer/handle lifetime across calls, and pure algorithms outside
Android adapters. A future native HDR API should operate on dedicated HDR frame/batch descriptors,
retain the Kotlin reference as oracle, and add ordinary C++ tests plus JNI parity before selection.

GPU rendering, night/denoise/super-resolution processing, and external executable plugins remain
outside PH5. The internal JNI filter descriptor is not an extension API.
