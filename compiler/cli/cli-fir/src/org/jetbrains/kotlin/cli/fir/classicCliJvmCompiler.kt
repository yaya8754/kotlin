/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.io.PrintStream

class ClassicCliJvmCompilerBuilder : CompilationStageBuilder<K2JVMCompilerArguments, List<File>> {

    var compilerArguments: K2JVMCompilerArguments? = null

    var messageCollector: MessageCollector = MessageCollector.NONE

    var services: Services = Services.EMPTY

    override fun build(): CompilationStage<K2JVMCompilerArguments, List<File>> =
        ClassicCliJvmCompiler(compilerArguments!!, messageCollector, services)

    operator fun invoke(body: ClassicCliJvmCompilerBuilder.() -> Unit): ClassicCliJvmCompilerBuilder {
        this.body()
        return this
    }
}

class ClassicCliJvmCompiler internal constructor(
    val compilerArguments: K2JVMCompilerArguments,
    val messageCollector: MessageCollector,
    val services: Services,
): CompilationStage<K2JVMCompilerArguments, List<File>> {

    override fun execute(
        input: K2JVMCompilerArguments
    ): ExecutionResult<List<File>> {
        try {
            val res = K2JVMCompiler().exec(messageCollector, services, compilerArguments)
            return if (res == ExitCode.OK) ExecutionResult.Success(emptyList(), emptyList())
            else ExecutionResult.Failure(res, emptyList())
        } catch (e: Throwable) {
            return ExecutionResult.Failure(ExitCode.INTERNAL_ERROR, listOf(ExecutionExceptionWrapper(e)))
        }
    }
}

inline fun CompilationSession.buildClassicCliCompiler(body: ClassicCliJvmCompilerBuilder.() -> Unit): ClassicCliJvmCompiler =
    (createStageBuilder(ClassicCliJvmCompiler::class) as ClassicCliJvmCompilerBuilder).also { it.body() }.build() as ClassicCliJvmCompiler

fun CompilationService.createLocalJvmDefaultCliCompilationSession() = createLocalCompilationSession {
    registerStage<ClassicCliJvmCompiler, ClassicCliJvmCompilerBuilder> { ClassicCliJvmCompilerBuilder() }
}

internal fun classicCliJvmCompile(args: List<String>, outStream: PrintStream): ExecutionResult<List<File>> {

    val service = LocalCompilationServiceBuilder().build()

    val session = service.createLocalJvmDefaultCliCompilationSession()

    val (arguments, collector) = args.toJvmArgumentsAndCollector(outStream)

    val compiler = session.buildClassicCliCompiler {
        compilerArguments = arguments
        messageCollector = collector
    }

    return compiler.execute(arguments)
}
