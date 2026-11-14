package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.Mode
import io.github.saigyo.aslan.spec.NamedDoc
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind

private fun errorEqualsField(what: String) = FieldSpec("ErrorEquals", ValueKind.ErrorNames, required = true,
    description = "The error names this $what handles, as a non-empty list; States.ALL matches any error and must stand alone.",
    docUrl = Doc.ERRORS)

internal val errorHandlingStructures = listOf(
    StructureSpec(StructureId.RETRIER, listOf(
        errorEqualsField("retry rule"),
        FieldSpec("IntervalSeconds", ValueKind.Integer, min = 1.0, max = 99_999_999.0,
            description = "Seconds to wait before the first retry, a positive integer; 1 when absent.", docUrl = Doc.ERRORS),
        FieldSpec("MaxAttempts", ValueKind.Integer, min = 0.0, max = 99_999_999.0,
            description = "How many retries to make at most; 3 when absent, and 0 means the errors are never retried.",
            docUrl = Doc.ERRORS),
        FieldSpec("BackoffRate", ValueKind.Number, min = 1.0,
            description = "The factor by which the wait grows after each retry, at least 1.0; 2.0 when absent.", docUrl = Doc.ERRORS),
        FieldSpec("MaxDelaySeconds", ValueKind.Integer, min = 1.0, max = 31_622_400.0,
            description = "An upper limit in seconds for the wait between retries, however large the backoff makes it.",
            docUrl = Doc.ERRORS),
        FieldSpec("JitterStrategy", ValueKind.OneOf(listOf(
            NamedDoc("FULL", "Each wait is a random time between zero and the computed interval.", Doc.ERRORS),
            NamedDoc("NONE", "Each wait is exactly the computed interval; the default.", Doc.ERRORS),
        )), description = "Whether to randomise the waits between retries, so that many callers do not retry at the same moment.",
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
