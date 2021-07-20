/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.build.report.BuildReporter
import org.jetbrains.kotlin.build.report.ICReporter
import org.jetbrains.kotlin.build.report.metrics.BuildTime
import org.jetbrains.kotlin.build.report.metrics.DoNothingBuildMetricsReporter
import org.jetbrains.kotlin.build.report.metrics.measure
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compilerRunner.MessageCollectorToOutputItemsCollectorAdapter
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollectorImpl
import org.jetbrains.kotlin.compilerRunner.SimpleOutputItem
import org.jetbrains.kotlin.compilerRunner.toGeneratedFile
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.*
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.multiproject.EmptyModulesApiHistory
import org.jetbrains.kotlin.incremental.multiproject.ModulesApiHistory
import org.jetbrains.kotlin.incremental.util.BufferingMessageCollector
import org.jetbrains.kotlin.name.FqName
import java.io.File
import java.util.HashSet

class IncrementalFirJvmCompilerRunner(
    workingDir: File,
    reporter: BuildReporter,
    usePreciseJavaTracking: Boolean,
    buildHistoryFile: File,
    outputFiles: Collection<File>,
    modulesApiHistory: ModulesApiHistory,
    kotlinSourceFilesExtensions: List<String> = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
) : IncrementalJvmCompilerRunner(
    workingDir, reporter, usePreciseJavaTracking, buildHistoryFile, outputFiles, modulesApiHistory, kotlinSourceFilesExtensions
)
{
    override fun compileIncrementally(
        args: K2JVMCompilerArguments,
        caches: IncrementalJvmCachesManager,
        allKotlinSources: List<File>,
        compilationMode: CompilationMode,
        originalMessageCollector: MessageCollector
    ): ExitCode {
        preBuildHook(args, compilationMode)

        val buildTimeMode: BuildTime
        val dirtySources = when (compilationMode) {
            is CompilationMode.Incremental -> {
                buildTimeMode = BuildTime.INCREMENTAL_ITERATION
                compilationMode.dirtyFiles.toMutableList()
            }
            is CompilationMode.Rebuild -> {
                buildTimeMode = BuildTime.NON_INCREMENTAL_ITERATION
                reporter.addAttribute(compilationMode.reason)
                allKotlinSources.toMutableList()
            }
        }

        val currentBuildInfo = BuildInfo(startTS = System.currentTimeMillis())
        val buildDirtyLookupSymbols = HashSet<LookupSymbol>()
        val buildDirtyFqNames = HashSet<FqName>()
        val allDirtySources = HashSet<File>()

        var exitCode = ExitCode.OK

        while (dirtySources.any() || runWithNoDirtyKotlinSources(caches)) {
            val complementaryFiles = caches.platformCache.getComplementaryFilesRecursive(dirtySources)
            dirtySources.addAll(complementaryFiles)
            caches.platformCache.markDirty(dirtySources)
            caches.inputsCache.removeOutputForSourceFiles(dirtySources)

            val lookupTracker = LookupTrackerImpl(LookupTracker.DO_NOTHING)
            val expectActualTracker = ExpectActualTrackerImpl()
            val (sourcesToCompile, removedKotlinSources) = dirtySources.partition(File::exists)

            allDirtySources.addAll(dirtySources)
            val text = allDirtySources.joinToString(separator = System.getProperty("line.separator")) { it.canonicalPath }
            dirtySourcesSinceLastTimeFile.writeText(text)

            val services = makeServices(
                args, lookupTracker, expectActualTracker, caches,
                dirtySources.toSet(), compilationMode is CompilationMode.Incremental
            ).build()

            args.reportOutputFiles = true
            val outputItemsCollector = OutputItemsCollectorImpl()
            val bufferingMessageCollector = BufferingMessageCollector()
            val messageCollectorAdapter = MessageCollectorToOutputItemsCollectorAdapter(bufferingMessageCollector, outputItemsCollector)

            exitCode = reporter.measure(buildTimeMode) {
                runCompiler(sourcesToCompile.toSet(), args, caches, services, messageCollectorAdapter)
            }

            val generatedFiles = outputItemsCollector.outputs.map(SimpleOutputItem::toGeneratedFile)
            if (compilationMode is CompilationMode.Incremental) {
                // todo: feels dirty, can this be refactored?
                val dirtySourcesSet = dirtySources.toHashSet()
                val additionalDirtyFiles = additionalDirtyFiles(caches, generatedFiles, services).filter { it !in dirtySourcesSet }
                if (additionalDirtyFiles.isNotEmpty()) {
                    dirtySources.addAll(additionalDirtyFiles)
                    generatedFiles.forEach { it.outputFile.delete() }
                    continue
                }
            }

            reporter.reportCompileIteration(compilationMode is CompilationMode.Incremental, sourcesToCompile, exitCode)
            bufferingMessageCollector.flush(originalMessageCollector)

            if (exitCode != ExitCode.OK) break

            dirtySourcesSinceLastTimeFile.delete()

            val changesCollector = ChangesCollector()
            reporter.measure(BuildTime.IC_UPDATE_CACHES) {
                caches.platformCache.updateComplementaryFiles(dirtySources, expectActualTracker)
                caches.inputsCache.registerOutputForSourceFiles(generatedFiles)
                caches.lookupCache.update(lookupTracker, sourcesToCompile, removedKotlinSources)
                updateCaches(services, caches, generatedFiles, changesCollector)
            }
            if (compilationMode is CompilationMode.Rebuild) break

            val (dirtyLookupSymbols, dirtyClassFqNames, forceRecompile) = changesCollector.getDirtyData(listOf(caches.platformCache), reporter)
            val compiledInThisIterationSet = sourcesToCompile.toHashSet()

            val forceToRecompileFiles = mapClassesFqNamesToFiles(listOf(caches.platformCache), forceRecompile, reporter)
            with(dirtySources) {
                clear()
                addAll(mapLookupSymbolsToFiles(caches.lookupCache, dirtyLookupSymbols, reporter, excludes = compiledInThisIterationSet))
                addAll(
                    mapClassesFqNamesToFiles(
                        listOf(caches.platformCache),
                        dirtyClassFqNames,
                        reporter,
                        excludes = compiledInThisIterationSet
                    )
                )
                if (!compiledInThisIterationSet.containsAll(forceToRecompileFiles)) {
                    addAll(forceToRecompileFiles)
                }
            }

            buildDirtyLookupSymbols.addAll(dirtyLookupSymbols)
            buildDirtyFqNames.addAll(dirtyClassFqNames)
        }

        if (exitCode == ExitCode.OK) {
            BuildInfo.write(currentBuildInfo, lastBuildInfoFile)
        }
        if (exitCode == ExitCode.OK && compilationMode is CompilationMode.Incremental) {
            buildDirtyLookupSymbols.addAll(additionalDirtyLookupSymbols())
        }

        val dirtyData = DirtyData(buildDirtyLookupSymbols, buildDirtyFqNames)
        processChangesAfterBuild(compilationMode, currentBuildInfo, dirtyData)

        return exitCode
    }
}

fun makeIncrementallyWithFir(
    cachesDir: File,
    sourceRoots: Iterable<File>,
    args: K2JVMCompilerArguments,
    messageCollector: MessageCollector = MessageCollector.NONE,
    reporter: ICReporter = EmptyICReporter
) {
    val kotlinExtensions = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
    val allExtensions = kotlinExtensions + "java"
    val rootsWalk = sourceRoots.asSequence().flatMap { it.walk() }
    val files = rootsWalk.filter(File::isFile)
    val sourceFiles = files.filter { it.extension.lowercase() in allExtensions }.toList()
    val buildHistoryFile = File(cachesDir, "build-history.bin")
    args.javaSourceRoots = sourceRoots.map { it.absolutePath }.toTypedArray()
    val buildReporter = BuildReporter(icReporter = reporter, buildMetricsReporter = DoNothingBuildMetricsReporter)

    withIC {
        val compiler = IncrementalFirJvmCompilerRunner(
            cachesDir,
            buildReporter,
            // Use precise setting in case of non-Gradle build
            usePreciseJavaTracking = !args.useFir, // TODO: add fir-based java classes tracker when available and set this to true
            outputFiles = emptyList(),
            buildHistoryFile = buildHistoryFile,
            modulesApiHistory = EmptyModulesApiHistory,
            kotlinSourceFilesExtensions = kotlinExtensions
        )
        compiler.compile(sourceFiles, args, messageCollector, providedChangedFiles = null)
    }
}

