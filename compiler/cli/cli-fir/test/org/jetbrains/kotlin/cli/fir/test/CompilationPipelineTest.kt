/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir.test

import com.intellij.openapi.util.io.FileUtil
import junit.framework.TestCase
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.fir.ExecutionResult
import org.jetbrains.kotlin.cli.fir.classicCliJvmCompile
import org.jetbrains.kotlin.cli.fir.firCompile
import org.jetbrains.kotlin.cli.fir.oldFeOldBeCompile
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.integration.KotlinIntegrationTestBase
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_DATA_DIR = "compiler/cli/cli-fir/testData"

class CompilationPipelineTest : KotlinIntegrationTestBase() {

    @Test
    fun testHelloWorldClassicJvm() {
        val source = File(TEST_DATA_DIR, "helloWorld/helloWorld.kt").normalize().absolutePath

        val baseDest = FileUtil.createTempDirectory("base", "out").normalize()
        val (baseResult, _) = compileWithOutput {
            K2JVMCompiler().exec(it, "-Xuse-old-backend", "-d", baseDest.path, source)
        }
        TestCase.assertEquals(ExitCode.OK, baseResult)

        val classicCliDest = FileUtil.createTempDirectory("classic", "out")
        val (classicCliResult, _) = compileWithOutput {
            classicCliJvmCompile(listOf("-Xuse-old-backend", "-d", classicCliDest.path, source), it)
        }
        assertTrue(classicCliResult is ExecutionResult.Success)

        compareDirectories(baseDest, classicCliDest)

        val classicFeBeDest = FileUtil.createTempDirectory("oldFeBe", "out")
        val (classicFeBeResult, classicFeBeOut) = compileWithOutput { outStr ->
            oldFeOldBeCompile(listOf("-d", classicFeBeDest.path, source), outStr).also {
                outStr.flush()
            }
        }
        assertTrue(classicFeBeOut, classicFeBeResult is ExecutionResult.Success)

        compareDirectories(baseDest, classicFeBeDest)
    }

    @Test
    fun testHelloWorldFirJvm() {
        val source = File(TEST_DATA_DIR, "helloWorld/helloWorld.kt").normalize().absolutePath

        val baseDest = FileUtil.createTempDirectory("base", "out").normalize()
        val (baseResult, _) = compileWithOutput {
            K2JVMCompiler().exec(it, "-Xuse-fir", "-d", baseDest.path, source)
        }
        TestCase.assertEquals(ExitCode.OK, baseResult)

        val classicCliDest = FileUtil.createTempDirectory("classic", "out")
        val (classicCliResult, _) = compileWithOutput {
            classicCliJvmCompile(listOf("-Xuse-fir", "-d", classicCliDest.path, source), it)
        }
        assertTrue(classicCliResult is ExecutionResult.Success)

        compareDirectories(baseDest, classicCliDest)

        val firDest = FileUtil.createTempDirectory("fir", "out")
        val (firResult, firOut) = compileWithOutput { outStr ->
            firCompile(listOf("-d", firDest.path, source), outStr).also {
                outStr.flush()
            }
        }
        assertTrue(firOut, firResult is ExecutionResult.Success)

        compareDirectories(baseDest, firDest)
    }
}

internal inline fun <T> compileWithOutput(body: (PrintStream) -> T): Pair<T, String> {
    ByteArrayOutputStream().use { bos ->
        val (_, cerr, res) = captureOutErrRet { body(PrintStream(bos)) }
        assertFalse(cerr.contains("error", ignoreCase = true), cerr)
        return res to bos.toString().trim()
    }
}

fun compareDirectories(src: File, dst: File) {
    for (srcFile in src.walkTopDown()) {
        val dstFile = dst.resolve(srcFile.relativeTo(src))
        compareFiles(srcFile, dstFile)
    }
}

fun compareFiles(src: File, dst: File, message: String? = null) {
    assertTrue(dst.exists())
    assertEquals(src.isFile, dst.isFile, message)
    if (dst.isFile) {
        assertTrue(src.readBytes() contentEquals dst.readBytes(), message)
    }
}

internal inline fun <T> captureOutErrRet(body: () -> T): Triple<String, String, T> {
    val outStream = ByteArrayOutputStream()
    val errStream = ByteArrayOutputStream()
    val prevOut = System.out
    val prevErr = System.err
    System.setOut(PrintStream(outStream))
    System.setErr(PrintStream(errStream))
    val ret = try {
        body()
    } finally {
        System.out.flush()
        System.err.flush()
        System.setOut(prevOut)
        System.setErr(prevErr)
    }
    return Triple(outStream.toString().trim(), errStream.toString().trim(), ret)
}

