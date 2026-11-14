package io.github.saigyo.aslan.jsonata.edit

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.saigyo.aslan.model.AslModel
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.files.AslFiles
import org.jetbrains.yaml.YAMLLanguage

/**
 * Enter inside a JSONata expression in a YAML file gives the new line the leading white space of the line the caret is
 * on, as in Java or JavaScript, and nothing else: the text after the caret follows, nothing is removed or added. The
 * platform would indent the new line like the block scalar, or like the quoted scalar's continuation lines, which is
 * less than the line the caret is on when the expression is indented deeper. A JSON string is left to the platform, since
 * a raw line break would break it, and so is a block scalar that keeps its final line break (`|` and `>` without `-`):
 * its value does not end in `%}`, so it is no JSONata string.
 */
class JsonataEnterHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): Result {
        // For other files nothing is looked at: the top-level file is known without a commit.
        val top = InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file) ?: file
        if (!top.language.isKindOf(YAMLLanguage.INSTANCE) || !AslFiles.isAslFile(top)) return Result.Continue
        val host = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        if (host.caretModel.caretCount != 1 || host.selectionModel.hasSelection()) return Result.Continue
        val document = host.document
        val offset = host.caretModel.offset
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        if (!insideExpression(top, offset)) return Result.Continue
        val text = document.charsSequence
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val indentEnd = indentEnd(text, start, document.getLineEndOffset(line))
        // Inside the leading white space the rest of it would follow the new line's: leave that to the platform.
        if (offset < indentEnd || indentEnd == start) return Result.Continue
        val insert = "\n" + text.subSequence(start, indentEnd)
        // Written here and then stopped: the platform's own line break would be re-indented by the formatter afterwards, which
        // puts a line of a block scalar back at the indentation of the block.
        document.insertString(offset, insert)
        host.caretModel.moveToOffset(offset + insert.length)
        return Result.Stop
    }

    private fun indentEnd(text: CharSequence, start: Int, lineEnd: Int): Int {
        var end = start
        while (end < lineEnd && (text[end] == ' ' || text[end] == '\t')) end++
        return end
    }

    /**
     * Whether [offset] lies between the `{%` and the `%}` of a string that Step Functions evaluates as JSONata. The model
     * is asked, not the injection: a folded block scalar with a deeper line is not injected at all, since its line breaks
     * are kept and the expression cannot be located with certainty, but Enter in it needs the same help.
     */
    private fun insideExpression(file: PsiFile, offset: Int): Boolean {
        val host = generateSequence(file.findElementAt(offset) ?: file.findElementAt(offset - 1)) { it.parent }
            .takeWhile { it !is PsiFile }.firstOrNull { it is PsiLanguageInjectionHost } ?: return false
        if (AslModel.of(file)?.jsonataSites?.at(host).isNullOrEmpty()) return false
        val text = host.text
        val open = text.indexOf("{%")
        val close = text.lastIndexOf("%}")
        val relative = offset - host.textRange.startOffset
        return open >= 0 && close >= open + 2 && relative >= open + 2 && relative <= close
    }
}
