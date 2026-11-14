package io.github.saigyo.aslan

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

abstract class AslTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData"

    fun yaml(text: String, name: String = "test.asl.yaml"): PsiFile =
        myFixture.configureByText(name, text.trimIndent())

    fun json(text: String, name: String = "test.asl.json"): PsiFile =
        myFixture.configureByText(name, text.trimIndent())

    /** The lookup strings offered at `<caret>`; empty when a single match was inserted directly. */
    protected fun complete(text: String, name: String = "test.asl.yaml"): List<String> {
        myFixture.configureByText(name, text.trimIndent())
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }
}
