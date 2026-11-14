package io.github.saigyo.aslan.completion

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonReferenceExpression
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.psi.PsiDocumentManager
import io.github.saigyo.aslan.refs.NameWriter
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.NamedDoc
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.ValueKind
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.impl.YAMLPlainTextImpl

/**
 * Completes ASL field names and fixed values. Where [CompletionContexts] finds a context, its items are the only
 * ones offered; elsewhere other contributors run as usual.
 */
class AslCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        when (val context = CompletionContexts.at(parameters) ?: return) {
            is CompletionContext.Key -> addKeys(context, parameters, result)
            is CompletionContext.StateName -> if (!addStateNames(context, parameters, result)) return
            is CompletionContext.Value -> {
                // Only fixed values are offered here; where none are, the IDE's own completion keeps working.
                if (!addValues(context, parameters, result)) return
            }
        }
        result.stopHere()
    }

    private fun addKeys(context: CompletionContext.Key, parameters: CompletionParameters, result: CompletionResultSet) {
        // Field names are matched from their start (camel humps allowed), not in the middle as the platform would.
        val owner = context.owner
        val yaml = parameters.originalFile is YAMLFile
        // A JSON key written without quotes gets them on insertion.
        val bareJson = parameters.position.parent is JsonReferenceExpression
        owner.structure.fieldsFor(owner.queryLanguage)
            .filter { it.name !in context.present && result.prefixMatcher.isStartMatch(it.name) }
            .sortedBy { it.name }
            .forEach { spec ->
                val item = LookupElementBuilder.create(spec.name)
                    .withTypeText(kindText(spec.kind))
                    .withBoldness(spec.required)
                    .withStrikeoutness(spec.deprecated)
                    .let {
                        when {
                            yaml -> it.withInsertHandler(YamlKeyInsertHandler(spec.kind))
                            bareJson -> it.withInsertHandler(JsonQuoteInsertHandler)
                            else -> it
                        }
                    }
                result.addElement(PrioritizedLookupElement.withPriority(item, priority(spec)))
            }
    }

    /** Required fields first, deprecated ones last. */
    private fun priority(spec: FieldSpec): Double = when {
        spec.deprecated -> 0.0
        spec.required -> 2.0
        else -> 1.0
    }

    /** Adds the values that fit the caret; false when there are none. */
    private fun addValues(context: CompletionContext.Value, parameters: CompletionParameters, result: CompletionResultSet): Boolean {
        val kind = context.field.spec.kind
        val parent = parameters.position.parent
        if (context.field.spec.name == "Type" && kind is ValueKind.OneOf) return addStateTypes(context, parameters, result)
        when (kind) {
            // A state name the model does not read as a transition (such as `Next` in a nested rule) gets no names.
            ValueKind.StateName -> return false
            ValueKind.ErrorNames -> return addNamed(AslSpec.errorNames, parameters, result, anywhere = false)
            // The service integration ARNs are those of Task states; ItemReader and ResultWriter take others.
            ValueKind.ResourceArn ->
                return context.field.owner.structure.id == StructureId.TASK && addNamed(AslSpec.taskResourceArns, parameters, result, anywhere = true)
            else -> Unit
        }
        val quoted = parent is JsonStringLiteral || parent is YAMLQuotedText
        val names = when (kind) {
            is ValueKind.OneOf -> kind.values
            ValueKind.Bool -> if (quoted) emptyList() else listOf(NamedDoc("true", ""), NamedDoc("false", ""))
            else -> emptyList()
        }
        // A JSON value written without quotes must become a string literal, unless it is a boolean.
        val quote = parent is JsonReferenceExpression && kind is ValueKind.OneOf
        for (value in names) {
            val item = LookupElementBuilder.create(value.name).withTypeText(value.description.ifEmpty { null })
            result.addElement(if (quote) item.withInsertHandler(JsonQuoteInsertHandler) else item)
        }
        return names.isNotEmpty()
    }

    /** The state types in specification order; the type of a new, otherwise empty YAML state also brings its fields. */
    private fun addStateTypes(context: CompletionContext.Value, parameters: CompletionParameters, result: CompletionResultSet): Boolean {
        val matcher = result.withPrefixMatcher(typedPrefix(parameters).let { StartOrAnywhere(it, false) })
        val yaml = parameters.originalFile is YAMLFile
        val types = AslSpec.stateTypes.filter { matcher.prefixMatcher.prefixMatches(it.name) }
        types.forEach { type ->
            val item = LookupElementBuilder.create(type.name).withTypeText(type.description.ifEmpty { null })
            val handler: InsertHandler<LookupElement>? = when {
                yaml -> StateTypeInsertHandler(context.field.owner.queryLanguage)
                isBareJson(parameters) -> JsonQuoteInsertHandler
                else -> null
            }
            val priority = (AslSpec.stateTypes.size - AslSpec.stateTypes.indexOf(type)).toDouble()
            matcher.addElement(PrioritizedLookupElement.withPriority(if (handler != null) item.withInsertHandler(handler) else item, priority))
        }
        return types.isNotEmpty()
    }

    /**
     * The state names of the machine the value belongs to; the state being edited comes last. A name that would not
     * read back as itself written plain in YAML is inserted in double quotes.
     */
    private fun addStateNames(context: CompletionContext.StateName, parameters: CompletionParameters, result: CompletionResultSet): Boolean {
        val matcher = result.withPrefixMatcher(StartOrAnywhere(typedPrefix(parameters), false))
        val own = context.from?.name
        val quote = isBareJson(parameters)
        val plainYaml = parameters.position.parent is YAMLPlainTextImpl
        var added = false
        for (state in context.machine.states) {
            val name = state.name
            if (name.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) || !matcher.prefixMatcher.prefixMatches(name)) continue
            val item = LookupElementBuilder.create(name).let {
                when {
                    quote -> it.withInsertHandler(JsonQuoteInsertHandler)
                    plainYaml && !NameWriter.isPlainSafe(parameters.originalFile.project, name) -> it.withInsertHandler(YamlQuoteInsertHandler)
                    else -> it
                }
            }
            matcher.addElement(PrioritizedLookupElement.withPriority(item, if (name == own) 0.0 else 1.0))
            added = true
        }
        return added
    }

    private fun addNamed(values: List<NamedDoc>, parameters: CompletionParameters, result: CompletionResultSet, anywhere: Boolean): Boolean {
        val matcher = result.withPrefixMatcher(StartOrAnywhere(typedPrefix(parameters), anywhere))
        val quote = isBareJson(parameters)
        var added = false
        for (value in values) {
            if (!matcher.prefixMatcher.prefixMatches(value.name)) continue
            val item = LookupElementBuilder.create(value.name).withTypeText(value.description.ifEmpty { null })
            matcher.addElement(if (quote) item.withInsertHandler(JsonQuoteInsertHandler) else item)
            added = true
        }
        return added
    }

    private fun isBareJson(parameters: CompletionParameters) = parameters.position.parent is JsonReferenceExpression

    /** The text of the value before the caret without an opening quote; unlike the platform's own prefix it may contain `:`. */
    private fun typedPrefix(parameters: CompletionParameters): String {
        val position = parameters.position
        val length = (parameters.offset - position.textRange.startOffset).coerceIn(0, position.textLength)
        return position.text.substring(0, length).trimStart('"', '\'')
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

/** Writes a state name inserted as a plain YAML value in double quotes, so that it reads back as that name. */
private object YamlQuoteInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val text = NameWriter.yamlValue(context.project, item.lookupString)
        context.document.replaceString(context.startOffset, context.tailOffset, text)
        context.editor.caretModel.moveToOffset(context.startOffset + text.length)
    }
}

/** Puts quotes around a key or fixed value inserted where the JSON has none yet. */
private object JsonQuoteInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        context.document.insertString(context.tailOffset, "\"")
        context.document.insertString(context.startOffset, "\"")
        context.editor.caretModel.moveToOffset(context.tailOffset)
    }
}

/** Matches names that start with the prefix, or contain it anywhere, ignoring case. */
private class StartOrAnywhere(prefix: String, private val anywhere: Boolean) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean = name.contains(prefix, ignoreCase = true) && (anywhere || name.startsWith(prefix, ignoreCase = true))
    override fun cloneWithPrefix(prefix: String): PrefixMatcher = StartOrAnywhere(prefix, anywhere)
}

/**
 * Inserts the fields a new state of the chosen type needs, below the `Type` line at its indentation, when `Type` is
 * the only key of its state and nothing else follows the value on its line.
 */
private class StateTypeInsertHandler(private val queryLanguage: QueryLanguage) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        PsiDocumentManager.getInstance(context.project).commitDocument(document)
        val keyValue = PsiTreeUtil.getParentOfType(context.file.findElementAt(context.startOffset), YAMLKeyValue::class.java) ?: return
        val mapping = keyValue.parent as? YAMLMapping ?: return
        if (keyValue.keyText != "Type" || mapping.keyValues.size != 1) return
        val text = document.charsSequence
        val tail = context.tailOffset
        val lineEnd = text.indexOf('\n', tail).let { if (it < 0) text.length else it }
        if (text.subSequence(tail, lineEnd).isNotBlank()) return
        val keyOffset = keyValue.textRange.startOffset
        val lineStart = document.getLineStartOffset(document.getLineNumber(keyOffset))
        if (text.subSequence(lineStart, keyOffset).isNotBlank()) return
        val indent = " ".repeat(keyOffset - lineStart)
        val body = StateTemplates.yamlBody(item.lookupString, queryLanguage)
        if (body.isEmpty()) return
        val insert = body.joinToString("") { "\n$indent$it" }
        val caret = insert.indexOf(StateTemplates.CARET)
        document.insertString(tail, insert.replace(StateTemplates.CARET, ""))
        context.editor.caretModel.moveToOffset(if (caret >= 0) tail + caret else tail)
    }
}
