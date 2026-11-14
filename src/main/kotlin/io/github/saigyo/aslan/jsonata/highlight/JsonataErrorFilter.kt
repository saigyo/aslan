package io.github.saigyo.aslan.jsonata.highlight

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import io.github.saigyo.aslan.jsonata.psi.JsonataFile

/**
 * Hides the error elements of JSONata injected into a definition. The syntax error inspection shows them, on the
 * right characters and only where the expression is judged; the editor would otherwise show each one twice.
 */
class JsonataErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile as? JsonataFile ?: return true
        return !InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
    }
}
