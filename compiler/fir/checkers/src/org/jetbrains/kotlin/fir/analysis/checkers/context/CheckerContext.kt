/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.context

import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnostic
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.resolve.ImplicitReceiverStack
import org.jetbrains.kotlin.fir.resolve.SessionHolder
import org.jetbrains.kotlin.fir.resolve.calls.ImplicitReceiverValue
import org.jetbrains.kotlin.fir.resolve.transformers.ReturnTypeCalculator
import org.jetbrains.kotlin.name.Name

abstract class CheckerContext {
    // Services
    abstract val sessionHolder: SessionHolder
    abstract val returnTypeCalculator: ReturnTypeCalculator

    // Context
    abstract val implicitReceiverStack: ImplicitReceiverStack
    abstract val containingDeclarations: List<FirDeclaration>
    abstract val qualifiedAccessOrAnnotationCalls: List<FirStatement>
    abstract val getClassCalls: List<FirGetClassCall>
    abstract val annotationContainers: List<FirAnnotationContainer>

    // Suppress
    abstract val suppressedDiagnostics: Set<String>
    abstract val allInfosSuppressed: Boolean
    abstract val allWarningsSuppressed: Boolean
    abstract val allErrorsSuppressed: Boolean

    val session: FirSession
        get() = sessionHolder.session

    abstract fun addSuppressedDiagnostics(
        diagnosticNames: Collection<String>,
        allInfosSuppressed: Boolean,
        allWarningsSuppressed: Boolean,
        allErrorsSuppressed: Boolean
    ): CheckerContext

    abstract fun addImplicitReceiver(name: Name?, value: ImplicitReceiverValue<*>): CheckerContext

    abstract fun addDeclaration(declaration: FirDeclaration): CheckerContext

    abstract fun dropDeclaration()

    abstract fun addQualifiedAccessOrAnnotationCall(qualifiedAccessOrAnnotationCall: FirStatement): CheckerContext

    abstract fun dropQualifiedAccessOrAnnotationCall()

    abstract fun addGetClassCall(getClassCall: FirGetClassCall): CheckerContext

    abstract fun dropGetClassCall()

    abstract fun addAnnotationContainer(annotationContainer: FirAnnotationContainer): CheckerContext

    abstract fun dropAnnotationContainer()

    fun isDiagnosticSuppressed(diagnostic: FirDiagnostic): Boolean {
        val factory = diagnostic.factory
        val name = factory.name
        val suppressedByAll = when (factory.severity) {
            Severity.INFO -> allInfosSuppressed
            Severity.WARNING -> allWarningsSuppressed
            Severity.ERROR -> allErrorsSuppressed
        }

        return suppressedByAll || name in suppressedDiagnostics
    }
}

/**
 * Returns the closest to the end of context.containingDeclarations instance of type [T] or null if no such item could be found.
 * By specifying [check] you can filter which exact declaration should be found
 * E.g., property accessor is either getter or setter, but a type-based search could return, say,
 *   the closest setter, while we want to keep searching for a getter.
 */

inline fun <reified T : FirElement> CheckerContext.findClosest(check: (T) -> Boolean = { true }): T? {
    for (it in containingDeclarations.asReversed()) {
        return (it as? T)?.takeIf(check) ?: continue
    }

    return null
}
