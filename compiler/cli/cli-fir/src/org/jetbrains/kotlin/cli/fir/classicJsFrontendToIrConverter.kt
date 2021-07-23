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
import org.jetbrains.kotlin.backend.common.serialization.ICData
import org.jetbrains.kotlin.backend.common.serialization.mangle.ManglerChecker
import org.jetbrains.kotlin.backend.common.serialization.mangle.descriptor.Ir2DescriptorManglerAdapter
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureDescriptor
import org.jetbrains.kotlin.backend.jvm.JvmBackendExtension
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.codegen.JvmBackendClassResolver
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.ir.backend.js.KotlinFileSerializedData
import org.jetbrains.kotlin.ir.backend.js.generateModuleFragmentWithPlugins
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerDesc
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerIr
import org.jetbrains.kotlin.ir.backend.js.prepareIncrementalCompilationData
import org.jetbrains.kotlin.ir.backend.js.sortDependencies
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrFactory
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.js.config.ErrorTolerancePolicy
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.library.resolver.KotlinResolvedLibrary
import org.jetbrains.kotlin.modules.Module
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi2ir.Psi2IrConfiguration
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import org.jetbrains.kotlin.resolve.BindingContext

data class FrontendToIrConverterResult(
    val moduleFragment: IrModuleFragment,
    val icData: List<KotlinFileSerializedData>,
    val symbolTable: SymbolTable,
    val bindingContext: BindingContext?,
    val project: Project,
    val sourceFiles: List<KtFile>,
    val dependenciesList: List<KotlinResolvedLibrary>,
    val expectDescriptorToSymbol: MutableMap<DeclarationDescriptor, IrSymbol>,
    val hasErrors: Boolean,
    val configuration: CompilerConfiguration,
    val module: Module?,
    val jvmBackendClassResolver: JvmBackendClassResolver?,
    val backendExtensions: JvmBackendExtension?,
    val frontendBindingContext: BindingContext
)

class ClassicJsFrontendToIrConverterBuilder : CompilationStageBuilder<ClassicJsFrontendResult, FrontendToIrConverterResult> {

    var irFactory: IrFactory = PersistentIrFactory()

    override fun build(): CompilationStage<ClassicJsFrontendResult, FrontendToIrConverterResult> {
        return ClassicJsFrontendToIrConverter(irFactory)
    }

    operator fun invoke(body: ClassicJsFrontendToIrConverterBuilder.() -> Unit): ClassicJsFrontendToIrConverterBuilder {
        this.body()
        return this
    }
}

class ClassicJsFrontendToIrConverter internal constructor(
    val irFactory: IrFactory
) : CompilationStage<ClassicJsFrontendResult, FrontendToIrConverterResult> {

    override fun execute(
        input: ClassicJsFrontendResult
    ): ExecutionResult<FrontendToIrConverterResult> {

        val configuration = input.descriptors.compilerConfiguration

        val errorPolicy = configuration.get(JSConfigurationKeys.ERROR_TOLERANCE_POLICY) ?: ErrorTolerancePolicy.DEFAULT

        val messageLogger = configuration[IrMessageLogger.IR_MESSAGE_LOGGER] ?: IrMessageLogger.None

        val psi2Ir = Psi2IrTranslator(
            configuration.languageVersionSettings,
            Psi2IrConfiguration(errorPolicy.allowErrors)
        )
        val symbolTable = SymbolTable(IdSignatureDescriptor(JsManglerDesc), irFactory)
        val psi2IrContext =
            psi2Ir.createGeneratorContext(input.analysisResult.moduleDescriptor, input.analysisResult.bindingContext, symbolTable)

        val irBuiltIns = psi2IrContext.irBuiltIns

        val expectDescriptorToSymbol = mutableMapOf<DeclarationDescriptor, IrSymbol>()
        val feContext = psi2IrContext.run {
            JsIrLinker.JsFePluginContext(moduleDescriptor, symbolTable, typeTranslator, irBuiltIns)
        }

        val (icData, serializedIrFiles) = prepareIncrementalCompilationData(configuration, input.sourceFiles)

        val irLinker = JsIrLinker(
            psi2IrContext.moduleDescriptor,
            messageLogger,
            psi2IrContext.irBuiltIns,
            psi2IrContext.symbolTable,
            feContext,
            serializedIrFiles?.let { ICData(it, errorPolicy.allowErrors) }
        )

        val dependenciesList = input.descriptors.allDependencies

        sortDependencies(dependenciesList, input.descriptors.descriptors).map {
            irLinker.deserializeOnlyHeaderModule(input.descriptors.getModuleDescriptor(it), it)
        }

        val moduleFragment =
            psi2IrContext.generateModuleFragmentWithPlugins(
                input.descriptors.project,
                input.sourceFiles,
                irLinker,
                messageLogger,
                expectDescriptorToSymbol
            )

        moduleFragment.acceptVoid(ManglerChecker(JsManglerIr, Ir2DescriptorManglerAdapter(JsManglerDesc)))

        return ExecutionResult.Success(
            FrontendToIrConverterResult(
                moduleFragment = moduleFragment,
                icData = icData,
                symbolTable = psi2IrContext.symbolTable,
                bindingContext = psi2IrContext.bindingContext,
                project = input.descriptors.project,
                sourceFiles = input.sourceFiles,
                dependenciesList = dependenciesList,
                expectDescriptorToSymbol = expectDescriptorToSymbol,
                hasErrors = input.analysisResult.hasErrors,
                configuration = configuration,
                module = null,
                jvmBackendClassResolver = null,
                backendExtensions = null,
                frontendBindingContext = NoScopeRecordCliBindingTrace().bindingContext
            ),
            emptyList()
        )
    }
}

