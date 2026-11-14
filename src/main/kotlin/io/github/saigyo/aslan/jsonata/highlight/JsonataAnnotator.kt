package io.github.saigyo.aslan.jsonata.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.highlight.AslHighlighting
import io.github.saigyo.aslan.jsonata.parser.JsonataElements
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.tree.Placeholders

/**
 * Colours what the tokens alone do not tell: built-in functions and `$states` (only where no scope declares the name),
 * the `function` or `λ` that heads a lambda, and the placeholders inside string literals.
 */
class JsonataAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val type = element.node?.elementType ?: return
        when (type) {
            JsonataElements.VARIABLE_REF -> variable(element, holder)
            JsonataElements.LAMBDA -> lambda(element, holder)
            JsonataTokens.STRING, JsonataTokens.BAD_STRING -> placeholders(element, holder)
        }
    }

    private fun variable(element: PsiElement, holder: AnnotationHolder) {
        val ref = element as? JsonataVariableRef ?: return
        val name = ref.variableName
        if (name != "states" && AslSpec.jsonataFunction(name) == null) return
        if (JsonataScopes.declarations(ref).isNotEmpty()) return
        mark(holder, ref.textRange, if (name == "states") JsonataHighlighting.STATES else JsonataHighlighting.FUNCTION)
    }

    private fun lambda(element: PsiElement, holder: AnnotationHolder) {
        val head = element.node.firstChildNode ?: return
        if (head.elementType == JsonataTokens.NAME && (head.text == "function" || head.text == "λ")) {
            mark(holder, head.textRange, JsonataHighlighting.KEYWORD)
        }
    }

    private fun placeholders(element: PsiElement, holder: AnnotationHolder) {
        val start = element.textRange.startOffset
        for (found in Placeholders.ranges(element.text)) {
            mark(holder, TextRange(start + found.first, start + found.last + 1), AslHighlighting.PLACEHOLDER)
        }
    }

    private fun mark(holder: AnnotationHolder, range: TextRange, key: TextAttributesKey) {
        if (range.isEmpty) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}
