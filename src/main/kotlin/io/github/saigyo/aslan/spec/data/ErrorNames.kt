package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.NamedDoc

private fun errorName(name: String, description: String) = NamedDoc(name, description, Doc.ERRORS)

/** The predefined error names, all starting with `States.`. */
internal val predefinedErrorNames = listOf(
    errorName("States.ALL", "Matches any error name except States.DataLimitExceeded and States.Runtime; must be the only name in its rule and in the last rule."),
    errorName("States.BranchFailed", "A branch of a Parallel state failed."),
    errorName("States.DataLimitExceeded", "A state's input, output or a service result grew beyond the payload size limit; States.ALL does not match it."),
    errorName("States.ExceedToleratedFailureThreshold", "A Map state failed because more items failed than its tolerated failure settings allow."),
    errorName("States.HeartbeatTimeout", "A Task state went longer than HeartbeatSeconds without a heartbeat."),
    errorName("States.Http.Socket", "An HTTP task timed out after 60 seconds."),
    errorName("States.IntrinsicFailure", "An intrinsic function in a payload template could not be evaluated."),
    errorName("States.ItemReaderFailed", "A Map state could not read the items from the source given in ItemReader."),
    errorName("States.NoChoiceMatched", "No rule of a Choice state held and the state has no Default."),
    errorName("States.ParameterPathFailure", "A path in a payload template, in a key ending in .\$, could not be resolved."),
    errorName("States.Permissions", "A Task state was not allowed to run the work it names."),
    errorName("States.QueryEvaluationError", "A JSONata expression failed, returned nothing, or returned a value of the wrong type or range."),
    errorName("States.ResultPathMatchFailure", "A state's ResultPath could not be applied to the input it received."),
    errorName("States.ResultWriterFailed", "A Map state could not write its results to the destination given in ResultWriter."),
    errorName("States.Runtime", "The execution failed on an exception it could not process; it cannot be retried and States.ALL does not catch it."),
    errorName("States.TaskFailed", "A Task state failed while running; in a rule it matches any error name except States.Timeout."),
    errorName("States.Timeout", "A Task state ran longer than TimeoutSeconds or missed a heartbeat, or the whole execution ran past its TimeoutSeconds."),
)
