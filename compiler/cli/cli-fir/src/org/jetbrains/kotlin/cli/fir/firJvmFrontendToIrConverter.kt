/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import org.jetbrains.kotlin.backend.jvm.JvmGeneratorExtensionsImpl
import org.jetbrains.kotlin.backend.jvm.serialization.JvmIdSignatureDescriptor
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.fir.backend.Fir2IrConverter
import org.jetbrains.kotlin.fir.backend.jvm.*
import org.jetbrains.kotlin.ir.backend.jvm.serialization.JvmManglerDesc
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl

class FirJvmFrontendToIrConverterBuilder : CompilationStageBuilder<FirFrontendOutputs, FrontendToIrConverterResult> {
    var messageCollector: MessageCollector? = null

    var irFactory: IrFactory = IrFactoryImpl

    override fun build(): CompilationStage<FirFrontendOutputs, FrontendToIrConverterResult> {
        return FirJvmFrontendToIrConverter(irFactory)
    }

    operator fun invoke(body: FirJvmFrontendToIrConverterBuilder.() -> Unit): FirJvmFrontendToIrConverterBuilder {
        this.body()
        return this
    }
}

class FirJvmFrontendToIrConverter internal constructor(
    val irFactory: IrFactory
) : CompilationStage<FirFrontendOutputs, FrontendToIrConverterResult> {

    override fun execute(
        input: FirFrontendOutputs
    ): ExecutionResult<FrontendToIrConverterResult> {
        val signaturer = JvmIdSignatureDescriptor(JvmManglerDesc())
        val (moduleFragment, symbolTable, components) = Fir2IrConverter.createModuleFragment(
            input.session, input.scopeSession!!, input.firFiles!!,
            input.configuration.languageVersionSettings, signaturer,
            JvmGeneratorExtensionsImpl(generateFacades = true), FirJvmKotlinMangler(input.session), irFactory,
            FirJvmVisibilityConverter,
            Fir2IrJvmSpecialAnnotationSymbolProvider()
        )

        val outputs = FrontendToIrConverterResult(
            moduleFragment, emptyList(),
            symbolTable, null,
            input.project,
            input.sourceFiles,
            emptyList(),
            HashMap(),
            false,
            input.configuration,
            input.module,
            FirJvmBackendClassResolver(components),
            FirJvmBackendExtension(input.session, components),
            input.packagePartProvider
        )

        return ExecutionResult.Success(outputs, emptyList())
    }
}

inline fun CompilationSession.buildJvmFirToIrConverter(body: FirJvmFrontendToIrConverterBuilder.() -> Unit): FirJvmFrontendToIrConverter =
    (createStageBuilder(FirJvmFrontendToIrConverter::class) as FirJvmFrontendToIrConverterBuilder).also { it.body() }.build() as FirJvmFrontendToIrConverter


