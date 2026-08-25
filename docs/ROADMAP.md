# Roadmap

Roadmap phases are gates, not parallel feature lists. A phase starts only after the previous
foundation is built, tested, device-validated, and reviewed.

## PH1 — Camera Foundation (current)

Camera2 discovery, capability domain model, lifecycle preview, camera switching, JPEG/MediaStore,
state/error/thread boundaries, capability UI, `ImageFrame`/pipeline/filter contracts, JNI proof,
C plugin ABI foundation, tests, and architecture documentation.

## PH2 — Pro Controls + RAW

Manual focus/exposure/ISO/white-balance controls, request-state validation, RAW/DNG capture, and
capture metadata. Preview stability remains the gate.

## PH3 — Filter Engine + LUT

Working preview/final adjustment nodes, 3D LUT and `.cube` parsing, presets, and color-managed
parameter UI.

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
