package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind

internal val stateMachineStructures = listOf(
    StructureSpec(StructureId.STATE_MACHINE, listOf(
        startAtField(Doc.STRUCTURE),
        statesField(Doc.STRUCTURE),
        commentField(Doc.STRUCTURE, "the state machine"),
        FieldSpec("Version", ValueKind.Text, lenient = true,
            description = "Which revision of the States Language this definition follows; 1.0, the only one so far, is assumed when absent.",
            docUrl = Doc.STRUCTURE),
        FieldSpec("TimeoutSeconds", ValueKind.Integer, min = 1.0,
            description = "A limit in seconds on the whole execution; going past it fails the execution with States.Timeout.",
            docUrl = Doc.STRUCTURE),
        FieldSpec("QueryLanguage", ValueKind.OneOf(queryLanguageValues),
            description = "The query language every state uses unless it sets its own: JSONPath, the default, or JSONata.",
            docUrl = Doc.STRUCTURE),
    )),

    StructureSpec(StructureId.BRANCH, listOf(
        startAtField(Doc.PARALLEL),
        statesField(Doc.PARALLEL),
        commentField(Doc.PARALLEL, "this branch"),
    )),
)
