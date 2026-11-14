package io.github.saigyo.aslan.jsonata.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.jsonata.parser.JsonataElements
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens as T
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPath
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.ContextField
import io.github.saigyo.aslan.spec.JsonataFunction
import io.github.saigyo.aslan.spec.StatesField

/**
 * Completes the names in a JSONata expression: functions, `$states` and local variables after a `$`, the fields of
 * `$states` and of the context object after `$states.`, and the keywords elsewhere. Nothing is offered inside strings
 * and comments, nor for the data fields of `$states.input`.
 *
 * The tree at the caret holds the dummy identifier the platform inserts, and may hold an error; it is walked by hand.
 */
class JsonataCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        if (position.containingFile !is JsonataFile) return
        val type = position.node.elementType
        if (type != T.VARIABLE && type != T.NAME) {
            if (type in NOTHING) result.stopHere()
            return
        }
        val start = position.textRange.startOffset
        val prefix = position.text.take((parameters.offset - start).coerceIn(0, position.textLength))
        val matching = result.withPrefixMatcher(prefix)
        if (type == T.VARIABLE) {
            if (prefix.startsWith("$")) addVariables(position, matching)
        } else {
            val previous = previousLeaf(position)
            if (previous?.node?.elementType == T.DOT) addAfterDot(position, parameters, matching)
            else if (!prefix.startsWith("$")) KEYWORDS.forEach { matching.addElement(LookupElementBuilder.create(it).bold()) }
        }
        result.stopHere()
    }

    /** A function hidden by a local variable of the same name is not offered: `$name` there is the variable. */
    private fun addVariables(position: PsiElement, result: CompletionResultSet) {
        val locals = JsonataScopes.visibleNames(position)
        for (function in AslSpec.jsonataFunctions.filter { it.supported && it.name !in locals }) result.addElement(functionItem(function))
        result.addElement(LookupElementBuilder.create("\$states").withTypeText("reserved variable", true).bold())
        for (name in locals) {
            if (name.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) || name == "states") continue
            result.addElement(LookupElementBuilder.create("$$name").withTypeText("variable", true))
        }
    }

    private fun functionItem(function: JsonataFunction): LookupElement {
        val parameters = function.parameters.joinToString(", ") { if (it.optional) "[${it.name}]" else it.name }
        return LookupElementBuilder.create("$" + function.name)
            .withTailText("($parameters)", true)
            .withTypeText(function.category, true)
            .withInsertHandler(brackets(function.parameters.isEmpty()))
    }

    private fun brackets(empty: Boolean) = InsertHandler<LookupElement> { context, _ ->
        val document = context.document
        val tail = context.tailOffset
        if (document.charsSequence.getOrNull(tail) == '(') {
            context.editor.caretModel.moveToOffset(tail + 1)
            return@InsertHandler
        }
        document.insertString(tail, "()")
        context.editor.caretModel.moveToOffset(if (empty) tail + 2 else tail + 1)
    }

    private fun addAfterDot(position: PsiElement, parameters: CompletionParameters, result: CompletionResultSet) {
        val field = position.parent?.takeIf { it.node.elementType == JsonataElements.FIELD } ?: position
        val path = field.parent as? JsonataPath ?: return
        if (path.rhs !== field) return
        val names = ArrayList<String>()
        var left: PsiElement? = path.lhs
        while (left is JsonataPath) {
            names += left.rhs?.text ?: return
            left = left.lhs
        }
        if (left !is JsonataVariableRef || left.node.elementType != JsonataElements.VARIABLE_REF || left.variableName != "states") return
        if (JsonataScopes.declarations(left).isNotEmpty()) return
        names.reverse()
        when {
            names.isEmpty() -> statesFields(parameters).forEach { result.addElement(LookupElementBuilder.create(it.text).withTypeText("\$states field", true)) }
            names[0] == "context" -> {
                var fields: List<ContextField> = AslSpec.contextFields
                for (name in names.drop(1)) fields = fields.firstOrNull { it.name == name }?.children ?: return
                fields.forEach { result.addElement(LookupElementBuilder.create(it.name).withTypeText("context", true)) }
            }
        }
    }

    /** The fields every site of the expression provides, sorted by name; all of them when it has no site. */
    private fun statesFields(parameters: CompletionParameters): List<StatesField> {
        val file = parameters.originalFile as? JsonataFile
        val sites = file?.let { JsonataPsi.sites(it) }.orEmpty()
        val fields = if (sites.isEmpty()) StatesField.entries.toSet() else sites.map { it.provides }.reduce { a, b -> a intersect b }
        return fields.sortedBy { it.text }
    }

    private fun previousLeaf(element: PsiElement): PsiElement? {
        var leaf = PsiTreeUtil.prevLeaf(element)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment)) leaf = PsiTreeUtil.prevLeaf(leaf)
        return leaf
    }

    private companion object {
        val KEYWORDS = listOf("and", "or", "in", "true", "false", "null", "function")
        val NOTHING = setOf(T.STRING, T.BAD_STRING, T.COMMENT, T.BAD_COMMENT, T.REGEX, T.BAD_REGEX, T.QUOTED_NAME, T.BAD_QUOTED_NAME)
    }
}
