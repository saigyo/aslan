package io.github.saigyo.aslan.check

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * One reported problem, on the element it is shown on. A [range] narrows it to a part of the element: relative to the
 * element's start, inside it and never empty; null marks the whole element.
 */
data class AslFinding(val problem: AslProblem, val element: PsiElement, val message: String, val range: TextRange? = null)
