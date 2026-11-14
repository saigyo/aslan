package io.github.saigyo.aslan.jsonata.docs

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.docs.AslDocumentation
import io.github.saigyo.aslan.jsonata.parser.JsonataElements
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPath
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.ContextField
import io.github.saigyo.aslan.spec.StatesField

/** Quick documentation for the built-in functions, `$states` and its fields in a JSONata expression. */
class JsonataDocumentationProvider : AbstractDocumentationProvider() {
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
        contextElement?.takeIf { html(it) != null }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? = element?.let { html(it) }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = html(element)

    companion object {
        private const val STATES = "\$states"

        /** The documentation of the token [element] in an injected or standalone JSONata file, or null. */
        fun html(element: PsiElement): String? {
            if (element.containingFile !is JsonataFile) return null
            return when (element.node?.elementType) {
                JsonataTokens.VARIABLE -> (element.parent as? JsonataVariableRef)?.let { variableHtml(it) }
                JsonataTokens.NAME -> fieldHtml(element)
                else -> null
            }
        }

        private fun isLocal(ref: JsonataVariableRef): Boolean = JsonataScopes.declarations(ref).isNotEmpty()

        private fun variableHtml(ref: JsonataVariableRef): String? {
            if (ref.node.elementType != JsonataElements.VARIABLE_REF || isLocal(ref)) return null
            if (ref.variableName == "states") {
                return AslDocumentation.page(
                    AslDocumentation.esc(STATES), null,
                    "The reserved variable of Step Functions with the fields input, result, errorOutput and context. Which of them " +
                        "exist depends on the field the expression is in.",
                    null,
                )
            }
            val function = AslSpec.jsonataFunction(ref.variableName) ?: return null
            val facts = when {
                !function.supported -> "Step Functions does not support this function."
                function.stepFunctionsOnly -> "An addition of Step Functions; it is not part of JSONata."
                else -> null
            }
            return AslDocumentation.page(
                AslDocumentation.esc(callForm(function)), facts?.let { AslDocumentation.esc(it) }, function.description, function.url,
                "JSONata documentation",
            )
        }

        private fun fieldHtml(leaf: PsiElement): String? {
            val field = leaf.parent?.takeIf { it.node.elementType == JsonataElements.FIELD } ?: return null
            val path = field.parent as? JsonataPath ?: return null
            if (path.rhs !== field) return null
            val names = ArrayList<String>()
            var left = path.lhs
            while (left is JsonataPath) {
                names += left.rhs?.let { JsonataPsi.decodedText(it) } ?: return null
                left = left.lhs
            }
            if (left !is JsonataVariableRef || left.node.elementType != JsonataElements.VARIABLE_REF || left.variableName != "states" || isLocal(left)) return null
            names.reverse()
            val name = JsonataPsi.decodedText(field)
            if (names.isEmpty()) {
                val states = StatesField.entries.firstOrNull { it.text == name } ?: return null
                val doc = AslSpec.statesFieldDoc(states)
                return AslDocumentation.page(AslDocumentation.esc("$STATES.$name"), null, doc.description, doc.docUrl)
            }
            if (names[0] != "context") return null
            var fields: List<ContextField> = AslSpec.contextFields
            for (parent in names.drop(1)) fields = fields.firstOrNull { it.name == parent }?.children ?: return null
            val context = fields.firstOrNull { it.name == name } ?: return null
            return AslDocumentation.page(AslDocumentation.esc("$STATES.${names.joinToString(".")}.$name"), null, context.description, null)
        }
    }
}
