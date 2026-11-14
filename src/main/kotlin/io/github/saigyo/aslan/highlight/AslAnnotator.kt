package io.github.saigyo.aslan.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.refs.StateTargets
import io.github.saigyo.aslan.tree.QuotedScalars

/**
 * Colours ASL field names, state names, transition targets and `${name}` placeholders. It is registered for all YAML
 * and JSON files; the model lookup, done once per annotation session, tells whether the file is an ASL file.
 */
class AslAnnotator : Annotator, DumbAware {
    /** The model of the file being annotated, or null for a file that is not an ASL file. */
    private class Session(val document: AslDocument?)

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val target = AslHighlighting.textElement(element) ?: return
        val document = session(target, holder).document ?: return
        val placeholders = AslHighlighting.placeholderRanges(target, target.text)
        if (AslHighlighting.isKey(target)) key(target, placeholders.isEmpty(), document, holder) else value(target, placeholders.isEmpty(), holder)
        for (range in placeholders) mark(holder, range, AslHighlighting.PLACEHOLDER)
    }

    private fun session(element: PsiElement, holder: AnnotationHolder): Session {
        val data = holder.currentAnnotationSession
        data.getUserData(SESSION)?.let { return it }
        return Session(AslModel.of(element.containingFile)).also { data.putUserData(SESSION, it) }
    }

    private fun key(key: PsiElement, plain: Boolean, document: AslDocument, holder: AnnotationHolder) {
        val range = key.textRange
        if (document.schema.field(key) != null) {
            mark(holder, range, AslHighlighting.FIELD)
        } else if (plain && document.schema.stateNameKey(key) != null) {
            mark(holder, unquoted(key.text, range), AslHighlighting.STATE_NAME)
        }
    }

    /** The name of a transition target, coloured exactly where [StateTargets.referenceAt] finds a reference. */
    private fun value(value: PsiElement, plain: Boolean, holder: AnnotationHolder) {
        if (!plain) return
        val reference = StateTargets.referenceAt(value) ?: return
        val start = value.textRange.startOffset
        mark(holder, reference.rangeInElement.shiftRight(start), AslHighlighting.STATE_REFERENCE)
    }

    /** [range] of the key written as [text], without the quotes around it. */
    private fun unquoted(text: String, range: TextRange): TextRange =
        if (QuotedScalars.isQuoted(text.trim())) TextRange(range.startOffset + 1, range.endOffset - 1) else range

    private fun mark(holder: AnnotationHolder, range: TextRange, key: TextAttributesKey) {
        if (range.isEmpty) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }

    private companion object {
        val SESSION = Key.create<Session>("io.github.saigyo.aslan.highlight.session")
    }
}
