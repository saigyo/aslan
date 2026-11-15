package io.github.saigyo.aslan.model

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
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
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLFile

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

    /** The name after the `$` at [dollar], scanned by code point so a supplementary letter belongs to it; UTF-16 offsets. */
    private fun readAt(text: String, dollar: Int): PathName? {
        var end = dollar + 1
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            if (!isNamePart(codePoint)) break
            end += Character.charCount(codePoint)
        }
        return if (end == dollar + 1) null else PathName(text.substring(dollar + 1, end), dollar, end)
    }

    /** A letter, a digit or `_`, the characters of a name after its first. */
    internal fun isNamePart(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) || codePoint == '_'.code

    /** The strings of [doc] that JSONPath evaluates (see [Collector]), with the state each belongs to; one entry per use through an alias. */
    fun sites(doc: AslDocument): List<Pair<AslScalar, AslState>> = collect(doc).first

    /**
     * The whole values of the places [sites] looks at, and of free-form fields, where the file does not settle them
     * ([standsForAnyValue]): they may stand for a path or for `.$` entries that read any variable.
     */
    internal fun unsettled(doc: AslDocument): List<PsiElement> = collect(doc).second.distinct()

    /**
     * The `Result` values of the JSONPath Pass states, where they are written: AWS does not evaluate them
     * (`r3-AC-pass-result-key`, `PASS_RESULT_IS_STATIC`), so a `$name` there is plain data. Not a value that an alias
     * or merge key elsewhere in the file reuses, wholly or in part, through an anchor on it, on one of its ancestors or
     * on a value inside it: the reuse may be a field that is evaluated (`Parameters: *shared` in a state whose `Type` is
     * a placeholder).
     */
    internal fun staticValues(doc: AslDocument): List<PsiElement> {
        val reused = reusedAnchoredValues(doc.file)
        return doc.allStates().mapNotNull { state ->
            if (!isJsonPath(state) || state.type?.structure != StructureId.PASS) return@mapNotNull null
            state.obj?.get(STATIC_RESULT)?.takeIf { !it.viaMerge }?.value?.takeIf { it.psi === it.usePsi }?.psi
                ?.takeIf { value -> reused.none { PsiTreeUtil.isAncestor(it, value, false) || PsiTreeUtil.isAncestor(value, it, false) } }
        }.distinct()
    }

    /** The values that carry an anchor which an alias, a merge key's included, of [file] resolves to. */
    private fun reusedAnchoredValues(file: PsiFile): List<PsiElement> =
        if (file !is YAMLFile) emptyList()
        else PsiTreeUtil.findChildrenOfType(file, YAMLAlias::class.java).mapNotNull { it.reference?.resolve()?.markedValue }.distinct()

    private const val STATIC_RESULT = "Result"

    private fun isJsonPath(state: AslState) = state.queryLanguageKnown && state.queryLanguage == QueryLanguage.JSONPATH

    private fun collect(doc: AslDocument): Pair<List<Pair<AslScalar, AslState>>, List<PsiElement>> {
        val out = ArrayList<Pair<AslScalar, AslState>>()
        val unsettled = ArrayList<PsiElement>()
        for (state in doc.allStates()) {
            val type = state.type ?: continue
            val obj = state.obj ?: continue
            if (!isJsonPath(state)) continue
            Collector(state, out, unsettled).walk(obj, AslSpec.structure(type.structure))
        }
        return out to unsettled
    }

    /**
     * Collects the certain string values of the entries whose key ends in `.$` inside free-form fields, of the fields
     * whose name ends in `Path` (not `ResultPath`) and of `Variable` in a Choice rule; not those of a Pass state's
     * `Result`, which AWS does not evaluate ([staticValues]).
     */
    private class Collector(val state: AslState, val out: MutableList<Pair<AslScalar, AslState>>, val unsettled: MutableList<PsiElement>) {
        fun walk(o: AslObject, structure: StructureSpec) {
            for (entry in o.entries) {
                if (isUnappliedMerge(entry)) continue
                if (structure.id == StructureId.PASS && entry.key == STATIC_RESULT) continue
                val field = structure.field(entry.key) ?: continue
                if (!field.mode.allows(QueryLanguage.JSONPATH)) continue
                val kind = field.kind
                val value = entry.value
                when {
                    (kind == ValueKind.FreeForm || isPathField(entry.key, structure)) && standsForAnyValue(value) -> unsettled += value.psi
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
                    if (entry.key.endsWith(".$") && standsForAnyValue(value)) unsettled += value.psi
                    else if (entry.key.endsWith(".$") && value is AslScalar) candidate(value)
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
