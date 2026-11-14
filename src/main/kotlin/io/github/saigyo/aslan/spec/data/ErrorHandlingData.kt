package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.Mode
import io.github.saigyo.aslan.spec.NamedDoc
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind

private fun errorEqualsField(what: String) = FieldSpec("ErrorEquals", ValueKind.ErrorNames, required = true,
    description = "Lists which errors trigger this $what; give at least one name, and if you use States.ALL, put it on its own.",
    docUrl = Doc.ERRORS)

internal val errorHandlingStructures = listOf(
    StructureSpec(StructureId.RETRIER, listOf(
        errorEqualsField("retry rule"),
        FieldSpec("IntervalSeconds", ValueKind.Integer, min = 1.0, max = 99_999_999.0,
            description = "The delay in seconds before retrying the first time, which BackoffRate then stretches; a positive integer, 1 by default.", docUrl = Doc.ERRORS),
        FieldSpec("MaxAttempts", ValueKind.Integer, min = 0.0, max = 99_999_999.0,
            description = "The retry budget for the listed errors: 3 by default, and 0 turns retrying off for them.",
            docUrl = Doc.ERRORS),
        FieldSpec("BackoffRate", ValueKind.Number, min = 1.0,
            description = "Multiplies the delay after every retry, so 2.0, the default, doubles it each time; must be at least 1.0.", docUrl = Doc.ERRORS),
        FieldSpec("MaxDelaySeconds", ValueKind.Integer, min = 1.0, max = 31_622_400.0,
            description = "Caps the delay between retries at this many seconds, however far BackoffRate would push it.",
            docUrl = Doc.ERRORS),
        FieldSpec("JitterStrategy", ValueKind.OneOf(listOf(
            NamedDoc("FULL", "Each wait is a random time between zero and the computed interval.", Doc.ERRORS),
            NamedDoc("NONE", "Each wait is exactly the computed interval; the default.", Doc.ERRORS),
        )), description = "Set to FULL to draw each retry delay at random below the computed value, which spreads out retries from many executions.",
            docUrl = Doc.ERRORS),
        commentField(Doc.ERRORS, "this retry rule"),
    )),

    StructureSpec(StructureId.CATCHER, listOf(
        errorEqualsField("fallback rule"),
        FieldSpec("Next", ValueKind.StateName, required = true,
            description = "The state to continue with when this rule catches an error.", docUrl = Doc.ERRORS),
        assignField(Doc.ERRORS),
        FieldSpec("Output", ValueKind.FreeForm, Mode.JSONATA, jsonata = true,
            description = "JSONata only. The input for the Next state when this rule catches an error; it can read \$states.errorOutput.",
            docUrl = Doc.ERRORS),
        FieldSpec("ResultPath", ValueKind.Text, Mode.JSONPATH, nullable = true,
            description = "JSONPath only. A reference path saying where in the state input the error output is placed; \$ when absent.",
            docUrl = Doc.ERRORS),
        commentField(Doc.ERRORS, "this fallback rule"),
    )),
)
