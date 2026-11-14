package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.Mode
import io.github.saigyo.aslan.spec.NamedDoc
import io.github.saigyo.aslan.spec.StateTypeSpec
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind

/** Developer guide pages the reference data links to. */
internal object Doc {
    private const val BASE = "https://docs.aws.amazon.com/step-functions/latest/dg/"
    const val STRUCTURE = BASE + "statemachine-structure.html"
    const val TASK = BASE + "state-task.html"
    const val PASS = BASE + "state-pass.html"
    const val CHOICE = BASE + "state-choice.html"
    const val WAIT = BASE + "state-wait.html"
    const val SUCCEED = BASE + "state-succeed.html"
    const val FAIL = BASE + "state-fail.html"
    const val PARALLEL = BASE + "state-parallel.html"
    const val MAP = BASE + "state-map.html"
    const val MAP_INLINE = BASE + "state-map-inline.html"
    const val MAP_DISTRIBUTED = BASE + "state-map-distributed.html"
    const val ITEM_READER = BASE + "input-output-itemreader.html"
    const val ITEM_BATCHER = BASE + "input-output-itembatcher.html"
    const val RESULT_WRITER = BASE + "input-output-resultwriter.html"
    const val ERRORS = BASE + "concepts-error-handling.html"
    const val JSONATA = BASE + "transforming-data.html"
    fun page(name: String) = "$BASE$name.html"
}

internal val stateTypeSpecs = listOf(
    StateTypeSpec("Task", StructureId.TASK, needsTransition = true, terminal = false,
        "Calls whatever does the work, named in Resource: a Lambda function, an activity, an AWS service API or an HTTPS endpoint.", Doc.TASK),
    StateTypeSpec("Pass", StructureId.PASS, needsTransition = true, terminal = false,
        "Hands data on, unchanged or reshaped, without calling anything; handy for shaping data and for stubs.", Doc.PASS),
    StateTypeSpec("Choice", StructureId.CHOICE, needsTransition = false, terminal = false,
        "Picks the next state by testing its rules in order; the first rule that holds decides, otherwise Default.", Doc.CHOICE),
    StateTypeSpec("Wait", StructureId.WAIT, needsTransition = true, terminal = false,
        "Pauses the execution for a number of seconds or until a point in time, then continues.", Doc.WAIT),
    StateTypeSpec("Succeed", StructureId.SUCCEED, needsTransition = false, terminal = true,
        "A successful stopping point: reaching it completes the execution, or the Parallel branch or Map iteration it sits in.", Doc.SUCCEED),
    StateTypeSpec("Fail", StructureId.FAIL, needsTransition = false, terminal = true,
        "A stopping point that reports an error, with an optional name and cause; inside a branch or iteration, the enclosing state's Catch may handle it.", Doc.FAIL),
    StateTypeSpec("Parallel", StructureId.PARALLEL, needsTransition = true, terminal = false,
        "Runs several branches on the same input at the same time and outputs an array with one result per branch.", Doc.PARALLEL),
    StateTypeSpec("Map", StructureId.MAP, needsTransition = true, terminal = false,
        "Iterates over a collection, applying one sub-workflow to each item in Inline or Distributed mode, and gathers what each returns.", Doc.MAP),
)

internal val queryLanguageValues = listOf(
    NamedDoc("JSONPath", "Paths, payload templates and intrinsic functions select and shape data; the default.", Doc.JSONATA),
    NamedDoc("JSONata", "Expressions written as {% ... %} strings select and shape data, using Arguments and Output.", Doc.JSONATA),
)

internal fun typeField(url: String) = FieldSpec("Type", ValueKind.OneOf(stateTypeSpecs.map { NamedDoc(it.name, it.description, it.docUrl) }),
    required = true, description = "The kind of state, which decides what it does and which other fields it takes.", docUrl = url)

internal fun commentField(url: String, what: String = "this object") = FieldSpec("Comment", ValueKind.Text,
    description = "Notes about $what for people reading the definition; Step Functions ignores it.", docUrl = url)

internal fun queryLanguageField(url: String) = FieldSpec("QueryLanguage", ValueKind.OneOf(queryLanguageValues),
    description = "The query language of this state, JSONPath or JSONata; when absent, the state machine's top-level setting applies.",
    docUrl = url)

internal fun startAtField(url: String) = FieldSpec("StartAt", ValueKind.StateName, required = true,
    description = "The name of the state that runs first; it must be one of the states in the States field next to it.", docUrl = url)

internal fun statesField(url: String) = FieldSpec("States", ValueKind.StatesMap, required = true,
    description = "The states of this state machine, each under its own unique name.", docUrl = url)

internal fun assignField(url: String) = FieldSpec("Assign", ValueKind.FreeForm,
    description = "Stores values in workflow variables: each key names a variable and each value is what it gets; later states read them as \$name.",
    docUrl = url)

internal fun outputField(url: String) = FieldSpec("Output", ValueKind.FreeForm, Mode.JSONATA, jsonata = true,
    description = "JSONata only. Replaces what the state hands on; write any JSON, with {% %} expressions wherever values should be computed.",
    docUrl = url)

internal fun argumentsField(url: String, what: String) = FieldSpec("Arguments", ValueKind.FreeForm, Mode.JSONATA, jsonata = true,
    description = "JSONata only. What $what receives; write any JSON, with {% %} expressions wherever values should be computed.",
    docUrl = url)

internal fun parametersField(url: String, what: String, deprecated: Boolean = false) = FieldSpec("Parameters", ValueKind.FreeForm,
    Mode.JSONPATH, deprecated = deprecated,
    description = "JSONPath only. A payload template that builds the input passed to $what; keys ending in .\$ take their value from a path.",
    docUrl = url)

internal fun resultPathField(url: String) = FieldSpec("ResultPath", ValueKind.Text, Mode.JSONPATH, nullable = true,
    description = "JSONPath only. Decides how result and original input combine: a reference path to write the result into, \$ to replace the input, or null to discard the result.",
    docUrl = url)

private fun nextField(url: String) = FieldSpec("Next", ValueKind.StateName,
    description = "Where execution goes once this state is done, given as the name of a state in the same States object. Give Next or End, not both.", docUrl = url)

private fun endField(url: String) = FieldSpec("End", ValueKind.Bool,
    description = "true marks this as a last state, after which its execution, branch or iteration is over. Give Next or End, not both.", docUrl = url)

private fun inputPathField(url: String) = FieldSpec("InputPath", ValueKind.Text, Mode.JSONPATH, nullable = true,
    description = "JSONPath only. Narrows the input down to the part this state works with; \$, the default, keeps all of it and null drops it.",
    docUrl = url)

private fun outputPathField(url: String) = FieldSpec("OutputPath", ValueKind.Text, Mode.JSONPATH, nullable = true,
    description = "JSONPath only. Narrows what is handed to the next state, applied last, after ResultPath; \$, the default, hands on everything.",
    docUrl = url)

private fun resultSelectorField(url: String) = FieldSpec("ResultSelector", ValueKind.FreeForm, Mode.JSONPATH,
    description = "JSONPath only. A payload template applied to the raw result before ResultPath places it in the input.", docUrl = url)

private fun retryField(url: String) = FieldSpec("Retry", ValueKind.ListOf(ValueKind.Struct(StructureId.RETRIER)),
    description = "Retry rules, tried in order, that say which errors to retry, how often and with what delay.", docUrl = url)

private fun catchField(url: String) = FieldSpec("Catch", ValueKind.ListOf(ValueKind.Struct(StructureId.CATCHER)),
    description = "Error handlers checked after Retry has given up: the first one whose ErrorEquals matches sends execution to its Next state.", docUrl = url)

/** What a state type takes from the fields shared between state types. */
internal class Shared(
    val transition: Boolean = false,
    val assign: Boolean = false,
    val io: Boolean = false,
    val result: Boolean = false,
    val errors: Boolean = false,
)

/** The fields shared between state types, as [shared] selects them, followed by [own]. */
internal fun stateFields(url: String, shared: Shared, own: List<FieldSpec>, parametersFor: String = "the state"): List<FieldSpec> =
    buildList {
        add(typeField(url))
        add(commentField(url, "this state"))
        add(queryLanguageField(url))
        addAll(own)
        if (shared.transition) { add(nextField(url)); add(endField(url)) }
        if (shared.assign) add(assignField(url))
        if (shared.io) { add(outputField(url)); add(inputPathField(url)); add(outputPathField(url)) }
        if (shared.result) { add(parametersField(url, parametersFor)); add(resultPathField(url)) }
        if (shared.errors) { add(resultSelectorField(url)); add(retryField(url)); add(catchField(url)) }
    }

internal val nextOrEnd = setOf("Next", "End")
private val waitTimes = setOf("Seconds", "Timestamp", "SecondsPath", "TimestampPath")

internal val stateStructures = listOf(
    StructureSpec(StructureId.STATE, listOf(typeField(Doc.STRUCTURE), commentField(Doc.STRUCTURE, "this state"), queryLanguageField(Doc.STRUCTURE))),

    StructureSpec(StructureId.TASK, stateFields(Doc.TASK, Shared(transition = true, assign = true, io = true, result = true, errors = true), listOf(
        FieldSpec("Resource", ValueKind.ResourceArn, required = true,
            description = "The ARN or URI of the work to run: an activity, a Lambda function, a service integration or an HTTP task.",
            docUrl = Doc.TASK),
        argumentsField(Doc.TASK, "the resource"),
        FieldSpec("TimeoutSeconds", ValueKind.Integer, jsonata = true, min = 1.0,
            description = "A time budget in seconds for this task; when it is used up, the state fails with States.Timeout. Must be a positive integer.",
            docUrl = Doc.TASK),
        FieldSpec("TimeoutSecondsPath", ValueKind.Text, Mode.JSONPATH,
            description = "JSONPath only. A reference path to a positive integer in the input to use as TimeoutSeconds.", docUrl = Doc.TASK),
        FieldSpec("HeartbeatSeconds", ValueKind.Integer, jsonata = true, min = 1.0,
            description = "If the worker sends no heartbeat for this many seconds, the task fails with States.Timeout; keep it below TimeoutSeconds.",
            docUrl = Doc.TASK),
        FieldSpec("HeartbeatSecondsPath", ValueKind.Text, Mode.JSONPATH,
            description = "JSONPath only. A reference path to a positive integer in the input to use as HeartbeatSeconds.",
            docUrl = Doc.TASK),
        FieldSpec("Credentials", ValueKind.FreeForm,
            description = "Lets this one call run under a different IAM role: put the role ARN under RoleArn and the execution role switches to it first.",
            docUrl = Doc.TASK),
    ), parametersFor = "the resource"),
        exclusive = listOf(nextOrEnd, setOf("TimeoutSeconds", "TimeoutSecondsPath"), setOf("HeartbeatSeconds", "HeartbeatSecondsPath"))),

    StructureSpec(StructureId.PASS, stateFields(Doc.PASS, Shared(transition = true, assign = true, io = true, result = true), listOf(
        FieldSpec("Result", ValueKind.FreeForm, Mode.JSONPATH,
            description = "JSONPath only. A constant value the Pass state treats as its result; ResultPath decides where it ends up.",
            docUrl = Doc.PASS),
    )), exclusive = listOf(nextOrEnd)),

    StructureSpec(StructureId.CHOICE, stateFields(Doc.CHOICE, Shared(assign = true, io = true), listOf(
        FieldSpec("Choices", ValueKind.ListOf(ValueKind.Struct(StructureId.CHOICE_RULE)), required = true,
            description = "The rules to test, in order; the first rule that holds names the next state. At least one rule is needed.",
            docUrl = Doc.CHOICE),
        FieldSpec("Default", ValueKind.StateName,
            description = "Fallback target for input that no rule accepts; leave it out and such input fails the execution with States.NoChoiceMatched.",
            docUrl = Doc.CHOICE),
    ))),

    StructureSpec(StructureId.WAIT, stateFields(Doc.WAIT, Shared(transition = true, assign = true, io = true), listOf(
        FieldSpec("Seconds", ValueKind.Integer, jsonata = true, min = 0.0, max = 99_999_999.0,
            description = "The length of the pause in whole seconds, between 0 and 99,999,999.", docUrl = Doc.WAIT),
        FieldSpec("Timestamp", ValueKind.Text, jsonata = true,
            description = "Pauses until this point in time, written in RFC 3339 form such as 2026-03-14T01:59:00Z, with upper-case T and Z.",
            docUrl = Doc.WAIT),
        FieldSpec("SecondsPath", ValueKind.Text, Mode.JSONPATH,
            description = "JSONPath only. Takes the pause length in seconds from the input, at this reference path.",
            docUrl = Doc.WAIT),
        FieldSpec("TimestampPath", ValueKind.Text, Mode.JSONPATH,
            description = "JSONPath only. Takes the point in time to wait until from the input, at this reference path.",
            docUrl = Doc.WAIT),
    )), oneOfRequired = listOf(waitTimes), exclusive = listOf(nextOrEnd, waitTimes)),

    StructureSpec(StructureId.SUCCEED, stateFields(Doc.SUCCEED, Shared(io = true), emptyList())),

    StructureSpec(StructureId.FAIL, stateFields(Doc.FAIL, Shared(), listOf(
        FieldSpec("Error", ValueKind.Text, jsonata = true,
            description = "The name to fail with; a Catch on an enclosing Parallel or Map state can match on it.",
            docUrl = Doc.FAIL),
        FieldSpec("ErrorPath", ValueKind.Text, Mode.JSONPATH,
            description = "JSONPath only. A reference path or intrinsic function that yields the error name as a string.",
            docUrl = Doc.FAIL),
        FieldSpec("Cause", ValueKind.Text, jsonata = true,
            description = "Free text saying why the execution stopped, shown with the failure; keep secrets and internal details out of it.", docUrl = Doc.FAIL),
        FieldSpec("CausePath", ValueKind.Text, Mode.JSONPATH,
            description = "JSONPath only. A reference path or intrinsic function that yields the cause as a string.", docUrl = Doc.FAIL),
    )), exclusive = listOf(setOf("Error", "ErrorPath"), setOf("Cause", "CausePath"))),

    StructureSpec(StructureId.PARALLEL, stateFields(Doc.PARALLEL, Shared(transition = true, assign = true, io = true, result = true, errors = true), listOf(
        FieldSpec("Branches", ValueKind.ListOf(ValueKind.Struct(StructureId.BRANCH)), required = true,
            description = "The sub-workflows to start together on the same input; each one needs its own StartAt and States.",
            docUrl = Doc.PARALLEL),
        argumentsField(Doc.PARALLEL, "every branch"),
    ), parametersFor = "every branch"), exclusive = listOf(nextOrEnd)),
)
