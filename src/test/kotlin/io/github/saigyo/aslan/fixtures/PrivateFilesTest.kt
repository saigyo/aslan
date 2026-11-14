package io.github.saigyo.aslan.fixtures

import com.intellij.codeHighlighting.HighlightDisplayLevel
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.check.AslChecker
import io.github.saigyo.aslan.files.AslFiles
import java.io.File

/**
 * Loads definitions kept outside the repository and checks that they show no errors. The failure message gives
 * the problem and the line number only, never file content.
 */
class PrivateFilesTest : AslTestCase() {
    fun `test private definitions show no errors`() {
        val dirs = listOfNotNull(System.getProperty("aslan.privateFixtures")?.takeIf { it.isNotBlank() }, "private-fixtures").map(::File)
        val files = dirs.filter { it.isDirectory }.flatMap { it.listFiles()!!.toList() }
            .filter { it.isFile && AslFiles.isAslFileName(it.name) }
        if (files.isEmpty()) {
            println("PrivateFilesTest: no private definitions present, nothing checked")
            return
        }
        for (f in files) {
            val psi = myFixture.configureByText(f.name, f.readText())
            val document = psi.viewProvider.document!!
            val errors = AslChecker.findings(psi).filter { it.problem.level == HighlightDisplayLevel.ERROR }
            assertEquals(
                "private definition",
                emptyList<String>(),
                errors.map { "${it.problem} at line ${document.getLineNumber(it.element.textOffset + (it.range?.startOffset ?: 0)) + 1}" },
            )
        }
        println("PrivateFilesTest: checked ${files.size} private definition(s)")
    }
}
