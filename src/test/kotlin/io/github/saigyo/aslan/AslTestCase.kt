package io.github.saigyo.aslan

import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.codeInsight.highlighting.HighlightManagerImpl
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.openapi.editor.colors.EditorColors
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
    protected fun highlightedUsages(): List<String> = highlightedAccesses().map { it.first }

    /** Like [highlightedUsages], each text with `true` for a write highlight (the write colour of search results). */
    protected fun highlightedAccesses(): List<Pair<String, Boolean>> {
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
        val manager = HighlightManager.getInstance(project) as HighlightManagerImpl
        // the platform clears instead of highlighting when a leftover highlighter contains the caret
        for (highlighter in manager.getHighlighters(editor)) manager.removeSegmentHighlighter(editor, highlighter)
        HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)
        val text = editor.document.charsSequence
        return manager.getHighlighters(editor)
            .sortedBy { it.startOffset }
            .map { text.subSequence(it.startOffset, it.endOffset).toString() to (it.textAttributesKey == EditorColors.WRITE_SEARCH_RESULT_ATTRIBUTES) }
    }

}
