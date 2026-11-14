package io.github.saigyo.aslan

import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.codeInsight.highlighting.HighlightManagerImpl
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
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

    /**
     * The text of every range highlighted by Highlight Usages at `<caret>`, sorted by start offset; the highlighters of
     * the daemon do not count. With the caret in an injected file the fixture hands out the injected editor, so the
     * highlighters are read from the host editor.
     */
    protected fun highlightedUsages(): List<String> {
        HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
        val text = editor.document.charsSequence
        return (HighlightManager.getInstance(project) as HighlightManagerImpl).getHighlighters(editor)
            .sortedBy { it.startOffset }
            .map { text.subSequence(it.startOffset, it.endOffset).toString() }
    }
}
