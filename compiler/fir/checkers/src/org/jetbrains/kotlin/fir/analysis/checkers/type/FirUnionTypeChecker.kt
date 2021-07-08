/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.type

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.isSupertypeOf
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.inference.isBuiltinFunctionalType
import org.jetbrains.kotlin.fir.typeContext
import org.jetbrains.kotlin.fir.types.*

object FirUnionTypeChecker : FirTypeRefChecker() {
    override fun check(typeRef: FirTypeRef, context: CheckerContext, reporter: DiagnosticReporter) {
        if (typeRef !is FirUnionTypeRef)
            return

        val session = context.session
        val nestedTypes = typeRef.nestedTypes

        nestedTypes.forEach {
            if (it.coneType.fullyExpandedType(session).isBuiltinFunctionalType(session)) {
                reporter.reportOn(it.source, FirErrors.FUNCTION_TYPE_IN_UNION_TYPE, context)
            }
        }

        for (possibleSubtype in nestedTypes) {
            nestedTypes.find {
                it != possibleSubtype && it.coneType.isSupertypeOf(session.typeContext, possibleSubtype.coneType)
            }?.let {
                reporter.reportOn(
                    possibleSubtype.source,
                    FirErrors.SUBTYPE_IN_UNION_TYPE,
                    possibleSubtype.coneType,
                    it.coneType,
                    context
                )
            }
        }
    }
}
