# LUT Format and Reference Sampling

PH3 provides compact contiguous `Lut1D` and `Lut3D` models. Both require size 2 or greater, finite
samples, exact sample count, and per-channel `DOMAIN_MIN < DOMAIN_MAX`. Defensive copies prevent a
caller from mutating a registered LUT after validation.

The `.cube` parser supports `TITLE`, `DOMAIN_MIN`, `DOMAIN_MAX`, `LUT_1D_SIZE`, `LUT_3D_SIZE`, RGB
sample rows, comments, blank lines, and arbitrary whitespace. Numbers use locale-independent Kotlin
parsing. PH3 accepts either a 1D or a 3D LUT; combined shaper+3D files are rejected rather than
silently misinterpreted. Sizes are bounded to 65,536 for 1D and 65 for 3D. Duplicate/unknown
directives, non-finite numbers, invalid domains/dimensions, and incorrect sample counts return
line-numbered errors.

3D samples follow Adobe/IRIDAS ordering: red changes fastest, green next, blue slowest. For an input
channel, domain mapping is:

```text
t = clamp((input - DOMAIN_MIN) / (DOMAIN_MAX - DOMAIN_MIN), 0, 1) × (size - 1)
```

The reference sampler chooses floor/ceiling indices for each axis, interpolates the four red-axis
edges, then green, then blue. Edge coordinates clamp to the first/last cell. Output clamps to
`[0, 1]`. Tiny size-2 identity/inversion tests are the oracle for future C++/SIMD/GPU paths.

Common creative `.cube` files expect encoded RGB. `Lut3DFilter` therefore converts the PH3 linear
sRGB working pixel to encoded sRGB, samples and blends `original + (lut - original) × strength` in
encoded space, then converts back to linear sRGB. `strength` is validated to `[0, 1]`.

PH3 treats `.cube` as data, never executable code. Storage Access Framework import/copy UI is left
for a later integration; any importer must enforce a byte limit, run this parser, and persist only
validated data in app-private storage.
