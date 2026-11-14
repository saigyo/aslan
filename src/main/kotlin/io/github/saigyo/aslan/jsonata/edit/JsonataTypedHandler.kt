package io.github.saigyo.aslan.jsonata.edit

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import java.util.WeakHashMap

/**
 * A quote typed in an expression that is the host string's own quote would end the host string. It is written in its
 * escaped form into the host instead: `''` in a single-quoted YAML scalar, `\"` in a double-quoted one or a JSON string.
 * A selection is replaced. Only inside the injected part of the host of an ASL file; elsewhere the platform types as usual.
 *
 * With several carets the injected editor knows only one of them, and a write at one caret would invalidate it for the
 * next. The first call of a typed character therefore looks at all carets and writes all escapes; the calls for the
 * other carets of the same character only report what was decided for them.
 */
class JsonataTypedHandler : TypedHandlerDelegate() {
    override fun beforeSelectionRemoved(c: Char, project: Project, editor: Editor, file: PsiFile): Result = escape(c, project, editor, file)

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result =
        escape(c, project, editor, file)

    private class Batch(val verdicts: MutableMap<Caret, Boolean>, val at: Long)

    private val batches = WeakHashMap<Editor, Batch>()

    private fun escape(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '\'' && c != '"') return Result.CONTINUE
        // Everything else is for ASL files only; the top-level file is known without a commit.
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(file) ?: file
        if (!AslFiles.isAslFile(top)) return Result.CONTINUE
        val hostEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        return if (hostEditor.caretModel.caretCount > 1) escapeAtAllCarets(c, project, hostEditor, top) else escapeAtOneCaret(c, project, editor, hostEditor, file)
    }

    private fun escapeAtOneCaret(c: Char, project: Project, editor: Editor, hostEditor: Editor, file: PsiFile): Result {
        val window: DocumentWindow
        val from: Int
        val to: Int
        val caret = hostEditor.caretModel.currentCaret
        if (file is JsonataFile) {
            window = editor.document as? DocumentWindow ?: return Result.CONTINUE
            if (hostEditor === editor) return Result.CONTINUE
            val selection = editor.selectionModel
            val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
            val end = if (selection.hasSelection()) selection.selectionEnd else start
            from = window.injectedToHost(start)
            to = window.injectedToHost(end)
        } else {
            // The platform hands out the host editor when the typed character is the host's own quote.
            if (hostEditor !== editor) return Result.CONTINUE
            PsiDocumentManager.getInstance(project).commitDocument(hostEditor.document)
            from = if (caret.hasSelection()) caret.selectionStart else caret.offset
            to = if (caret.hasSelection()) caret.selectionEnd else from
            window = windowAt(file, from, project) ?: return Result.CONTINUE
        }
        if (!isOurs(window, c, from, to)) return Result.CONTINUE
        val escaped = escaped(c)
        caret.removeSelection()
        hostEditor.document.replaceString(from, to, escaped)
        caret.moveToOffset(from + escaped.length)
        return Result.STOP
    }

    private fun escapeAtAllCarets(c: Char, project: Project, hostEditor: Editor, top: PsiFile): Result {
        val caret = hostEditor.caretModel.currentCaret
        val now = System.nanoTime()
        val batch = batches[hostEditor]
        if (batch != null && now - batch.at < EXPIRY && caret in batch.verdicts) {
            val verdict = batch.verdicts.remove(caret)!!
            if (batch.verdicts.isEmpty()) batches.remove(hostEditor)
            return if (verdict) Result.STOP else Result.CONTINUE
        }
        batches.remove(hostEditor)
        PsiDocumentManager.getInstance(project).commitDocument(hostEditor.document)
        class Edit(val caret: Caret, val from: Int, val to: Int, val plain: Boolean = false)
        val verdicts = LinkedHashMap<Caret, Boolean>()
        val edits = ArrayList<Edit>()
        for (each in hostEditor.caretModel.allCarets) {
            val from = if (each.hasSelection()) each.selectionStart else each.offset
            val to = if (each.hasSelection()) each.selectionEnd else from
            val window = windowAt(top, from, project)
            val ours = window != null && isOurs(window, c, from, to)
            verdicts[each] = ours
            if (ours) edits += Edit(each, from, to)
        }
        if (edits.isNotEmpty()) {
            // The injected editor is out of date after the writes, so the platform could not type at the other carets any
            // more: they get the plain character from here.
            for (each in hostEditor.caretModel.allCarets) {
                if (verdicts[each] == true) continue
                val from = if (each.hasSelection()) each.selectionStart else each.offset
                edits += Edit(each, from, if (each.hasSelection()) each.selectionEnd else from, plain = true)
                verdicts[each] = true
            }
            val escaped = escaped(c)
            // from the end of the document, so that the offsets of the edits still to come stay valid
            for (edit in edits.sortedByDescending { it.from }) {
                edit.caret.removeSelection()
                hostEditor.document.replaceString(edit.from, edit.to, if (edit.plain) c.toString() else escaped)
            }
            fun text(edit: Edit) = if (edit.plain) 1 else escaped.length
            for (edit in edits) {
                val shift = edits.filter { it.from < edit.from }.sumOf { text(it) - (it.to - it.from) }
                edit.caret.moveToOffset(edit.from + shift + text(edit))
            }
        }
        val verdict = verdicts.remove(caret) ?: false
        if (verdicts.isNotEmpty()) batches[hostEditor] = Batch(verdicts, now)
        return if (verdict) Result.STOP else Result.CONTINUE
    }

    private fun escaped(c: Char): String = if (c == '\'') "''" else "\\\""

    /** The injected document of the JSONata expression whose host string holds [offset] of the host file [file], or null. */
    private fun windowAt(file: PsiFile, offset: Int, project: Project): DocumentWindow? {
        val host = generateSequence(file.findElementAt(offset) ?: file.findElementAt(offset - 1)) { it.parent }
            .takeWhile { it !is PsiFile }.firstOrNull { it is PsiLanguageInjectionHost } ?: return null
        val injected = JsonataPsi.injectedFile(host) ?: return null
        return PsiDocumentManager.getInstance(project).getCachedDocument(injected) as? DocumentWindow
    }

    /** Whether [c] is the host's own quote and [from] to [to] lies inside the injected part of the host, between `{%` and `%}`. */
    private fun isOurs(window: DocumentWindow, c: Char, from: Int, to: Int): Boolean =
        from >= 0 && to >= from && hostQuote(window) == c && window.hostRanges.any { from >= it.startOffset && to <= it.endOffset }

    private companion object {
        const val EXPIRY = 2_000_000_000L
    }
}
