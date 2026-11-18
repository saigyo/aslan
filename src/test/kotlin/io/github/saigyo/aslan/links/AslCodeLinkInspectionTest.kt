package io.github.saigyo.aslan.links

import io.github.saigyo.aslan.AslTestCase

class AslCodeLinkInspectionTest : AslTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("lambdas/src/contracts.json", "{\"IntakeInput\": {}}")
        myFixture.addFileToProject("lambdas/src/notes.txt", "The Intake contract.")
        myFixture.addFileToProject("lambdas/pack.zip", "Intake")
        myFixture.enableInspections(AslCodeLinkInspection())
    }

    private fun check(comment: String, name: String = "sm.asl.yaml") {
        myFixture.configureByText(name, "StartAt: Scan\nStates:\n  $comment\n  Scan:\n    Type: Succeed\n")
        myFixture.checkHighlighting(false, false, true)
    }

    fun `test a missing file is a weak warning on the path`() =
        check("# Function: <weak_warning descr=\"File not found: lambdas/none.ts\">lambdas/none.ts</weak_warning>")

    fun `test a missing type is a weak warning on the type`() =
        check("# Input: lambdas/src/contracts.json#<weak_warning descr=\"Type Missing not found in contracts.json\">Missing</weak_warning>")

    fun `test a found file and type are no warning`() =
        check("# Input: lambdas/src/contracts.json#IntakeInput and lambdas/src/notes.txt#Intake")

    fun `test a binary file with a type warns about the type`() =
        check("# lambdas/pack.zip#<weak_warning descr=\"Type Intake not found in pack.zip\">Intake</weak_warning>")

    fun `test a path in a Comment field is checked too`() {
        myFixture.configureByText(
            "sm.asl.yaml",
            "StartAt: Scan\nStates:\n  Scan:\n    Type: Succeed\n    Comment: See <weak_warning descr=\"File not found: lambdas/none.ts\">lambdas/none.ts</weak_warning>\n",
        )
        myFixture.checkHighlighting(false, false, true)
    }

    fun `test words that are no paths are never marked`() =
        check("# https://example.com/a.json v1.2 and/or x/y")

    fun `test the inspection does nothing in a YAML file that is no ASL file`() {
        myFixture.configureByText("other.yaml", "# lambdas/none.ts\nkey: value\n")
        myFixture.checkHighlighting(false, false, true)
    }

    fun `test a Comment key in payload data is not checked`() {
        myFixture.configureByText("sm.asl.yaml", "StartAt: Scan\nStates:\n  Scan:\n    Type: Pass\n    Parameters:\n      Comment: incoming/report.pdf\n    End: true\n")
        myFixture.checkHighlighting(false, false, true)
    }
}
