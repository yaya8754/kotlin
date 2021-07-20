/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir.test

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.fir.CompilationServiceBuilder
import org.jetbrains.kotlin.cli.fir.CompilationSession
import org.jetbrains.kotlin.cli.fir.ExecutionResult

/*
fun actualCompilerImpl(options: K2JVMCompilerArguments) {

    val compilationService = setupCompilationService {
        fsCacheSize = 10
    }.build()

    val compilationSession = compilationService.createSession("s1")

    val remoteCompilationSession = RemoteCompilationSession.connectOrRun {
        requiredCapabilities(version, jvmir)
    }

    val frontend = remoteCompilationSession.createStage<FirIncrementalStage<Ir, Diagnostics, DirtyFiles>> {
        registerExtension<Extension1>()
    }.build()

    val backend = compilationSession.createStage<IrJvmTranslator<CompiledFiles, Diagnostics>> {
        registerExtension<Extension2>()
    }.build()

    try {
        var dirtyFiles = options.dirtyFiles

        var frontendResults = null
        while (dirtyFiles.isNotEmpty) {

            frontendResults = frontend.execute(FirArguments(dirtyFiles, options), frontendResults?.state)

            when (frontendResults) {
                is ExecutionResult.Failure -> return frontendResults
                is ExecutionResult.Partial -> {
                    dirtyFiles = frontendResults.dirtyFiles
                }
                is ExecutionResult.Success -> break
            }
        }

        return backend.execute(IrArguments(frontendResults.ir))
    } finally {
        remoteCompilationSession.close()
        compilationSession.close()
    }
}

fun setupCompilationService(body: CompilationServiceBuilder.() -> Unit): CompilationServiceBuilder {

}

object RemoteCompilationSession {
    fun connectOrRun(body: () -> Unit): CompilationSession
}*/
