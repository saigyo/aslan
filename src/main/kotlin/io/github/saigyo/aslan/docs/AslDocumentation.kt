package io.github.saigyo.aslan.docs

import com.intellij.json.psi.JsonLiteral
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.FieldInfo
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.Mode
import io.github.saigyo.aslan.spec.NamedDoc
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslScalar
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import com.intellij.json.psi.JsonArray

/** Builds the quick documentation text for ASL field names and fixed values. */
object AslDocumentation {
    /**
     * The documentation for the key or scalar value that [element] is or lies in, or null outside ASL files, inside free-form
     * data and for anything that is not a known field name or a documented value.
     */
    fun html(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        if (!AslFiles.isAslFile(file)) return null
        val schema = AslModel.of(file)?.schema ?: return null
        val chain = generateSequence(element) { it.parent }.takeWhile { it !is PsiFile }
        // A key is a token in YAML and a literal in JSON; a quoted key is a token inside a quoted scalar.
        chain.takeWhile { it !is YAMLKeyValue && it !is JsonProperty }.firstNotNullOfOrNull { schema.field(it) }
            ?.let { return fieldHtml(it) }
        val leaf = chain.firstOrNull { it is YAMLScalar || it is JsonLiteral } ?: return null
        val owner = entryOwning(leaf) ?: return null
        val info = schema.field(owner) ?: return null
        val scalar = scalarsOf(info.entry.value).firstOrNull { it.psi === leaf } ?: return null
        return valueHtml(info, scalar.text)
    }

    /** The key element of the property or key-value pair whose value contains [leaf], possibly inside a list. */
    private fun entryOwning(leaf: PsiElement): PsiElement? {
        var child: PsiElement = leaf
        var parent = leaf.parent
        while (parent is YAMLSequenceItem || parent is YAMLSequence || parent is JsonArray) {
            child = parent
            parent = parent.parent
        }
        return when (parent) {
            is YAMLKeyValue -> parent.key.takeIf { parent.value === child }
            is JsonProperty -> parent.nameElement.takeIf { parent.value === child }
            else -> null
        }
    }

    private fun scalarsOf(node: AslNode): List<AslScalar> = when (node) {
        is AslScalar -> listOf(node)
        is AslArray -> node.items.flatMap { scalarsOf(it) }
        else -> emptyList()
    }

    private fun fieldHtml(info: FieldInfo): String {
        val spec = info.spec
        val facts = buildList {
            add(if (spec.required) "Required" else "Optional")
            when (spec.mode) {
                Mode.JSONATA -> add("JSONata only")
                Mode.JSONPATH -> add("JSONPath only")
                Mode.BOTH -> {}
            }
            range(spec.min, spec.max)?.let { add(it) }
            if (spec.jsonata && info.owner.queryLanguage == QueryLanguage.JSONATA) add("Accepts a JSONata expression")
            if (spec.deprecated) add("Deprecated")
        }
        return page(
            "${esc(spec.name)}: ${esc(kindText(spec.kind))}",
            esc(facts.joinToString(". ")),
            spec.description, spec.docUrl,
        )
    }

    private fun valueHtml(info: FieldInfo, value: String): String? {
        val doc: NamedDoc = when {
            info.spec.name == "Type" && info.spec.kind is ValueKind.OneOf ->
                AslSpec.stateType(value)?.let { NamedDoc(it.name, it.description, it.docUrl) }
            else -> when (val kind = info.spec.kind.element()) {
                is ValueKind.OneOf -> kind.values.firstOrNull { it.name == value }
                ValueKind.ErrorNames -> AslSpec.errorNames.firstOrNull { it.name == value }
                ValueKind.ResourceArn -> AslSpec.resourceArns.firstOrNull { it.name == value }
                else -> null
            }
        } ?: return null
        return page(esc(doc.name), null, doc.description, doc.docUrl)
    }

    private fun ValueKind.element(): ValueKind = if (this is ValueKind.ListOf) element.element() else this

    private fun range(min: Double?, max: Double?): String? = when {
        min != null && max != null -> "Range ${num(min)} to ${num(max)}"
        min != null -> "Minimum ${num(min)}"
        max != null -> "Maximum ${num(max)}"
        else -> null
    }

    private fun num(value: Double): String = if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun kindText(kind: ValueKind): String = when (kind) {
        ValueKind.Text, ValueKind.StateName, ValueKind.ResourceArn -> "string"
        ValueKind.Integer -> "integer"
        ValueKind.Number -> "number"
        ValueKind.Bool -> "boolean"
        is ValueKind.OneOf -> "one of " + kind.values.joinToString(", ") { it.name }
        ValueKind.StatesMap -> "mapping of state names to states"
        ValueKind.ErrorNames -> "list of error names"
        ValueKind.FreeForm -> "any value"
        is ValueKind.Struct -> "object"
        is ValueKind.ListOf -> "list of " + kindText(kind.element)
    }

    /** [definition] and [facts] are already escaped; [description] and [url] come from the reference data and are escaped here. */
    private fun page(definition: String, facts: String?, description: String, url: String?): String = buildString {
        append(DocumentationMarkup.DEFINITION_START).append(definition).append(DocumentationMarkup.DEFINITION_END)
        append(DocumentationMarkup.CONTENT_START)
        if (!facts.isNullOrEmpty()) append("<i>").append(facts).append("</i><br>")
        append(esc(description))
        append(DocumentationMarkup.CONTENT_END)
        if (url != null) {
            append(DocumentationMarkup.SECTIONS_START)
            append("<p><a href=\"").append(esc(url)).append("\">AWS documentation</a>")
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)
}
