package io.github.saigyo.aslan.jsonata.refs

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef

/**
 * A use or a later declaration of a local variable, referring to the variable's first declaration. The range is the
 * whole `$name`, the same range Highlight Usages marks on the declaration; in-place rename edits only the name
 * (see [JsonataInplaceRenamer]).
 */
class JsonataVariableReference(element: JsonataVariableRef) :
    PsiReferenceBase<JsonataVariableRef>(element, TextRange(0, element.textLength), false) {
    override fun resolve(): PsiElement? = JsonataScopes.declarations(element).firstOrNull()?.takeIf { it !== element }

    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)
}
