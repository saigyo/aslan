package io.github.saigyo.aslan.jsonata.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import io.github.saigyo.aslan.jsonata.JsonataLanguage
import io.github.saigyo.aslan.jsonata.parser.JsonataElements
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.jsonata.refs.JsonataVariableReference
import io.github.saigyo.aslan.refs.AslVariableTargets

open class JsonataElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    /** The child nodes of the tree, without tokens, whitespace, comments and error elements. */
    val operands: List<PsiElement> get() = children.filterIsInstance<JsonataElement>()
}

/**
 * A `$name` (`VARIABLE_REF`) or a parameter of a function definition (`PARAMETER`). Its name is the name without `$`;
 * [JsonataScopes] decides whether it is a local variable. A local variable that is not its own first declaration has a
 * reference to that first declaration, and a read of a state machine variable one to its assignments; the use scope is
 * the file, since every expression is its own world.
 */
class JsonataVariableRef(node: ASTNode) : JsonataElement(node), PsiNameIdentifierOwner {
    /** The name without the leading `$`, decoded from the host's escapes. */
    val variableName: String get() = JsonataPsi.decodedText(this).removePrefix("$")

    override fun getName(): String = variableName

    /** Replaces the variable token by `$` and [name]; the platform carries the change into the host of an injected file. */
    override fun setName(name: String): PsiElement {
        val token = nameIdentifier ?: throw IncorrectOperationException("not a variable: $text")
        token.node.treeParent.replaceChild(token.node, JsonataElementFactory.variableToken(project, name).node)
        return this
    }

    /** The variable token, `$` included. */
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(JsonataTokens.VARIABLE)?.psi

    /**
     * A local variable's reference to its first declaration, as above; for a `$name` that is no local variable, the
     * reference to the assignments of the state machine variable it reads, when the definition has one.
     */
    override fun getReference(): PsiReference? {
        val target = JsonataScopes.declarations(this).firstOrNull() ?: return AslVariableTargets.readReference(this)
        return if (target === this) null else JsonataVariableReference(this)
    }

    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

    override fun getUseScope(): SearchScope = LocalSearchScope(containingFile)
}

internal object JsonataElementFactory {
    /** A `$name` token, from a JSONata file of that text; fails for a name that does not scan as one variable. */
    fun variableToken(project: Project, name: String): PsiElement {
        val text = "$" + name
        val file = PsiFileFactory.getInstance(project).createFileFromText("variable", JsonataLanguage, text)
        val token = PsiTreeUtil.firstChild(file)
        if (token.node.elementType != JsonataTokens.VARIABLE || token.text != text) throw IncorrectOperationException("not a variable name: $name")
        return token
    }
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
