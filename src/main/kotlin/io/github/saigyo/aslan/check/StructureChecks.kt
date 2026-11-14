package io.github.saigyo.aslan.check

import com.intellij.json.psi.JsonFile
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.check.AslProblem.EMPTY_CHOICES
import io.github.saigyo.aslan.check.AslProblem.EXCLUSIVE_FIELDS
import io.github.saigyo.aslan.check.AslProblem.INAPPLICABLE_FIELD
import io.github.saigyo.aslan.check.AslProblem.INVALID_PATH
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_FIELD
import io.github.saigyo.aslan.check.AslProblem.END_FALSE_WITHOUT_NEXT
import io.github.saigyo.aslan.check.AslProblem.JSONPATH_STATE_IN_JSONATA_MACHINE
import io.github.saigyo.aslan.check.AslProblem.MISSING_REQUIRED_FIELD
import io.github.saigyo.aslan.check.AslProblem.MISPLACED_JSONATA_DELIMITER
import io.github.saigyo.aslan.check.AslProblem.MISSING_TRANSITION
import io.github.saigyo.aslan.check.AslProblem.NULL_VALUE
import io.github.saigyo.aslan.check.AslProblem.ROOT_NOT_MAPPING
import io.github.saigyo.aslan.check.AslProblem.UNCLOSED_JSONATA_DELIMITER
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_STATE_TYPE
import io.github.saigyo.aslan.check.AslProblem.WRONG_QUERY_LANGUAGE_FIELD
import io.github.saigyo.aslan.check.AslProblem.INVALID_RESOURCE_ARN
import io.github.saigyo.aslan.check.AslProblem.INVALID_ROLE_ARN
import io.github.saigyo.aslan.check.AslProblem.WRONG_VALUE_KIND
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.model.isUnappliedMerge
import io.github.saigyo.aslan.model.leavesFieldsOpen
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

/**
 * The checks of the definition's structure whose failure AWS documents as a rejection, and the two warnings that are
 * decided at the same place: a null optional field and JSONata delimiters that do not wrap the whole value.
 */
internal object StructureChecks {
    private const val CREDENTIALS = "Credentials"
    private const val ROLE_ARN = "RoleArn"
    private const val ROLE_ARN_PATH = "RoleArn.$"
    private val PAYLOAD_FIELDS = listOf("Parameters", "Arguments")

    /** Free-form fields for which AWS rejects `null`; it accepts `null` in the others, such as `Output` and `ResultSelector`. */
    private val NEVER_NULL = setOf("Assign", "Items")

    /** The structures whose `Parameters` or `Arguments` AWS does not accept as `null`; on a Pass or Map state it does. */
    private val NEEDS_PAYLOAD = setOf(StructureId.TASK, StructureId.ITEM_READER, StructureId.RESULT_WRITER)

    /** JSONPath fields whose keys that end in `.$` are evaluated. */
    private val PAYLOAD_TEMPLATES = setOf("Parameters", "ResultSelector", "ItemSelector")
    private val INTRINSIC_CALL = Regex("""^States\.\w+\(""")

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
    internal class Context(val ql: QueryLanguage, val known: Boolean, val inState: Boolean) {
        /** Where a `{%` string may stand for any kind of value. */
        val jsonataPossible: Boolean get() = ql == QueryLanguage.JSONATA || !known

        /** Where Step Functions evaluates `{%` strings in JSONata fields and free-form data. */
        val evaluatesJsonata: Boolean get() = inState && known && ql == QueryLanguage.JSONATA

        companion object {
            /** The context of the top level of [doc], whose `QueryLanguage` decides that of every state unless it sets its own. */
            fun topLevel(doc: AslDocument) = Context(doc.queryLanguage, doc.queryLanguageKnown, inState = false)

            fun of(state: AslState) = Context(state.queryLanguage, state.queryLanguageKnown, inState = true)
        }
    }

    private class Walk(val doc: AslDocument, val root: AslStateMachine, val out: MutableList<AslFinding>) {
        private val machines = IdentityHashMap<AslObject, AslStateMachine>().apply { for (m in doc.allMachines()) put(m.obj, m) }
        private val topLevelJsonata = doc.queryLanguageKnown && doc.queryLanguage == QueryLanguage.JSONATA

        fun run() {
            obj(root.obj, AslSpec.structure(StructureId.STATE_MACHINE), Context.topLevel(doc), root.obj.anchorPsi)
        }

        private fun report(problem: AslProblem, element: PsiElement, message: String) {
            out += AslFinding(problem, element, message)
        }

        /** Checks [o] as [structure]; [anchor] is where a missing field is reported. */
        private fun obj(o: AslObject, structure: StructureSpec, ctx: Context, anchor: PsiElement) {
            if (!uncertain(o)) {
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
                if (field.kind == ValueKind.FreeForm) freeForm(entry, structure, ctx)
                value(entry.value, field.kind, field.nullable, field.jsonata, ctx, entry.keyPsi, o, optional = !field.required && field.kind != ValueKind.StateName)
                if (structure.id == StructureId.TASK && field.kind == ValueKind.ResourceArn) {
                    ResourceChecks.certainText(entry.value, ctx)?.let(ResourceChecks::resourceProblem)?.let { report(INVALID_RESOURCE_ARN, entry.value.psi, it) }
                }
            }
            if (structure.id == StructureId.TASK) task(o, ctx, anchor)
        }

        /** Rules on a Task state that depend on its `Resource`. */
        private fun task(o: AslObject, ctx: Context, anchor: PsiElement) {
            val resource = o["Resource"]?.value?.let { ResourceChecks.certainText(it, ctx) }
            if (resource != null && ctx.known && !uncertain(o) && ResourceChecks.needsParameters(resource) && PAYLOAD_FIELDS.none { o[it] != null }) {
                val name = if (ctx.ql == QueryLanguage.JSONATA) "Arguments" else "Parameters"
                report(MISSING_REQUIRED_FIELD, anchor, "A service integration needs '$name', even when the call takes no parameters")
            }
            val credentials = o[CREDENTIALS]?.takeIf { !it.viaMerge } ?: return
            if (resource != null && ResourceChecks.isActivity(resource)) {
                report(INAPPLICABLE_FIELD, credentials.keyPsi, "'Credentials' cannot be used with an activity")
            }
            credentials(credentials, ctx)
        }

        /**
         * `Credentials` is a mapping with the role under `RoleArn`, or in a JSONPath state with a path or an intrinsic
         * function under `RoleArn.$`.
         */
        private fun credentials(entry: AslEntry, ctx: Context) {
            val node = entry.value
            if (exemptFromKind(node) || (node is AslScalar && ctx.jsonataPossible && isJsonata(node))) return
            if (node is AslScalar && node.type == ScalarType.NULL) return report(NULL_VALUE, node.psi, "The field is present but has no value")
            if (node !is AslObject) return wrongKind(node, "a mapping with the role under 'RoleArn'")
            for (field in node.entries) {
                if (field.viaMerge || isUnappliedMerge(field) || Placeholders.contains(field.key) || field.key == ROLE_ARN || field.key == ROLE_ARN_PATH) continue
                report(UNKNOWN_FIELD, field.keyPsi, "Unknown field '${field.key}'; 'Credentials' only takes 'RoleArn'")
            }
            val role = node[ROLE_ARN]
            val rolePath = node[ROLE_ARN_PATH]
            if (role == null && rolePath == null && !uncertain(node)) report(MISSING_REQUIRED_FIELD, entry.keyPsi, "Missing required field 'RoleArn'")
            if (role != null && rolePath != null && !rolePath.viaMerge) {
                report(EXCLUSIVE_FIELDS, rolePath.keyPsi, "'$ROLE_ARN_PATH' cannot be used together with '$ROLE_ARN'")
            }
            if (role != null && !role.viaMerge) roleArn(role.value, ctx)
            if (rolePath != null && !rolePath.viaMerge && ctx.known && ctx.ql == QueryLanguage.JSONPATH) pathOrIntrinsic(rolePath)
        }

        private fun roleArn(node: AslNode, ctx: Context) {
            if (exemptFromKind(node) || (node is AslScalar && ctx.jsonataPossible && isJsonata(node))) return
            if (node !is AslScalar || node.type != ScalarType.STRING) return wrongKind(node, "a string")
            if (!ResourceChecks.isRoleArn(node.text)) {
                report(INVALID_ROLE_ARN, node.psi, "Not an IAM role ARN; a role is written arn:partition:iam::account:role/name")
            }
        }

        /** The value of a key that ends in `.$` is evaluated, so it must be a path or the call of an intrinsic function. */
        private fun pathOrIntrinsic(entry: AslEntry) {
            val node = entry.value
            if (exemptFromKind(node)) return
            if (node !is AslScalar || node.type != ScalarType.STRING) return wrongKind(node, "a string with a path or an intrinsic function")
            if (!node.text.startsWith("$") && !INTRINSIC_CALL.containsMatchIn(node.text)) {
                report(INVALID_PATH, node.psi, "The value of '${entry.key}' must be a path that starts with $ or an intrinsic function such as States.Format(...)")
            }
        }

        /** Rules on fields whose content is free-form data. */
        private fun freeForm(entry: AslEntry, structure: StructureSpec, ctx: Context) {
            val node = entry.value
            val rejectsNull = entry.key in NEVER_NULL || (structure.id in NEEDS_PAYLOAD && entry.key in PAYLOAD_FIELDS)
            if (rejectsNull && node is AslScalar && node.type == ScalarType.NULL && !node.ambiguous) {
                report(NULL_VALUE, node.psi, "The field is present but has no value")
            }
            if (ctx.known && ctx.ql == QueryLanguage.JSONPATH && entry.key in PAYLOAD_TEMPLATES) payloadTemplate(node)
        }

        private fun payloadTemplate(node: AslNode) {
            when (node) {
                is AslObject -> for (entry in node.entries) {
                    if (entry.viaMerge || isUnappliedMerge(entry) || Placeholders.contains(entry.key)) continue
                    if (entry.key.endsWith(".$")) pathOrIntrinsic(entry) else payloadTemplate(entry.value)
                }
                is AslArray -> for (item in node.items) payloadTemplate(item)
                else -> {}
            }
        }

        /**
         * Checks that [node] fits [kind] and, if it does, descends into it. [jsonata] tells whether the field evaluates
         * JSONata; [owner] is the object holding the field. [optional] is true for the direct value of a field that is
         * neither required nor a transition target, where a null value is reported as such and not as a wrong kind.
         */
        private fun value(
            node: AslNode, kind: ValueKind, nullable: Boolean, jsonata: Boolean, ctx: Context, anchor: PsiElement, owner: AslObject,
            optional: Boolean = false,
        ) {
            if (kind == ValueKind.FreeForm) {
                if (ctx.evaluatesJsonata) freeFormDelimiters(node)
                return
            }
            if (exemptFromKind(node)) return
            if (node is AslScalar && ctx.jsonataPossible && isJsonata(node)) {
                if (jsonata && ctx.evaluatesJsonata) unclosed(node)
                return
            }
            if (!fitsKind(node, kind, nullable)) {
                if (optional && node is AslScalar && node.type == ScalarType.NULL) {
                    report(NULL_VALUE, node.psi, "The field is present but has no value")
                } else {
                    wrongKind(node, expectedText(kind))
                }
                return
            }
            if (node is AslScalar && jsonata && ctx.evaluatesJsonata) misplaced(node)
            if (kind == ValueKind.Integer && node is AslScalar && notWhole(node)) wrongKind(node, "a whole number, written without a decimal point or an exponent")
            when (kind) {
                is ValueKind.Struct -> obj(node as AslObject, AslSpec.structure(kind.id), ctx, anchor)
                is ValueKind.ListOf ->
                    for (item in (node as AslArray).items) value(item, kind.element, false, jsonata, ctx, (item as? AslObject)?.anchorPsi ?: item.psi, owner)
                ValueKind.StatesMap -> machines[owner]?.let(::states)
                else -> {}
            }
        }

        /**
         * AWS takes only a number written with digits alone as a whole number, so it rejects `1.5` and also `5.0`, `1e2`
         * and `500e-1`. A JSON file reaches AWS as it is written. A YAML file is converted first, and the tools differ:
         * some turn `5.0` into `5`, others keep it. So in YAML only a number with a real fraction is certain to be
         * rejected.
         */
        private fun notWhole(scalar: AslScalar): Boolean {
            val fraction = scalar.text.toBigDecimalOrNull()?.let { it.stripTrailingZeros().scale() > 0 } ?: return false
            return fraction || (scalar.psi.containingFile is JsonFile && scalar.text.any { it in ".eE" })
        }

        private fun expectedText(kind: ValueKind): String = when (kind) {
            is ValueKind.Struct -> "a mapping"
            is ValueKind.ListOf -> "a list"
            ValueKind.StatesMap -> "a mapping of states"
            ValueKind.ErrorNames -> "a list of error names"
            ValueKind.Integer, ValueKind.Number -> "a number"
            ValueKind.Bool -> "true or false"
            else -> "a string"
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
            val ctx = Context.of(state)
            val typeEntry = o["Type"]?.takeIf { !it.viaMerge }
            val typeValue = typeEntry?.value as? AslScalar
            if (state.type == null && typeValue != null && typeValue.type == ScalarType.STRING && !typeValue.ambiguous) {
                report(UNKNOWN_STATE_TYPE, typeValue.psi, "Unknown state type '${typeValue.text}'")
            }
            obj(o, AslSpec.structure(state.type?.structure ?: StructureId.STATE), ctx, state.entry.keyPsi)

            val type = state.type
            if (type != null && type.needsTransition && !uncertain(o) && o["Next"] == null) {
                val end = o["End"]
                if (end == null) {
                    report(MISSING_TRANSITION, state.entry.keyPsi, "A ${type.name} state needs 'Next' or 'End: true'")
                } else if (!mayEnd(end)) {
                    // AWS accepts this as long as another state ends the machine, so it is not an error.
                    val element = if (end.viaMerge) state.entry.keyPsi else end.value.psi
                    report(END_FALSE_WITHOUT_NEXT, element, "With 'End: false' and no 'Next', the state neither ends the execution nor continues")
                }
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
                if (rule !is AslObject || uncertain(rule)) continue
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
                is AslScalar -> when {
                    node.ambiguous || Placeholders.contains(node.text) -> {}
                    isJsonata(node) -> unclosed(node)
                    else -> misplaced(node)
                }
                is AslUnresolved -> {}
            }
        }

        private fun unclosed(scalar: AslScalar) {
            val text = scalar.text
            if (text.endsWith("%}")) return
            val message = if (text.trimEnd('\n', '\r').endsWith("%}")) {
                "A JSONata expression must end with %}, but this value ends with a line break; in a YAML block scalar, >- or |- removes it"
            } else {
                "A JSONata expression must end with %}"
            }
            report(UNCLOSED_JSONATA_DELIMITER, scalar.psi, message)
        }

        /** A string that holds JSONata delimiters but does not start with `{%` is plain text, which is probably not meant. */
        private fun misplaced(scalar: AslScalar) {
            val text = scalar.text
            if (scalar.type != ScalarType.STRING || text.startsWith("{%") || Placeholders.contains(text)) return
            if ("{%" in text || "%}" in text) {
                report(MISPLACED_JSONATA_DELIMITER, scalar.psi, "Step Functions evaluates JSONata only in a value that starts with {% and ends with %}; this is plain text")
            }
        }
    }

    /** A state whose type is a placeholder is not checked at all. */
    private fun skipped(state: AslState): Boolean = state.typeName?.let(Placeholders::contains) == true

    /** Placeholders, ambiguous scalars, unresolved aliases and tagged mappings or lists are never checked for kind. */
    internal fun exemptFromKind(node: AslNode): Boolean = when (node) {
        is AslUnresolved -> true
        is AslScalar -> node.ambiguous || Placeholders.contains(node.text)
        else -> false
    }

    /**
     * Whether the basic kind of [node] fits [kind]: a mapping for a structure or the states, a list for a list or error
     * names, and for scalar kinds a scalar of the matching type, or `null` where the field is [nullable].
     */
    internal fun fitsKind(node: AslNode, kind: ValueKind, nullable: Boolean): Boolean {
        val expected = when (kind) {
            ValueKind.FreeForm -> return true
            is ValueKind.Struct, ValueKind.StatesMap -> return node is AslObject
            is ValueKind.ListOf, ValueKind.ErrorNames -> return node is AslArray
            ValueKind.Text, ValueKind.StateName, ValueKind.ResourceArn, is ValueKind.OneOf -> ScalarType.STRING
            ValueKind.Integer, ValueKind.Number -> ScalarType.NUMBER
            ValueKind.Bool -> ScalarType.BOOLEAN
        }
        return node is AslScalar && (node.type == expected || (node.type == ScalarType.NULL && nullable))
    }

    internal fun isJsonata(scalar: AslScalar) = scalar.type == ScalarType.STRING && scalar.text.startsWith("{%")

    /** `End` counts as possibly true unless it is the Boolean `false`; a placeholder or ambiguous value stays possible. */
    internal fun mayEnd(end: AslEntry?): Boolean {
        val value = end?.value ?: return false
        return !(value is AslScalar && value.type == ScalarType.BOOLEAN && !value.ambiguous && value.text.equals("false", ignoreCase = true))
    }

    /**
     * Whether the file leaves open which fields [o] has: an unapplied merge or a placeholder key may supply any field,
     * so no rule may report a field of [o] as absent.
     */
    internal fun uncertain(o: AslObject) = leavesFieldsOpen(o)
}
