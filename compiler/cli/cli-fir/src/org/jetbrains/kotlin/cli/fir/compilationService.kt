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

import kotlin.reflect.KClass

interface CompilationStage<T, R> {
    fun execute(input: T): ExecutionResult<R>
}

interface CompilationStageBuilder<T, R> {
    fun build(): CompilationStage<T, R>
}

abstract class CompilationSession(
    val compilationService: CompilationService,
    val registeredCreateStage: MutableMap<KClass<out CompilationStage<*, *>>, (CompilationSession) -> CompilationStageBuilder<*, *>>
) {
    fun <S : CompilationStage<*, *>> createStageBuilder(impl: KClass<S>): CompilationStageBuilder<*, *> {
        val compilationStage = registeredCreateStage[impl] ?: throw AssertionError("Unknown compilation stage: $impl")
        return compilationStage.invoke(this)
    }

    @Suppress("UNUSED_PARAMETER")
    fun <T : Any, R : Any> createStageBuilder(from: KClass<T>, to: KClass<R>): CompilationStageBuilder<T, R> {
        TODO("Not yet implemented")
    }

    abstract fun close()
}

abstract class CompilationSessionBuilder(val compilationService: CompilationService) {
    val registeredCreateStage = mutableMapOf<KClass<out CompilationStage<*, *>>, (CompilationSession) -> CompilationStageBuilder<*, *>>()
    // registeredDestroyStage ?

    inline fun <reified S: CompilationStage<*, *>, reified B: CompilationStageBuilder<*, *>> registerStage(noinline createBuilder: (CompilationSession) -> B) =
        registeredCreateStage.put(S::class, createBuilder)

    abstract fun build(): CompilationSession
}

interface CompilationService {
}

abstract class CompilationServiceBuilder {
    abstract fun build(): CompilationService
}


