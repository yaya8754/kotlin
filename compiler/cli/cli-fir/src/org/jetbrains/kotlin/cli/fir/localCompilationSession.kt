/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import com.intellij.openapi.Disposable
import kotlin.reflect.KClass

class LocalCompilationSession(
    compilationService: CompilationService,
    registeredCreateStage: MutableMap<KClass<out CompilationStage<*, *>>, (CompilationSession) -> CompilationStageBuilder<*, *>>,
    val rootDisposable: Disposable,
) : CompilationSession(compilationService, registeredCreateStage) {

    override fun close() {
        rootDisposable.dispose()
    }
}

class LocalCompilationSessionBuilder(compilationService: CompilationService) : CompilationSessionBuilder(compilationService) {
    var rootDisposable: Disposable? = null

    override fun build(): LocalCompilationSession =
        LocalCompilationSession(
            compilationService,
            registeredCreateStage,
            rootDisposable ?: (compilationService as LocalCompilationService).rootDisposable
        )
}

inline fun CompilationService.createLocalCompilationSession(body: LocalCompilationSessionBuilder.() -> Unit): CompilationSession =
    LocalCompilationSessionBuilder(this).apply(body).build()

