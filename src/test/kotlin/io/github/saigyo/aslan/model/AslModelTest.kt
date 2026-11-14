package io.github.saigyo.aslan.model

import com.intellij.json.JsonLanguage
import com.intellij.lang.Language
import com.intellij.psi.PsiFileFactory
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.model.TransitionKind.CATCH
import io.github.saigyo.aslan.model.TransitionKind.CHOICE
import io.github.saigyo.aslan.model.TransitionKind.DEFAULT
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslObject
import org.jetbrains.yaml.YAMLLanguage

class AslModelTest : AslTestCase() {
    private val nested = """
        QueryLanguage: JSONata
        StartAt: Sort
        States:
          Sort:
            Type: Choice
            Choices:
              - Condition: '{% ${'$'}a %}'
                Next: Each
              - Condition: '{% ${'$'}b %}'
                Next: Both
            Default: Done
          Each:
            Type: Map
            QueryLanguage: JSONPath
            ItemProcessor:
              StartAt: One
              States:
                One:
                  Type: Task
                  Resource: arn:aws:states:::lambda:invoke
                  Arguments:
                    Next: notAField
                  Catch:
                    - ErrorEquals: [States.ALL]
                      Next: OneFailed
                  End: true
                OneFailed:
                  Type: Fail
            Next: Done
          Both:
            Type: Parallel
            Branches:
              - StartAt: Left
                States:
                  Left: { Type: Succeed }
              - StartAt: Right
                States:
                  Right: { Type: Succeed }
            End: true
          Done:
            Type: Succeed
    """
    private fun doc(text: String = nested) = AslModel.of(yaml(text))!!

    fun `test states machines and nesting`() {
        val d = doc()
        assertEquals(listOf("Sort", "Each", "Both", "Done"), d.root!!.states.map { it.name })
        assertEquals(listOf("Sort", "Each", "One", "OneFailed", "Both", "Left", "Right", "Done"), d.allStates().map { it.name })
        assertEquals(4, d.allMachines().size)
        assertEquals(2, d.root.state("Both")!!.children.size)
        assertEquals("Each", d.root.state("Each")!!.children.single().owner!!.name)
        assertEquals("Map", d.root.state("Each")!!.type!!.name)
    }
    fun `test transitions in order with kinds`() {
        val sort = doc().root!!.state("Sort")!!
        assertEquals(listOf(CHOICE to "Each", CHOICE to "Both", DEFAULT to "Done"), sort.transitions.map { it.kind to it.targetName })
        assertEquals(listOf(0, 1, 0), sort.transitions.map { it.index })
        val one = doc().allStates().first { it.name == "One" }
        assertEquals(listOf(CATCH to "OneFailed"), one.transitions.map { it.kind to it.targetName })
    }
    fun `test effective query language comes from the top level not the enclosing state`() {
        val d = doc()
        assertEquals(QueryLanguage.JSONPATH, d.root!!.state("Each")!!.queryLanguage)
        assertEquals(QueryLanguage.JSONATA, d.allStates().first { it.name == "One" }.queryLanguage)
        assertEquals(QueryLanguage.JSONPATH, doc("StartAt: A\nStates:\n  A:\n    Type: Succeed").root!!.state("A")!!.queryLanguage)
    }
    fun `test schema index knows structures fields and free-form data`() {
        val d = doc()
        val one = d.allStates().first { it.name == "One" }
        assertEquals(StructureId.TASK, d.schema.info(one.obj!!.psi)!!.structure.id)
        assertEquals("Resource", d.schema.field(one.obj["Resource"]!!.keyPsi)!!.spec.name)
        val arguments = one.obj["Arguments"]!!.value as AslObject
        assertNull(d.schema.field(arguments["Next"]!!.keyPsi))
        assertTrue(d.schema.isFreeForm(arguments["Next"]!!.keyPsi))
        val catcher = (one.obj["Catch"]!!.value as AslArray).items.single() as AslObject
        assertEquals(StructureId.CATCHER, d.schema.info(catcher.psi)!!.structure.id)
        assertSame(one, d.schema.stateNameKey(one.entry.keyPsi))
        assertNull(d.schema.field(one.entry.keyPsi))
        assertEquals(StructureId.STATE_MACHINE, d.schema.info(d.root!!.obj.psi)!!.structure.id)
    }
    fun `test stateAt returns the innermost state`() {
        val d = doc()
        val one = d.allStates().first { it.name == "One" }
        assertSame(one, d.stateAt(one.obj!!["Resource"]!!.keyPsi))
        assertNull(d.stateAt(d.root!!.startAt!!.keyPsi))
    }
    fun `test json files give the same model`() {
        val d = AslModel.of(json("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "Next": "B"}, "B": {"Type": "Succeed"}}}"""))!!
        assertEquals(listOf("A", "B"), d.root!!.states.map { it.name })
        assertEquals("B", d.root.state("A")!!.transitions.single().targetName)
    }
    fun `test not an asl file and broken files`() {
        assertNull(AslModel.of(yaml("StartAt: A", "plain.yaml")))
        assertNull(AslModel.of(yaml("- a"))!!.root)
        assertNotNull(AslModel.of(yaml(""))!!)
        val partial = AslModel.of(yaml("StartAt: A\nStates:\n  A:\n  B: text\n  C:\n    Type: Bogus"))!!
        assertEquals(listOf("A", "B", "C"), partial.root!!.states.map { it.name })
        assertNull(partial.root.state("A")!!.obj)
        assertNull(partial.root.state("C")!!.type)
        assertEquals("Bogus", partial.root.state("C")!!.typeName)
        assertEquals(StructureId.STATE, partial.schema.info(partial.root.state("C")!!.obj!!.psi)!!.structure.id)
    }
    fun `test placeholders make the model say what it cannot know`() {
        val d = doc("QueryLanguage: \${Language}\nStartAt: A\nStates:\n  A:\n    Type: Succeed\n  \${Extra}:\n    Type: Succeed")
        assertFalse(d.root!!.state("A")!!.queryLanguageKnown)
        assertTrue(d.root.open)
        val plain = doc("StartAt: A\nStates:\n  A:\n    Type: Succeed")
        assertTrue(plain.root!!.state("A")!!.queryLanguageKnown)
        assertFalse(plain.root.open)
    }
    fun `test a rule or catcher without Next yields a transition without target`() {
        val d = doc("StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: x\n    Catch:\n      - ErrorEquals: [States.ALL]\n    End: true")
        assertNull(d.root!!.state("A")!!.transitions.single().target)
    }
    fun `test model is cached until the file changes`() {
        val file = yaml("StartAt: A\nStates:\n  A:\n    Type: Succeed")
        assertSame(AslModel.of(file), AslModel.of(file))
    }
    fun `test half-typed files never break the model`() {
        val text = nested.trimIndent()
        for (end in text.indices step 3) {
            val d = AslModel.of(file("test.asl.yaml", YAMLLanguage.INSTANCE, text.substring(0, end)))!!
            d.allStates().forEach { d.stateAt(it.entry.keyPsi) }
        }
        val jsonText = """{"StartAt": "A", "States": {"A": {"Type": "Parallel", "Branches": [{"StartAt": "B", "States": {"B": {"Type": "Pass", "End": true}}}], "Catch": [{"Next": "A"}], "End": true}}}"""
        for (end in jsonText.indices) AslModel.of(file("test.asl.json", JsonLanguage.INSTANCE, jsonText.substring(0, end)))!!.allStates()
    }

    /** A file without an editor, so the many prefixes above do not each open one. */
    private fun file(name: String, language: Language, text: String) = PsiFileFactory.getInstance(project).createFileFromText(name, language, text)
    fun `test unapplied merge keys and unresolved aliases are not read`() {
        val d = doc("""
            StartAt: A
            States:
              <<: notAnAlias
              A:
                Type: Map
                <<: [plain]
                ItemProcessor: *missing
                Catch: *missing
                Next: B
              B: *missing
        """)
        val a = d.root!!.state("A")!!
        assertEquals(listOf("A", "B"), d.root.states.map { it.name })
        assertTrue(d.root.open)
        assertNull(d.schema.field(a.obj!!["<<"]!!.keyPsi))
        assertTrue(a.children.isEmpty())
        assertNull(d.schema.info(a.obj["ItemProcessor"]!!.value.psi))
        assertEquals(listOf("B"), a.transitions.map { it.targetName })
        assertNull(d.root.state("B")!!.obj)
        assertTrue(doc("StartAt: A\nStates: *missing").root!!.open)
        assertTrue(doc("StartAt: A\nStates: *missing").root!!.states.isEmpty())
    }
}
