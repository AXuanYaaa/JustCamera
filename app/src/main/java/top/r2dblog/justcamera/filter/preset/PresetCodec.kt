package top.r2dblog.justcamera.filter.preset

import top.r2dblog.justcamera.filter.model.FilterChain
import top.r2dblog.justcamera.filter.model.FilterOperation
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.model.FilterPreset
import java.nio.charset.StandardCharsets
import java.util.Base64

data class PresetCodecError(val line: Int, val message: String)

sealed interface PresetDecodeResult {
    data class Success(val preset: FilterPreset) : PresetDecodeResult
    data class Failure(val errors: List<PresetCodecError>) : PresetDecodeResult
}

/** Versioned, deterministic, UI-free persistence format for PH3 presets. */
object PresetCodec {
    fun encode(preset: FilterPreset): String = buildString {
        appendLine(HEADER)
        appendLine(
            listOf(
                "PRESET",
                encodeText(preset.id),
                encodeText(preset.name),
                preset.version,
                preset.chain.version,
            ).joinToString("\t"),
        )
        preset.chain.operations.forEach { operation ->
            appendLine(
                listOf(
                    "OP",
                    encodeText(operation.filterId),
                    if (operation.enabled) "1" else "0",
                    operation.lutReference?.let(::encodeText) ?: "-",
                ).joinToString("\t"),
            )
            operation.parameters.values.toSortedMap().forEach { (key, value) ->
                val (type, encodedValue) = encodeValue(value)
                appendLine(listOf("PARAM", encodeText(key), type, encodedValue).joinToString("\t"))
            }
            appendLine("END")
        }
    }

    fun decode(text: String): PresetDecodeResult {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != HEADER) {
            return failure(1, "Missing or unsupported preset header")
        }
        val presetParts = lines.getOrNull(1)?.split('\t')
        if (presetParts == null || presetParts.size != 5 || presetParts[0] != "PRESET") {
            return failure(2, "Malformed PRESET record")
        }
        val errors = mutableListOf<PresetCodecError>()
        val presetId = decodeText(presetParts[1], 2, errors)
        val presetName = decodeText(presetParts[2], 2, errors)
        val presetVersion = presetParts[3].toIntOrNull()
        val chainVersion = presetParts[4].toIntOrNull()
        if (presetVersion == null || presetVersion <= 0) {
            errors += PresetCodecError(2, "Invalid preset version")
        }
        if (chainVersion == null || chainVersion <= 0) {
            errors += PresetCodecError(2, "Invalid chain version")
        }

        val operations = mutableListOf<FilterOperation>()
        var current: MutableOperation? = null
        lines.drop(2).forEachIndexed { relativeIndex, raw ->
            val lineNumber = relativeIndex + 3
            if (raw.isBlank()) return@forEachIndexed
            val parts = raw.split('\t')
            when (parts.firstOrNull()) {
                "OP" -> {
                    if (parts.size != 4 || current != null) {
                        errors += PresetCodecError(lineNumber, "Malformed or nested OP record")
                    } else {
                        val id = decodeText(parts.getOrElse(1) { "" }, lineNumber, errors)
                        val enabled = when (parts.getOrNull(2)) {
                            "1" -> true
                            "0" -> false
                            else -> {
                                errors += PresetCodecError(lineNumber, "Invalid enabled flag")
                                false
                            }
                        }
                        val lutReference = parts.getOrNull(3)?.takeUnless { it == "-" }
                            ?.let { decodeText(it, lineNumber, errors) }
                        current = MutableOperation(id.orEmpty(), enabled, lutReference)
                    }
                }
                "PARAM" -> {
                    val operation = current
                    if (parts.size != 4 || operation == null) {
                        errors += PresetCodecError(lineNumber, "PARAM must appear inside an OP")
                    } else {
                        val key = decodeText(parts[1], lineNumber, errors)
                        val value = decodeValue(parts[2], parts[3], lineNumber, errors)
                        if (key != null && value != null &&
                            operation.parameters.put(key, value) != null
                        ) {
                            errors += PresetCodecError(lineNumber, "Duplicate parameter '$key'")
                        }
                    }
                }
                "END" -> {
                    val operation = current
                    if (parts.size != 1 || operation == null) {
                        errors += PresetCodecError(lineNumber, "END without an active OP")
                    } else {
                        runCatching {
                            FilterOperation(
                                operation.filterId,
                                FilterParameters(operation.parameters.toMap()),
                                operation.enabled,
                                operation.lutReference,
                            )
                        }.onSuccess(operations::add).onFailure {
                            errors += PresetCodecError(lineNumber, it.message ?: "Invalid operation")
                        }
                        current = null
                    }
                }
                else -> errors += PresetCodecError(lineNumber, "Unknown preset record")
            }
        }
        if (current != null) errors += PresetCodecError(lines.size, "Unterminated OP record")
        if (errors.isNotEmpty() || presetId == null || presetName == null ||
            presetVersion == null || chainVersion == null
        ) {
            return PresetDecodeResult.Failure(errors)
        }
        return runCatching {
            FilterPreset(
                presetId,
                presetName,
                FilterChain(operations, chainVersion),
                presetVersion,
            )
        }.fold(
            onSuccess = { PresetDecodeResult.Success(it) },
            onFailure = {
                PresetDecodeResult.Failure(
                    listOf(PresetCodecError(2, it.message ?: "Invalid preset")),
                )
            },
        )
    }

    private fun encodeValue(value: FilterParameterValue): Pair<String, String> = when (value) {
        is FilterParameterValue.FloatValue -> "F" to value.value.toString()
        is FilterParameterValue.IntValue -> "I" to value.value.toString()
        is FilterParameterValue.BooleanValue -> "B" to if (value.value) "1" else "0"
        is FilterParameterValue.EnumValue -> "E" to encodeText(value.value)
    }

    private fun decodeValue(
        type: String,
        encoded: String,
        line: Int,
        errors: MutableList<PresetCodecError>,
    ): FilterParameterValue? = when (type) {
        "F" -> encoded.toFloatOrNull()?.takeIf(Float::isFinite)?.let(FilterParameterValue::FloatValue)
            ?: invalidValue(line, "float", errors)
        "I" -> encoded.toIntOrNull()?.let(FilterParameterValue::IntValue)
            ?: invalidValue(line, "int", errors)
        "B" -> when (encoded) {
            "1" -> FilterParameterValue.BooleanValue(true)
            "0" -> FilterParameterValue.BooleanValue(false)
            else -> invalidValue(line, "boolean", errors)
        }
        "E" -> decodeText(encoded, line, errors)?.let(FilterParameterValue::EnumValue)
        else -> invalidValue(line, "parameter type", errors)
    }

    private fun invalidValue(
        line: Int,
        type: String,
        errors: MutableList<PresetCodecError>,
    ): Nothing? {
        errors += PresetCodecError(line, "Invalid $type value")
        return null
    }

    private fun encodeText(value: String): String = ENCODER.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
    )

    private fun decodeText(
        value: String,
        line: Int,
        errors: MutableList<PresetCodecError>,
    ): String? = try {
        String(DECODER.decode(value), StandardCharsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        errors += PresetCodecError(line, "Invalid encoded text")
        null
    }

    private fun failure(line: Int, message: String) =
        PresetDecodeResult.Failure(listOf(PresetCodecError(line, message)))

    private data class MutableOperation(
        val filterId: String,
        val enabled: Boolean,
        val lutReference: String?,
        val parameters: LinkedHashMap<String, FilterParameterValue> = linkedMapOf(),
    )

    private const val HEADER = "JUSTCAMERA_PRESET\t1"
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()
}
