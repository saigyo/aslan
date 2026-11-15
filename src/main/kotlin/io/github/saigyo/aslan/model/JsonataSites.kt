package io.github.saigyo.aslan.model

import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StatesField
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.AslUnresolved
import io.github.saigyo.aslan.tree.ScalarType

/** One use of a string that Step Functions evaluates as JSONata. */
class JsonataSite(val scalar: AslScalar, val state: AslState, val provides: Set<StatesField>)

/**
 * The strings of a definition that Step Functions evaluates as JSONata: in every state that certainly uses JSONata and
 * has a known type, the strings under its free-form fields and the scalar values of its other JSONata fields, that are
 * certain strings starting with `{%` and ending with `%}`. A scalar used by several states through an alias has one
 * site for each; all of them are keyed by the anchored scalar.
 */
class JsonataSites internal constructor(doc: AslDocument) {
    val all: List<JsonataSite>
    private val byHost: Map<PsiElement, List<JsonataSite>>

    /**
     * The whole values of fields that take JSONata, where the file does not settle them ([standsForAnyValue]): a
     * placeholder, a tagged value or an unresolved one, which may stand for an expression that reads any variable.
     */
    internal val unsettled: List<PsiElement>

    init {
        val sites = ArrayList<JsonataSite>()
        val unsettledValues = ArrayList<PsiElement>()
        for (state in doc.allStates()) {
            val type = state.type ?: continue
            val obj = state.obj ?: continue
            if (!evaluatedIn(state.queryLanguage, state.queryLanguageKnown)) continue
            Collector(state, sites, unsettledValues).walk(obj, AslSpec.structure(type.structure), stateLevel = true)
        }
        all = sites
        byHost = sites.groupBy { it.scalar.psi }
        unsettled = unsettledValues.distinct()
    }

    /** The sites whose scalar is [host], the element [AslScalar.psi] gives. */
    fun at(host: PsiElement): List<JsonataSite> = byHost[host].orEmpty()

    private class Collector(val state: AslState, val out: MutableList<JsonataSite>, val unsettled: MutableList<PsiElement>) {
        fun walk(o: AslObject, structure: StructureSpec, stateLevel: Boolean) {
            for (entry in o.entries) {
                if (isUnappliedMerge(entry)) continue
                val field = structure.field(entry.key) ?: continue
                if (!field.mode.allows(QueryLanguage.JSONATA)) continue
                val provides = provides(structure.id, entry.key, stateLevel)
                val kind = field.kind
                val value = entry.value
                when {
                    (kind == ValueKind.FreeForm || field.jsonata) && standsForAnyValue(value) -> unsettled += value.psi
                    kind == ValueKind.FreeForm -> freeForm(value, provides)
                    value is AslScalar && !standsForAnyValue(value) -> if (field.jsonata) candidate(value, provides)
                    kind is ValueKind.Struct && value is AslObject -> walk(value, AslSpec.structure(kind.id), stateLevel = false)
                    // a whole structure the file does not settle, which may supply a field that takes JSONata
                    kind is ValueKind.Struct && standsForAnyValue(value) -> structural(value, kind.id)
                    kind is ValueKind.ListOf && kind.element is ValueKind.Struct && standsForAnyValue(value) -> structural(value, kind.element.id)
                    kind is ValueKind.ListOf && kind.element is ValueKind.Struct && value is AslArray ->
                        for (item in value.items) {
                            if (item is AslObject) walk(item, AslSpec.structure(kind.element.id), stateLevel = false)
                            else if (standsForAnyValue(item)) structural(item, kind.element.id)
                        }
                }
            }
        }

        private fun structural(value: AslNode, id: StructureId) {
            if (holdsEvaluated(id, QueryLanguage.JSONATA, { field, _ -> field.kind == ValueKind.FreeForm || field.jsonata })) unsettled += value.psi
        }

        private fun freeForm(node: AslNode, provides: Set<StatesField>) {
            when (node) {
                is AslObject -> for (entry in node.entries) if (!isUnappliedMerge(entry)) freeForm(entry.value, provides)
                is AslArray -> for (item in node.items) freeForm(item, provides)
                is AslScalar -> candidate(node, provides)
                is AslUnresolved -> {}
            }
        }

        private fun candidate(scalar: AslScalar, provides: Set<StatesField>) {
            if (isExpression(scalar)) out += JsonataSite(scalar, state, provides)
        }

        /** `$states.result` is set in the state's own `Output` and `Assign`, `$states.errorOutput` in those of a catcher. */
        private fun provides(structure: StructureId, key: String, stateLevel: Boolean): Set<StatesField> = when {
            key != "Output" && key != "Assign" -> BASE
            stateLevel && structure in WITH_RESULT -> RESULT
            structure == StructureId.CATCHER -> ERROR_OUTPUT
            else -> BASE
        }
    }

    companion object {
        const val OPEN = "{%"
        const val CLOSE = "%}"

        private val BASE = setOf(StatesField.INPUT, StatesField.CONTEXT)
        private val RESULT = BASE + StatesField.RESULT
        private val ERROR_OUTPUT = BASE + StatesField.ERROR_OUTPUT
        private val WITH_RESULT = setOf(StructureId.TASK, StructureId.MAP, StructureId.PARALLEL)

        /** Whether a state with this query language has its `{%` strings evaluated. */
        fun evaluatedIn(language: QueryLanguage, known: Boolean): Boolean = known && language == QueryLanguage.JSONATA

        /** A string that starts with `{%`: meant as JSONata, closed or not. */
        fun opens(scalar: AslScalar): Boolean = scalar.type == ScalarType.STRING && scalar.text.startsWith(OPEN)

        fun closes(text: String): Boolean = text.endsWith(CLOSE)

        /** A certain string that Step Functions evaluates where it evaluates JSONata: `{%`, the expression, `%}`. */
        fun isExpression(scalar: AslScalar): Boolean =
            !scalar.ambiguous && !scalar.dynamic && opens(scalar) && closes(scalar.text) && scalar.text.length >= OPEN.length + CLOSE.length
    }
}
