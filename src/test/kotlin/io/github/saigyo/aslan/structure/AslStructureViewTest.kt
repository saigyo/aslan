package io.github.saigyo.aslan.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.testFramework.PlatformTestUtil
import io.github.saigyo.aslan.AslIcons
import io.github.saigyo.aslan.AslTestCase
import com.intellij.json.psi.JsonFile
import org.jetbrains.yaml.psi.YAMLFile

class AslStructureViewTest : AslTestCase() {
    private fun tree(): String {
        var result = ""
        myFixture.testStructureView { component ->
            PlatformTestUtil.expandAll(component.tree)
            result = PlatformTestUtil.print(component.tree, false)
        }
        return result
    }

    fun `test states with nested machines assignments rules and catchers`() {
        yaml("""
            QueryLanguage: JSONata
            StartAt: Load
            States:
              Load:
                Type: Task
                Resource: x
                Assign:
                  batch: 1
                  hasSeal: false
                Catch:
                  - ErrorEquals: [States.Timeout, States.TaskFailed]
                    Next: Failed
                Next: Sort
              Sort:
                Type: Choice
                Choices:
                  - Condition: '{% ${'$'}hasSeal %}'
                    Next: Each
                Default: Both
              Each:
                Type: Map
                ItemProcessor:
                  StartAt: One
                  States:
                    One: { Type: Succeed }
                Next: Both
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
              Failed:
                Type: Fail
        """)
        assertEquals("""
            -test.asl.yaml
             -Load
              Assign batch
              Assign hasSeal
              Catch States.Timeout, States.TaskFailed
             -Sort
              Rule 1
             -Each
              One
             -Both
              -Branch 1
               Left
              -Branch 2
               Right
             Failed
        """.trimIndent(), tree().trim())
    }

    fun `test state node presentation`() {
        yaml("StartAt: A\nStates:\n  A:\n    Type: Wait\n    Seconds: 1\n    End: true")
        myFixture.testStructureView { component ->
            val root = component.treeModel.root
            val state = root.children.single() as StructureViewTreeElement
            assertEquals("A", state.presentation.presentableText)
            assertEquals("Wait", state.presentation.locationString)
            assertSame(AslIcons.forStateType("Wait"), state.presentation.getIcon(false))
        }
    }

    fun `test rule and catcher show their target and navigate to their object`() {
        yaml("""
            StartAt: A
            States:
              A:
                Type: Task
                Resource: x
                Catch:
                  - ErrorEquals: [States.ALL]
                    Next: B
                  - ErrorEquals: States.ALL
                    Next: B
                End: true
              B: { Type: Succeed }
        """)
        myFixture.testStructureView { component ->
            val state = component.treeModel.root.children.first() as StructureViewTreeElement
            val catchers = state.children.map { it as StructureViewTreeElement }
            assertEquals(listOf("Catch States.ALL", "Catch"), catchers.map { it.presentation.presentableText })
            assertEquals("→ B", catchers[0].presentation.locationString)
            assertTrue((catchers[0].value as com.intellij.psi.PsiElement).text.startsWith("ErrorEquals"))
        }
    }

    fun `test json`() {
        json("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "Next": "B"}, "B": {"Type": "Succeed"}}}""")
        assertEquals("-test.asl.json\n A\n B", tree().trim())
    }

    fun `test jsonpath assign key loses its suffix`() {
        json("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "Assign": {"x.$": "$.a"}, "End": true}}}""")
        assertTrue("Assign x" in tree())
        assertFalse(".$" in tree())
    }

    fun `test other yaml files keep the default structure view`() {
        yaml("services:\n  web:\n    image: x", "docker-compose.yaml")
        assertTrue("services" in tree())
    }

    fun `test a file that is not a state machine yet keeps the default structure view`() {
        val factory = AslYamlStructureViewFactory()
        assertNull(factory.getStructureViewBuilder(yaml("- a\n- b") as YAMLFile))
        assertNull(factory.getStructureViewBuilder(yaml("") as YAMLFile))
        assertNull(factory.getStructureViewBuilder(yaml("StartAt: A\nStates:\n  A:\n    Type: Succeed", "plain.yaml") as YAMLFile))
        assertNotNull(factory.getStructureViewBuilder(yaml("StartAt: A\nStates:\n  A:\n    Type: Succeed") as YAMLFile))
        assertNull(AslJsonStructureViewFactory().getStructureViewBuilder(json("[1]") as JsonFile))
    }

    fun `test incomplete definitions do not break the tree`() {
        yaml("StartAt: A\nStates:\n  A:\n    Type:\n    Catch: 3\n    Assign: x\n  B: 5\n  C:\n    Type: Choice\n    Choices:\n      - 1\n      - Next:\n")
        assertTrue("A" in tree())
    }
}
