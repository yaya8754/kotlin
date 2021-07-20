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
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.registerJavacIfNeeded
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.FirAnalyzerFacade
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.session.FirSessionFactory
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.modules.Module
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
    val packagePartProvider: PackagePartProvider?,
    val session1: FirSession,
    val scopeSession: ScopeSession?,
    val firFiles: List<FirFile>?
)

class FirJvmFrontendBuilder : CompilationStageBuilder<FirJvmFrontendInputs, FirFrontendOutputs> {
    var configuration: CompilerConfiguration = CompilerConfiguration()

    var messageCollector: MessageCollector? = null

    var project: Project? = null

    var packagePartProvider: PackagePartProvider? = null

    override fun build(): CompilationStage<FirJvmFrontendInputs, FirFrontendOutputs> {
        return FirJvmFrontend(
            messageCollector ?: configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY),
            project ?: error(""),
            configuration,
            packagePartProvider ?: error("")
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
    val packagePartProvider: PackagePartProvider,
) : CompilationStage<FirJvmFrontendInputs, FirFrontendOutputs> {

    override fun execute(
        input: FirJvmFrontendInputs
    ): ExecutionResult<FirFrontendOutputs> {
        val (module, ktFiles, moduleConfiguration) = input
        val actualConfiguration = moduleConfiguration ?: configuration
        if (!actualConfiguration.getBoolean(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE) &&
            !checkKotlinPackageUsage(ktFiles, messageCollector)
        ) return ExecutionResult.Failure(-1, emptyList())

        val scope = GlobalSearchScope.filesScope(project, ktFiles.map { it.virtualFile })
            .uniteWith(TopDownAnalyzerFacadeForJVM.AllJavaSourcesInProjectScope(project))
        val provider = FirProjectSessionProvider()

        class FirJvmModuleInfo(override val name: Name) : ModuleInfo {
            constructor(moduleName: String) : this(Name.identifier(moduleName))

            val dependencies: MutableList<ModuleInfo> = mutableListOf()

            override val platform: TargetPlatform
                get() = JvmPlatforms.unspecifiedJvmPlatform

            override val analyzerServices: PlatformDependentAnalyzerServices
                get() = JvmPlatformAnalyzerServices

            override fun dependencies(): List<ModuleInfo> {
                return dependencies
            }
        }

        val moduleInfo = FirJvmModuleInfo(module.getModuleName())
        val session: FirSession =
            FirSessionFactory.createJavaModuleBasedSession(
                moduleInfo, provider, scope, project, languageVersionSettings = configuration.languageVersionSettings
            ) {
//            if (extendedAnalysisMode) {
//                registerExtendedCommonCheckers()
//            }
            }.also {
                val dependenciesInfo = FirJvmModuleInfo(Name.special("<dependencies>"))
                moduleInfo.dependencies.add(dependenciesInfo)
                val librariesScope = ProjectScope.getLibrariesScope(project)
                FirSessionFactory.createLibrarySession(dependenciesInfo, provider, librariesScope, project, packagePartProvider)
            }

        val firAnalyzerFacade = FirAnalyzerFacade(session, actualConfiguration.languageVersionSettings, ktFiles)

        firAnalyzerFacade.runResolution()
        val firDiagnostics = firAnalyzerFacade.runCheckers().values.flatten()
        AnalyzerWithCompilerReport.reportDiagnostics(
            SimpleGenericDiagnostics(firDiagnostics),
            messageCollector
        )

        if (firDiagnostics.any { it.severity == Severity.ERROR }) {
            return ExecutionResult.Failure(-1, emptyList())
        }

        val outputs = FirFrontendOutputs(
            session,
            module,
            project,
            ktFiles,
            actualConfiguration,
            packagePartProvider,
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

fun CompilationService.createLocalJvmFirCompilationSession() = createLocalCompilationSession {
    registerStage<FirJvmFrontend, FirJvmFrontendBuilder> { FirJvmFrontendBuilder() }
    registerStage<FirJvmFrontendToIrConverter, FirJvmFrontendToIrConverterBuilder> { FirJvmFrontendToIrConverterBuilder() }
    registerStage<IrJvmBackend, IrJvmBackendBuilder> { IrJvmBackendBuilder() }
}

