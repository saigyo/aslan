package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.ContextField
import io.github.saigyo.aslan.spec.NamedDoc
import io.github.saigyo.aslan.spec.StatesField

/** What `$states` itself is. */
internal val statesVariableDoc = NamedDoc("states", "The reserved variable of Step Functions with the fields input, result, errorOutput " +
    "and context. Which of them exist depends on the field the expression is in.", Doc.STATES_VARIABLE)

private fun states(field: StatesField, description: String) = NamedDoc(field.text, description, Doc.STATES_VARIABLE)

/** What each field of `$states` holds. */
internal val statesFieldDocs: Map<StatesField, NamedDoc> = listOf(
    states(StatesField.INPUT, "The input the state received when it was entered. It stays the same throughout the state and can be read " +
        "in every field that accepts a JSONata expression."),
    states(StatesField.RESULT, "The raw result of the call or of the nested workflow, when it succeeded. Only Task, Parallel and Map " +
        "states have it, and only where the result is already known, for example in Output and Assign."),
    states(StatesField.ERROR_OUTPUT, "The error output of a call or nested workflow that failed. Only Task, Parallel and Map states have it, " +
        "and only inside a Catch rule, in its Output and Assign."),
    states(StatesField.CONTEXT, "The context object, with facts about the running execution, the state machine, the current state and " +
        "the task token."),
).associateBy { StatesField.entries.first { f -> f.text == it.name } }


private fun field(name: String, description: String, vararg children: ContextField) = ContextField(name, description, children.toList())

/** The fields of `$states.context`. */
internal val contextObjectFields = listOf(
    field("Execution", "Facts about the current execution.",
        field("Id", "The ARN of the execution."),
        field("Input", "The input the execution was started with."),
        field("Name", "The name of the execution."),
        field("RoleArn", "The ARN of the IAM role the execution runs with."),
        field("StartTime", "When the execution started, as an ISO 8601 timestamp."),
        field("RedriveCount", "How many times the execution has been redriven."),
        field("RedriveTime", "When the execution was last redriven, as an ISO 8601 timestamp. Present only after a redrive."),
    ),
    field("State", "Facts about the state that is running.",
        field("EnteredTime", "When the state was entered, as an ISO 8601 timestamp."),
        field("Name", "The name of the state."),
        field("RetryCount", "How many retries of the state have happened so far."),
    ),
    field("StateMachine", "Facts about the state machine.",
        field("Id", "The ARN of the state machine."),
        field("Name", "The name of the state machine."),
    ),
    field("Task", "Facts about the running task.",
        field("Token", "The task token that a worker returns to report success or failure of a callback task."),
    ),
    field("Map", "Present only while a Map state runs an iteration. Read it in the ItemSelector of the Map state itself, " +
        "not inside the ItemProcessor.",
        field("Item", "The item of the current iteration.",
            field("Index", "The position of the item, counting from zero."),
            field("Key", "The key of the item. Only meaningful when the items come from a JSON object."),
            field("Value", "The item itself."),
            field("Source", "Where the item came from: STATE_DATA for the state input, otherwise the S3 URI of the bucket or object."),
        ),
    ),
)
