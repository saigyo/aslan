package io.github.saigyo.aslan.completion

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonStringLiteral
import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.ValueKind
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLQuotedText

/**
 * Completes ASL field names and fixed values. Where [CompletionContexts] finds a context, its items are the only
 * ones offered; elsewhere other contributors run as usual.
 */
class AslCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val context = CompletionContexts.at(parameters) ?: return
        when (context) {
            is CompletionContext.Key -> addKeys(context, parameters, result)
            is CompletionContext.Value -> addValues(context, parameters, result)
        }
        result.stopHere()
    }

    private fun addKeys(context: CompletionContext.Key, parameters: CompletionParameters, result: CompletionResultSet) {
        // Field names are matched from their start (camel humps allowed), not in the middle as the platform would.
        val owner = context.owner
        val yaml = parameters.originalFile is YAMLFile
        owner.structure.fieldsFor(owner.queryLanguage)
            .filter { it.name !in context.present && result.prefixMatcher.isStartMatch(it.name) }
            .sortedBy { it.name }
            .forEach { spec ->
                val item = LookupElementBuilder.create(spec.name)
                    .withTypeText(kindText(spec.kind))
                    .withBoldness(spec.required)
                    .withStrikeoutness(spec.deprecated)
                    .let { if (yaml) it.withInsertHandler(YamlKeyInsertHandler(spec.kind)) else it }
                result.addElement(PrioritizedLookupElement.withPriority(item, priority(spec)))
            }
    }

    /** Required fields first, deprecated ones last. */
    private fun priority(spec: FieldSpec): Double = when {
        spec.deprecated -> 0.0
        spec.required -> 2.0
        else -> 1.0
    }

    private fun addValues(context: CompletionContext.Value, parameters: CompletionParameters, result: CompletionResultSet) {
        val quoted = parameters.position.parent.let { it is JsonStringLiteral || it is YAMLQuotedText }
        when (val kind = context.field.spec.kind) {
            is ValueKind.OneOf -> kind.values.forEach { result.addElement(LookupElementBuilder.create(it.name).withTypeText(it.description)) }
            ValueKind.Bool -> if (!quoted) listOf("true", "false").forEach { result.addElement(LookupElementBuilder.create(it)) }
            else -> {}
        }
    }

    private fun kindText(kind: ValueKind): String = when (kind) {
        ValueKind.Text -> "string"
        ValueKind.Integer -> "integer"
        ValueKind.Number -> "number"
        ValueKind.Bool -> "boolean"
        is ValueKind.OneOf -> kind.values.joinToString(" | ") { it.name }.let { if (it.length > 30) "enumeration" else it }
        ValueKind.StateName -> "state name"
        ValueKind.StatesMap -> "states"
        ValueKind.ErrorNames -> "error names"
        ValueKind.ResourceArn -> "resource ARN"
        ValueKind.FreeForm -> "any"
        is ValueKind.Struct -> "object"
        is ValueKind.ListOf -> "list"
    }
}

/**
 * Completes a YAML key with what its value kind needs: `: ` for a scalar, a new line one level deeper for an object,
 * and that line started with `- ` for a list. Adds nothing when a colon already follows the key.
 */
private class YamlKeyInsertHandler(private val kind: ValueKind) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val text = document.charsSequence
        val tail = context.tailOffset
        var next = tail
        while (next < text.length && text[next] == ' ') next++
        if (next < text.length && text[next] == ':') return
        val column = context.startOffset - document.getLineStartOffset(document.getLineNumber(context.startOffset))
        val indent = " ".repeat(column + CodeStyle.getIndentOptions(context.file).INDENT_SIZE)
        val insert = when (kind) {
            is ValueKind.Struct, ValueKind.StatesMap, ValueKind.FreeForm -> ":\n$indent"
            is ValueKind.ListOf, ValueKind.ErrorNames -> ":\n$indent- "
            else -> ": "
        }
        document.insertString(tail, insert)
        context.editor.caretModel.moveToOffset(tail + insert.length)
    }
}
