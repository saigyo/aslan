package io.github.saigyo.aslan.jsonata.refs

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef

/** A declaration of a local variable (parameter, `:=` target, `@` or `#` binding) writes it; every other occurrence reads it. */
class JsonataReadWriteAccessDetector : ReadWriteAccessDetector() {
    override fun isReadWriteAccessible(element: PsiElement): Boolean = isLocalVariable(element)

    override fun isDeclarationWriteAccess(element: PsiElement): Boolean =
        element is JsonataVariableRef && JsonataScopes.isDeclaration(element)

    override fun getReferenceAccess(referencedElement: PsiElement, reference: PsiReference): Access =
        getExpressionAccess(reference.element)

    override fun getExpressionAccess(expression: PsiElement): Access =
        if (expression is JsonataVariableRef && JsonataScopes.isDeclaration(expression)) Access.Write else Access.Read
}
