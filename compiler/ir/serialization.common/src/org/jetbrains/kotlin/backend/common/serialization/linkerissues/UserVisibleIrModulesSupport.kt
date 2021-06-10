/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.linkerissues

import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializer
import org.jetbrains.kotlin.descriptors.konan.DeserializedKlibModuleOrigin
import org.jetbrains.kotlin.descriptors.konan.KlibModuleOrigin
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.uniqueName
import org.jetbrains.kotlin.utils.*

open class UserVisibleIrModulesSupport(protected val externalDependenciesLoader: ExternalDependenciesLoader) {
    /**
     * Load external [ResolvedDependency]s provided by the build system. These dependencies:
     * - all have [ResolvedDependency.selectedVersion] specified
     * - keep the information about which modules are first-level dependencies (i.e. the modules that the source code module
     *   directly depends on) and indirect (transitive) dependencies
     * - miss modules provided by Kotlin/Native distribution (stdlib, endorsed and platform libraries), as they are
     *   not visible to the build system
     */
    interface ExternalDependenciesLoader {
        fun load(): MutableMap<ResolvedDependencyId, ResolvedDependency>

        companion object {
            val EMPTY = object : ExternalDependenciesLoader {
                override fun load(): MutableMap<ResolvedDependencyId, ResolvedDependency> = mutableMapOf()
            }

            fun from(externalDependenciesFile: File?, onMalformedExternalDependencies: (String) -> Unit): ExternalDependenciesLoader =
                if (externalDependenciesFile != null)
                    object : ExternalDependenciesLoader {
                        override fun load(): MutableMap<ResolvedDependencyId, ResolvedDependency> =
                            mutableMapOf<ResolvedDependencyId, ResolvedDependency>().apply {
                                if (externalDependenciesFile.exists) {
                                    // Deserialize external dependencies from the [externalDependenciesFile].
                                    val externalDependenciesText = String(externalDependenciesFile.readBytes())
                                    val deserialized = ResolvedDependenciesSupport.deserialize(externalDependenciesText) { lineNo, line ->
                                        onMalformedExternalDependencies("Malformed external dependencies at $externalDependenciesFile:$lineNo: $line")
                                    }

                                    deserialized.associateByTo(this) { it.id }
                                }
                            }
                    }
                else
                    EMPTY
        }
    }

    fun getUserVisibleModuleId(deserializer: IrModuleDeserializer): ResolvedDependencyId {
        val nameFromMetadataModuleHeader: String = deserializer.moduleFragment.name.asStringStripSpecialMarkers()
        val nameFromKlibManifest: String? = deserializer.kotlinLibrary?.uniqueName

        return ResolvedDependencyId(listOfNotNull(nameFromMetadataModuleHeader, nameFromKlibManifest))
    }

    open fun getUserVisibleModules(deserializers: Collection<IrModuleDeserializer>): Map<ResolvedDependencyId, ResolvedDependency> {
        return mergedModules(deserializers)
    }

    /**
     * Load [ResolvedDependency]s that represent all libraries participating in the compilation. Includes external dependencies,
     * but without version and hierarchy information. Also includes the libraries that are not visible to the build system
     * (and therefore are missing in [ExternalDependenciesLoader.load]) but are provided by the compiler. For Kotlin/Native such
     * libraries are stdlib, endorsed and platform libraries.
     */
    protected open fun modulesFromDeserializers(deserializers: Collection<IrModuleDeserializer>): Map<ResolvedDependencyId, ResolvedDependency> {
        val modules: Map<ResolvedDependencyId, ModuleWithUninitializedDependencies> = deserializers.associate { deserializer ->
            val moduleId = getUserVisibleModuleId(deserializer)
            val module = ResolvedDependency(
                id = moduleId,
                // TODO: support extracting all the necessary details for non-Native libs: selectedVersion, requestedVersions, artifacts
                selectedVersion = ResolvedDependencyVersion.EMPTY,
                // Assumption: As we don't know for sure which modules the source code module depends on directly and which modules
                // it depends on transitively, so let's assume it depends on all modules directly.
                requestedVersionsByIncomingDependencies = mutableMapOf(ResolvedDependencyId.SOURCE_CODE_MODULE_ID to ResolvedDependencyVersion.EMPTY),
                artifactPaths = mutableSetOf()
            )

            val outgoingDependencyIds = deserializer.moduleDependencies.map { getUserVisibleModuleId(it) }

            moduleId to ModuleWithUninitializedDependencies(module, outgoingDependencyIds)
        }

        // Stamp dependencies.
        return modules.stampDependenciesWithRequestedVersionEqualToSelectedVersion()
    }

    /**
     * The result of the merge of [ExternalDependenciesLoader.load] with [modulesFromDeserializers].
     */
    protected fun mergedModules(deserializers: Collection<IrModuleDeserializer>): MutableMap<ResolvedDependencyId, ResolvedDependency> {
        // First, load external dependencies.
        val mergedModules: MutableMap<ResolvedDependencyId, ResolvedDependency> = externalDependenciesLoader.load()

        // The build system may express a group of modules where one module is a library KLIB and one or more modules
        // are just C-interop KLIBs as a single module with multiple artifacts. We need to expand them so that every particular
        // module/artifact will be represented as an individual [ResolvedDependency] instance.
        val artifactPathsToOriginModules: MutableMap<ResolvedDependencyArtifactPath, ResolvedDependency> = mutableMapOf()
        val originModuleIds: MutableSet<ResolvedDependencyId> = mutableSetOf()
        mergedModules.values.forEach { originModule ->
            val artifactPaths: Set<ResolvedDependencyArtifactPath> = originModule.artifactPaths.takeIf { it.size > 1 } ?: return@forEach
            artifactPaths.forEach { artifactPath -> artifactPathsToOriginModules[artifactPath] = originModule }
            originModuleIds += originModule.id
        }

        // Also, the build system may express the single module as two modules where the first one is a common
        // module without artifacts and the second one is a platform-specific module with mandatory artifact.
        // Example: "org.jetbrains.kotlinx:atomicfu" (common) and "org.jetbrains.kotlinx:atomicfu-macosx64" (platform-specific).
        // Both such modules can be merged into a single module with just two names.
        val moduleIdsToMerge: MutableMap</* platform-specific */ ResolvedDependencyId, /* common */ ResolvedDependencyId> = mutableMapOf()

        // Next, merge external dependencies with dependencies from deserializers.
        modulesFromDeserializers(deserializers).forEach { (moduleId, module) ->
            val originModule = module.artifactPaths.firstNotNullOfOrNull { artifactPathsToOriginModules[it] }

            val externalDependencyModule = mergedModules[moduleId]
            when {
                externalDependencyModule != null -> {
                    // Just add missing dependencies to the same module in [mergedModules].
                    module.requestedVersionsByIncomingDependencies.forEach { (incomingDependencyId, requestedVersion) ->
                        if (incomingDependencyId !in externalDependencyModule.requestedVersionsByIncomingDependencies) {
                            externalDependencyModule.requestedVersionsByIncomingDependencies[incomingDependencyId] = requestedVersion
                        }
                    }

                    if (originModule != null
                        && externalDependencyModule.id != originModule.id
                        && externalDependencyModule.id in originModule.requestedVersionsByIncomingDependencies
                        && externalDependencyModule.selectedVersion == originModule.selectedVersion
                    ) {
                        // These two modules must be merged.
                        moduleIdsToMerge[originModule.id] = externalDependencyModule.id
                    }
                }
                originModule != null -> {
                    // Handle artifacts that needs to be represented as individual [ResolvedDependency] objects.
                    module.selectedVersion = originModule.selectedVersion

                    val incomingDependencyIdsToStampRequestedVersion = module.requestedVersionsByIncomingDependencies
                        .mapNotNull { (incomingDependencyId, requestedVersion) ->
                            if (requestedVersion.isEmpty()) incomingDependencyId else null
                        }
                    incomingDependencyIdsToStampRequestedVersion.forEach { incomingDependencyId ->
                        module.requestedVersionsByIncomingDependencies[incomingDependencyId] = originModule.selectedVersion
                    }

                    mergedModules[moduleId] = module
                }
                else -> {
                    // Just copy the module to [mergedModules]. If it has no incoming dependencies, then treat it as
                    // the first-level dependency module (i.e. the module that only the source code module depends on).
                    if (module.requestedVersionsByIncomingDependencies.isEmpty()) {
                        module.requestedVersionsByIncomingDependencies[ResolvedDependencyId.SOURCE_CODE_MODULE_ID] = module.selectedVersion
                    }
                    mergedModules[moduleId] = module
                }
            }
        }

        return mergeModuleIds(moduleIdsToMerge, mergedModules)
    }

    protected data class ModuleWithUninitializedDependencies(
        val module: ResolvedDependency,
        val outgoingDependencyIds: List<ResolvedDependencyId>
    )

    private fun Map<ResolvedDependencyId, ModuleWithUninitializedDependencies>.stampDependenciesWithRequestedVersionEqualToSelectedVersion(): Map<ResolvedDependencyId, ResolvedDependency> {
        return mapValues { (moduleId, moduleWithUninitializedDependencies) ->
            val (module, outgoingDependencyIds) = moduleWithUninitializedDependencies
            outgoingDependencyIds.forEach { outgoingDependencyId ->
                val dependencyModule = getValue(outgoingDependencyId).module
                dependencyModule.requestedVersionsByIncomingDependencies[moduleId] = dependencyModule.selectedVersion
            }
            module
        }
    }

    private fun mergeModuleIds(
        moduleIdsToMerge: MutableMap</* platform-specific */ ResolvedDependencyId, /* common */ ResolvedDependencyId>,
        modules: MutableMap<ResolvedDependencyId, ResolvedDependency>
    ): MutableMap<ResolvedDependencyId, ResolvedDependency> {
        if (moduleIdsToMerge.isEmpty())
            return modules

        // Do merge.
        val replacedModules: MutableMap</* old */ ResolvedDependencyId, /* new */ ResolvedDependency> = mutableMapOf()
        moduleIdsToMerge.forEach { (platformSpecificModuleId, commonModuleId) ->
            val platformSpecificModule = modules.getValue(platformSpecificModuleId)
            val commonModule = modules.getValue(commonModuleId)

            val replacementModuleId = ResolvedDependencyId(platformSpecificModuleId.uniqueNames + commonModuleId.uniqueNames)
            val replacementModule = ResolvedDependency(
                id = replacementModuleId,
                visibleAsFirstLevelDependency = commonModule.visibleAsFirstLevelDependency,
                selectedVersion = commonModule.selectedVersion,
                requestedVersionsByIncomingDependencies = mutableMapOf<ResolvedDependencyId, ResolvedDependencyVersion>().apply {
                    this += commonModule.requestedVersionsByIncomingDependencies
                    this += platformSpecificModule.requestedVersionsByIncomingDependencies - commonModuleId
                },
                artifactPaths = mutableSetOf<ResolvedDependencyArtifactPath>().apply {
                    this += commonModule.artifactPaths
                    this += platformSpecificModule.artifactPaths
                }
            )

            replacedModules[platformSpecificModuleId] = replacementModule
            replacedModules[commonModuleId] = replacementModule
        }

        // Assemble new modules together (without replaced and with replacements).
        val mergedModules: MutableMap<ResolvedDependencyId, ResolvedDependency> = mutableMapOf()
        mergedModules += modules - replacedModules.keys
        replacedModules.values.forEach { replacement -> mergedModules[replacement.id] = replacement }

        // Fix references to point to replacement modules instead of replaced modules.
        mergedModules.values.forEach { module ->
            (module.requestedVersionsByIncomingDependencies.keys intersect replacedModules.keys).forEach inner@{ replacedModuleId ->
                val replacementModuleId = replacedModules[replacedModuleId]?.id ?: return@inner

                with(module.requestedVersionsByIncomingDependencies) {
                    val oldRequestedVersion: ResolvedDependencyVersion? = this[replacementModuleId]
                    val newRequestedVersion: ResolvedDependencyVersion = getValue(replacedModuleId)

                    this[replacementModuleId] = when {
                        !newRequestedVersion.isEmpty() -> newRequestedVersion
                        else -> oldRequestedVersion ?: newRequestedVersion
                    }
                    remove(replacedModuleId)
                }
            }
        }

        return mergedModules
    }

    // TODO: find a way to access KotlinLibrary without descriptors
    protected val IrModuleDeserializer.kotlinLibrary: KotlinLibrary?
        get() = (moduleDescriptor.getCapability(KlibModuleOrigin.CAPABILITY) as? DeserializedKlibModuleOrigin)?.library

    val moduleIdComparator: Comparator<ResolvedDependencyId> = Comparator { a, b ->
        when {
            a == b -> 0
            // Kotlin libs go lower.
            a.isKotlinLibrary && !b.isKotlinLibrary -> 1
            !a.isKotlinLibrary && b.isKotlinLibrary -> -1
            // Modules with simple names go upper as they are most likely user-made libs.
            a.hasSimpleName && !b.hasSimpleName -> -1
            !a.hasSimpleName && b.hasSimpleName -> 1
            // Else: just compare by names.
            else -> {
                val aUniqueNames = a.uniqueNames.iterator()
                val bUniqueNames = b.uniqueNames.iterator()

                while (aUniqueNames.hasNext() && bUniqueNames.hasNext()) {
                    val diff = aUniqueNames.next().compareTo(bUniqueNames.next())
                    if (diff != 0) return@Comparator diff
                }

                when {
                    aUniqueNames.hasNext() -> 1
                    bUniqueNames.hasNext() -> -1
                    else -> 0
                }
            }
        }
    }

    protected open val ResolvedDependencyId.isKotlinLibrary: Boolean
        get() = uniqueNames.any { uniqueName -> uniqueName.startsWith(KOTLIN_LIBRARY_PREFIX) }

    protected open val ResolvedDependencyId.hasSimpleName: Boolean
        get() = uniqueNames.all { uniqueName -> uniqueName.none { it == '.' || it == ':' } }

    companion object {
        const val KOTLIN_LIBRARY_PREFIX = "org.jetbrains.kotlin"

        val DEFAULT = UserVisibleIrModulesSupport(ExternalDependenciesLoader.EMPTY)
    }
}
