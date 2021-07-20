/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import org.jetbrains.kotlin.asJava.FilteredJvmDiagnostics
import org.jetbrains.kotlin.backend.common.output.OutputFileCollection
import org.jetbrains.kotlin.backend.common.output.SimpleOutputFileCollection
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.jvm.JvmGeneratorExtensionsImpl
import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.backend.jvm.jvmPhases
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.OutputMessageUtil
import org.jetbrains.kotlin.cli.common.output.writeAll
import org.jetbrains.kotlin.cli.jvm.compiler.CompileEnvironmentUtil
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.CodegenFactory
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.codegen.state.GenerationStateEventCallback
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.modules.Module
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import java.io.File

class IrJvmBackendBuilder : CompilationStageBuilder<FrontendToIrConverterResult, GenerationState> {
    
    var messageCollector: MessageCollector = MessageCollector.NONE
    
    override fun build(): CompilationStage<FrontendToIrConverterResult, GenerationState> =
        IrJvmBackend(messageCollector)

    operator fun invoke(body: IrJvmBackendBuilder.() -> Unit): IrJvmBackendBuilder {
        this.body()
        return this
    }
}

class IrJvmBackend internal constructor(
    val messageCollector: MessageCollector
) : CompilationStage<FrontendToIrConverterResult, GenerationState> {

    override fun execute(input: FrontendToIrConverterResult): ExecutionResult<GenerationState> {
        val dummyBindingContext = NoScopeRecordCliBindingTrace().bindingContext

        val codegenFactory = JvmIrCodegenFactory(input.configuration.get(CLIConfigurationKeys.PHASE_CONFIG) ?: PhaseConfig(jvmPhases))

        // Create and initialize the module and its dependencies
        val container = TopDownAnalyzerFacadeForJVM.createContainer(
            input.project, input.sourceFiles, NoScopeRecordCliBindingTrace(),
            input.configuration, { input.packagePartProvider ?: error("") },
            ::FileBasedDeclarationProviderFactory, CompilerEnvironment,
            TopDownAnalyzerFacadeForJVM.newModuleSearchScope(input.project, input.sourceFiles), emptyList()
        )

        val generatorExtensions = JvmGeneratorExtensionsImpl()

        val generationState = GenerationState.Builder(
            input.project, ClassBuilderFactories.BINARIES,
            container.get(), dummyBindingContext, input.sourceFiles,
            input.configuration
        ).codegenFactory(
            codegenFactory
        ).withModule(
            input.module
        ).onIndependentPartCompilationEnd(
            createOutputFilesFlushingCallbackIfPossible(input.configuration)
        ).isIrBackend(
            true
        ).jvmBackendClassResolver(
            input.jvmBackendClassResolver
        ).build()

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        generationState.beforeCompile()
        codegenFactory.generateModuleInFrontendIRMode(
            generationState, input.moduleFragment, input.symbolTable, generatorExtensions, input.backendExtensions!!,
            {
//                performanceManager?.notifyIRLoweringFinished()
//                performanceManager?.notifyIRGenerationStarted()
            }
        )
        CodegenFactory.doCheckCancelled(generationState)
        generationState.factory.done()

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        AnalyzerWithCompilerReport.reportDiagnostics(
            FilteredJvmDiagnostics(
                generationState.collectedExtraJvmDiagnostics,
                dummyBindingContext.diagnostics
            ),
            messageCollector
        )

        writeOutput(generationState, input.configuration)

        return ExecutionResult.Success(generationState, emptyList())
    }
}

inline fun CompilationSession.buildIrJvmBackend(body: IrJvmBackendBuilder.() -> Unit): IrJvmBackend =
    (createStageBuilder(IrJvmBackend::class) as IrJvmBackendBuilder).also { it.body() }.build() as IrJvmBackend

private fun GenerationState.Builder.withModule(module: Module?) =
    apply {
        if (module != null) {
            targetId(TargetId(module))
            moduleName(module.getModuleName())
            outDirectory(File(module.getOutputDirectory()))
        }
    }

private fun createOutputFilesFlushingCallbackIfPossible(configuration: CompilerConfiguration): GenerationStateEventCallback {
    if (configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY) == null) {
        return GenerationStateEventCallback.DO_NOTHING
    }
    return GenerationStateEventCallback { state ->
        writeOutput(state, configuration)
    }
}

private fun writeOutput(
    state: GenerationState,
    configuration: CompilerConfiguration
) {
    val currentOutput = SimpleOutputFileCollection(state.factory.currentOutput)
    writeOutput(configuration, currentOutput, null)
    if (!configuration.get(JVMConfigurationKeys.RETAIN_OUTPUT_IN_MEMORY, false)) {
        state.factory.releaseGeneratedOutput()
    }
}

private fun writeOutput(
    configuration: CompilerConfiguration,
    outputFiles: OutputFileCollection,
    mainClassFqName: FqName?
) {
    val reportOutputFiles = configuration.getBoolean(CommonConfigurationKeys.REPORT_OUTPUT_FILES)
    val jarPath = configuration.get(JVMConfigurationKeys.OUTPUT_JAR)
    val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    if (jarPath != null) {
        val includeRuntime = configuration.get(JVMConfigurationKeys.INCLUDE_RUNTIME, false)
        val noReflect = configuration.get(JVMConfigurationKeys.NO_REFLECT, false)
        val resetTimestamps = !configuration.get(JVMConfigurationKeys.NO_RESET_JAR_TIMESTAMPS, false)
        CompileEnvironmentUtil.writeToJar(
            jarPath, includeRuntime, noReflect, resetTimestamps, mainClassFqName, outputFiles, messageCollector
        )
        if (reportOutputFiles) {
            val message = OutputMessageUtil.formatOutputMessage(outputFiles.asList().flatMap { it.sourceFiles }.distinct(), jarPath)
            messageCollector.report(CompilerMessageSeverity.OUTPUT, message)
        }
        return
    }

    val outputDir = configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY) ?: File(".")
    outputFiles.writeAll(outputDir, messageCollector, reportOutputFiles)
}
