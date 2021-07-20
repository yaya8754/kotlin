/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("unused")

package org.jetbrains.kotlin.cli.fir

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.CommonCompilerPerformanceManager
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.utils.KotlinPaths

class K2JvmFirCompiler : CLICompiler<K2JVMCompilerArguments>() {
    override val performanceManager: CommonCompilerPerformanceManager
        get() = TODO("Not yet implemented")

    override fun createMetadataVersion(versionArray: IntArray): BinaryVersion {
        TODO("Not yet implemented")
    }

    override fun setupPlatformSpecificArgumentsAndServices(
        configuration: CompilerConfiguration,
        arguments: K2JVMCompilerArguments,
        services: Services
    ) {
        TODO("Not yet implemented")
    }

    override fun doExecute(
        arguments: K2JVMCompilerArguments,
        configuration: CompilerConfiguration,
        rootDisposable: Disposable,
        paths: KotlinPaths?
    ): ExitCode {
        TODO("Not yet implemented")
    }

    override fun MutableList<String>.addPlatformOptions(arguments: K2JVMCompilerArguments) {
        TODO("Not yet implemented")
    }

    override fun createArguments(): K2JVMCompilerArguments {
        TODO("Not yet implemented")
    }

    override fun executableScriptFileName(): String {
        TODO("Not yet implemented")
    }
}