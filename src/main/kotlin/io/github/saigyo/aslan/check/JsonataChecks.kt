package io.github.saigyo.aslan.check

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.jsonata.parser.JsonataParser
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.JsonataSites
import io.github.saigyo.aslan.tree.Placeholders

/**
 * Reports the syntax error of each JSONata expression, on the characters inside the host string where the scanner gave
 * up. An expression with a deployment-time placeholder outside a string literal is not judged: the tool that replaces
 * the placeholder changes the expression.
 */
internal object JsonataChecks {
    fun check(doc: AslDocument, out: MutableList<AslFinding>) {
        val hosts = LinkedHashSet<PsiElement>()
        for (site in doc.jsonataSites.all) hosts += site.scalar.psi
        for (host in hosts) {
            val file = JsonataPsi.injectedFile(host)
            if (file == null) {
                emptyExpression(host, out)
                continue
            }
            val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) ?: continue
            if (hasPlaceholderOutsideString(file)) continue
            out += finding(host, file, error)
        }
    }

    /** `{%%}` has no injected file; the error is reported on its closing `%}`. */
    private fun emptyExpression(host: PsiElement, out: MutableList<AslFinding>) {
        if (host.text.trim('\'', '"') != JsonataSites.OPEN + JsonataSites.CLOSE) return
        val close = host.text.lastIndexOf(JsonataSites.CLOSE)
        if (close < 0) return
        out += AslFinding(AslProblem.JSONATA_SYNTAX_ERROR, host, message("S0207", null), TextRange(close, close + JsonataSites.CLOSE.length))
    }

    private fun hasPlaceholderOutsideString(file: JsonataFile): Boolean {
        val leaves = leaves(file)
        if (leaves.any { it.node.elementType == JsonataTokens.PLACEHOLDER }) return true
        val strings = leaves.filter { it.node.elementType == JsonataTokens.STRING }.map { it.textRange }
        return Placeholders.ranges(file.text).any { p -> strings.none { it.startOffset <= p.first && p.last < it.endOffset } }
    }

    private fun leaves(file: JsonataFile): List<PsiElement> {
        val out = ArrayList<PsiElement>()
        var leaf = PsiTreeUtil.firstChild(file)
        while (true) {
            if (leaf.firstChild == null && leaf.textLength > 0) out += leaf
            leaf = PsiTreeUtil.nextLeaf(leaf) ?: break
        }
        return out
    }

    private fun finding(host: PsiElement, file: JsonataFile, error: PsiErrorElement): AslFinding {
        val next = skip(error, forward = true)
        val token = next ?: skip(error, forward = false)
        val tokenRange = token?.textRange ?: TextRange(0, file.textLength)
        val range = JsonataPsi.rangeInHost(file, tokenRange)
            ?.takeIf { it.first == host && !it.second.isEmpty && it.second.endOffset <= host.textLength }?.second
        return AslFinding(AslProblem.JSONATA_SYNTAX_ERROR, host, message(JsonataParser.codeOf(error), next?.text), range)
    }

    /** The nearest leaf from [from] on, in the given direction, that is neither whitespace nor a comment. */
    private fun skip(from: PsiElement, forward: Boolean): PsiElement? {
        var leaf = if (forward) PsiTreeUtil.nextLeaf(from) else PsiTreeUtil.prevLeaf(from)
        while (leaf != null) {
            val type = leaf.node.elementType
            if (leaf.textLength > 0 && leaf !is PsiWhiteSpace && type != JsonataTokens.COMMENT && leaf !is PsiErrorElement) return leaf
            leaf = if (forward) PsiTreeUtil.nextLeaf(leaf) else PsiTreeUtil.prevLeaf(leaf)
        }
        return null
    }

    /** A plain sentence for the error [code] of the reference parser; [token] is the text the parser stopped at. */
    internal fun message(code: String?, token: String?): String {
        val shown = token?.let { "'" + (if (it.length > 20) it.take(20) + "..." else it) + "'" }
        val at = shown?.let { " at $it" }.orEmpty()
        return when (code) {
            "S0101" -> "String literal is not closed"
            "S0102" -> "Number out of range$at"
            "S0103" -> "Unsupported escape sequence in string$at"
            "S0104" -> "Escape sequence \\u must be followed by 4 hex digits"
            "S0105" -> "Quoted name is not closed by a backquote"
            "S0106" -> "Comment is not closed by */"
            "S0201" -> "Syntax error$at"
            "S0202" -> "Unexpected token$at"
            "S0203" -> "Expression ends before a closing token"
            "S0204" -> "Unknown operator$at"
            "S0207" -> "Expression ends unexpectedly"
            "S0208" -> "Parameter of a function definition must be a variable name starting with \$"
            "S0209" -> "A predicate cannot follow a grouping expression in a step"
            "S0210" -> "Each step can have only one grouping expression"
            "S0211" -> "Symbol$at cannot be used as a unary operator"
            "S0212" -> "The left side of := must be a variable name starting with \$"
            "S0213" -> "A literal value cannot be a step in a path"
            "S0214" -> "The right side of the operator must be a variable name starting with \$"
            "S0215" -> "A context variable binding must come before any predicate on a step"
            "S0216" -> "A context variable binding must come before an order-by clause on a step"
            "S0301" -> "Empty regular expression"
            "S0302" -> "Regular expression is not closed by /"
            else -> "JSONata syntax error" + (code?.let { " $it" }.orEmpty()) + at
        }
    }
}
