package top.r2dblog.justcamera.nativecore

data class NativeCapabilities(
    val coreVersion: String,
    val processingVersion: String,
    val abi: String,
    val neonAvailable: Boolean,
    val simdKernelsActive: Boolean,
)

object NativeCore {
    private val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("justcamera_native") }
    }

    fun isAvailable(): Boolean = loadResult.isSuccess

    fun loadFailure(): Throwable? = loadResult.exceptionOrNull()

    fun version(): String = capabilities()?.coreVersion ?: "JustCamera Native Core unavailable"

    fun capabilities(): NativeCapabilities? {
        if (!isAvailable()) return null
        return runCatching {
            val flags = nativeCapabilityFlags()
            NativeCapabilities(
                coreVersion = nativeVersion(),
                processingVersion = nativeProcessingVersion(),
                abi = nativeAbi(),
                neonAvailable = flags and CAPABILITY_NEON != 0L,
                simdKernelsActive = flags and CAPABILITY_SIMD_KERNEL_ACTIVE != 0L,
            )
        }.getOrNull()
    }

    private external fun nativeVersion(): String
    private external fun nativeProcessingVersion(): String
    private external fun nativeAbi(): String
    private external fun nativeCapabilityFlags(): Long

    private const val CAPABILITY_NEON = 1L
    private const val CAPABILITY_SIMD_KERNEL_ACTIVE = 1L shl 1
}
