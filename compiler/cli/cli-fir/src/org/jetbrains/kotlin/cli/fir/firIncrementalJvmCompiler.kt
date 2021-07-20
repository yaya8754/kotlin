/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("unused")

package org.jetbrains.kotlin.cli.fir

import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.registerJavacIfNeeded
import java.io.File
import java.io.PrintStream

private fun firCompileIncrementally(
    args: List<String>,
    cachesDir: File,
    outStream: PrintStream
): ExecutionResult<List<File>> {

    val service = LocalCompilationServiceBuilder().build()

    val session = service.createLocalJvmFirCompilationSession()

    val rootDisposable = (service as LocalCompilationService).rootDisposable

    val (arguments, collector) = args.toJvmArgumentsAndCollector(outStream)

    try {
        if (collector.hasErrors()) return collector.makeCompilationFailureResult()

        val configuration = arguments.toCompilerConfiguration(collector)

        val module = configuration.configureModuleToCompile(arguments)

        val environment =
            KotlinCoreEnvironment.createForProduction(
                rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
            ).apply {
                registerJavacIfNeeded(arguments)
            }

        if (collector.hasErrors()) return collector.makeCompilationFailureResult()

        val frontend = session.buildJvmFirFrontend {
            this.messageCollector = collector
            this.configuration = configuration
            this.project = environment.project
            this.packagePartProvider = environment.createPackagePartProvider(ProjectScope.getLibrariesScope(project!!))
        }

        val fir2ir = session.buildJvmFirToIrConverter {
            this.messageCollector = collector
        }

        val backend = session.buildIrJvmBackend {
            this.messageCollector = collector
        }

        if (collector.hasErrors()) return collector.makeCompilationFailureResult()



        val frontendRes = frontend.execute(FirJvmFrontendInputs(module, environment.getSourceFiles()))
        if (frontendRes is ExecutionResult.Success) {
            val convertorRes = fir2ir.execute(frontendRes.value)

            if (convertorRes is ExecutionResult.Success) {
                backend.execute(convertorRes.value)
            }
        }

        if (collector.hasErrors()) return collector.makeCompilationFailureResult()
        return ExecutionResult.Success(emptyList(), emptyList()) //TODO: list of files, diagnostics
    } finally {
        if (collector is GroupingMessageCollector) {
            collector.flush()
        }
        service.rootDisposable.dispose()
    }
}