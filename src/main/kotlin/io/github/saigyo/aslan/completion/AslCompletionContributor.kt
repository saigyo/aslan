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
import com.intellij.json.psi.JsonReferenceExpression
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
        when (val context = CompletionContexts.at(parameters) ?: return) {
            is CompletionContext.Key -> addKeys(context, parameters, result)
            is CompletionContext.Value -> {
                // Only fixed values are offered here; in any other value the IDE's own completion keeps working.
                val kind = context.field.spec.kind
                if (kind !is ValueKind.OneOf && kind != ValueKind.Bool) return
                addValues(kind, parameters, result)
            }
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

    private fun addValues(kind: ValueKind, parameters: CompletionParameters, result: CompletionResultSet) {
        val parent = parameters.position.parent
        val quoted = parent is JsonStringLiteral || parent is YAMLQuotedText
        // An unquoted JSON value must become a string literal, so the fixed value is inserted with quotes.
        val bareJson = parent is JsonReferenceExpression
        when (kind) {
            is ValueKind.OneOf -> kind.values.forEach {
                val item = LookupElementBuilder.create(it.name).withTypeText(it.description)
                result.addElement(if (bareJson) item.withInsertHandler(JsonQuoteInsertHandler) else item)
            }
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

/** Puts quotes around a fixed value inserted where a JSON value has none yet. */
private object JsonQuoteInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        context.document.insertString(context.tailOffset, "\"")
        context.document.insertString(context.startOffset, "\"")
        context.editor.caretModel.moveToOffset(context.tailOffset)
    }
}
