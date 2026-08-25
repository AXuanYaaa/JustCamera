package top.r2dblog.justcamera.filter.model

enum class FilterExecutionTarget {
    /** Low-latency implementation suitable for a live preview. */
    PREVIEW,

    /** Quality-first implementation used on the final captured frame. */
    FINAL_CAPTURE,

    BOTH,
}

data class FilterDescriptor(
    val id: String,
    val displayName: String,
    val version: Int,
    val executionTarget: FilterExecutionTarget,
    val parameterSpecs: List<FilterParameterSpec> = emptyList(),
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid filter id: $id" }
        require(displayName.isNotBlank()) { "Filter display name must not be blank" }
        require(version > 0) { "Filter version must be positive" }
        require(parameterSpecs.map { it.key }.distinct().size == parameterSpecs.size) {
            "Filter parameter keys must be unique"
        }
    }
}

sealed interface FilterParameterSpec {
    val key: String
    val displayName: String

    data class FloatRange(
        override val key: String,
        override val displayName: String,
        val minimum: Float,
        val maximum: Float,
        val default: Float,
    ) : FilterParameterSpec {
        init {
            require(minimum <= default && default <= maximum) {
                "Default must be inside the parameter range"
            }
        }
    }

    data class Choice(
        override val key: String,
        override val displayName: String,
        val choices: List<String>,
        val default: String,
    ) : FilterParameterSpec {
        init {
            require(choices.isNotEmpty() && default in choices) { "Invalid choice parameter" }
        }
    }

    data class Toggle(
        override val key: String,
        override val displayName: String,
        val default: Boolean,
    ) : FilterParameterSpec
}

sealed interface FilterParameterValue {
    data class FloatValue(val value: Float) : FilterParameterValue
    data class ChoiceValue(val value: String) : FilterParameterValue
    data class ToggleValue(val value: Boolean) : FilterParameterValue
}

data class FilterParameters(val values: Map<String, FilterParameterValue> = emptyMap()) {
    fun float(key: String): Float? = (values[key] as? FilterParameterValue.FloatValue)?.value
    fun choice(key: String): String? = (values[key] as? FilterParameterValue.ChoiceValue)?.value
    fun toggle(key: String): Boolean? = (values[key] as? FilterParameterValue.ToggleValue)?.value

    fun validateAgainst(descriptor: FilterDescriptor): List<String> {
        val specs = descriptor.parameterSpecs.associateBy { it.key }
        val errors = values.keys.filterNot(specs::containsKey)
            .map { "Unknown parameter: $it" }
            .toMutableList()
        values.forEach { (key, value) ->
            val spec = specs[key] ?: return@forEach
            val valid = when {
                spec is FilterParameterSpec.FloatRange &&
                    value is FilterParameterValue.FloatValue ->
                    value.value in spec.minimum..spec.maximum
                spec is FilterParameterSpec.Choice &&
                    value is FilterParameterValue.ChoiceValue -> value.value in spec.choices
                spec is FilterParameterSpec.Toggle &&
                    value is FilterParameterValue.ToggleValue -> true
                else -> false
            }
            if (!valid) errors += "Invalid value for parameter: $key"
        }
        return errors
    }
}
