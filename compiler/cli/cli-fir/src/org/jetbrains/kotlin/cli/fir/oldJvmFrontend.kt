/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.registerJavacIfNeeded
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.io.PrintStream

@Suppress("unused")
class OldJvmFrontendBuilder : CompilationStageBuilder<Pair<K2JVMCompilerArguments, List<KtFile>>, Pair<AnalysisResult, List<KtFile>>> {

    var configuration: CompilerConfiguration = CompilerConfiguration()

    var messageCollector: MessageCollector = MessageCollector.NONE

    var project: Project? = null

    var createPackagePartProvider: ((GlobalSearchScope) -> PackagePartProvider)? = null

    override fun build(): CompilationStage<Pair<K2JVMCompilerArguments, List<KtFile>>, Pair<AnalysisResult, List<KtFile>>> {
        configuration.apply {
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        }
        return OldJvmFrontend(messageCollector, configuration, project!!, createPackagePartProvider!!)
    }

    operator fun invoke(body: OldJvmFrontendBuilder.() -> Unit): OldJvmFrontendBuilder {
        this.body()
        return this
    }
}

class OldJvmFrontend internal constructor(
    val messageCollector: MessageCollector,
    val configuration: CompilerConfiguration,
    val project: Project,
    val createPackagePartProvider: (GlobalSearchScope) -> PackagePartProvider,
) : CompilationStage<Pair<K2JVMCompilerArguments, List<KtFile>>, Pair<AnalysisResult, List<KtFile>>> {

    override fun execute(
        input: Pair<K2JVMCompilerArguments, List<KtFile>>
    ): ExecutionResult<Pair<AnalysisResult, List<KtFile>>> {
        val sourceFiles = input.second
        val analyzer = AnalyzerWithCompilerReport(messageCollector, configuration.languageVersionSettings)
        analyzer.analyzeAndReport(sourceFiles) {
            val project = project
            TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                project,
                sourceFiles,
                NoScopeRecordCliBindingTrace(),
                configuration,
                createPackagePartProvider
            )
        }
        return ExecutionResult.Success(analyzer.analysisResult to sourceFiles, emptyList())
    }
}

inline fun CompilationSession.buildJvmOldFrontend(body: OldJvmFrontendBuilder.() -> Unit): OldJvmFrontend =
    (createStageBuilder(OldJvmFrontend::class) as OldJvmFrontendBuilder).also { it.body() }.build() as OldJvmFrontend

fun CompilationService.createLocalJvmOldFeOldBeCompilationSession() = createLocalCompilationSession {
    registerStage<OldJvmFrontend, OldJvmFrontendBuilder> { OldJvmFrontendBuilder() }
    registerStage<OldJvmBackend, OldJvmBackendBuilder> { OldJvmBackendBuilder() }
}

fun CompilationService.createLocalJvmOldFeIrCompilationSession() = createLocalCompilationSession {
    registerStage<OldJvmFrontend, OldJvmFrontendBuilder> { OldJvmFrontendBuilder() }
    registerStage<IrJvmBackend, IrJvmBackendBuilder> { IrJvmBackendBuilder() }
}

fun oldFeOldBeCompile(args: List<String>, outStream: PrintStream): ExecutionResult<List<File>> {

    val service = LocalCompilationServiceBuilder().build()

    val session = service.createLocalJvmOldFeOldBeCompilationSession()

    val rootDisposable = (service as LocalCompilationService).rootDisposable

    val (arguments, collector) = args.toJvmArgumentsAndCollector(outStream)

    try {
        if (collector.hasErrors()) return collector.makeCompilationFailureResult()

        val configuration = arguments.toCompilerConfiguration(collector).apply {
            put(JVMConfigurationKeys.IR, false)
            configureModuleToCompile(arguments)
        }

        if (collector.hasErrors()) return collector.makeCompilationFailureResult()

        val environment =
            KotlinCoreEnvironment.createForProduction(
                rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
            ).apply {
                registerJavacIfNeeded(arguments)
            }

        if (collector.hasErrors()) return collector.makeCompilationFailureResult()

        val frontend = session.buildJvmOldFrontend {
            this.configuration = configuration
            this.messageCollector = collector
            this.project = environment.project
            this.createPackagePartProvider = environment::createPackagePartProvider
        }

        val backend = session.buildJvmOldBackend {
            this.configuration = configuration
        }

        if (collector.hasErrors()) return collector.makeCompilationFailureResult()

        val files = environment.getSourceFiles()

        val feRes = frontend.execute(arguments to files)

        if (feRes !is ExecutionResult.Success || collector.hasErrors()) return collector.makeCompilationFailureResult()

        val beRes = backend.execute(feRes.value)

        if (beRes !is ExecutionResult.Success || collector.hasErrors()) return collector.makeCompilationFailureResult()

        val mainClassFqName =
            if (configuration.get(JVMConfigurationKeys.OUTPUT_JAR) != null)
                findMainClass(feRes.value.first.bindingContext, configuration.languageVersionSettings, environment.getSourceFiles())
            else null

        KotlinToJVMBytecodeCompiler.writeOutput(configuration, beRes.value.factory, mainClassFqName)

        if (collector.hasErrors()) return collector.makeCompilationFailureResult()

        return ExecutionResult.Success(emptyList(), emptyList()) //TODO: list of files, diagnostics
    } finally {
        if (collector is GroupingMessageCollector) {
            collector.flush()
        }
        service.rootDisposable.dispose()
    }
}
