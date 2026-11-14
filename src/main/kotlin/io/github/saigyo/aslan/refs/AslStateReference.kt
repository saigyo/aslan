package io.github.saigyo.aslan.refs

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult

/**
 * A state name written as the value of `StartAt`, `Next` or `Default`. It resolves to the entry of every state with
 * that name in the state machine the value belongs to; [StateTargets] decides what that is.
 */
class AslStateReference(element: PsiElement, range: TextRange) : PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        StateTargets.declarations(element).map { PsiElementResolveResult(it) }.toTypedArray()

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement = NameWriter.writeValue(element, newElementName)
}
