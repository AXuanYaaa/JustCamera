package top.r2dblog.justcamera.filter.model

enum class FilterExecutionMode { PREVIEW, FINAL_CAPTURE }

enum class FilterCategory {
    ADJUSTMENT, COLOR, BLACK_AND_WHITE, FILM, PORTRAIT, CINEMATIC, LUT, EFFECT, UTILITY,
}

enum class FilterImplementationType { KOTLIN_CPU_REFERENCE, NATIVE_BUILTIN, NATIVE_PLUGIN }

data class FilterDescriptor(
    val id: String,
    val displayName: String,
    val category: FilterCategory,
    val implementationType: FilterImplementationType,
    val supportedModes: Set<FilterExecutionMode>,
    val parameterSpecs: List<FilterParameterSpec> = emptyList(),
    val version: Int = 1,
    val deterministic: Boolean = true,
    val previewSafe: Boolean = FilterExecutionMode.PREVIEW in supportedModes,
    val finalQualityCapable: Boolean = FilterExecutionMode.FINAL_CAPTURE in supportedModes,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid filter id: $id" }
        require(displayName.isNotBlank()) { "Filter display name must not be blank" }
        require(version > 0) { "Filter version must be positive" }
        require(supportedModes.isNotEmpty()) { "A filter must support at least one execution mode" }
        require(parameterSpecs.map { it.key }.distinct().size == parameterSpecs.size) {
            "Filter parameter keys must be unique"
        }
        require(!previewSafe || FilterExecutionMode.PREVIEW in supportedModes)
        require(!finalQualityCapable || FilterExecutionMode.FINAL_CAPTURE in supportedModes)
    }
}

sealed interface FilterParameterSpec {
    val key: String
    val label: String

    data class FloatParameter(
        override val key: String,
        override val label: String,
        val default: Float,
        val minimum: Float,
        val maximum: Float,
        val step: Float,
    ) : FilterParameterSpec {
        init {
            validateKeyAndLabel(key, label)
            require(minimum.isFinite() && maximum.isFinite() && default.isFinite())
            require(minimum <= default && default <= maximum)
            require(step.isFinite() && step > 0f)
        }
    }

    data class IntParameter(
        override val key: String,
        override val label: String,
        val default: Int,
        val minimum: Int,
        val maximum: Int,
        val step: Int = 1,
    ) : FilterParameterSpec {
        init {
            validateKeyAndLabel(key, label)
            require(minimum <= default && default <= maximum)
            require(step > 0)
        }
    }

    data class BooleanParameter(
        override val key: String,
        override val label: String,
        val default: Boolean,
    ) : FilterParameterSpec {
        init {
            validateKeyAndLabel(key, label)
        }
    }

    data class EnumParameter(
        override val key: String,
        override val label: String,
        val default: String,
        val allowedValues: List<String>,
    ) : FilterParameterSpec {
        init {
            validateKeyAndLabel(key, label)
            require(allowedValues.isNotEmpty() && allowedValues.distinct().size == allowedValues.size)
            require(default in allowedValues)
        }
    }

    companion object {
        private fun validateKeyAndLabel(key: String, label: String) {
            require(key.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid parameter key: $key" }
            require(label.isNotBlank()) { "Parameter label must not be blank" }
        }
    }
}

sealed interface FilterParameterValue {
    data class FloatValue(val value: Float) : FilterParameterValue
    data class IntValue(val value: Int) : FilterParameterValue
    data class BooleanValue(val value: Boolean) : FilterParameterValue
    data class EnumValue(val value: String) : FilterParameterValue
}

data class FilterParameters(val values: Map<String, FilterParameterValue> = emptyMap()) {
    fun float(key: String, fallback: Float = 0f): Float =
        (values[key] as? FilterParameterValue.FloatValue)?.value ?: fallback

    fun int(key: String, fallback: Int = 0): Int =
        (values[key] as? FilterParameterValue.IntValue)?.value ?: fallback

    fun boolean(key: String, fallback: Boolean = false): Boolean =
        (values[key] as? FilterParameterValue.BooleanValue)?.value ?: fallback

    fun enum(key: String, fallback: String = ""): String =
        (values[key] as? FilterParameterValue.EnumValue)?.value ?: fallback

    fun validateAndClamp(descriptor: FilterDescriptor): FilterParameterValidation {
        val specs = descriptor.parameterSpecs.associateBy(FilterParameterSpec::key)
        val normalized = linkedMapOf<String, FilterParameterValue>()
        val issues = mutableListOf<FilterValidationIssue>()

        values.keys.filterNot(specs::containsKey).forEach { key ->
            issues += FilterValidationIssue(key, "Unknown parameter '$key'", isError = true)
        }
        descriptor.parameterSpecs.forEach { spec ->
            val supplied = values[spec.key]
            normalized[spec.key] = when (spec) {
                is FilterParameterSpec.FloatParameter -> normalizeFloat(spec, supplied, issues)
                is FilterParameterSpec.IntParameter -> normalizeInt(spec, supplied, issues)
                is FilterParameterSpec.BooleanParameter -> {
                    if (supplied != null && supplied !is FilterParameterValue.BooleanValue) {
                        issues += typeIssue(spec.key, "boolean")
                    }
                    supplied as? FilterParameterValue.BooleanValue
                        ?: FilterParameterValue.BooleanValue(spec.default)
                }
                is FilterParameterSpec.EnumParameter -> normalizeEnum(spec, supplied, issues)
            }
        }
        return FilterParameterValidation(FilterParameters(normalized), issues)
    }

    companion object {
        fun defaults(descriptor: FilterDescriptor): FilterParameters = FilterParameters(
            descriptor.parameterSpecs.associate { spec ->
                spec.key to when (spec) {
                    is FilterParameterSpec.FloatParameter ->
                        FilterParameterValue.FloatValue(spec.default)
                    is FilterParameterSpec.IntParameter -> FilterParameterValue.IntValue(spec.default)
                    is FilterParameterSpec.BooleanParameter ->
                        FilterParameterValue.BooleanValue(spec.default)
                    is FilterParameterSpec.EnumParameter ->
                        FilterParameterValue.EnumValue(spec.default)
                }
            },
        )

        private fun normalizeFloat(
            spec: FilterParameterSpec.FloatParameter,
            supplied: FilterParameterValue?,
            issues: MutableList<FilterValidationIssue>,
        ): FilterParameterValue {
            val value = (supplied as? FilterParameterValue.FloatValue)?.value
            return when {
                supplied != null && value == null -> {
                    issues += typeIssue(spec.key, "float")
                    FilterParameterValue.FloatValue(spec.default)
                }
                value == null -> FilterParameterValue.FloatValue(spec.default)
                !value.isFinite() -> {
                    issues += FilterValidationIssue(
                        spec.key,
                        "Non-finite value replaced with default",
                        isError = true,
                    )
                    FilterParameterValue.FloatValue(spec.default)
                }
                else -> {
                    val clamped = value.coerceIn(spec.minimum, spec.maximum)
                    if (clamped != value) issues += clampedIssue(spec.key, value, clamped)
                    FilterParameterValue.FloatValue(clamped)
                }
            }
        }

        private fun normalizeInt(
            spec: FilterParameterSpec.IntParameter,
            supplied: FilterParameterValue?,
            issues: MutableList<FilterValidationIssue>,
        ): FilterParameterValue {
            val value = (supplied as? FilterParameterValue.IntValue)?.value
            if (supplied != null && value == null) {
                issues += typeIssue(spec.key, "int")
                return FilterParameterValue.IntValue(spec.default)
            }
            val actual = value ?: spec.default
            val clamped = actual.coerceIn(spec.minimum, spec.maximum)
            if (clamped != actual) issues += clampedIssue(spec.key, actual, clamped)
            return FilterParameterValue.IntValue(clamped)
        }

        private fun normalizeEnum(
            spec: FilterParameterSpec.EnumParameter,
            supplied: FilterParameterValue?,
            issues: MutableList<FilterValidationIssue>,
        ): FilterParameterValue {
            val value = (supplied as? FilterParameterValue.EnumValue)?.value
            return when {
                supplied != null && value == null -> {
                    issues += typeIssue(spec.key, "enum")
                    FilterParameterValue.EnumValue(spec.default)
                }
                value == null -> FilterParameterValue.EnumValue(spec.default)
                value !in spec.allowedValues -> {
                    issues += FilterValidationIssue(
                        spec.key,
                        "Unsupported enum value '$value'; using '${spec.default}'",
                        isError = true,
                    )
                    FilterParameterValue.EnumValue(spec.default)
                }
                else -> FilterParameterValue.EnumValue(value)
            }
        }

        private fun typeIssue(key: String, expected: String) = FilterValidationIssue(
            key,
            "Parameter '$key' must be $expected; using default",
            isError = true,
        )

        private fun clampedIssue(key: String, from: Any, to: Any) = FilterValidationIssue(
            key,
            "Parameter '$key' was clamped from $from to $to",
            isError = false,
        )
    }
}

data class FilterValidationIssue(val key: String?, val message: String, val isError: Boolean)

data class FilterParameterValidation(
    val parameters: FilterParameters,
    val issues: List<FilterValidationIssue>,
) {
    val errors: List<FilterValidationIssue> get() = issues.filter(FilterValidationIssue::isError)
}
