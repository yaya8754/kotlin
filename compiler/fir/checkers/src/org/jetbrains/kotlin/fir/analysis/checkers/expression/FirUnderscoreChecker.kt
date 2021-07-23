/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.fir.FirLightSourceElement
import org.jetbrains.kotlin.fir.FirPsiSourceElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.checkUnderscoreDiagnostics
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.diagnostics.ConeUnderscoreUsageWithoutBackticks
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCatchClause

object FirUnderscoreChecker : FirBasicExpressionChecker() {
    private val singleUnderscore: Name = Name.identifier("_")

    override fun check(expression: FirStatement, context: CheckerContext, reporter: DiagnosticReporter) {
        when (expression) {
            is FirResolvable -> {
                checkUnderscoreDiagnostics(expression.calleeReference.source, context, reporter, true)
                checkResolvedToUnderscoreNamedCatchParameter(expression, context, reporter)
            }
            is FirResolvedQualifier -> {
                for (reservedUnderscoreDiagnostic in expression.nonFatalDiagnostics.filterIsInstance<ConeUnderscoreUsageWithoutBackticks>()) {
                    reporter.reportOn(reservedUnderscoreDiagnostic.source, FirErrors.UNDERSCORE_USAGE_WITHOUT_BACKTICKS, context)
                }
            }
        }
    }

    private fun checkResolvedToUnderscoreNamedCatchParameter(
        expression: FirResolvable,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val calleeReference = expression.calleeReference
        val symbol = calleeReference.toResolvedCallableSymbol()
        if (symbol !is FirValueParameterSymbol) return

        var report = false
        val source = symbol.source
        if (source is FirPsiSourceElement) {
            val parent = source.psi.parent?.parent
            if (parent is KtCatchClause) {
                report = true
            }
        } else if (source is FirLightSourceElement) {
            val treeStructure = source.treeStructure
            var parent = treeStructure.getParent(source.lighterASTNode)
            parent?.let { parent = treeStructure.getParent(it) }
            if (parent?.tokenType == KtNodeTypes.CATCH) {
                report = true
            }
        }

        if (report && symbol.name == singleUnderscore) {
            reporter.reportOn(
                calleeReference.source,
                FirErrors.RESOLVED_TO_UNDERSCORE_NAMED_CATCH_PARAMETER,
                context
            )
        }
    }
}