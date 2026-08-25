package top.r2dblog.justcamera.filter.processing.backend

import java.nio.ByteBuffer
import java.nio.ByteOrder
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory
import top.r2dblog.justcamera.nativecore.NativeCapabilities
import top.r2dblog.justcamera.nativecore.NativeCore

class NativeProcessingBackend internal constructor(
    private val bridge: NativeProcessingBridge,
    private val fallbackLogger: (String, Throwable?) -> Unit,
) {
    constructor() : this(
        bridge = JniNativeProcessingBridge,
        fallbackLogger = { message, cause -> JcLog.warn(LogCategory.NATIVE, message, cause) },
    )

    fun capabilities(): NativeCapabilities? = bridge.capabilities()

    internal fun process(
        input: RgbFloatFrame,
        operations: List<NativeFilterOperation>,
    ): NativeBackendResult = bridge.process(input, operations)

    internal fun recordFallback(message: String, cause: Throwable? = null) {
        fallbackLogger(message, cause)
    }
}

internal object NativeProcessingJni {
    external fun nativeProcessInPlace(
        frameBuffer: ByteBuffer,
        width: Int,
        height: Int,
        channels: Int,
        rowStrideFloats: Int,
        pixelStrideFloats: Int,
        operationBuffer: ByteBuffer,
        operationCount: Int,
        lutBuffer: ByteBuffer?,
    ): Int

    external fun nativeColorTransferInPlace(
        buffer: ByteBuffer,
        sampleCount: Int,
        direction: Int,
    ): Int
}

internal object JniNativeProcessingBridge : NativeProcessingBridge {
    override fun capabilities(): NativeCapabilities? = NativeCore.capabilities()

    override fun process(
        input: RgbFloatFrame,
        operations: List<NativeFilterOperation>,
    ): NativeBackendResult {
        if (!NativeCore.isAvailable()) {
            return NativeBackendResult.Unavailable(
                NativeCore.loadFailure()?.message ?: "Native processing library is unavailable",
            )
        }
        val capabilities = capabilities()
            ?: return NativeBackendResult.Failure(
                NativeStatus.INTERNAL_ERROR,
                "Native capability query failed after the library loaded",
            )
        if (operations.isEmpty()) return NativeBackendResult.Success(input, capabilities)
        return runCatching {
            val frameBytes = checkedByteCount(input.width, input.height, input.channelCount)
            val frameBuffer = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.nativeOrder())
            input.copyPixelsTo(frameBuffer.asFloatBuffer())
            val encoded = NativeOperationEncoder.encode(operations)
            val status = NativeStatus.fromCode(
                NativeProcessingJni.nativeProcessInPlace(
                    frameBuffer = frameBuffer,
                    width = input.width,
                    height = input.height,
                    channels = input.channelCount,
                    rowStrideFloats = input.width * input.channelCount,
                    pixelStrideFloats = input.channelCount,
                    operationBuffer = encoded.descriptors,
                    operationCount = operations.size,
                    lutBuffer = encoded.lutSamples,
                ),
            )
            if (status != NativeStatus.OK) {
                NativeBackendResult.Failure(
                    status,
                    "Native processing returned ${status.name}",
                    capabilities,
                )
            } else {
                val output = FloatArray(input.pixelCount * input.channelCount)
                frameBuffer.position(0)
                frameBuffer.asFloatBuffer().get(output)
                NativeBackendResult.Success(input.withOwnedPixels(output), capabilities)
            }
        }.getOrElse { cause ->
            NativeBackendResult.Failure(
                NativeStatus.INTERNAL_ERROR,
                "Native processing call failed: ${cause.message ?: cause::class.java.simpleName}",
                capabilities,
            )
        }
    }

    private fun checkedByteCount(width: Int, height: Int, channels: Int): Int {
        val sampleCount = width.toLong() * height * channels
        val bytes = sampleCount * Float.SIZE_BYTES
        require(sampleCount > 0 && bytes <= Int.MAX_VALUE) { "Frame is too large for native processing" }
        return bytes.toInt()
    }
}

internal data class EncodedNativeOperations(
    val descriptors: ByteBuffer,
    val lutSamples: ByteBuffer?,
)

internal object NativeOperationEncoder {
    private const val MAGIC = 0x4A435034
    private const val VERSION = 1
    private const val HEADER_BYTES = 16
    private const val RECORD_BYTES = 48

    fun encode(operations: List<NativeFilterOperation>): EncodedNativeOperations {
        require(operations.size <= 1024) { "Too many native operations" }
        val descriptorBytes = HEADER_BYTES.toLong() + operations.size.toLong() * RECORD_BYTES
        require(descriptorBytes <= Int.MAX_VALUE) { "Native operation descriptor is too large" }
        val descriptors = ByteBuffer.allocateDirect(descriptorBytes.toInt()).order(ByteOrder.nativeOrder())
        descriptors.putInt(MAGIC)
        descriptors.putInt(VERSION)
        descriptors.putInt(RECORD_BYTES)
        descriptors.putInt(operations.size)

        val totalLutSamples = operations.sumOf { operation ->
            operation.lut?.sampleCount?.toLong() ?: 0L
        }
        val lutBytes = totalLutSamples * Float.SIZE_BYTES
        require(lutBytes <= Int.MAX_VALUE) { "Native LUT data is too large" }
        val lutBuffer = if (lutBytes == 0L) null else {
            ByteBuffer.allocateDirect(lutBytes.toInt()).order(ByteOrder.nativeOrder())
        }
        val lutFloats = lutBuffer?.asFloatBuffer()
        var lutOffset = 0L
        operations.forEach { operation ->
            require(operation.parameter.isFinite() && operation.strength.isFinite())
            require(operation.strength in 0f..1f)
            val lut = operation.lut
            if (operation.type == NativeOperationType.LUT_3D) requireNotNull(lut)
            if (operation.type != NativeOperationType.LUT_3D) require(lut == null)
            descriptors.putInt(operation.type.code)
            descriptors.putInt(lut?.size ?: 0)
            descriptors.putLong(if (lut == null) 0L else lutOffset)
            descriptors.putFloat(operation.parameter)
            descriptors.putFloat(operation.strength)
            descriptors.putFloat(lut?.domainMin?.red ?: 0f)
            descriptors.putFloat(lut?.domainMin?.green ?: 0f)
            descriptors.putFloat(lut?.domainMin?.blue ?: 0f)
            descriptors.putFloat(lut?.domainMax?.red ?: 0f)
            descriptors.putFloat(lut?.domainMax?.green ?: 0f)
            descriptors.putFloat(lut?.domainMax?.blue ?: 0f)
            if (lut != null) {
                lut.copySamplesTo(requireNotNull(lutFloats))
                lutOffset += lut.sampleCount
            }
        }
        descriptors.position(0)
        lutBuffer?.position(0)
        return EncodedNativeOperations(descriptors, lutBuffer)
    }
}
