package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.NamedDoc

private fun errorName(name: String, description: String) = NamedDoc(name, description, Doc.ERRORS)

/** The predefined error names, all starting with `States.`. */
internal val predefinedErrorNames = listOf(
    errorName("States.ALL", "Wildcard for catch-all handling of almost every error. List it on its own, in the final Retry or Catch rule; " +
        "payload-size and runtime errors still slip past it."),
    errorName("States.BranchFailed", "Raised by a Parallel state when one of its branches ends in failure; catch it to recover from any branch."),
    errorName("States.DataLimitExceeded", "Raised when data passed into or out of a state, or returned by a service, is too large. " +
        "The wildcard does not cover it, so name it explicitly to handle it."),
    errorName("States.ExceedToleratedFailureThreshold", "Raised by a Map state once its failed items go past the limit set by " +
        "ToleratedFailureCount or ToleratedFailurePercentage."),
    errorName("States.HeartbeatTimeout", "Raised when the worker behind a Task state stays silent for longer than HeartbeatSeconds allows."),
    errorName("States.Http.Socket", "Raised when an HTTP task gets no answer within its 60-second limit."),
    errorName("States.IntrinsicFailure", "Raised in a JSONPath state when a call such as States.Format inside a payload template goes wrong."),
    errorName("States.ItemReaderFailed", "Raised by a Map state when reading its items through ItemReader goes wrong."),
    errorName("States.NoChoiceMatched", "Raised by a Choice state that has no Default when none of its rules is true for the input."),
    errorName("States.ParameterPathFailure", "Raised in a JSONPath state when a .\$ key in Parameters or a similar template points at data that is not there."),
    errorName("States.Permissions", "Raised when the execution role lacks the rights a Task state needs to call its resource."),
    errorName("States.QueryEvaluationError", "Raised in a JSONata state when an expression throws, yields nothing, " +
        "or yields a value the field cannot use."),
    errorName("States.ResultPathMatchFailure", "Raised in a JSONPath state when the result cannot be inserted at the location ResultPath names."),
    errorName("States.ResultWriterFailed", "Raised by a Map state when storing its results through ResultWriter goes wrong."),
    errorName("States.Runtime", "Stops the execution on an unexpected internal problem, such as a path applied to null input. " +
        "Retry and Catch rules, even with the wildcard, cannot handle it."),
    errorName("States.TaskFailed", "Raised when the work behind a Task state goes wrong. As a match, it covers every error except a timeout."),
    errorName("States.Timeout", "Raised when a Task state exceeds TimeoutSeconds or its heartbeat interval; also ends a whole execution " +
        "that runs past the top-level TimeoutSeconds."),
)
