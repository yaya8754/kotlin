/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("unused")

package org.jetbrains.kotlin.cli.fir

import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile

class OldJvmBackendBuilder : CompilationStageBuilder<Pair<AnalysisResult, List<KtFile>>, GenerationState> {
    var configuration: CompilerConfiguration? = null

    override fun build(): CompilationStage<Pair<AnalysisResult, List<KtFile>>, GenerationState> {
        return OldJvmBackend(configuration!!)
    }

    operator fun invoke(body: OldJvmBackendBuilder.() -> Unit): OldJvmBackendBuilder {
        this.body()
        return this
    }
}

class OldJvmBackend internal constructor(
    val configuration: CompilerConfiguration
) : CompilationStage<Pair<AnalysisResult, List<KtFile>>, GenerationState> {

    override fun execute(
        input: Pair<AnalysisResult, List<KtFile>>
    ): ExecutionResult<GenerationState> {
        val analysisResult = input.first
        val sourceFiles = input.second
        val res = GenerationState.Builder(
            sourceFiles.first().project,
            ClassBuilderFactories.BINARIES,
            analysisResult.moduleDescriptor,
            analysisResult.bindingContext,
            sourceFiles,
            configuration
        ).build().also(KotlinCodegenFacade::compileCorrectFiles)
        return ExecutionResult.Success(res, emptyList())
    }
}

inline fun CompilationSession.buildJvmOldBackend(body: OldJvmBackendBuilder.() -> Unit): OldJvmBackend =
    (createStageBuilder(OldJvmBackend::class) as OldJvmBackendBuilder).also { it.body() }.build() as OldJvmBackend
