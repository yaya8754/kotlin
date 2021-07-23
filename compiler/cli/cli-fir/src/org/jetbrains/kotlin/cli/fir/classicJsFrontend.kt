/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JsArgumentConstants
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.common.setupCommonArguments
import org.jetbrains.kotlin.cli.js.setupJsSpecificArguments
import org.jetbrains.kotlin.cli.js.setupJsSpecificServices
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.ir.backend.js.*
import org.jetbrains.kotlin.js.config.ErrorTolerancePolicy
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.addIfNotNull
import java.io.File
import java.io.PrintStream

data class ClassicJsFrontendResult(
    val analysisResult: ModulesStructure.JsFrontEndResult,
    val descriptors: ModulesStructure,
    val sourceFiles: List<KtFile>
)

class ClassicJsFrontendBuilder : CompilationStageBuilder<Pair<K2JSCompilerArguments, List<KtFile>>, ClassicJsFrontendResult> {
    var configuration: CompilerConfiguration = CompilerConfiguration()

    var messageCollector: MessageCollector? = null

    var project: Project? = null

    val friendDependencies: MutableList<String> = ArrayList()

    override fun build(): CompilationStage<Pair<K2JSCompilerArguments, List<KtFile>>, ClassicJsFrontendResult> {
        val actualConfiguration = configuration // ?: environment?.configuration ?: error("")
        return ClassicJsFrontend(
            project ?: error(""),
            messageCollector ?: actualConfiguration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY),
            actualConfiguration,
            friendDependencies
        )
    }

    operator fun invoke(body: ClassicJsFrontendBuilder.() -> Unit): ClassicJsFrontendBuilder {
        this.body()
        return this
    }
}

class ClassicJsFrontend internal constructor(
    val project: Project,
    val messageCollector: MessageCollector,
    val configuration: CompilerConfiguration,
    val friendLibraries: List<String>
) : CompilationStage<Pair<K2JSCompilerArguments, List<KtFile>>, ClassicJsFrontendResult> {

    internal val analyzer: AnalyzerWithCompilerReport = AnalyzerWithCompilerReport(messageCollector, configuration.languageVersionSettings)

    override fun execute(
        input: Pair<K2JSCompilerArguments, List<KtFile>>
    ): ExecutionResult<ClassicJsFrontendResult> {

        val (arguments, sourceFiles) = input
        // TODO: Handle non-empty main call arguments
        @Suppress("UNUSED_VARIABLE") val mainCallArguments = if (K2JsArgumentConstants.NO_CALL == arguments.main) null else emptyList<String>()

        val libraries = configuration.getList(JSConfigurationKeys.LIBRARIES)

        val descriptors = ModulesStructure(
            project,
            MainModule.SourceFiles(sourceFiles),
            analyzer,
            configuration,
            libraries,
            friendLibraries
        )

        val analysisResult = descriptors.runAnalysis(configuration.get(JSConfigurationKeys.ERROR_TOLERANCE_POLICY) ?: ErrorTolerancePolicy.DEFAULT)

        return ExecutionResult.Success(
            ClassicJsFrontendResult(analysisResult, descriptors, sourceFiles),
            emptyList()
        )
    }

}

@Suppress("unused")
private fun example(args: List<String>, outStream: PrintStream) {

    val service = LocalCompilationServiceBuilder().build()

    val session = service.createLocalJsOldFeIrCompilationSession()

    val rootDisposable = Disposer.newDisposable()

    try {
        val arguments = K2JSCompilerArguments()
        parseCommandLineArguments(args, arguments)
        val collector = GroupingMessageCollector(
            PrintingMessageCollector(outStream, MessageRenderer.WITHOUT_PATHS, arguments.verbose),
            arguments.allWarningsAsErrors
        )

        var environment: KotlinCoreEnvironment? = null

        val outputFilePath = arguments.outputFile
        if (outputFilePath == null) {
            collector.report(CompilerMessageSeverity.ERROR, "IR: Specify output file via -output", null)
            return //ExitCode.COMPILATION_ERROR
        }

        val jsFrontendBuilder = session.createStageBuilder(ClassicJsFrontend::class) as ClassicJsFrontendBuilder
        val jsFrontend = jsFrontendBuilder {

            configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)
            messageCollector = collector

            val services: Services = Services.EMPTY

            configuration.setupCommonArguments(arguments)
            configuration.setupJsSpecificArguments(arguments)
            configuration.setupJsSpecificServices(services)

            this.messageCollector = collector

            environment =
                KotlinCoreEnvironment.createForProduction(
                    rootDisposable, configuration, EnvironmentConfigFiles.JS_CONFIG_FILES
                )

            project = environment!!.project

            val libraries = ArrayList<String>()
            arguments.libraries?.let {
                libraries.addAll(it.splitByPathSeparator())
            }
            libraries.addIfNotNull(arguments.includes)

            configuration.put(JSConfigurationKeys.LIBRARIES, libraries)
            configuration.put(JSConfigurationKeys.TRANSITIVE_LIBRARIES, libraries)

            val commonSourcesArray = arguments.commonSources
            val commonSources = commonSourcesArray?.toSet() ?: emptySet()
            for (arg in arguments.freeArgs) {
                configuration.addKotlinSourceRoot(arg, commonSources.contains(arg))
            }

            configuration.put(
                CommonConfigurationKeys.MODULE_NAME,
                arguments.irModuleName ?: FileUtil.getNameWithoutExtension(File(outputFilePath))
            )
        }.build()

        val jsFrontendToIrConverterBuilder =
            session.createStageBuilder(ClassicJsFrontendToIrConverter::class) as ClassicJsFrontendToIrConverterBuilder
        val jsFrontendToIrConverter = jsFrontendToIrConverterBuilder {
            // defaults are ok
        }.build()

        val jsKLibGeneratorBuilder = session.createStageBuilder(ClassicJsKLibGenerator::class) as ClassicJsKLibGeneratorBuilder
        val jsKLibGenerator = jsKLibGeneratorBuilder {
            outputKlibPath =
                if (arguments.irProduceKlibDir)
                    File(outputFilePath).parent
                else
                    outputFilePath
            nopack = arguments.irProduceKlibDir
        }.build()

        val frontendRes = jsFrontend.execute(arguments to environment!!.getSourceFiles())
        if (frontendRes is ExecutionResult.Success) {
            val convertorRes = jsFrontendToIrConverter.execute(frontendRes.value)

            if (convertorRes is ExecutionResult.Success) {
                jsKLibGenerator.execute(convertorRes.value)
            }
        }
    } finally {
        // TODO: error handling
        rootDisposable.dispose()
        session.close()
    }
}

private fun String.splitByPathSeparator(): List<String> {
    return this.split(File.pathSeparator.toRegex())
        .dropLastWhile { it.isEmpty() }
        .toTypedArray()
        .filterNot { it.isEmpty() }
}

fun CompilationService.createLocalJsOldFeIrCompilationSession() = createLocalCompilationSession {
    registerStage<ClassicJsFrontend, ClassicJsFrontendBuilder> { ClassicJsFrontendBuilder() }
    registerStage<ClassicJsFrontendToIrConverter, ClassicJsFrontendToIrConverterBuilder> { ClassicJsFrontendToIrConverterBuilder() }
    registerStage<ClassicJsFrontendToIrConverter, ClassicJsFrontendToIrConverterBuilder> { ClassicJsFrontendToIrConverterBuilder() }
    registerStage<IrJvmBackend, IrJvmBackendBuilder> { IrJvmBackendBuilder() }
}
