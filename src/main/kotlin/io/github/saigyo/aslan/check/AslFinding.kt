package io.github.saigyo.aslan.check

import com.intellij.psi.PsiElement

/** One reported problem, on the element it is shown on. */
data class AslFinding(val problem: AslProblem, val element: PsiElement, val message: String)
