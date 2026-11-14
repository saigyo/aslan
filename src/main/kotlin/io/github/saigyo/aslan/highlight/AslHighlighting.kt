package io.github.saigyo.aslan.highlight

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.tree.Placeholders
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** The colours of the semantic highlighting, and the helpers shared by the annotator and the placeholder usages. */
object AslHighlighting {
    val FIELD: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("ASL_FIELD", DefaultLanguageHighlighterColors.KEYWORD)
    val STATE_NAME: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("ASL_STATE_NAME", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val STATE_REFERENCE: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("ASL_STATE_REFERENCE", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val PLACEHOLDER: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("ASL_PLACEHOLDER", DefaultLanguageHighlighterColors.METADATA)

    /**
     * [element] when it is a key or a scalar value, the elements whose text can hold a placeholder: the key token of a
     * YAML entry, a YAML scalar, or a JSON string (a name or a value). Null for everything else, comments included.
     */
    internal fun textElement(element: PsiElement): PsiElement? = when (element) {
        is YAMLScalar, is JsonStringLiteral -> element
        else -> element.parent.takeIf { it is YAMLKeyValue && it.key === element }?.let { element }
    }

    /** Whether [element] (as given by [textElement]) is the key of an entry. */
    internal fun isKey(element: PsiElement): Boolean {
        val parent = element.parent
        return (parent is YAMLKeyValue && parent.key === element) || (parent is JsonProperty && parent.nameElement === element)
    }

    /** The ranges in the file of the placeholders written in [element] (from [textElement]), whose text is [text]. */
    internal fun placeholderRanges(element: PsiElement, text: String = element.text): List<TextRange> {
        val found = Placeholders.ranges(text)
        if (found.isEmpty()) return emptyList()
        val start = element.textRange.startOffset
        return found.map { TextRange(start + it.first, start + it.last + 1) }
    }
}
