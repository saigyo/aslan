package io.github.saigyo.aslan.jsonata.edit

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.injected.editor.DocumentWindow
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiDocumentManager
import io.github.saigyo.aslan.files.AslFiles
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.jsonata.psi.JsonataFile

/**
 * A quote typed in an expression that is the host string's own quote would end the host string. It is written in its
 * escaped form into the host instead: `''` in a single-quoted YAML scalar, `\"` in a double-quoted one or a JSON string.
 * A selection is replaced.
 */
class JsonataTypedHandler : TypedHandlerDelegate() {
    override fun beforeSelectionRemoved(c: Char, project: Project, editor: Editor, file: PsiFile): Result = escape(c, project, editor, file)

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result =
        escape(c, project, editor, file)

    private fun escape(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '\'' && c != '"') return Result.CONTINUE
        // Everything else is for ASL files only; the top-level file is known without a commit.
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(file) ?: file
        if (!AslFiles.isAslFile(top)) return Result.CONTINUE
        // The platform hands out the host editor when the typed character is the host's own quote.
        val hostEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        val window: DocumentWindow
        val from: Int
        val to: Int
        val caret = hostEditor.caretModel.currentCaret
        // With several carets the injected editor knows only one of them: each caret is then looked up in the host.
        if (file is JsonataFile && hostEditor.caretModel.caretCount == 1) {
            window = editor.document as? DocumentWindow ?: return Result.CONTINUE
            if (hostEditor === editor) return Result.CONTINUE
            val selection = editor.selectionModel
            val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
            val end = if (selection.hasSelection()) selection.selectionEnd else start
            from = window.injectedToHost(start)
            to = window.injectedToHost(end)
        } else {
            if (hostEditor !== editor && hostEditor.caretModel.caretCount == 1) return Result.CONTINUE
            val at = if (caret.hasSelection()) caret.selectionStart else caret.offset
            PsiDocumentManager.getInstance(project).commitDocument(hostEditor.document)
            val hostFile = top
            val host = generateSequence(hostFile.findElementAt(at) ?: hostFile.findElementAt(at - 1)) { it.parent }
                .takeWhile { it !is PsiFile }.firstOrNull { it is PsiLanguageInjectionHost } ?: return Result.CONTINUE
            val injected = JsonataPsi.injectedFile(host) ?: return Result.CONTINUE
            window = PsiDocumentManager.getInstance(project).getCachedDocument(injected) as? DocumentWindow ?: return Result.CONTINUE
            from = at
            to = if (caret.hasSelection()) caret.selectionEnd else at
        }
        if (hostQuote(window) != c || from < 0 || to < from) return Result.CONTINUE
        // Only inside the injected part of the host, between `{%` and `%}`; elsewhere the platform types as usual.
        if (window.hostRanges.none { from >= it.startOffset && to <= it.endOffset }) return Result.CONTINUE
        val escaped = if (c == '\'') "''" else "\\\""
        caret.removeSelection()
        hostEditor.document.replaceString(from, to, escaped)
        caret.moveToOffset(from + escaped.length)
        return Result.STOP
    }
}
