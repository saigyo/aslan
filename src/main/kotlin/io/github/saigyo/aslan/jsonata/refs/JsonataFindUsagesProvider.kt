package io.github.saigyo.aslan.jsonata.refs

import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef

/** Find Usages for local JSONata variables only; the usages come from [JsonataReferencesSearcher]. */
class JsonataFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner? = null

    override fun canFindUsagesFor(element: PsiElement): Boolean = isLocalVariable(element)

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = if (element is JsonataVariableRef) "variable" else ""

    override fun getDescriptiveName(element: PsiElement): String = (element as? JsonataVariableRef)?.text.orEmpty()

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}

/** Whether [element] is a declaration or use of a local JSONata variable. */
internal fun isLocalVariable(element: PsiElement?): Boolean =
    element is JsonataVariableRef && JsonataScopes.declarations(element).isNotEmpty()
