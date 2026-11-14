package io.github.saigyo.aslan.spec

import io.github.saigyo.aslan.spec.StructureId.*
import junit.framework.TestCase

class AslSpecTest : TestCase() {
    private fun field(s: StructureId, name: String) = AslSpec.structure(s).field(name) ?: error("$s.$name missing")

    fun `test eight state types in order`() {
        assertEquals(listOf("Task", "Pass", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map"), AslSpec.stateTypes.map { it.name })
        assertTrue(AslSpec.stateType("Task")!!.needsTransition)
        assertTrue(AslSpec.stateType("Fail")!!.terminal)
        assertFalse(AslSpec.stateType("Choice")!!.needsTransition)
        assertNull(AslSpec.stateType("task"))
    }

    fun `test task fields and modes`() {
        assertTrue(field(TASK, "Resource").required)
        assertEquals(Mode.JSONATA, field(TASK, "Arguments").mode)
        assertEquals(Mode.JSONPATH, field(TASK, "Parameters").mode)
        assertEquals(Mode.JSONPATH, field(TASK, "ResultPath").mode)
        assertTrue(field(TASK, "ResultPath").nullable)
        assertEquals(Mode.BOTH, field(TASK, "Assign").mode)
        assertEquals(ValueKind.FreeForm, field(TASK, "Arguments").kind)
        assertTrue(field(TASK, "TimeoutSeconds").jsonata)
        assertEquals(ValueKind.ListOf(ValueKind.Struct(RETRIER)), field(TASK, "Retry").kind)
    }

    fun `test fields absent where the specification forbids them`() {
        assertNull(AslSpec.structure(SUCCEED).field("Next"))
        assertNull(AslSpec.structure(SUCCEED).field("Assign"))
        assertNull(AslSpec.structure(FAIL).field("Output"))
        assertNull(AslSpec.structure(CHOICE).field("Next"))
        assertNull(AslSpec.structure(PASS).field("Retry"))
        assertNull(AslSpec.structure(WAIT).field("Parameters"))
    }

    fun `test fieldsFor filters by query language`() {
        val jsonata = AslSpec.structure(TASK).fieldsFor(QueryLanguage.JSONATA).map { it.name }
        assertTrue("Arguments" in jsonata && "Assign" in jsonata)
        assertFalse("Parameters" in jsonata || "InputPath" in jsonata)
    }

    fun `test choice rule`() {
        assertEquals(Mode.JSONATA, field(CHOICE_RULE, "Condition").mode)
        assertEquals(Mode.JSONPATH, field(CHOICE_RULE, "Variable").mode)
        assertEquals(39, AslSpec.structure(CHOICE_RULE).fields.count { it.mode == Mode.JSONPATH && it.name !in setOf("Variable", "And", "Or", "Not") })
        assertEquals(ValueKind.Number, field(CHOICE_RULE, "NumericLessThan").kind)
        assertEquals(ValueKind.Text, field(CHOICE_RULE, "NumericLessThanPath").kind)
        assertEquals(ValueKind.Bool, field(CHOICE_RULE, "IsPresent").kind)
    }

    fun `test ranges groups and enumerations`() {
        assertEquals(0.0, field(RETRIER, "MaxAttempts").min)
        assertEquals(1.0, field(RETRIER, "BackoffRate").min)
        assertEquals(100.0, field(MAP, "ToleratedFailurePercentage").max)
        assertEquals(listOf("FULL", "NONE"), (field(RETRIER, "JitterStrategy").kind as ValueKind.OneOf).values.map { it.name })
        assertTrue(setOf("Seconds", "Timestamp", "SecondsPath", "TimestampPath") in AslSpec.structure(WAIT).oneOfRequired)
        assertTrue(setOf("Next", "End") in AslSpec.structure(TASK).exclusive)
        assertTrue(setOf("ItemProcessor", "Iterator") in AslSpec.structure(MAP).oneOfRequired)
    }

    fun `test every field has a description and a documentation link`() {
        for (id in StructureId.entries) for (f in AslSpec.structure(id).fields) {
            assertTrue("$id.${f.name} description", f.description.length >= 20)
            assertTrue("$id.${f.name} docUrl", f.docUrl.startsWith("https://docs.aws.amazon.com/step-functions/"))
        }
    }

    fun `test error names and resources`() {
        val errors = AslSpec.errorNames.map { it.name }
        assertTrue(errors.containsAll(listOf("States.ALL", "States.Timeout", "States.TaskFailed", "States.Permissions",
            "States.HeartbeatTimeout", "States.NoChoiceMatched", "States.QueryEvaluationError", "States.BranchFailed")))
        val arns = AslSpec.resourceArns.map { it.name }
        assertTrue(arns.containsAll(listOf("arn:aws:states:::lambda:invoke", "arn:aws:states:::lambda:invoke.waitForTaskToken",
            "arn:aws:states:::dynamodb:putItem", "arn:aws:states:::sns:publish", "arn:aws:states:::events:putEvents",
            "arn:aws:states:::states:startExecution.sync:2")))
    }

    fun `test permissive readings where the sources disagree`() {
        assertFalse(field(RESULT_WRITER, "Resource").required)
        assertEquals(Mode.BOTH, field(PASS, "Assign").mode)
        assertEquals(Mode.BOTH, field(MAP, "ItemSelector").mode)
    }

    fun `test generic state structure`() =
        assertEquals(setOf("Type", "Comment", "QueryLanguage"), AslSpec.structure(STATE).fields.map { it.name }.toSet())

    fun `test definesFieldAnywhere`() {
        assertTrue(AslSpec.definesFieldAnywhere("Next"))
        assertTrue(AslSpec.definesFieldAnywhere("WriterConfig"))
        assertFalse(AslSpec.definesFieldAnywhere("Retries"))
    }
}
