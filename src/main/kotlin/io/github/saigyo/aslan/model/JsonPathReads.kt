package io.github.saigyo.aslan.model

import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.ScalarType

/** A `$name` read in a JSONPath string: [name] without `$`; [start] and [end] in the decoded text, `$` included. */
data class PathName(val name: String, val start: Int, val end: Int)

/** The `$name` variable reads in the strings that a JSONPath state evaluates. */
object JsonPathReads {
    private const val INTRINSIC = "States."

    /** The variable reads in [text], a path or an intrinsic function call. */
    fun names(text: String): List<PathName> {
        if (text.startsWith("$")) return listOfNotNull(readAt(text, 0))
        if (!text.startsWith(INTRINSIC)) return emptyList()
        val reads = ArrayList<PathName>()
        var previous = ' '
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> {
                    i++
                    while (i < text.length && text[i] != '\'') i += if (text[i] == '\\') 2 else 1
                    previous = '\''
                    i++
                }
                c == '$' && (previous == '(' || previous == ',') -> {
                    val read = readAt(text, i)
                    if (read != null) {
                        reads += read
                        i = read.end
                    } else i++
                    previous = 'x'
                }
                Character.isWhitespace(c) -> i++
                else -> {
                    previous = c
                    i++
                }
            }
        }
        return reads
    }

    private fun readAt(text: String, dollar: Int): PathName? {
        var end = dollar + 1
        while (end < text.length && (Character.isLetterOrDigit(text[end]) || text[end] == '_')) end++
        return if (end == dollar + 1) null else PathName(text.substring(dollar + 1, end), dollar, end)
    }

    /** The strings of [doc] that JSONPath evaluates (see [Collector]), with the state each belongs to; one entry per use through an alias. */
    fun sites(doc: AslDocument): List<Pair<AslScalar, AslState>> {
        val out = ArrayList<Pair<AslScalar, AslState>>()
        for (state in doc.allStates()) {
            val type = state.type ?: continue
            val obj = state.obj ?: continue
            if (!state.queryLanguageKnown || state.queryLanguage != QueryLanguage.JSONPATH) continue
            Collector(state, out).walk(obj, AslSpec.structure(type.structure))
        }
        return out
    }

    /**
     * Collects the certain string values of the entries whose key ends in `.$` inside free-form fields, of the fields
     * whose name ends in `Path` (not `ResultPath`) and of `Variable` in a Choice rule.
     */
    private class Collector(val state: AslState, val out: MutableList<Pair<AslScalar, AslState>>) {
        fun walk(o: AslObject, structure: StructureSpec) {
            for (entry in o.entries) {
                if (isUnappliedMerge(entry)) continue
                val field = structure.field(entry.key) ?: continue
                if (!field.mode.allows(QueryLanguage.JSONPATH)) continue
                val kind = field.kind
                val value = entry.value
                when {
                    kind == ValueKind.FreeForm -> freeForm(value)
                    value is AslScalar -> if (isPathField(entry.key, structure)) candidate(value)
                    kind is ValueKind.Struct && value is AslObject -> walk(value, AslSpec.structure(kind.id))
                    kind is ValueKind.ListOf && kind.element is ValueKind.Struct && value is AslArray ->
                        for (item in value.items) if (item is AslObject) walk(item, AslSpec.structure(kind.element.id))
                }
            }
        }

        private fun isPathField(key: String, structure: StructureSpec): Boolean =
            (key.endsWith("Path") && key != "ResultPath") ||
                (key == "Variable" && structure.id == StructureId.CHOICE_RULE)

        private fun freeForm(node: AslNode) {
            when (node) {
                is AslObject -> for (entry in node.entries) {
                    if (isUnappliedMerge(entry)) continue
                    val value = entry.value
                    if (entry.key.endsWith(".$") && value is AslScalar) candidate(value)
                    else freeForm(value)
                }
                is AslArray -> for (item in node.items) freeForm(item)
                else -> {}
            }
        }

        private fun candidate(scalar: AslScalar) {
            if (scalar.type == ScalarType.STRING && !scalar.ambiguous && !scalar.dynamic) out += scalar to state
        }
    }
}
