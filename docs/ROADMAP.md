# Roadmap

Roadmap phases are architecture/build gates, not parallel feature lists. Real-device validation is
also tracked explicitly and may remain required after a phase's implementation review.

## PH1 — Camera Foundation (complete)

Camera2 discovery, capability domain model, lifecycle preview, camera switching, JPEG/MediaStore,
state/error/thread boundaries, capability UI, `ImageFrame`/pipeline/filter contracts, JNI proof,
C plugin ABI foundation, tests, and architecture documentation.

## PH2 — Pro Controls + RAW (complete)

Capability-driven focus/exposure/ISO/white-balance/zoom/lock controls, shared preview/still
request state, observed metadata, RAW/DNG capture, multi-output status, and bounded timestamp
pairing. Real-device preview, HAL control, and DNG validation remain required validation debt.

## PH3 — Filter Engine + LUT (current)

Linear-sRGB working frames, preview/final filter contract, typed parameters, registry, ordered
chains, versioned presets, CPU adjustment references, compact 1D/3D LUTs, strict `.cube` parsing,
trilinear interpolation, pipeline adapter, and schema-generated configuration UI. Live Camera2
pixel rendering and captured-image encode integration remain explicit future adapters.

## PH4 — Native Image Processing

Owned native buffers, JNI lifecycle, SIMD kernels, benchmarks, and deterministic fallback paths.

## PH5 — HDR

Exposure bracketing, alignment, merge, ghost handling, tone mapping, and quality evaluation.

## PH6 — Night Mode

Low-light capture orchestration, motion-aware stacking, and long-exposure UX.

## PH7 — Multi-frame Denoise

Burst selection, alignment, temporal/spatial denoise, detail retention, and artifact metrics.

## PH8 — Super Resolution

Sub-pixel registration, fusion/reconstruction, sharpening controls, and memory/performance budgets.

## PH9 — External `.so` Plugin System

Signed/private installation, ABI/architecture/integrity validation, safe inventory and update flow,
dynamic loading, and explicit trust UX.

## PH10 — Performance / GPU Optimization

End-to-end profiling, thermal/memory policy, GPU processing evaluation, scheduling, and device-tier
fallbacks. Vulkan or another GPU backend is chosen only from measured needs.
