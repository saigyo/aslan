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
            description = "The version of the Amazon States Language the definition is written in; \"1.0\" when absent.",
            docUrl = Doc.STRUCTURE),
        FieldSpec("TimeoutSeconds", ValueKind.Integer, min = 1.0,
            description = "The longest time in seconds an execution may run before it fails with States.Timeout.",
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
