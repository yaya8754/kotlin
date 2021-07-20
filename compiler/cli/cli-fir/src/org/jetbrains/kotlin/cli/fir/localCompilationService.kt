/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.fir

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer

class LocalCompilationServiceBuilder : CompilationServiceBuilder() {

    var rootDisposable: Disposable? = null

    override fun build(): CompilationService = LocalCompilationService(rootDisposable ?: Disposer.newDisposable())
}

internal class LocalCompilationService(val rootDisposable: Disposable) : CompilationService {
}

