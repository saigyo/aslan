package io.github.saigyo.aslan.check

import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.check.AslProblem.CHOICE_WITHOUT_DEFAULT
import io.github.saigyo.aslan.check.AslProblem.DUPLICATE_MAP_LABEL
import io.github.saigyo.aslan.check.AslProblem.EMPTY_LIST
import io.github.saigyo.aslan.check.AslProblem.EXCLUSIVE_FIELDS
import io.github.saigyo.aslan.check.AslProblem.HEARTBEAT_NOT_BELOW_TIMEOUT
import io.github.saigyo.aslan.check.AslProblem.INAPPLICABLE_FIELD
import io.github.saigyo.aslan.check.AslProblem.INCOMPLETE_CONFIGURATION
import io.github.saigyo.aslan.check.AslProblem.INVALID_MAP_LABEL
import io.github.saigyo.aslan.check.AslProblem.INVALID_PATH
import io.github.saigyo.aslan.check.AslProblem.INVALID_TIMESTAMP
import io.github.saigyo.aslan.check.AslProblem.JSONATA_IN_STATIC_FIELD
import io.github.saigyo.aslan.check.AslProblem.MALFORMED_CHOICE_RULE
import io.github.saigyo.aslan.check.AslProblem.MISPLACED_FIELD
import io.github.saigyo.aslan.check.AslProblem.STATES_ALL_PLACEMENT
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_ENUM_VALUE
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_FIELD
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_PREDEFINED_ERROR
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_SERVICE_INTEGRATION
import io.github.saigyo.aslan.check.AslProblem.UNREACHABLE_STATE
import io.github.saigyo.aslan.check.AslProblem.VALUE_OUT_OF_RANGE
import io.github.saigyo.aslan.check.StructureChecks.Context
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.model.isUnappliedMerge
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.Mode
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslEntry
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType
import java.util.IdentityHashMap

/** Checks for probable mistakes in definitions that deploy, or whose rejection no AWS source documents. */
internal object WarningChecks {
    fun check(doc: AslDocument, out: MutableList<AslFinding>) {
        val root = doc.root ?: return
        Walk(doc, root, out).apply { run(); duplicateLabels() }
        for (machine in doc.allMachines()) unreachable(machine, out)
    }

    /**
     * Walks the definition along the reference data, the way [StructureChecks] does, and checks each object against its
     * structure. Values whose kind does not fit their field, and fields in the wrong query language, are not looked into.
     */
    private class Walk(val doc: AslDocument, val root: AslStateMachine, val out: MutableList<AslFinding>) {
        private val machines = IdentityHashMap<AslObject, AslStateMachine>().apply { for (m in doc.allMachines()) put(m.obj, m) }

        /** The `Label` values of Map states, by the element they are written in, so an aliased state counts once. */
        private val labels = LinkedHashMap<PsiElement, AslScalar>()

        fun run() = obj(root.obj, AslSpec.structure(StructureId.STATE_MACHINE), Context.topLevel(doc), root.obj.anchorPsi)

        /**
         * A Map label must be unique in the definition; every use after the first is reported. AWS documents this as a
         * rejection, so unlike the rest of this walk it is an error.
         */
        fun duplicateLabels() {
            for (group in labels.values.sortedBy { it.psi.textRange.startOffset }.groupBy { it.text }.values) {
                for (label in group.drop(1)) report(DUPLICATE_MAP_LABEL, label.psi, "Label '${label.text}' is already used by another Map state")
            }
        }

        private fun report(problem: AslProblem, element: PsiElement, message: String) {
            out += AslFinding(problem, element, message)
        }

        /** Checks [o] as [structure]; [anchor] is where a missing field is reported. */
        private fun obj(o: AslObject, structure: StructureSpec, ctx: Context, anchor: PsiElement) {
            if (structure.id != StructureId.STATE) fieldNames(o, structure)
            exclusive(o, structure, ctx)
            for (entry in o.entries) {
                if (entry.viaMerge || isUnappliedMerge(entry)) continue
                val field = structure.field(entry.key) ?: continue
                if (ctx.known && !field.mode.allows(ctx.ql)) continue
                if (field.lenient) continue
                if (field.name in NON_EMPTY_LISTS && (entry.value as? AslArray)?.items?.isEmpty() == true) {
                    report(EMPTY_LIST, entry.keyPsi, "'${field.name}' needs at least one entry")
                }
                value(entry.value, field, field.kind, ctx, direct = true, owner = o, anchor = entry.keyPsi)
                if (structure.id == StructureId.TASK && field.kind == ValueKind.ResourceArn) {
                    ResourceChecks.certainText(entry.value, ctx)?.let(ResourceChecks::integrationProblem)
                        ?.let { report(UNKNOWN_SERVICE_INTEGRATION, entry.value.psi, it) }
                }
            }
            when (structure.id) {
                StructureId.TASK -> heartbeat(o)
                StructureId.CHOICE_RULE -> choiceRule(o, ctx)
                StructureId.ITEM_BATCHER, StructureId.RESULT_WRITER, StructureId.PROCESSOR_CONFIG, StructureId.READER_CONFIG ->
                    configuration(o, structure.id, anchor)
                else -> {}
            }
        }

        /** Keys the structure does not define: misplaced when another structure defines them, else unknown. */
        private fun fieldNames(o: AslObject, structure: StructureSpec) {
            for (entry in o.entries) {
                if (entry.viaMerge || isUnappliedMerge(entry) || structure.field(entry.key) != null || Placeholders.contains(entry.key)) continue
                if (AslSpec.definesFieldAnywhere(entry.key)) {
                    report(MISPLACED_FIELD, entry.keyPsi, "'${entry.key}' does not belong here")
                } else {
                    report(UNKNOWN_FIELD, entry.keyPsi, "Unknown field '${entry.key}'")
                }
            }
        }

        /**
         * Reports every field of an exclusive set after the first present one. `End` counts only when it is certainly
         * true, and a field in the wrong query language not at all, since it is reported as such.
         */
        private fun exclusive(o: AslObject, structure: StructureSpec, ctx: Context) {
            for (set in structure.exclusive) {
                val present = o.entries.filter {
                    it.key in set && !(ctx.known && structure.field(it.key)?.mode?.allows(ctx.ql) == false) &&
                        (it.key != "End" || certainlyTrue(it.value))
                }.distinctBy { it.key }
                if (present.size < 2) continue
                for (entry in present.drop(1)) {
                    if (!entry.viaMerge) report(EXCLUSIVE_FIELDS, entry.keyPsi, "'${entry.key}' cannot be used together with '${present.first().key}'")
                }
            }
        }

        /**
         * Checks [node] as a value of [kind] in [field]; [direct] is true for the field's own value, false for an item of
         * a list it holds. [owner] is the object holding the field, [anchor] where a field missing in [node] is reported.
         */
        private fun value(node: AslNode, field: FieldSpec, kind: ValueKind, ctx: Context, direct: Boolean, owner: AslObject, anchor: PsiElement) {
            if (kind == ValueKind.FreeForm || StructureChecks.exemptFromKind(node)) return
            if (node is AslScalar && ctx.jsonataPossible && StructureChecks.isJsonata(node)) {
                if (direct && isScalarKind(kind) && !field.jsonata && field.name != "Type" && ctx.known && ctx.ql == QueryLanguage.JSONATA) {
                    report(JSONATA_IN_STATIC_FIELD, node.psi, "'${field.name}' does not evaluate JSONata, so this is taken as plain text")
                }
                return
            }
            if (!StructureChecks.fitsKind(node, kind, direct && field.nullable)) return
            when (kind) {
                is ValueKind.Struct -> obj(node as AslObject, AslSpec.structure(kind.id), ctx, anchor)
                is ValueKind.ListOf -> {
                    val items = (node as AslArray).items
                    for (item in items) value(item, field, kind.element, ctx, direct = false, owner = owner, anchor = (item as? AslObject)?.anchorPsi ?: item.psi)
                    val element = kind.element
                    if (element is ValueKind.Struct && (element.id == StructureId.RETRIER || element.id == StructureId.CATCHER)) statesAll(items)
                }
                ValueKind.StatesMap -> machines[owner]?.let(::states)
                is ValueKind.OneOf -> {
                    val text = (node as AslScalar).text
                    if (node.type == ScalarType.STRING && field.name != "Type" && kind.values.none { it.name == text }) {
                        report(UNKNOWN_ENUM_VALUE, node.psi, "'$text' is not one of ${kind.values.joinToString { it.name }}")
                    }
                }
                ValueKind.Integer, ValueKind.Number -> range(node as AslScalar, field)
                ValueKind.ErrorNames -> errorNames(node as AslArray)
                ValueKind.Text -> if (direct) text(node as AslScalar, field)
                else -> {}
            }
        }

        /** Rules on the text of paths, timestamps and Map labels. */
        private fun text(scalar: AslScalar, field: FieldSpec) {
            if (scalar.type != ScalarType.STRING) return
            val text = scalar.text
            when {
                field.mode == Mode.JSONPATH && (field.name.endsWith("Path") || field.name == "Variable") -> {
                    val valid = when (field.name) {
                        "ResultPath" -> text == "$" || text.startsWith("$.") || text.startsWith("$[")
                        "ErrorPath", "CausePath" -> text.startsWith("$") || text.startsWith("States.")
                        else -> text.startsWith("$")
                    }
                    if (!valid) report(INVALID_PATH, scalar.psi, if (field.name == "ResultPath") {
                        "'ResultPath' must be a reference path into the state input, such as $.result"
                    } else {
                        "'${field.name}' must be a path that starts with $"
                    })
                }
                field.name in TIMESTAMP_FIELDS ->
                    if (!RFC_3339.matches(text)) report(INVALID_TIMESTAMP, scalar.psi, "Expected an RFC 3339 timestamp such as 2026-03-14T01:59:00Z")
                field.name == "Label" -> {
                    labels.putIfAbsent(scalar.psi, scalar)
                    if (text.codePointCount(0, text.length) > MAX_LABEL_LENGTH) {
                        report(INVALID_MAP_LABEL, scalar.psi, "A Map label has at most $MAX_LABEL_LENGTH characters")
                    } else if (text.any(::forbiddenInLabel)) {
                        report(INVALID_MAP_LABEL, scalar.psi, "A Map label cannot contain whitespace, control characters or any of ?*<>{}[]:;,\\|^~$#%&`\"")
                    }
                }
            }
        }

        /** Custom error names cannot begin with `States.`, so such a name that is not predefined is probably mistyped. */
        private fun errorNames(list: AslArray) {
            for (item in list.items) {
                val name = certain(item) ?: continue
                if (name.startsWith("States.") && AslSpec.errorNames.none { it.name == name }) {
                    report(UNKNOWN_PREDEFINED_ERROR, item.psi, "'$name' is not a predefined error name")
                }
            }
        }

        /**
         * A JSONPath rule is either one Boolean combination (`And`, `Or` or `Not`) or a `Variable` with exactly one
         * comparison operator; rules nested in a combination must not have `Next`.
         */
        private fun choiceRule(rule: AslObject, ctx: Context) {
            if (!ctx.known || ctx.ql != QueryLanguage.JSONPATH) return
            for (key in listOf("And", "Or", "Not")) {
                val nested = when (val value = rule[key]?.takeIf { !it.viaMerge }?.value) {
                    is AslArray -> if (key == "Not") emptyList() else value.items
                    is AslObject -> if (key == "Not") listOf(value) else emptyList()
                    else -> emptyList()
                }
                for (child in nested) {
                    val next = (child as? AslObject)?.get("Next")?.takeIf { !it.viaMerge } ?: continue
                    report(MISPLACED_FIELD, next.keyPsi, "A rule nested in And, Or or Not cannot have 'Next'")
                }
            }
            // A rule written for JSONata is reported as such, not as a malformed JSONPath rule.
            if (StructureChecks.uncertain(rule) || rule.entries.any { CHOICE_RULE.field(it.key)?.mode == Mode.JSONATA }) return
            val combinations = rule.entries.count { it.key in COMBINATIONS }
            val operators = rule.entries.count { it.key in COMPARISONS }
            val variable = rule["Variable"] != null
            val valid = if (combinations > 0) combinations == 1 && operators == 0 && !variable else variable && operators == 1
            if (!valid) {
                report(MALFORMED_CHOICE_RULE, rule.anchorPsi, "A rule needs either one of And, Or, Not, or a Variable with exactly one comparison operator")
            }
        }

        /** Fields that the specification or the developer guide requires or excludes in certain configurations. */
        private fun configuration(o: AslObject, id: StructureId, anchor: PsiElement) {
            val missing = when (id) {
                StructureId.ITEM_BATCHER ->
                    if (BATCH_LIMITS.none { o[it] != null }) "An ItemBatcher needs at least one of ${BATCH_LIMITS.joinToString()}" else null
                StructureId.RESULT_WRITER ->
                    if (o["Resource"] == null && o["WriterConfig"] == null) "A ResultWriter needs 'Resource', 'WriterConfig' or both" else null
                StructureId.PROCESSOR_CONFIG ->
                    if (certain(o, "Mode") == "DISTRIBUTED" && o["ExecutionType"] == null) "Distributed mode needs 'ExecutionType'" else null
                else -> readerConfig(o)
            }
            if (missing != null && !StructureChecks.uncertain(o)) report(INCOMPLETE_CONFIGURATION, anchor, missing)
        }

        /** Returns what a ReaderConfig misses, and reports the fields that do not apply to its input type. */
        private fun readerConfig(o: AslObject): String? {
            val inputType = certain(o, "InputType")
            val inputTypeEntry = o["InputType"]?.takeIf { !it.viaMerge }
            if (certain(o, "ManifestType") == "S3_INVENTORY" && inputTypeEntry != null) {
                report(INAPPLICABLE_FIELD, inputTypeEntry.keyPsi, "'InputType' cannot be given with an S3_INVENTORY manifest")
            }
            if (inputType != null) {
                for ((key, types) in READER_FIELD_INPUT_TYPES) {
                    val entry = o[key]?.takeIf { !it.viaMerge } ?: continue
                    if (inputType !in types) report(INAPPLICABLE_FIELD, entry.keyPsi, "'$key' only applies when InputType is ${types.joinToString(" or ")}")
                }
            }
            return when {
                certain(o, "Transformation") == "LOAD_AND_FLATTEN" && o["InputType"] == null -> "LOAD_AND_FLATTEN needs 'InputType'"
                certain(o, "CSVHeaderLocation") == "GIVEN" && o["CSVHeaders"] == null -> "CSVHeaderLocation GIVEN needs 'CSVHeaders'"
                else -> null
            }
        }

        private fun range(scalar: AslScalar, field: FieldSpec) {
            if (scalar.type != ScalarType.NUMBER) return
            val number = scalar.text.toDoubleOrNull() ?: return
            val min = field.min
            val max = field.max
            if (min != null && number < min) report(VALUE_OUT_OF_RANGE, scalar.psi, "'${field.name}' must be at least ${format(min)}")
            if (max != null && number > max) report(VALUE_OUT_OF_RANGE, scalar.psi, "'${field.name}' must be at most ${format(max)}")
        }

        /** `States.ALL` must stand alone in its `ErrorEquals` and in the last retrier or catcher of its list. */
        private fun statesAll(items: List<AslNode>) {
            for ((index, item) in items.withIndex()) {
                val errorEquals = (item as? AslObject)?.get("ErrorEquals")?.takeIf { !it.viaMerge }?.value as? AslArray ?: continue
                for (name in errorEquals.items) {
                    if (name !is AslScalar || name.type != ScalarType.STRING || name.ambiguous || name.text != "States.ALL") continue
                    if (errorEquals.items.size > 1) {
                        report(STATES_ALL_PLACEMENT, name.psi, "States.ALL must be the only error name in its list")
                    } else if (index < items.lastIndex) {
                        report(STATES_ALL_PLACEMENT, name.psi, "A rule with States.ALL must be the last one in its list")
                    }
                }
            }
        }

        private fun heartbeat(o: AslObject) {
            val heartbeatEntry = o["HeartbeatSeconds"]?.takeIf { !it.viaMerge } ?: return
            val heartbeat = number(heartbeatEntry) ?: return
            val timeout = o["TimeoutSeconds"]?.let(::number) ?: return
            if (heartbeat >= timeout) {
                report(HEARTBEAT_NOT_BELOW_TIMEOUT, heartbeatEntry.value.psi, "'HeartbeatSeconds' should be smaller than 'TimeoutSeconds'")
            }
        }

        private fun number(entry: AslEntry): Double? {
            val scalar = entry.value as? AslScalar ?: return null
            if (scalar.type != ScalarType.NUMBER || StructureChecks.exemptFromKind(scalar)) return null
            return scalar.text.toDoubleOrNull()
        }

        private fun states(machine: AslStateMachine) {
            for (state in machine.states) if (!state.entry.viaMerge) state(state)
        }

        private fun state(state: AslState) {
            val o = state.obj ?: return
            if (state.typeName?.let(Placeholders::contains) == true) return
            obj(o, AslSpec.structure(state.type?.structure ?: StructureId.STATE), Context.of(state), state.entry.keyPsi)
            if (state.type?.structure == StructureId.CHOICE && o["Default"] == null && !StructureChecks.uncertain(o)) {
                report(CHOICE_WITHOUT_DEFAULT, state.entry.keyPsi, "Input that no rule accepts fails the execution with States.NoChoiceMatched")
            }
        }
    }

    /** States of [machine] that no chain of transitions from `StartAt` reaches; only judged when the whole flow is known. */
    private fun unreachable(machine: AslStateMachine, out: MutableList<AslFinding>) {
        if (machine.hasUnknownFlow()) return
        val start = (machine.startAt?.value as? AslScalar)?.text ?: return
        val reached = HashSet<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!reached.add(name)) continue
            val state = machine.state(name) ?: continue
            for (t in state.transitions) t.targetName?.let(queue::addLast)
        }
        val targeted = machine.states.flatMapTo(HashSet()) { state -> state.transitions.mapNotNull { it.targetName } }
        for (state in machine.states) {
            if (state.name !in reached && !state.entry.viaMerge) {
                val message = if (state.name in targeted) "Only unreachable states lead to '${state.name}'" else "No transition leads to '${state.name}'"
                out += AslFinding(UNREACHABLE_STATE, state.entry.keyPsi, message)
            }
        }
    }

    /** The text of a string that is certainly what it says: no placeholder, not ambiguous; null otherwise. */
    private fun certain(node: AslNode?): String? =
        (node as? AslScalar)?.takeIf { it.type == ScalarType.STRING && !StructureChecks.exemptFromKind(it) }?.text

    private fun certain(o: AslObject, key: String): String? = certain(o[key]?.value)

    private fun forbiddenInLabel(c: Char) = c.isWhitespace() || c.code <= 0x1f || c.code in 0x7f..0x9f || c in LABEL_FORBIDDEN

    private const val MAX_LABEL_LENGTH = 40
    private const val LABEL_FORBIDDEN = "?*<>{}[]:;,\\|^~$#%&`\""
    private val NON_EMPTY_LISTS = setOf("ErrorEquals", "And", "Or")
    private val COMBINATIONS = setOf("And", "Or", "Not")
    private val CHOICE_RULE = AslSpec.structure(StructureId.CHOICE_RULE)
    private val COMPARISONS: Set<String> = CHOICE_RULE.fields
        .filter { it.mode == Mode.JSONPATH && it.name != "Variable" && it.name !in COMBINATIONS }.map { it.name }.toSet()
    private val TIMESTAMP_FIELDS = setOf("Timestamp", "TimestampEquals", "TimestampLessThan", "TimestampGreaterThan",
        "TimestampLessThanEquals", "TimestampGreaterThanEquals")
    private val BATCH_LIMITS = listOf("MaxItemsPerBatch", "MaxItemsPerBatchPath", "MaxInputBytesPerBatch", "MaxInputBytesPerBatchPath")
    private val READER_FIELD_INPUT_TYPES = mapOf(
        "CSVDelimiter" to listOf("CSV", "MANIFEST"),
        "CSVHeaderLocation" to listOf("CSV", "MANIFEST"),
        "ItemsPointer" to listOf("JSON"),
    )

    /** RFC 3339 date and time with upper-case T and Z, as the specification requires. */
    private val RFC_3339 = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")

    private fun isScalarKind(kind: ValueKind) = when (kind) {
        ValueKind.Text, ValueKind.Integer, ValueKind.Number, ValueKind.Bool, ValueKind.StateName, ValueKind.ResourceArn, is ValueKind.OneOf -> true
        else -> false
    }

    private fun certainlyTrue(node: AslNode) =
        node is AslScalar && node.type == ScalarType.BOOLEAN && !node.ambiguous && node.text.equals("true", ignoreCase = true)

    private fun format(n: Double) = if (n == Math.floor(n)) n.toLong().toString() else n.toString()
}
