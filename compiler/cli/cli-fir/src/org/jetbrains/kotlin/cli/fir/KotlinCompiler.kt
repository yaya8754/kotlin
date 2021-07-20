/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("unused")

package org.jetbrains.kotlin.cli.fir

import java.io.File

interface KotlinCompilerFactory {

    fun createCompiler(
        env: KotlinCompilerEnvironment, // a compilation session representation, also task execution mode/controls - reporting, logging, etc.
    ): KotlinCompiler

//    fun createProjectCompiler(
//        env: KotlinCompilerEnvironment, // a compilation session representation
//        commonDependencies: ModuleDependencies, // dependencies - with what to compile
//        commonOptions: CompilerOptions, // options - how to compile - language features and options
//        mode: CompilationMode // compilation mode - expected target - full, IC, to particular stage, to particular artifact type
//    ) : KotlinProjectCompiler

    fun createModuleCompiler(
        env: KotlinCompilerEnvironment, // a compilation session representation
        initialModuleSources: Sources, // module sources - what to compile
        dependencies: ModuleDependencies, // dependencies - with what to compile
        options: CompilerOptions, // options - how to compile - language features and options
        mode: CompilationMode // compilation mode - expected target - full, IC, to particular stage, to particular artifact type
    ) : KotlinModuleCompiler
}

interface KotlinCompiler {
    fun compile(
        moduleSources: Sources, // module sources - what to compile
        dependencies: ModuleDependencies, // dependencies - with what to compile
        options: CompilerOptions, // options - how to compile - language features and options
        mode: CompilationMode // compilation mode - expected target - full, IC, to particular stage, to particular artifact type
    )
}

//interface KotlinProjectCompiler {
//    operator fun invoke(
//        moduleSources: Sources, // module sources - what to compile
//        dependencies: ModuleDependencies,// dependencies - with what to compile
//        options: CompilerOptions, // options - how to compile - language features and opti
//        mode: CompilationMode // compilation mode - expected target - full, IC, to particular stage, to particular artifact type
//    )
//}

interface KotlinModuleCompiler {
    operator fun invoke(
        moduleSources: Sources, // module sources - what to compile
        additionalDependencies: ModuleDependencies, // dependencies - with what to compile
        additionalOptions: CompilerOptions, // options - how to compile - language features and options
        mode: CompilationMode // compilation mode - expected target - full, IC, to particular stage, to particular artifact type
    )
}

interface KotlinCompilerEnvironment {
    val configuration: PropertiesMap

    fun registerReporter()
    fun registerLogger()
    fun registerArtifactHandler()
    fun registerEnvironmentExtension()
}

interface Sources {
    val sourceRoots: List<File>
    val sourcesToCompile: List<File>
}

interface ModuleDependencies {
    val jdk: File
    val dependenciesRoots: List<File>

    fun registerDependenciesProvider()
    fun registerKnownSymbolsProvider() // low level, e.g. package parts provider
}

abstract class CompilerOptions() : PropertiesMap

interface CompilationMode {
    val targetArtifact: TargetArtifact
    val mode: Int
    val buildTempDirectory: File
}

class TargetArtifact(
    val type: Int,
    val path: File?
)

interface PropertiesMap : Map<String, Any>
