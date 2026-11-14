package io.github.saigyo.aslan.jsonata.highlight

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi

/**
 * Hides the error elements of JSONata injected into the strings of a definition that Step Functions evaluates. The syntax error inspection shows them, on the
 * right characters and only where the expression is judged; the editor would otherwise show each one twice.
 */
class JsonataErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile as? JsonataFile ?: return true
        return JsonataPsi.sites(file).isEmpty()
    }
}
