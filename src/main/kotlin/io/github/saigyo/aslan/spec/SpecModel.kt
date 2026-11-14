package io.github.saigyo.aslan.spec

/** The query languages a state can use, with the exact text ASL uses for each. */
enum class QueryLanguage(val text: String) { JSONPATH("JSONPath"), JSONATA("JSONata") }

/** In which query language a field may appear. */
enum class Mode {
    BOTH, JSONATA, JSONPATH;

    fun allows(ql: QueryLanguage): Boolean = when (this) {
        BOTH -> true
        JSONATA -> ql == QueryLanguage.JSONATA
        JSONPATH -> ql == QueryLanguage.JSONPATH
    }
}

/** Every kind of object ASL defines. */
enum class StructureId {
    STATE_MACHINE,
    /** A state whose type is missing, unknown or a placeholder: only the fields every state has. */
    STATE,
    TASK, PASS, CHOICE, WAIT, SUCCEED, FAIL, PARALLEL, MAP,
    CHOICE_RULE, RETRIER, CATCHER, BRANCH, ITEM_PROCESSOR, PROCESSOR_CONFIG,
    ITEM_READER, READER_CONFIG, ITEM_BATCHER, RESULT_WRITER, WRITER_CONFIG,
}

/** What kind of value a field takes. */
sealed interface ValueKind {
    data object Text : ValueKind
    data object Integer : ValueKind
    data object Number : ValueKind
    data object Bool : ValueKind
    data class OneOf(val values: List<NamedDoc>) : ValueKind
    /** A transition target. */
    data object StateName : ValueKind
    /** A mapping of state name to state. */
    data object StatesMap : ValueKind
    /** A list of error name strings. */
    data object ErrorNames : ValueKind
    data object ResourceArn : ValueKind
    /** Any data; never checked, never descended into. */
    data object FreeForm : ValueKind
    data class Struct(val id: StructureId) : ValueKind
    data class ListOf(val element: ValueKind) : ValueKind
}

/** A named item (an enumeration value, error name or resource ARN) with a short description. */
data class NamedDoc(val name: String, val description: String, val docUrl: String? = null)

data class FieldSpec(
    val name: String,
    val kind: ValueKind,
    val mode: Mode = Mode.BOTH,
    val required: Boolean = false,
    /** AWS documents that this field accepts a `{% %}` string in a JSONata state. */
    val jsonata: Boolean = false,
    val nullable: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val deprecated: Boolean = false,
    val description: String,
    val docUrl: String,
)

data class StructureSpec(
    val id: StructureId,
    val fields: List<FieldSpec>,
    /** At least one field of each set must be present. */
    val oneOfRequired: List<Set<String>> = emptyList(),
    /** At most one field of each set may be present. */
    val exclusive: List<Set<String>> = emptyList(),
) {
    private val byName = fields.associateBy { it.name }

    init {
        require(byName.size == fields.size) { "$id defines a field twice" }
        for (set in oneOfRequired + exclusive) require(set.all { it in byName }) { "$id groups an undefined field: $set" }
    }

    fun field(name: String): FieldSpec? = byName[name]

    fun fieldsFor(ql: QueryLanguage): List<FieldSpec> = fields.filter { it.mode.allows(ql) }
}

data class StateTypeSpec(
    val name: String,
    val structure: StructureId,
    val needsTransition: Boolean,
    val terminal: Boolean,
    val description: String,
    val docUrl: String,
)
