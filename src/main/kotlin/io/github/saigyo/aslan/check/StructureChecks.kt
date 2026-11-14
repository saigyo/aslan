package io.github.saigyo.aslan.check

import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.check.AslProblem.EMPTY_CHOICES
import io.github.saigyo.aslan.check.AslProblem.JSONPATH_STATE_IN_JSONATA_MACHINE
import io.github.saigyo.aslan.check.AslProblem.MISSING_REQUIRED_FIELD
import io.github.saigyo.aslan.check.AslProblem.MISSING_TRANSITION
import io.github.saigyo.aslan.check.AslProblem.ROOT_NOT_MAPPING
import io.github.saigyo.aslan.check.AslProblem.UNCLOSED_JSONATA_DELIMITER
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_STATE_TYPE
import io.github.saigyo.aslan.check.AslProblem.WRONG_QUERY_LANGUAGE_FIELD
import io.github.saigyo.aslan.check.AslProblem.WRONG_VALUE_KIND
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.model.isUnappliedMerge
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslEntry
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.AslUnresolved
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType
import java.util.IdentityHashMap

/** The checks of the definition's structure whose failure AWS documents as a rejection. */
internal object StructureChecks {
    fun check(doc: AslDocument, out: MutableList<AslFinding>) {
        val rootNode = doc.rootNode ?: return
        val root = doc.root
        if (root == null) {
            if (!exemptFromKind(rootNode)) out += AslFinding(ROOT_NOT_MAPPING, rootNode.psi, "The definition must be a mapping")
            return
        }
        Walk(doc, root, out).run()
    }

    /**
     * The query language that applies to a value, whether it is known or decided by a placeholder, and whether the
     * value lies in a state (rather than at the top level of a state machine).
     */
    private class Context(val ql: QueryLanguage, val known: Boolean, val inState: Boolean) {
        /** Where a `{%` string may stand for any kind of value. */
        val jsonataPossible: Boolean get() = ql == QueryLanguage.JSONATA || !known

        /** Where Step Functions evaluates `{%` strings in JSONata fields and free-form data. */
        val evaluatesJsonata: Boolean get() = inState && known && ql == QueryLanguage.JSONATA
    }

    private class Walk(val doc: AslDocument, val root: AslStateMachine, val out: MutableList<AslFinding>) {
        private val machines = IdentityHashMap<AslObject, AslStateMachine>().apply { for (m in doc.allMachines()) put(m.obj, m) }
        private val rootLanguage = root.obj["QueryLanguage"]?.value as? AslScalar
        private val topLevelJsonata = rootLanguage?.type == ScalarType.STRING && rootLanguage.text == QueryLanguage.JSONATA.text

        fun run() {
            val known = rootLanguage == null || !Placeholders.contains(rootLanguage.text)
            val ql = if (topLevelJsonata) QueryLanguage.JSONATA else QueryLanguage.JSONPATH
            obj(root.obj, AslSpec.structure(StructureId.STATE_MACHINE), Context(ql, known, inState = false), root.obj.anchorPsi)
        }

        private fun report(problem: AslProblem, element: PsiElement, message: String) {
            out += AslFinding(problem, element, message)
        }

        /** Checks [o] as [structure]; [anchor] is where a missing field is reported. */
        private fun obj(o: AslObject, structure: StructureSpec, ctx: Context, anchor: PsiElement) {
            if (!hasUnappliedMerge(o)) {
                for (field in structure.fields) {
                    if (field.required && o[field.name] == null) report(MISSING_REQUIRED_FIELD, anchor, "Missing required field '${field.name}'")
                }
                for (set in structure.oneOfRequired) {
                    if (set.none { o[it] != null }) report(MISSING_REQUIRED_FIELD, anchor, "One of ${set.joinToString { "'$it'" }} is required")
                }
            }
            for (entry in o.entries) {
                if (entry.viaMerge || isUnappliedMerge(entry)) continue
                val field = structure.field(entry.key) ?: continue
                if (ctx.known && !field.mode.allows(ctx.ql)) {
                    report(WRONG_QUERY_LANGUAGE_FIELD, entry.keyPsi, "'${entry.key}' cannot be used with ${ctx.ql.text}")
                    continue
                }
                if (field.lenient) continue
                value(entry.value, field.kind, field.nullable, field.jsonata, ctx, entry.keyPsi, o)
            }
        }

        /**
         * Checks that [node] fits [kind] and, if it does, descends into it. [jsonata] tells whether the field evaluates
         * JSONata; [owner] is the object holding the field.
         */
        private fun value(node: AslNode, kind: ValueKind, nullable: Boolean, jsonata: Boolean, ctx: Context, anchor: PsiElement, owner: AslObject) {
            if (kind == ValueKind.FreeForm) {
                if (ctx.evaluatesJsonata) freeFormDelimiters(node)
                return
            }
            if (exemptFromKind(node)) return
            if (node is AslScalar && ctx.jsonataPossible && isJsonata(node)) {
                if (jsonata && ctx.evaluatesJsonata) unclosed(node)
                return
            }
            when (kind) {
                is ValueKind.Struct ->
                    if (node is AslObject) obj(node, AslSpec.structure(kind.id), ctx, anchor) else wrongKind(node, "a mapping")
                is ValueKind.ListOf ->
                    if (node is AslArray) {
                        for (item in node.items) value(item, kind.element, false, jsonata, ctx, (item as? AslObject)?.anchorPsi ?: item.psi, owner)
                    } else {
                        wrongKind(node, "a list")
                    }
                ValueKind.StatesMap ->
                    if (node is AslObject) machines[owner]?.let(::states) else wrongKind(node, "a mapping of states")
                ValueKind.ErrorNames -> if (node !is AslArray) wrongKind(node, "a list of error names")
                else -> scalar(node, kind, nullable)
            }
        }

        private fun scalar(node: AslNode, kind: ValueKind, nullable: Boolean) {
            val (expected, what) = when (kind) {
                ValueKind.Text, ValueKind.StateName, ValueKind.ResourceArn, is ValueKind.OneOf -> ScalarType.STRING to "a string"
                ValueKind.Integer, ValueKind.Number -> ScalarType.NUMBER to "a number"
                ValueKind.Bool -> ScalarType.BOOLEAN to "true or false"
                else -> return
            }
            val fits = node is AslScalar && (node.type == expected || (node.type == ScalarType.NULL && nullable))
            if (!fits) wrongKind(node, what)
        }

        private fun wrongKind(node: AslNode, expected: String) = report(WRONG_VALUE_KIND, node.psi, "Expected $expected")

        private fun states(machine: AslStateMachine) {
            for (state in machine.states) if (!state.entry.viaMerge) state(state)
        }

        private fun state(state: AslState) {
            val o = state.obj
            if (o == null) {
                if (!exemptFromKind(state.entry.value)) wrongKind(state.entry.value, "a mapping that describes the state")
                return
            }
            if (skipped(state)) return
            val ctx = Context(state.queryLanguage, state.queryLanguageKnown, inState = true)
            val typeEntry = o["Type"]?.takeIf { !it.viaMerge }
            val typeValue = typeEntry?.value as? AslScalar
            if (state.type == null && typeValue != null && typeValue.type == ScalarType.STRING && !typeValue.ambiguous) {
                report(UNKNOWN_STATE_TYPE, typeValue.psi, "Unknown state type '${typeValue.text}'")
            }
            obj(o, AslSpec.structure(state.type?.structure ?: StructureId.STATE), ctx, state.entry.keyPsi)

            val type = state.type
            if (type != null && type.needsTransition && !hasUnappliedMerge(o) && o["Next"] == null && !mayEnd(o["End"])) {
                report(MISSING_TRANSITION, state.entry.keyPsi, "A ${type.name} state needs 'Next' or 'End: true'")
            }
            if (topLevelJsonata && state.queryLanguageKnown) {
                val language = o["QueryLanguage"]?.takeIf { !it.viaMerge }?.value as? AslScalar
                if (language != null && language.type == ScalarType.STRING && language.text == QueryLanguage.JSONPATH.text) {
                    report(JSONPATH_STATE_IN_JSONATA_MACHINE, language.psi, "A JSONPath state cannot be part of a JSONata state machine")
                }
            }
            if (type?.structure == StructureId.CHOICE) choices(o, ctx)
        }

        /** Rules on the Choices of a Choice state that the reference data cannot express. */
        private fun choices(o: AslObject, ctx: Context) {
            val entry = o["Choices"]?.takeIf { !it.viaMerge } ?: return
            val list = entry.value as? AslArray ?: return
            if (list.items.isEmpty()) {
                report(EMPTY_CHOICES, entry.keyPsi, "A Choice state needs at least one rule")
                return
            }
            for (rule in list.items) {
                if (rule !is AslObject || hasUnappliedMerge(rule)) continue
                if (rule["Next"] == null) report(MISSING_REQUIRED_FIELD, rule.anchorPsi, "Missing required field 'Next'")
                if (ctx.known && ctx.ql == QueryLanguage.JSONATA && rule["Condition"] == null) {
                    report(MISSING_REQUIRED_FIELD, rule.anchorPsi, "Missing required field 'Condition'")
                }
            }
        }

        /** Every string in free-form data is evaluated in a JSONata state, so each must be a closed expression or plain text. */
        private fun freeFormDelimiters(node: AslNode) {
            when (node) {
                is AslObject -> for (entry in node.entries) if (!entry.viaMerge && !isUnappliedMerge(entry)) freeFormDelimiters(entry.value)
                is AslArray -> for (item in node.items) freeFormDelimiters(item)
                is AslScalar -> if (isJsonata(node) && !Placeholders.contains(node.text)) unclosed(node)
                is AslUnresolved -> {}
            }
        }

        private fun unclosed(scalar: AslScalar) {
            if (!scalar.text.endsWith("%}")) report(UNCLOSED_JSONATA_DELIMITER, scalar.psi, "A JSONata expression must end with %}")
        }
    }

    /** A state whose type is a placeholder is not checked at all. */
    private fun skipped(state: AslState): Boolean = state.typeName?.let(Placeholders::contains) == true

    /** Placeholders, ambiguous scalars and unresolved aliases are never checked for kind. */
    private fun exemptFromKind(node: AslNode): Boolean = when (node) {
        is AslUnresolved -> true
        is AslScalar -> node.ambiguous || Placeholders.contains(node.text)
        else -> false
    }

    private fun isJsonata(scalar: AslScalar) = scalar.type == ScalarType.STRING && scalar.text.startsWith("{%")

    /** `End` counts as possibly true unless it is the Boolean `false`; a placeholder or ambiguous value stays possible. */
    private fun mayEnd(end: AslEntry?): Boolean {
        val value = end?.value ?: return false
        return !(value is AslScalar && value.type == ScalarType.BOOLEAN && !value.ambiguous && value.text.equals("false", ignoreCase = true))
    }

    private fun hasUnappliedMerge(o: AslObject) = o.entries.any(::isUnappliedMerge)
}
