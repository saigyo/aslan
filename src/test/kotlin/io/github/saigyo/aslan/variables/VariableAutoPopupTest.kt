package io.github.saigyo.aslan.variables

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import com.intellij.util.ThrowableRunnable

/** Typing `$` in an expression opens completion without Ctrl+Space; the test runs off the dispatch thread, as the popup needs. */
class VariableAutoPopupTest : VariableTestCase() {
    private lateinit var tester: CompletionAutoPopupTester

    override fun setUp() {
        super.setUp()
        tester = CompletionAutoPopupTester(myFixture)
    }

    override fun runInDispatchThread(): Boolean = false

    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) = tester.runWithAutoPopupEnabled(testRunnable)

    private fun configure(output: String) = EdtTestUtil.runInEdtAndWait<Throwable> {
        yaml(
            machine(
                """
                Load:
                  Type: Pass
                  Assign:
                    reel: 1
                  Next: Show
                Show:
                  Type: Pass
                  Output: $output
                  End: true
                """,
                top = "QueryLanguage: JSONata",
            ),
        )
    }

    private fun lookupStrings(): List<String>? = EdtTestUtil.runInEdtAndGet<List<String>?, Throwable> { tester.lookup?.items?.map { it.lookupString } }

    fun `test typing a dollar sign in an expression opens completion with the variables`() {
        configure("\"{% <caret> %}\"")
        tester.typeWithPauses("$")
        val items = lookupStrings()
        assertNotNull(items)
        assertTrue(items.toString(), items!!.containsAll(listOf("\$reel", "\$states", "\$sum")))
    }

    fun `test typing a dollar sign in a block scalar expression opens completion`() {
        // the lines of the states are indented by 16 in the template above, so 20 puts the line under Output
        configure("|-\n" + " ".repeat(20) + "{% 1 + <caret> %}")
        tester.typeWithPauses("$")
        assertTrue(lookupStrings().toString(), lookupStrings().orEmpty().contains("\$reel"))
    }

    fun `test typing a dollar sign outside an expression opens nothing`() {
        configure("\"costs <caret>\"")
        tester.typeWithPauses("$")
        assertNull(lookupStrings())
    }
}
