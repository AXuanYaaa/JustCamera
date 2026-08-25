package top.r2dblog.justcamera.filter.lut

data class CubeLutDocument(val title: String?, val lut: CubeLut)

data class CubeParseError(val line: Int, val message: String)

sealed interface CubeParseResult {
    data class Success(val document: CubeLutDocument) : CubeParseResult
    data class Failure(val errors: List<CubeParseError>) : CubeParseResult
}

object CubeLutParser {
    fun parse(text: String): CubeParseResult {
        var title: String? = null
        var domainMin = LutDomain.ZERO
        var domainMax = LutDomain.ONE
        var oneDimensionalSize: Int? = null
        var threeDimensionalSize: Int? = null
        val samples = ArrayList<Float>()
        val errors = mutableListOf<CubeParseError>()
        var sampleRowsStarted = false
        var titleSeen = false
        var domainMinSeen = false
        var domainMaxSeen = false
        var lastContentLine = 1

        text.lineSequence().forEachIndexed { index, original ->
            val lineNumber = index + 1
            val line = original.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            lastContentLine = lineNumber
            val parts = line.split(Regex("\\s+"))
            val directive = parts.first().uppercase()

            if (directive in DIRECTIVES) {
                if (sampleRowsStarted) {
                    errors += CubeParseError(lineNumber, "Directive '$directive' appears after samples")
                    return@forEachIndexed
                }
                when (directive) {
                    "TITLE" -> {
                        if (titleSeen) errors += CubeParseError(lineNumber, "Duplicate TITLE")
                        titleSeen = true
                        val raw = line.substringAfter(parts.first(), "").trim()
                        if (raw.isEmpty()) {
                            errors += CubeParseError(lineNumber, "TITLE requires text")
                        } else {
                            title = raw.removeSurrounding("\"")
                        }
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        val duplicate = if (directive == "DOMAIN_MIN") domainMinSeen else domainMaxSeen
                        if (duplicate) errors += CubeParseError(lineNumber, "Duplicate $directive")
                        if (directive == "DOMAIN_MIN") domainMinSeen = true else domainMaxSeen = true
                        val domain = parseTriple(parts, lineNumber, directive, errors)
                        if (domain != null) {
                            if (directive == "DOMAIN_MIN") domainMin = domain else domainMax = domain
                        }
                    }
                    "LUT_1D_SIZE" -> {
                        if (oneDimensionalSize != null) {
                            errors += CubeParseError(lineNumber, "Duplicate LUT_1D_SIZE")
                        }
                        oneDimensionalSize = parseSize(parts, lineNumber, 2..MAX_1D_SIZE, errors)
                    }
                    "LUT_3D_SIZE" -> {
                        if (threeDimensionalSize != null) {
                            errors += CubeParseError(lineNumber, "Duplicate LUT_3D_SIZE")
                        }
                        threeDimensionalSize = parseSize(parts, lineNumber, 2..MAX_3D_SIZE, errors)
                    }
                }
                return@forEachIndexed
            }

            if (parts.first().firstOrNull()?.isLetter() == true) {
                errors += CubeParseError(lineNumber, "Unsupported directive '${parts.first()}'")
                return@forEachIndexed
            }
            if (parts.size != 3) {
                errors += CubeParseError(lineNumber, "Expected an RGB sample with three numbers")
                return@forEachIndexed
            }
            if (oneDimensionalSize == null && threeDimensionalSize == null) {
                errors += CubeParseError(lineNumber, "LUT size must be declared before samples")
                return@forEachIndexed
            }
            sampleRowsStarted = true
            parts.forEach { token ->
                val value = token.toFloatOrNull()
                if (value == null || !value.isFinite()) {
                    errors += CubeParseError(lineNumber, "Invalid finite sample '$token'")
                } else {
                    samples += value
                }
            }
        }

        if (oneDimensionalSize != null && threeDimensionalSize != null) {
            errors += CubeParseError(lastContentLine, "Combined 1D + 3D .cube files are not supported in PH3")
        }
        if (oneDimensionalSize == null && threeDimensionalSize == null) {
            errors += CubeParseError(lastContentLine, "Missing LUT_1D_SIZE or LUT_3D_SIZE")
        }
        if (!(domainMin.red < domainMax.red && domainMin.green < domainMax.green &&
                domainMin.blue < domainMax.blue)
        ) {
            errors += CubeParseError(lastContentLine, "DOMAIN_MAX must exceed DOMAIN_MIN on every channel")
        }

        val expectedRows = when {
            oneDimensionalSize != null -> oneDimensionalSize.toLong()
            threeDimensionalSize != null -> requireNotNull(threeDimensionalSize).toLong().let {
                it * it * it
            }
            else -> null
        }
        if (expectedRows != null && samples.size.toLong() != expectedRows * 3L) {
            errors += CubeParseError(
                lastContentLine,
                "Expected $expectedRows RGB sample rows, got ${samples.size / 3}",
            )
        }
        if (errors.isNotEmpty()) return CubeParseResult.Failure(errors)

        val lut = try {
            if (oneDimensionalSize != null) {
                Lut1D(oneDimensionalSize, domainMin, domainMax, samples.toFloatArray())
            } else {
                Lut3D(
                    requireNotNull(threeDimensionalSize),
                    domainMin,
                    domainMax,
                    samples.toFloatArray(),
                )
            }
        } catch (error: IllegalArgumentException) {
            return CubeParseResult.Failure(
                listOf(CubeParseError(lastContentLine, error.message ?: "Invalid LUT data")),
            )
        }
        return CubeParseResult.Success(CubeLutDocument(title, lut))
    }

    private fun parseTriple(
        parts: List<String>,
        line: Int,
        directive: String,
        errors: MutableList<CubeParseError>,
    ): LutDomain? {
        if (parts.size != 4) {
            errors += CubeParseError(line, "$directive requires three numbers")
            return null
        }
        val values = parts.drop(1).map(String::toFloatOrNull)
        if (values.any { it == null || !it.isFinite() }) {
            errors += CubeParseError(line, "$directive contains an invalid finite number")
            return null
        }
        return LutDomain(requireNotNull(values[0]), requireNotNull(values[1]), requireNotNull(values[2]))
    }

    private fun parseSize(
        parts: List<String>,
        line: Int,
        allowed: IntRange,
        errors: MutableList<CubeParseError>,
    ): Int? {
        val size = parts.singleOrNull(1)?.toIntOrNull()
        if (size == null || size !in allowed) {
            errors += CubeParseError(line, "LUT size must be in ${allowed.first}..${allowed.last}")
            return null
        }
        return size
    }

    private fun <T> List<T>.singleOrNull(index: Int): T? =
        if (size == index + 1) get(index) else null

    private val DIRECTIVES = setOf(
        "TITLE",
        "DOMAIN_MIN",
        "DOMAIN_MAX",
        "LUT_1D_SIZE",
        "LUT_3D_SIZE",
    )
    private const val MAX_1D_SIZE = 65_536
    private const val MAX_3D_SIZE = 65
}
