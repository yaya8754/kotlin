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
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.checkKotlinPackageUsage
import org.jetbrains.kotlin.cli.common.fir.FirDiagnosticsCompilerResultsReporter
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.jvmModularRoots
import org.jetbrains.kotlin.cli.jvm.registerJavacIfNeeded
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.FirAnalyzerFacade
import org.jetbrains.kotlin.fir.checkers.registerExtendedCommonCheckers
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.session.FirSessionFactory
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.modules.Module
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.PlatformDependentAnalyzerServices
import org.jetbrains.kotlin.resolve.diagnostics.SimpleGenericDiagnostics
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatformAnalyzerServices
import java.io.File
import java.io.PrintStream

data class FirJvmFrontendInputs(
    val module: Module,
    val filesToCompile: List<KtFile>,
    val configuration: CompilerConfiguration? = null
)

data class FirFrontendOutputs(
    val session: FirSession,
    val module: Module,
    val project: Project,
    val sourceFiles: List<KtFile>,
    val configuration: CompilerConfiguration,
    val getPackagePartProvider: ((GlobalSearchScope) -> PackagePartProvider)?,
    val session1: FirSession,
    val scopeSession: ScopeSession?,
    val firFiles: List<FirFile>?
)

class FirJvmFrontendBuilder : CompilationStageBuilder<FirJvmFrontendInputs, FirFrontendOutputs> {
    var configuration: CompilerConfiguration = CompilerConfiguration()

    var messageCollector: MessageCollector? = null

    var project: Project? = null

    var getPackagePartProvider: ((GlobalSearchScope) -> PackagePartProvider)? = null

    override fun build(): CompilationStage<FirJvmFrontendInputs, FirFrontendOutputs> {
        return FirJvmFrontend(
            messageCollector ?: configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY),
            project ?: error(""),
            configuration,
            getPackagePartProvider ?: error("")
        )
    }

    operator fun invoke(body: FirJvmFrontendBuilder.() -> Unit): FirJvmFrontendBuilder {
        this.body()
        return this
    }
}

class FirJvmFrontend internal constructor(
    val messageCollector: MessageCollector,
    val project: Project,
    val configuration: CompilerConfiguration,
    val getPackagePartProvider: (GlobalSearchScope) -> PackagePartProvider,
) : CompilationStage<FirJvmFrontendInputs, FirFrontendOutputs> {

    override fun execute(
        input: FirJvmFrontendInputs
    ): ExecutionResult<FirFrontendOutputs> {
        val (module, ktFiles, baseModuleConfiguration) = input
        val moduleConfiguration = baseModuleConfiguration ?: configuration
        if (!moduleConfiguration.getBoolean(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE) &&
            !checkKotlinPackageUsage(messageCollector, ktFiles)
        ) return ExecutionResult.Failure(-1, emptyList())

        val syntaxErrors = ktFiles.fold(false) { errorsFound, ktFile ->
            AnalyzerWithCompilerReport.reportSyntaxErrors(ktFile, messageCollector).isHasErrors or errorsFound
        }

        val sourceScope = GlobalSearchScope.filesWithoutLibrariesScope(project, ktFiles.map { it.virtualFile })
            .uniteWith(TopDownAnalyzerFacadeForJVM.AllJavaSourcesInProjectScope(project))

        val providerAndScopeForIncrementalCompilation: FirSessionFactory.ProviderAndScopeForIncrementalCompilation? = null
//            getProviderAndScopeForIncrementalCompilation(
//                project,
//                configuration.get(JVMConfigurationKeys.MODULES)?.map(::TargetId),
//                sourceScope,
//                moduleConfiguration[JVMConfigurationKeys.OUTPUT_DIRECTORY],
//                configuration.get(JVMConfigurationKeys.INCREMENTAL_COMPILATION_COMPONENTS),
//                getPackagePartProvider,
//                environment.projectEnvironment.environment.localFileSystem
//            )
        val extendedAnalysisMode = false

        val librariesScope = ProjectScope.getLibrariesScope(project).let {
            if (providerAndScopeForIncrementalCompilation != null)
                it.intersectWith(GlobalSearchScope.notScope(providerAndScopeForIncrementalCompilation.scope))
            else it
        }

        val languageVersionSettings = moduleConfiguration.languageVersionSettings
        val session = FirSessionFactory.createSessionWithDependencies(
            Name.identifier(module.getModuleName()),
            JvmPlatforms.unspecifiedJvmPlatform,
            JvmPlatformAnalyzerServices,
            externalSessionProvider = null,
            project,
            languageVersionSettings,
            sourceScope,
            librariesScope,
            lookupTracker = configuration.get(CommonConfigurationKeys.LOOKUP_TRACKER),
            providerAndScopeForIncrementalCompilation,
            getPackagePartProvider,
            dependenciesConfigurator = {
                dependencies(moduleConfiguration.jvmClasspathRoots.map { it.toPath() })
                dependencies(moduleConfiguration.jvmModularRoots.map { it.toPath() })
                friendDependencies(moduleConfiguration[JVMConfigurationKeys.FRIEND_PATHS] ?: emptyList())
            },
            sessionConfigurator = {
                if (extendedAnalysisMode) {
                    registerExtendedCommonCheckers()
                }
            }
        )

        val firAnalyzerFacade = FirAnalyzerFacade(session, moduleConfiguration.languageVersionSettings, ktFiles)

        firAnalyzerFacade.runResolution()
        val firDiagnostics = firAnalyzerFacade.runCheckers().values.flatten()
        val hasErrors = FirDiagnosticsCompilerResultsReporter.reportDiagnostics(firDiagnostics, messageCollector)

        if (syntaxErrors || hasErrors) {
            return ExecutionResult.Failure(-1, emptyList())
        }

        val outputs = FirFrontendOutputs(
            session,
            module,
            project,
            ktFiles,
            moduleConfiguration,
            getPackagePartProvider,
            firAnalyzerFacade.session,
            firAnalyzerFacade.scopeSession,
            firAnalyzerFacade.firFiles
        )

        return ExecutionResult.Success(outputs, emptyList())
    }
}

inline fun CompilationSession.buildJvmFirFrontend(body: FirJvmFrontendBuilder.() -> Unit): FirJvmFrontend =
    (createStageBuilder(FirJvmFrontend::class) as FirJvmFrontendBuilder).also { it.body() }.build() as FirJvmFrontend


fun firCompile(args: List<String>, outStream: PrintStream): ExecutionResult<List<File>> {
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
            this.getPackagePartProvider = { environment.createPackagePartProvider(it) }
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

fun CompilationService.createLocalJvmFirCompilationSession() = createLocalCompilationSession {
    registerStage<FirJvmFrontend, FirJvmFrontendBuilder> { FirJvmFrontendBuilder() }
    registerStage<FirJvmFrontendToIrConverter, FirJvmFrontendToIrConverterBuilder> { FirJvmFrontendToIrConverterBuilder() }
    registerStage<IrJvmBackend, IrJvmBackendBuilder> { IrJvmBackendBuilder() }
}

