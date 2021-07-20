/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import org.jetbrains.kotlin.backend.jvm.jvmPhases
import org.jetbrains.kotlin.cli.common.*
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.arguments.validateArguments
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.common.modules.ModuleBuilder
import org.jetbrains.kotlin.cli.jvm.*
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.jetbrains.kotlin.cli.jvm.plugins.PluginCliParser
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.utils.KotlinPaths
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.io.PrintStream
import java.util.*

fun List<String>.toJvmArguments(): K2JVMCompilerArguments {
    val arguments = K2JVMCompilerArguments()
    parseCommandLineArguments(this, arguments)
    return arguments
}

fun K2JVMCompilerArguments.validate(messageCollector: MessageCollector): Boolean =
    validateArguments(errors)?.let {
        messageCollector.report(CompilerMessageSeverity.ERROR, it)
        false
    } ?: true

fun List<String>.toJvmArgumentsAndCollector(messageStream: PrintStream = System.err): Pair<K2JVMCompilerArguments, MessageCollector> {
    val arguments = toJvmArguments()
    val collector =
        GroupingMessageCollector(
            PrintingMessageCollector(messageStream, MessageRenderer.WITHOUT_PATHS, arguments.verbose),
            arguments.allWarningsAsErrors
        )
    arguments.validate(collector)
    return arguments to collector
}

@Suppress("unused")
fun MessageCollector.makeCompilationFailureResult(): ExecutionResult<List<File>> =
    ExecutionResult.Failure(ExitCode.COMPILATION_ERROR, emptyList())

internal fun K2JVMCompilerArguments.toCompilerConfiguration(collector: MessageCollector): CompilerConfiguration {
    val paths = computeKotlinPaths(this, collector)

    return CompilerConfiguration().apply {
        put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)
        setupCommonArguments(this@toCompilerConfiguration)
        setupJvmSpecificArguments(this@toCompilerConfiguration)

        putIfNotNull(CLIConfigurationKeys.REPEAT_COMPILE_MODULES, repeatCompileModules?.toIntOrNull())
        put(CLIConfigurationKeys.PHASE_CONFIG, createPhaseConfig(jvmPhases, this@toCompilerConfiguration, collector))
        put(JVMConfigurationKeys.DISABLE_STANDARD_SCRIPT_DEFINITION, disableStandardScript)

        loadPlugins(this@toCompilerConfiguration, paths)

        configureJdkHome(this@toCompilerConfiguration)
        configureStandardLibs(this@toCompilerConfiguration, paths)
        configureExplicitContentRoots(this@toCompilerConfiguration)
        configureAdvancedJvmOptions(this@toCompilerConfiguration)
    }
}

internal fun CompilerConfiguration.configureModuleToCompile(arguments: K2JVMCompilerArguments): ModuleBuilder {
    val destination = arguments.destination?.let(::File)
    if (destination != null) {
        if (destination.path.endsWith(".jar")) {
            put(JVMConfigurationKeys.OUTPUT_JAR, destination)
        } else {
            put(JVMConfigurationKeys.OUTPUT_DIRECTORY, destination)
        }
    }

    val moduleName = arguments.moduleName ?: JvmProtoBufUtil.DEFAULT_MODULE_NAME
    put(CommonConfigurationKeys.MODULE_NAME, moduleName)

    val module = ModuleBuilder(moduleName, destination?.path ?: ".", "java-production")
    module.configureFromArgs(arguments)
    KotlinToJVMBytecodeCompiler.configureSourceRoots(this, listOf(module))

    return module
}

internal fun <A : CommonCompilerArguments> CompilerConfiguration.loadPlugins(
    arguments: A, paths: KotlinPaths? = null
): ExitCode {
    var pluginClasspaths: Iterable<String> = arguments.pluginClasspaths?.asIterable() ?: emptyList()
    val pluginOptions = arguments.pluginOptions?.toMutableList() ?: ArrayList()

    if (!arguments.disableDefaultScriptingPlugin) {
        val explicitOrLoadedScriptingPlugin =
            pluginClasspaths.any { File(it).name.startsWith(PathUtil.KOTLIN_SCRIPTING_COMPILER_PLUGIN_NAME) } ||
                    tryLoadScriptingPluginFromCurrentClassLoader(this)
        val messageCollector = getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)
        if (!explicitOrLoadedScriptingPlugin) {
            val kotlinPaths = paths ?: computeKotlinPaths(arguments, messageCollector) ?: PathUtil.kotlinPathsForCompiler
            val libPath = kotlinPaths.libPath.takeIf { it.exists() && it.isDirectory } ?: File(".")
            val (jars, missingJars) =
                PathUtil.KOTLIN_SCRIPTING_PLUGIN_CLASSPATH_JARS.map { File(libPath, it) }.partition { it.exists() }
            if (missingJars.isEmpty()) {
                pluginClasspaths = jars.map { it.canonicalPath } + pluginClasspaths
            } else {
                messageCollector.report(
                    CompilerMessageSeverity.LOGGING,
                    "Scripting plugin will not be loaded: not all required jars are present in the classpath (missing files: $missingJars)"
                )
            }
        }
        // TODO: restore
//        pluginOptions.addPlatformOptions(arguments)
    } else {
        pluginOptions.add("plugin:kotlin.scripting:disable=true")
    }
    return PluginCliParser.loadPluginsSafe(pluginClasspaths, pluginOptions, this)
}

internal fun tryLoadScriptingPluginFromCurrentClassLoader(configuration: CompilerConfiguration): Boolean = try {
    val pluginRegistrarClass = PluginCliParser::class.java.classLoader.loadClass(
        "org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar"
    )
    val pluginRegistrar = pluginRegistrarClass.newInstance() as? ComponentRegistrar
    if (pluginRegistrar != null) {
        configuration.add(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, pluginRegistrar)
        true
    } else false
} catch (_: Throwable) {
    // TODO: add finer error processing and logging
    false
}

