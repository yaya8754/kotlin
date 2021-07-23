/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers

import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.fir.FirFakeSourceElementKind
import org.jetbrains.kotlin.fir.FirLightSourceElement
import org.jetbrains.kotlin.fir.FirPsiSourceElement
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.stubs.elements.KtNameReferenceExpressionElementType
import org.jetbrains.kotlin.psi.stubs.elements.KtTypeProjectionElementType

fun checkUnderscoreDiagnostics(
    source: FirSourceElement?,
    context: CheckerContext,
    reporter: DiagnosticReporter,
    isExpression: Boolean
) {
    if (source != null && source.kind !is FirFakeSourceElementKind) {
        var rawName: String? = null
        if (source is FirPsiSourceElement) {
            val psi = source.psi
            rawName = if (psi is KtNameReferenceExpression) {
                psi.getReferencedNameElement().node.text
            } else if (psi is KtTypeProjection) {
                psi.typeReference?.typeElement?.text
            } else if (psi is LeafPsiElement && psi.elementType == KtTokens.IDENTIFIER) {
                psi.text
            } else {
                null
            }
        } else if (source is FirLightSourceElement) {
            val tokenType = source.elementType
            rawName = if (tokenType is KtNameReferenceExpressionElementType || tokenType == KtTokens.IDENTIFIER) {
                source.lighterASTNode.toString()
            } else if (tokenType is KtTypeProjectionElementType) {
                source.lighterASTNode.getChildren(source.treeStructure).last().toString()
            } else {
                null
            }
        }

        if (rawName?.isUnderscore == true) {
            reporter.reportOn(
                source,
                if (isExpression) FirErrors.UNDERSCORE_USAGE_WITHOUT_BACKTICKS else FirErrors.UNDERSCORE_IS_RESERVED,
                context
            )
        }
    }
}

val CharSequence.isUnderscore: Boolean
    get() = all { it == '_' }