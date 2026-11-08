package com.oss.mobile

/**
 * Core-owned registry for built-in and later installed source modules.
 *
 * Plugin Center can validate and stage an extension before registering it. A module
 * is active only after its API requirement and stable ID have been checked here.
 */
class OverlayModuleRegistry(
    private val coreApiVersion: Int = OverlayModuleApi.CURRENT_VERSION
) {
    private val modules = linkedMapOf<String, OverlaySourceModule>()

    init {
        require(coreApiVersion > 0) { "Core API version must be positive" }
    }

    @Synchronized
    fun register(module: OverlaySourceModule) {
        val descriptor = module.descriptor
        require(descriptor.minimumCoreApi <= coreApiVersion) {
            "Module ${descriptor.id} requires Core API ${descriptor.minimumCoreApi}; current is $coreApiVersion"
        }
        require(descriptor.capabilities.isNotEmpty()) {
            "Module ${descriptor.id} must declare at least one capability"
        }
        require(modules[descriptor.id] == null) {
            "A module with ID ${descriptor.id} is already registered"
        }
        modules[descriptor.id] = module
    }

    @Synchronized
    fun unregister(moduleId: String): OverlaySourceModule? = modules.remove(moduleId)

    @Synchronized
    fun find(moduleId: String): OverlaySourceModule? = modules[moduleId]

    @Synchronized
    fun snapshot(): List<OverlaySourceModule> = modules.values.toList()

    @Synchronized
    fun descriptors(): List<OverlayModuleDescriptor> = modules.values.map { it.descriptor }
}
