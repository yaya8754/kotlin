/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import java.io.PrintStream

abstract class ExecutionDiagnostic {
    open fun render(linePrefix: String = ""): String = linePrefix + toString()
    open fun render(strm: PrintStream, linePrefix: String = "") {
        strm.println(render(linePrefix))
    }
}

@Suppress("unused")
open class ExecutionMessage(val message: String, val severity: Severity = Severity.ERROR) : ExecutionDiagnostic() {
    enum class Severity { DEBUG, INFO, WARNING, ERROR, FATAL }

    override fun render(linePrefix: String): String = "$linePrefix${severity.name}: $message"
}

open class ExecutionExceptionWrapper(val e: Throwable) : ExecutionDiagnostic() {
    override fun render(strm: PrintStream, linePrefix: String) {
        e.printStackTrace(strm)
    }
}

sealed class ExecutionResult<R>(
    val diagnostics: List<ExecutionDiagnostic>
) {
    class Failure<R, T>(val value: T, diagnostics: List<ExecutionDiagnostic>) :
        ExecutionResult<R>(diagnostics)

    open class Success<T>(val value: T, diagnostics: List<ExecutionDiagnostic>) :
        ExecutionResult<T>(diagnostics)

    class Partial<T>(value: T, diagnostics: List<ExecutionDiagnostic>) :
        Success<T>(value, diagnostics)
}
