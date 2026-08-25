package top.r2dblog.justcamera.filter.processing.backend

import top.r2dblog.justcamera.filter.lut.Lut3D
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame
import top.r2dblog.justcamera.nativecore.NativeCapabilities

enum class ProcessingBackendSelection { AUTO, KOTLIN_REFERENCE, NATIVE }

enum class ProcessingBackendKind { KOTLIN_REFERENCE, NATIVE_SCALAR }

enum class NativeStatus(val code: Int, val recoverable: Boolean) {
    OK(0, true),
    INVALID_ARGUMENT(1, true),
    UNSUPPORTED_FORMAT(2, true),
    INVALID_BUFFER(3, true),
    INVALID_LUT(4, true),
    CANCELLED(5, true),
    INTERNAL_ERROR(6, false),
    UNKNOWN(Int.MIN_VALUE, false),
    ;

    companion object {
        fun fromCode(code: Int): NativeStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class NativeOperationType(val code: Int) {
    EXPOSURE(1),
    CONTRAST(2),
    SATURATION(3),
    LUT_3D(4),
}

data class NativeFilterOperation(
    val type: NativeOperationType,
    val parameter: Float = 0f,
    val strength: Float = 1f,
    val lut: Lut3D? = null,
)

/** Implemented by PH3 oracle filters that can describe an equivalent PH4 native operation. */
interface NativeOperationProvider {
    fun nativeOperation(parameters: FilterParameters): NativeFilterOperation
}

sealed interface NativeBackendResult {
    data class Success(
        val output: RgbFloatFrame,
        val capabilities: NativeCapabilities,
    ) : NativeBackendResult

    data class Failure(
        val status: NativeStatus,
        val message: String,
        val capabilities: NativeCapabilities? = null,
    ) : NativeBackendResult

    data class Unavailable(val message: String) : NativeBackendResult
}

data class ProcessingBackendEvent(
    val filterIds: List<String>,
    val backend: ProcessingBackendKind,
    val message: String,
)

internal interface NativeProcessingBridge {
    fun capabilities(): NativeCapabilities?

    fun process(
        input: RgbFloatFrame,
        operations: List<NativeFilterOperation>,
    ): NativeBackendResult
}
