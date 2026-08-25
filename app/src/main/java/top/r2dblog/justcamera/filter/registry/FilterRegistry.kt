package top.r2dblog.justcamera.filter.registry

import top.r2dblog.justcamera.filter.api.ImageFilter
import top.r2dblog.justcamera.filter.model.FilterCategory
import top.r2dblog.justcamera.filter.model.FilterDescriptor
import top.r2dblog.justcamera.filter.model.FilterExecutionMode

sealed interface FilterRegistrationResult {
    data class Registered(val descriptor: FilterDescriptor) : FilterRegistrationResult
    data class Rejected(val reason: String) : FilterRegistrationResult
}

/** Logical registry shared by Kotlin built-ins and future versioned native/plugin adapters. */
class FilterRegistry(filters: Iterable<ImageFilter> = emptyList()) {
    private val entries = linkedMapOf<String, ImageFilter>()

    init {
        filters.forEach { filter ->
            check(register(filter) is FilterRegistrationResult.Registered) {
                "Duplicate initial filter id: ${filter.descriptor.id}"
            }
        }
    }

    @Synchronized
    fun register(filter: ImageFilter): FilterRegistrationResult {
        val id = filter.descriptor.id
        if (id in entries) {
            return FilterRegistrationResult.Rejected("Filter id already registered: $id")
        }
        entries[id] = filter
        return FilterRegistrationResult.Registered(filter.descriptor)
    }

    @Synchronized
    fun resolve(id: String): ImageFilter? = entries[id]

    @Synchronized
    fun filters(): List<ImageFilter> = entries.values.toList()

    @Synchronized
    fun descriptors(): List<FilterDescriptor> = entries.values.map(ImageFilter::descriptor)

    @Synchronized
    fun descriptors(
        category: FilterCategory? = null,
        mode: FilterExecutionMode? = null,
    ): List<FilterDescriptor> = entries.values.asSequence()
        .map(ImageFilter::descriptor)
        .filter { category == null || it.category == category }
        .filter { mode == null || mode in it.supportedModes }
        .toList()

    @Synchronized
    fun categories(): Set<FilterCategory> = entries.values
        .mapTo(linkedSetOf()) { it.descriptor.category }
}
