package io.github.saigyo.aslan.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.FieldInfo
import io.github.saigyo.aslan.model.ObjectInfo
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslEntry
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.impl.YAMLArrayImpl

/** Where in an ASL definition the caret of a completion request is. */
sealed interface CompletionContext {
    /** The caret is where a field name goes, in an object of [owner]'s structure that already has the keys [present]. */
    data class Key(val owner: ObjectInfo, val present: Set<String>) : CompletionContext

    /** The caret is in the value of a known field. */
    data class Value(val field: FieldInfo, val state: AslState?) : CompletionContext
}

/**
 * Finds the [CompletionContext] from the PSI of the completion copy, where the dummy identifier
 * (`IntellijIdeaRulezzz `, with a trailing space) stands at the caret. The shapes the parsers produce there:
 *
 * YAML
 * - New line among sibling keys, partly typed key or not (`Type: Task\n    Re<caret>`), also before further keys and
 *   after the last line of the file: a plain scalar that is a direct child of the `YAMLMapping`, outside every
 *   `YAMLKeyValue`. The mapping is in the model; the scalar is not an entry.
 * - Key followed by a colon (`Comm<caret>: x` or `Comm<caret>:`): a `YAMLKeyValue` whose `scalar key` token is the
 *   caret element.
 * - First line under a key whose value is still empty (`A:\n    <caret>`, `Arguments:\n      <caret>`): a plain
 *   scalar that is the value of that `YAMLKeyValue`, on a later line than the key. The model then sees a scalar, not
 *   an object, so the structure comes from the key: a state name gives the generic state structure, a field of kind
 *   `Struct` the structure it names. A scalar on a later line that is not indented deeper than the key would be a new
 *   key beside it; the parser in fact makes that the sibling-keys shape above (`Resource:\n    <caret>`), and the
 *   column comparison keeps it right if that changes.
 * - Value position on the key's line (`QueryLanguage: <caret>`, `Type: Ta<caret>`): the same `YAMLKeyValue` value
 *   scalar, on the key's line; a `YAMLQuotedText` when inside quotes.
 * - First line of a block list item (`Retry:\n      - <caret>`): a plain scalar in a `YAMLSequenceItem` of a
 *   `YAMLSequence` that is the value of the key; a flow list item (`[States.ALL, <caret>]`) is the same inside a
 *   `YAMLArray`. Later lines of a block item that is a mapping are the sibling-keys shape above.
 * - Empty file: a plain scalar that is the top-level value of the `YAMLDocument`.
 * - In a comment: the caret element is the `PsiComment` itself.
 *
 * JSON
 * - Key in quotes (`{"Type": "Pass", "<caret>"}`): a `JsonStringLiteral` that is the name element of a
 *   `JsonProperty`, followed by an error element (':' expected); with a colon and value (`"Re<caret>": 1`) a complete
 *   property. Without closing braces the property is the same, the error elements come after it.
 * - Key without quotes (`{"Type": "Pass", <caret>`): a `JsonReferenceExpression` as the name element.
 * - Value (`"Type": "<caret>"` or `"End": <caret>`): a `JsonStringLiteral` or `JsonReferenceExpression` that is the
 *   property's value; a list item is a child of a `JsonArray` that is the value.
 * - Empty file: a `JsonReferenceExpression` directly in the file.
 */
object CompletionContexts {
    /** Null outside ASL files, inside free-form data and where no ASL field or value goes. */
    fun at(parameters: CompletionParameters): CompletionContext? {
        val position = parameters.position
        val file = position.containingFile ?: return null
        val doc = AslModel.of(file) ?: return null
        if (doc.schema.isFreeForm(position)) return null
        return when (file) {
            is YAMLFile -> Yaml(doc, position).context()
            is JsonFile -> Json(doc, position).context()
            else -> null
        }
    }

    private class Yaml(val doc: AslDocument, val position: PsiElement) {
        fun context(): CompletionContext? {
            val parent = position.parent
            if (parent is YAMLKeyValue) {
                val key = parent.key ?: return null
                return if (PsiTreeUtil.isAncestor(key, position, false)) keyIn(doc, parent.parentMapping, position) else null
            }
            val scalar = parent as? YAMLScalar ?: return null
            return when (val holder = scalar.parent) {
                is YAMLMapping -> keyIn(doc, holder, position)
                is YAMLKeyValue -> if (holder.value === scalar) valueOf(holder, scalar) else null
                is YAMLSequenceItem -> listItem(holder)
                is YAMLDocument -> if (holder.topLevelValue === scalar) topLevelKey() else null
                else -> null
            }
        }

        private fun valueOf(keyValue: YAMLKeyValue, scalar: YAMLScalar): CompletionContext? {
            val key = keyValue.key ?: return null
            val text = keyValue.containingFile.viewProvider.contents
            val start = scalar.textRange.startOffset
            val scalarLineStart = lineStart(text, start)
            if (scalarLineStart <= key.textRange.endOffset) return value(doc, key)
            // On a later line but not indented deeper than the key: a new key next to it, not its value.
            val keyStart = key.textRange.startOffset
            if (start - scalarLineStart <= keyStart - lineStart(text, keyStart)) return keyIn(doc, keyValue.parentMapping, position)
            // The value is still empty and the caret is on the first line under the key: the first key of the value.
            doc.schema.stateNameKey(key)?.let { state ->
                return CompletionContext.Key(ObjectInfo(AslSpec.structure(StructureId.STATE), state.queryLanguage, state), emptySet())
            }
            val field = doc.schema.field(key) ?: return null
            return when (val kind = field.spec.kind) {
                is ValueKind.Struct -> firstKey(field, kind.id)
                is ValueKind.ListOf, ValueKind.StatesMap, ValueKind.FreeForm -> null
                else -> value(doc, key)
            }
        }

        /** The offset at which the line holding [offset] starts. */
        private fun lineStart(text: CharSequence, offset: Int): Int {
            var i = offset
            while (i > 0 && text[i - 1] != '\n') i--
            return i
        }

        private fun listItem(item: YAMLSequenceItem): CompletionContext? {
            val sequence = item.parent as? YAMLSequence ?: return null
            val key = (sequence.parent as? YAMLKeyValue)?.key ?: return null
            val field = doc.schema.field(key) ?: return null
            val element = (field.spec.kind as? ValueKind.ListOf)?.element
            return when {
                element is ValueKind.Struct -> if (sequence is YAMLArrayImpl) null else firstKey(field, element.id)
                element != null || field.spec.kind == ValueKind.ErrorNames -> CompletionContext.Value(field, field.owner.state)
                else -> null
            }
        }

        private fun topLevelKey(): CompletionContext =
            CompletionContext.Key(ObjectInfo(AslSpec.structure(StructureId.STATE_MACHINE), QueryLanguage.JSONPATH, null), emptySet())
    }

    private class Json(val doc: AslDocument, val position: PsiElement) {
        fun context(): CompletionContext? {
            val literal = position.parent as? JsonValue ?: return null
            return when (val holder = literal.parent) {
                is JsonProperty -> when {
                    holder.nameElement === literal -> keyIn(doc, holder.parent as? JsonObject ?: return null, position)
                    holder.value === literal -> value(doc, holder.nameElement)
                    else -> null
                }
                is JsonArray -> {
                    val property = holder.parent as? JsonProperty ?: return null
                    val field = doc.schema.field(property.nameElement) ?: return null
                    val kind = field.spec.kind
                    if (kind == ValueKind.ErrorNames || kind is ValueKind.ListOf && kind.element !is ValueKind.Struct) {
                        CompletionContext.Value(field, field.owner.state)
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
    }

    /** A key position in [objectPsi], whose keys other than the one at [position] are present. */
    private fun keyIn(doc: AslDocument, objectPsi: PsiElement?, position: PsiElement): CompletionContext? {
        if (objectPsi == null) return null
        val owner = doc.schema.info(objectPsi) ?: return null
        val obj = doc.rootNode?.let { findObject(it, objectPsi) }
        val present = obj?.entries.orEmpty()
            .filterNot { PsiTreeUtil.isAncestor(it.entryPsi, position, false) }
            .mapTo(HashSet(), AslEntry::key)
        return CompletionContext.Key(owner, present)
    }

    /** The first key of a still empty value of [field], which is an object of structure [id]. */
    private fun firstKey(field: FieldInfo, id: StructureId): CompletionContext =
        CompletionContext.Key(ObjectInfo(AslSpec.structure(id), field.owner.queryLanguage, field.owner.state), emptySet())

    private fun value(doc: AslDocument, keyPsi: PsiElement): CompletionContext? {
        val field = doc.schema.field(keyPsi) ?: return null
        if (field.spec.kind == ValueKind.FreeForm) return null
        return CompletionContext.Value(field, field.owner.state)
    }

    /** The object of the tree whose PSI is [target], descending only into values written around it. */
    private fun findObject(node: AslNode, target: PsiElement): AslObject? {
        if (node is AslObject && node.psi === target) return node
        if (!node.usePsi.textRange.contains(target.textRange)) return null
        val children = when (node) {
            is AslObject -> node.entries.map { it.value }
            is AslArray -> node.items
            else -> return null
        }
        for (child in children) findObject(child, target)?.let { return it }
        return null
    }
}
