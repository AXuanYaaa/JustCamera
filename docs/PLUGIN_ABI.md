# Native Plugin ABI

## Status and design goals

PH1 defines and compiles the ABI plus an internal `dlopen`/`dlsym` validation foundation. It does
**not** let users install or execute arbitrary `.so` files. The Kotlin external loader returns a
deliberate disabled error until the trust flow exists.

The ABI aims to keep the host independent of HDR, night, denoise, film, color, sharpen, and
super-resolution implementations while allowing compatible revisions to coexist.

## C boundary

The canonical header is `app/src/main/cpp/justcamera_plugin.h`. Required exports are:

```c
uint32_t justcamera_plugin_api_version(void);
const JustCameraPluginInfo* justcamera_plugin_get_info(void);
JustCameraPluginHandle* justcamera_plugin_create(void);
JustCameraPluginResult justcamera_plugin_process(
    JustCameraPluginHandle* plugin,
    const JustCameraImageBuffer* input,
    JustCameraImageBuffer* output,
    const JustCameraProcessingParams* params);
void justcamera_plugin_destroy(JustCameraPluginHandle* plugin);
```

Exports use `extern "C"` in C++. The boundary contains fixed-width integers, opaque handles,
pointers, byte counts, and POD structs only—never STL, `std::string`, RTTI objects, or exceptions.
Plugins must catch every exception internally and return a documented error code.

## Structs and forward compatibility

Every struct begins with `struct_size`. Consumers validate the minimum fields they require and
ignore unknown trailing fields. Reserved fields are zero when sent and ignored when received.
Strings are pointer-plus-length views and need not be NUL terminated. Plugin info and its strings
must remain valid until the library unloads.

The 32-bit API version encodes `major` in bits 31–24, `minor` in bits 23–16, and patch in bits
15–0. Major changes are ABI-breaking. A host accepts a plugin that requests the same major and a
minor not newer than the host. Patch is informational and never changes layout.

## Image buffers and pixel format

`JustCameraImageBuffer` declares format, width, height, bit depth, plane count, timestamp,
rotation, and an array of planes. Each plane declares data byte size, row stride, and pixel stride.
Formats currently reserve identifiers for YUV 4:2:0 888, RAW sensor 16-bit containers, RGBA 8888,
and linear RGB 16F. A plugin rejects unsupported combinations instead of guessing layout.

## PH4 filter-system mapping

A future native FILTER adapter can expose a plugin as the same logical `ImageFilter` registered by
stable ID, translate the descriptor's typed parameters into versioned `encoded_data`, and map the
PREVIEW/FINAL_CAPTURE context to host scheduling policy. PH4's correctness frame is packed linear
sRGB 32-bit float, while ABI v1 reserves linear RGB 16F. An adapter must perform an explicit,
tested 32F↔16F conversion; it must never reinterpret the buffer. This is a compatible adapter
boundary, so PH4 does not revise API 1.0. A direct RGB32F enum would require a future versioned ABI
extension if profiling shows the conversion is inappropriate.

The PH4 built-in processing protocol is intentionally separate from this ABI. Built-ins are linked
into `libjustcamera_native`, accept only validated linear-sRGB Float32 RGB/RGBA direct buffers, and
use compact internal operation records. They are not discovered with `dlopen`/`dlsym`. Similar
format/stride/color concepts ease a future adapter, but neither internal C++ structs nor JNI record
layouts are promised to external plugins.

## Ownership and lifetime

- Host owns input/output structs, plane arrays, and their buffers.
- Input is immutable for the complete `process` call.
- Output capacity is set before the call; plugins do not retain or free host pointers.
- Parameter bytes are immutable and valid only during the call.
- Plugin owns the opaque handle from `create` until the matching `destroy`.
- `get_info` returns plugin-owned static data. Host copies descriptor values before unload.
- A plugin must not retain callbacks or spawn work that accesses buffers after `process` returns.

## Lifecycle, threading, and errors

The host validates file/trust policy, architecture, symbols, API version, and all mandatory struct
fields before `create`. It then calls `process` zero or more times and always calls `destroy` before
`dlclose`. A plugin declares `thread_safe`; a false value means the host serializes calls per
handle. Camera callbacks never call plugins directly.

Zero is success. Negative fixed codes cover invalid arguments, unsupported formats, insufficient
buffers, allocation failure, internal failure, and API mismatch. Future errors must remain
negative; hosts treat unknown negative values as plugin failure.

## Android architectures

An `.so` must match the process ABI (`arm64-v8a`, optionally `armeabi-v7a`, `x86_64` for supported
test targets), Android API level, STL/runtime policy, and ELF class. Package selection must occur
before loading; the linker is not an architecture validator or recovery mechanism.

## Security model for future external loading

Native plugins are executable code with the app's UID and permissions. A crash, memory corruption,
data leak, or malicious syscall can compromise the entire host process. Future PH9 installation
must therefore include:

1. accepted API/ABI and architecture validation before moving a file into place;
2. streaming SHA-256 integrity verification and an optional/required signature trust policy;
3. import into a dedicated private app directory—never direct loading from shared storage;
4. atomic installation, canonical-path validation, no symlinks, and read-only permissions before
   loading;
5. manifest-to-library identity checks and prevention of downgrade/replacement races;
6. explicit user provenance/trust UI and an inventory/removal mechanism;
7. conservative threading/time/memory limits and crash telemetry that identifies the plugin;
8. acknowledgement that in-process validation cannot sandbox untrusted native code. A separate
   isolated process should be evaluated before enabling third-party code.
