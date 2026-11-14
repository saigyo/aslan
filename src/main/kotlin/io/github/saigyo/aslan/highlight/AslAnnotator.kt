package io.github.saigyo.aslan.highlight

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.refs.StateTargets
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.QuotedScalars
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Colours ASL field names, state names, transition targets and `${name}` placeholders. It is registered for all YAML
 * and JSON files; the model lookup, done once per annotation session, tells whether the file is an ASL file.
 */
class AslAnnotator : Annotator {
    /** The model of the file being annotated, or null for a file that is not an ASL file. */
    private class Session(val document: AslDocument?)

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val target = AslHighlighting.textElement(element) ?: return
        val document = session(target, holder).document ?: return
        if (AslHighlighting.isKey(target)) key(target, document, holder) else value(target, document, holder)
        for (range in AslHighlighting.placeholderRanges(target)) mark(holder, range, AslHighlighting.PLACEHOLDER)
    }

    private fun session(element: PsiElement, holder: AnnotationHolder): Session {
        val data = holder.currentAnnotationSession
        data.getUserData(SESSION)?.let { return it }
        return Session(AslModel.of(element.containingFile)).also { data.putUserData(SESSION, it) }
    }

    private fun key(key: PsiElement, document: AslDocument, holder: AnnotationHolder) {
        val range = key.textRange
        if (document.schema.field(key) != null) {
            mark(holder, range, AslHighlighting.FIELD)
        } else if (document.schema.stateNameKey(key) != null && !Placeholders.contains(key.text)) {
            mark(holder, unquoted(key.text, range), AslHighlighting.STATE_NAME)
        }
    }

    private fun value(value: PsiElement, document: AslDocument, holder: AnnotationHolder) {
        if (value !is YAMLScalar && value !is JsonStringLiteral) return
        if (Placeholders.contains(value.text)) return
        val key = StateTargets.keyOf(value) ?: return
        if (document.schema.field(key)?.spec?.kind != ValueKind.StateName) return
        val relative = StateTargets.valueRange(value)
        val start = value.textRange.startOffset
        mark(holder, TextRange(start + relative.startOffset, start + relative.endOffset), AslHighlighting.STATE_REFERENCE)
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
