package io.github.saigyo.aslan.jsonata.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.jsonata.parser.JsonataElements

open class JsonataElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    /** The child nodes of the tree, without tokens, whitespace, comments and error elements. */
    val operands: List<PsiElement> get() = children.filterIsInstance<JsonataElement>()
}

/** A `$name` (`VARIABLE_REF`) or a parameter of a function definition (`PARAMETER`). */
class JsonataVariableRef(node: ASTNode) : JsonataElement(node) {
    /** The name without the leading `$`. */
    val variableName: String get() = text.removePrefix("$")
}

class JsonataCall(node: ASTNode) : JsonataElement(node) {
    val callee: PsiElement? get() = operands.firstOrNull()
    val arguments: List<PsiElement> get() = operands.drop(1)
}

class JsonataLambda(node: ASTNode) : JsonataElement(node) {
    val parameters: List<JsonataVariableRef>
        get() = operands.filterIsInstance<JsonataVariableRef>().filter { it.node.elementType == JsonataElements.PARAMETER }
    val body: PsiElement?
        get() = operands.lastOrNull()?.takeIf {
            it.node.elementType != JsonataElements.PARAMETER && it.node.elementType != JsonataElements.SIGNATURE
        }
}

class JsonataPath(node: ASTNode) : JsonataElement(node) {
    val lhs: PsiElement? get() = operands.firstOrNull()
    val rhs: PsiElement? get() = operands.getOrNull(1)
}
