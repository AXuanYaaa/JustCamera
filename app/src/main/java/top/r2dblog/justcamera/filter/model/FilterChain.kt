package top.r2dblog.justcamera.filter.model

data class FilterOperation(
    val filterId: String,
    val parameters: FilterParameters = FilterParameters(),
    val enabled: Boolean = true,
    val lutReference: String? = null,
) {
    init {
        require(filterId.isNotBlank()) { "Filter operation id must not be blank" }
        require(lutReference == null || lutReference.isNotBlank())
    }
}

/** Declaration order is execution and serialization order. */
data class FilterChain(
    val operations: List<FilterOperation> = emptyList(),
    val version: Int = 1,
) {
    init {
        require(version > 0) { "Filter chain version must be positive" }
    }
}

data class FilterPreset(
    val id: String,
    val name: String,
    val chain: FilterChain,
    val version: Int = 1,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid preset id: $id" }
        require(name.isNotBlank()) { "Preset name must not be blank" }
        require(version > 0) { "Preset version must be positive" }
    }
}
