package io.github.saigyo.aslan.variables

import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslVariable
import io.github.saigyo.aslan.model.AssignPlace
import io.github.saigyo.aslan.model.JsonataRead
import io.github.saigyo.aslan.model.PathRead
import io.github.saigyo.aslan.model.ordinal
import org.jetbrains.yaml.psi.YAMLKeyValue

class AslVariablesTest : VariableTestCase() {
    /** `$` written as a JSON or YAML double-quoted escape, built so that no tool decodes it on the way. */
    private val escapedDollar = "\\" + "u0024"

    private fun AslDocument.readNames(): List<String> = variables.reads.map { it.name }

    /** The names of the states whose reads may reach names the file does not say. */
    private fun AslDocument.openStates(): List<String> = allStates().filter { variables.isOpen(it) }.map { it.name }

    fun `test assignments in a state, a Choice rule and a Catcher`() {
        val doc = yamlModel(
            machine(
                """
                Load:
                  Type: Task
                  Resource: arn:aws:states:::lambda:invoke
                  Assign:
                    reel: 1
                  Catch:
                    - ErrorEquals: [States.ALL]
                      Assign:
                        fault: 2
                      Next: Check
                  Next: Check
                Check:
                  Type: Choice
                  Assign:
                    title: 3
                  Choices:
                    - Variable: §.a
                      IsPresent: true
                      Next: Done
                    - Variable: §.b
                      IsPresent: true
                      Assign:
                        archive: 4
                      Next: Done
                    - Not:
                        Variable: §.c
                        IsPresent: true
                        Assign:
                          nested: 5
                      Next: Done
                  Default: Done
                Done:
                  Type: Succeed
                """,
            ),
        )
        val assignments = doc.variables.assignments
        assertEquals(listOf("reel", "fault", "title", "archive"), assignments.map { it.name })
        assertEquals(listOf("Load", "Load", "Check", "Check"), assignments.map { it.state.name })
        assertEquals(listOf(AssignPlace.STATE, AssignPlace.CATCHER, AssignPlace.STATE, AssignPlace.CHOICE_RULE), assignments.map { it.place })
        assertEquals(listOf(0, 0, 0, 1), assignments.map { it.index })
        assertEquals(listOf("Load", "Load, first Catcher", "Check", "Check, second Choice rule"), assignments.map { it.placeText })
        assertEquals(listOf("reel", "fault", "title", "archive"), assignments.map { it.entry.key })
        assertEquals(
            listOf("first", "third", "tenth", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st", "111th", "112th"),
            listOf(1, 3, 10, 11, 12, 13, 21, 22, 23, 101, 111, 112).map { ordinal(it) },
        )
    }

    fun `test the dollar suffix of a JSONPath key is not part of the name`() {
        val doc = yamlModel(
            machine(
                """
                Load:
                  Type: Pass
                  Assign:
                    reel.§: §.reel
                    title: Nosferatu
                  End: true
                """,
            ),
        )
        assertEquals(listOf("reel", "title"), doc.variables.assignments.map { it.name })
        assertEquals(listOf("reel.$", "title"), doc.variables.assignments.map { it.entry.key })
    }

    fun `test JSONata reads`() {
        val doc = yamlModel(
            machine(
                """
                Show:
                  Type: Pass
                  Output: '{% [§reel, §reel.title, §states.input, §, §§, (§x := 1; §x)] %}'
                  End: true
                """,
                "QueryLanguage: JSONata",
            ),
        )
        val reads = doc.variables.reads
        assertEquals(listOf("reel", "reel"), reads.map { it.name })
        for (read in reads) {
            read as JsonataRead
            assertEquals("Show", read.state.name)
            assertEquals("\$reel", read.variable.text)
            assertEquals(listOf(read), doc.variables.readsAt(read.variable))
            assertSame(read.site, doc.jsonataSites.all.single())
        }
    }

    fun `test a built-in function is no read unless the name is assigned`() {
        val alone = yamlModel(
            machine(
                """
                Total:
                  Type: Pass
                  Output: '{% §sum([1]) %}'
                  End: true
                """,
                "QueryLanguage: JSONata",
            ),
        )
        assertEquals(emptyList<String>(), alone.readNames())

        val assigned = yamlModel(
            machine(
                """
                Count:
                  Type: Pass
                  Assign:
                    sum: 2
                  Next: Total
                Total:
                  Type: Pass
                  Output: '{% §sum([1]) %}'
                  End: true
                """,
                "QueryLanguage: JSONata",
            ),
        )
        assertEquals(listOf("sum"), assigned.readNames())
        assertEquals(AslVariable("sum", assigned.root!!), assigned.variables.variableOf(assigned.variables.reads.single()))
    }

    fun `test JSONPath reads with host ranges`() {
        val doc = yamlModel(
            machine(
                """
                Load:
                  Type: Pass
                  Assign:
                    reel: 1
                  Next: Use
                Use:
                  Type: Pass
                  Parameters:
                    a.§: §reel
                    b.§: "States.Format('{}', §reel)"
                    c.§: "${escapedDollar}reel"
                    d.§: "States.Format('{}',
                      §reel)"
                    e.§: States.Format('{}',
                      §reel)
                  InputPath: '§reel.title'
                  End: true
                """,
            ),
        )
        val reads = doc.variables.reads.filterIsInstance<PathRead>()
        assertEquals(6, doc.variables.reads.size)
        assertEquals(List(6) { "reel" }, reads.map { it.name })
        assertEquals(
            listOf("\$reel", "\$reel", escapedDollar + "reel", "\$reel", "\$reel", "\$reel"),
            reads.map { it.range.substring(it.scalar.psi.text) },
        )
        for (read in reads) assertEquals(listOf(read), doc.variables.pathReadsAt(read.scalar.psi))

        val json = jsonModel(
            """
            {"StartAt": "Load", "States": {
              "Load": {"Type": "Pass", "Assign": {"reel": 1}, "Next": "Use"},
              "Use": {"Type": "Pass", "InputPath": "${escapedDollar}reel.title", "End": true}}}
            """,
        )
        val read = json.variables.reads.single() as PathRead
        assertEquals("reel", read.name)
        assertEquals(escapedDollar + "reel", read.range.substring(read.scalar.psi.text))
        assertEquals(AslVariable("reel", json.root!!), json.variables.variableOf(read))
    }

    fun `test a name assigned at the top level is one variable below it`() {
        val doc = yamlModel(
            machine(
                """
                Load:
                  Type: Pass
                  Assign:
                    reel: 1
                  Next: Fork
                Fork:
                  Type: Parallel
                  Branches:
                    - StartAt: Left
                      States:
                        Left:
                          Type: Pass
                          Output: '{% §reel %}'
                          End: true
                  Next: Each
                Each:
                  Type: Map
                  Items: '{% [1] %}'
                  ItemProcessor:
                    StartAt: Item
                    States:
                      Item:
                        Type: Pass
                        Output: '{% §reel %}'
                        End: true
                  End: true
                """,
                "QueryLanguage: JSONata",
            ),
        )
        val variables = doc.variables
        val variable = AslVariable("reel", doc.root!!)
        assertEquals(variable, variables.variableOf(variables.assignments.single()))
        assertEquals(listOf("Left", "Item"), variables.reads.map { it.state.name })
        for (read in variables.reads) assertEquals(variable, variables.variableOf(read))
        assertEquals(variables.assignments, variables.assignmentsOf(variable))
        assertEquals(variables.reads, variables.readsOf(variable))
        assertEquals(setOf("reel"), variables.visibleNames(doc.state("Item")))
    }

    /** A Parallel state "Fork" whose branches hold the states "Left" and "Right", with [left] and [right] as their bodies. */
    private fun fork(left: String, right: String, before: String = ""): String {
        fun branch(name: String, body: String) =
            "- StartAt: $name\n  States:\n    $name:\n" + body.trimIndent().prependIndent("      ")
        val parallel = "Fork:\n  Type: Parallel\n  Branches:\n" +
            (branch("Left", left) + "\n" + branch("Right", right)).prependIndent("    ") + "\n  End: true"
        return machine(if (before.isBlank()) parallel else before.trimIndent() + "\n" + parallel)
    }

    fun `test two Parallel branches have separate variables`() {
        val body = "Type: Pass\nAssign:\n  count: 1\nOutputPath: §count\nEnd: true"
        val doc = yamlModel(fork(body, body))
        val variables = doc.variables
        val left = doc.state("Left")
        val right = doc.state("Right")
        assertEquals(listOf("Left", "Right"), variables.assignments.map { it.state.name })
        assertEquals(listOf("Left", "Right"), variables.reads.map { it.state.name })
        assertEquals(AslVariable("count", left.machine), variables.variableOf(variables.assignments[0]))
        assertEquals(AslVariable("count", right.machine), variables.variableOf(variables.assignments[1]))
        assertEquals(AslVariable("count", left.machine), variables.variableOf(variables.reads[0]))
        assertEquals(AslVariable("count", right.machine), variables.variableOf(variables.reads[1]))
        assertEquals(listOf(variables.assignments[0]), variables.assignmentsOf(AslVariable("count", left.machine)))
        assertEquals(listOf(variables.reads[1]), variables.readsOf(AslVariable("count", right.machine)))
    }

    fun `test an inner assignment of an outer name joins the outer variable`() {
        val body = "Type: Pass\nAssign:\n  count: 1\nOutputPath: §count\nEnd: true"
        val doc = yamlModel(fork(body, body, before = "Start:\n  Type: Pass\n  Assign:\n    count: 0\n  Next: Fork"))
        val variables = doc.variables
        val variable = AslVariable("count", doc.root!!)
        assertEquals(listOf("Start", "Left", "Right"), variables.assignments.map { it.state.name })
        for (assignment in variables.assignments) assertEquals(variable, variables.variableOf(assignment))
        for (read in variables.reads) assertEquals(variable, variables.variableOf(read))
        assertEquals(3, variables.assignmentsOf(variable).size)
        assertEquals(2, variables.readsOf(variable).size)
    }

    /**
     * A machine that assigns `reel` in "Load" and reads it in "Item", inside the Map "Each" whose processor also has
     * [config]; [item] holds further lines of "Item".
     */
    private fun mapMachine(config: String, item: String = ""): String {
        val extra = if (item.isBlank()) "" else item.trimIndent().prependIndent("    ") + "\n"
        val processor = config.trimIndent() + "\nStartAt: Item\nStates:\n  Item:\n    Type: Pass\n    Parameters:\n      r.§: §reel\n" +
            extra + "    End: true"
        return machine(
            "Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Each\nEach:\n  Type: Map\n  ItemProcessor:\n" +
                processor.prependIndent("    ") + "\n  End: true",
        )
    }

    fun `test a Distributed Map does not reach outward`() {
        val distributed = "ProcessorConfig:\n  Mode: DISTRIBUTED\n  ExecutionType: STANDARD"
        val outside = yamlModel(mapMachine(distributed))
        val read = outside.variables.reads.single()
        assertEquals("Item", read.state.name)
        assertNull(outside.variables.variableOf(read))
        assertEquals(emptySet<String>(), outside.variables.visibleNames(outside.state("Item")))

        val both = yamlModel(mapMachine(distributed, item = "  Assign:\n    reel: 2"))
        val variables = both.variables
        val inner = AslVariable("reel", both.state("Item").machine)
        assertEquals(AslVariable("reel", both.root!!), variables.variableOf(variables.assignments[0]))
        assertEquals(inner, variables.variableOf(variables.assignments[1]))
        assertEquals(inner, variables.variableOf(variables.reads.single()))
        assertEquals(listOf(variables.assignments[1]), variables.assignmentsOf(inner))

        val inline = yamlModel(mapMachine("ProcessorConfig:\n  Mode: INLINE"))
        assertEquals(AslVariable("reel", inline.root!!), inline.variables.variableOf(inline.variables.reads.single()))
    }

    fun `test a read with no assignment in reach has no variable`() {
        val ghost = yamlModel(machine("Show:\n  Type: Pass\n  OutputPath: §ghost\n  End: true"))
        assertNull(ghost.variables.variableOf(ghost.variables.reads.single()))

        val sibling = yamlModel(fork("Type: Pass\nAssign:\n  x: 1\nEnd: true", "Type: Pass\nOutputPath: §x\nEnd: true"))
        val read = sibling.variables.reads.single()
        assertEquals("Right", read.state.name)
        assertNull(sibling.variables.variableOf(read))
        assertEquals(setOf("x"), sibling.variables.visibleNames(sibling.state("Left")))
        assertEquals(emptySet<String>(), sibling.variables.visibleNames(sibling.state("Right")))
    }

    fun `test an Assign shared through an alias belongs to each branch`() {
        val doc = yamlModel(
            fork(
                "Type: Pass\nAssign: &shared\n  count: 1\nEnd: true",
                "Type: Pass\nAssign: *shared\nEnd: true",
            ),
        )
        val key = PsiTreeUtil.findChildrenOfType(doc.file, YAMLKeyValue::class.java).single { it.keyText == "count" }
        val variables = doc.variables
        val shared = variables.assignmentsAt(key)
        assertEquals(2, shared.size)
        assertEquals(variables.assignments, shared)
        assertEquals(listOf("Left", "Right"), shared.map { it.state.name })
        assertEquals(
            listOf(AslVariable("count", doc.state("Left").machine), AslVariable("count", doc.state("Right").machine)),
            shared.map { variables.variableOf(it) },
        )
    }

    fun `test open definitions`() {
        val closed = yamlModel(
            fork("Type: Pass\nAssign:\n  count: 1\nEnd: true", "Type: Pass\nEnd: true") + "\n" +
                mapMachine("ProcessorConfig:\n  Mode: DISTRIBUTED").substringAfter("States:\n"),
        )
        assertEquals(emptyList<String>(), closed.openStates())

        val placeholderAssign = yamlModel(fork("Type: Pass\nAssign: §{Vars}\nEnd: true", "Type: Pass\nEnd: true"))
        assertEquals(listOf("Left"), placeholderAssign.openStates())

        val jsonataAssign = yamlModel(machine("Load:\n  Type: Pass\n  Assign: '{% {} %}'\n  Next: Done\nDone:\n  Type: Succeed", "QueryLanguage: JSONata"))
        assertEquals(listOf("Load", "Done"), jsonataAssign.openStates())

        val placeholderKey = yamlModel(machine("Load:\n  Type: Pass\n  Assign:\n    §{K}: 1\n    reel: 2\n  End: true"))
        assertEquals(listOf("Load"), placeholderKey.openStates())
        assertEquals(listOf("reel"), placeholderKey.variables.assignments.map { it.name })

        val merge = yamlModel(machine("Load:\n  Type: Pass\n  Assign:\n    <<: §{Common}\n  End: true"))
        assertEquals(listOf("Load"), merge.openStates())
        assertEquals(emptyList<String>(), merge.variables.assignments.map { it.name })

        val mode = yamlModel(mapMachine("ProcessorConfig:\n  Mode: §{Mode}"))
        assertEquals(listOf("Item"), mode.openStates())

        val states = yamlModel(machine("Load:\n  Type: Pass\n  End: true\n§{Extra}:\n  Type: Pass\n  End: true"))
        assertEquals(listOf("Load", "\${Extra}"), states.openStates())
    }

    fun `test a JSON definition`() {
        val r = "\\" + "u0072"
        val doc = jsonModel(
            """
            {"StartAt": "Load", "States": {
              "Load": {"Type": "Pass", "Assign": {"${r}eel.§": "§.reel"}, "Next": "Show"},
              "Show": {"Type": "Pass", "QueryLanguage": "JSONata", "Output": "{% ${escapedDollar}reel.title %}", "Next": "Copy"},
              "Copy": {"Type": "Pass", "Parameters": {"t.§": "${escapedDollar}reel"}, "End": true}}}
            """,
        )
        val variables = doc.variables
        val variable = AslVariable("reel", doc.root!!)
        val assignment = variables.assignments.single()
        assertEquals("reel", assignment.name)
        assertEquals(listOf(assignment), variables.assignmentsAt(assignment.entry.entryPsi))
        assertEquals(variable, variables.variableOf(assignment))
        assertEquals(listOf("Show", "Copy"), variables.reads.map { it.state.name })
        assertEquals(listOf("reel", "reel"), variables.reads.map { it.name })
        val jsonata = variables.reads[0] as JsonataRead
        assertEquals(escapedDollar + "reel", jsonata.variable.text)
        assertEquals(listOf(jsonata), variables.readsAt(jsonata.variable))
        val path = variables.reads[1] as PathRead
        assertEquals(escapedDollar + "reel", path.range.substring(path.scalar.psi.text))
        assertEquals(listOf(path), variables.pathReadsAt(path.scalar.psi))
        for (read in variables.reads) assertEquals(variable, variables.variableOf(read))
        assertEquals(variables.reads, variables.readsOf(variable))
    }

    fun `test half-typed definitions do not throw`() {
        val noValue = yamlModel(machine("Load:\n  Type: Pass\n  Assign:\n  End: true"))
        assertEquals(emptyList<String>(), noValue.variables.assignments.map { it.name })
        assertEquals(listOf("Load"), noValue.openStates())

        val typing = yamlModel(machine("Load:\n  Type: Pass\n  Assign:\n    re"))
        assertEquals(emptyList<String>(), typing.variables.assignments.map { it.name })
        assertEquals(listOf("Load"), typing.openStates())

        val broken = yamlModel(machine("Show:\n  Type: Pass\n  Output: '{% §reel + %}'\n  End: true", "QueryLanguage: JSONata"))
        assertEquals(listOf("reel"), broken.readNames())
        assertNull(broken.variables.variableOf(broken.variables.reads.single()))
    }
}
