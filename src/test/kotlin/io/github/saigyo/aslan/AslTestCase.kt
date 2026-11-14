package io.github.saigyo.aslan

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

abstract class AslTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData"

    fun yaml(text: String, name: String = "test.asl.yaml"): PsiFile =
        myFixture.configureByText(name, text.trimIndent())

    fun json(text: String, name: String = "test.asl.json"): PsiFile =
        myFixture.configureByText(name, text.trimIndent())
}
