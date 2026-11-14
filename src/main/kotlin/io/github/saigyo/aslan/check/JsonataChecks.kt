package io.github.saigyo.aslan.check

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.jsonata.parser.JsonataElements as E
import io.github.saigyo.aslan.jsonata.parser.JsonataParser
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.jsonata.psi.JsonataCall
import io.github.saigyo.aslan.jsonata.psi.JsonataElement
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPath
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.JsonataSite
import io.github.saigyo.aslan.model.JsonataSites
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.StatesField

/**
 * Reports the syntax error of each JSONata expression, on the characters inside the host string where the scanner gave
 * up; in an expression without one, the checks of [Expression]. An expression with a deployment-time placeholder
 * outside a string literal is not judged: the tool that replaces the placeholder changes the expression.
 */
internal object JsonataChecks {
    fun check(doc: AslDocument, out: MutableList<AslFinding>) {
        val hosts = LinkedHashSet<PsiElement>()
        val values = HashMap<PsiElement, String>()
        for (site in doc.jsonataSites.all) {
            hosts += site.scalar.psi
            values[site.scalar.psi] = site.scalar.text
        }
        for (host in hosts) {
            val file = JsonataPsi.injectedFile(host)
            if (file == null) {
                emptyExpression(host, values.getValue(host), out)
                continue
            }
            if (!JsonataPsi.judged(file)) continue
            val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
            if (error != null) {
                out += finding(host, file, error)
                continue
            }
            if (complete(file)) Expression(host, file, doc.jsonataSites.at(host), out).check()
        }
    }

    /**
     * Whether the tree covers the whole expression: one node, with nothing but whitespace and comments beside it. A tree
     * too deep for the scanner to follow is cut off without an error, and the tokens after the cut stand outside it.
     */
    private fun complete(file: JsonataFile): Boolean {
        var nodes = 0
        var child = file.firstChild
        while (child != null) {
            when {
                child is JsonataElement -> nodes++
                child is PsiWhiteSpace || child.node.elementType == JsonataTokens.COMMENT || child.textLength == 0 -> {}
                else -> return false
            }
            child = child.nextSibling
        }
        return nodes == 1
    }

    /**
     * The checks inside one expression without a syntax error, whose host the states of [sites] use: the fields of
     * `$states`, the functions called, the operators of JSONata 2.1, and the names evaluated against the input of the
     * expression itself.
     */
    private class Expression(val host: PsiElement, val file: JsonataFile, val sites: List<JsonataSite>, val out: MutableList<AslFinding>) {
        private val manager = InjectedLanguageManager.getInstance(file.project)

        fun check() {
            newerOperators()
            // one walk with an explicit stack, since trees can be hundreds of levels deep; the flag is "without an input"
            val stack = ArrayList<Pair<PsiElement, Boolean>>()
            for (child in file.children) if (child is JsonataElement) stack += child to true
            while (stack.isNotEmpty()) {
                val (element, bare) = stack.removeAt(stack.size - 1)
                visit(element, bare)
                val operands = (element as JsonataElement).operands
                for (i in operands.indices.reversed()) stack += operands[i] to (bare && inherits(element, operands[i], i))
            }
        }

        private fun visit(element: PsiElement, bare: Boolean) {
            when (element.node.elementType) {
                E.FIELD -> if (bare) withoutInput(element, "Field '${name(element)}'")
                E.CONTEXT_REF -> if (!bound(element) && (bare || element.text == "\$\$")) withoutInput(element, "'${element.text}'")
                E.VARIABLE_REF -> function(element as JsonataVariableRef)
                E.PATH -> statesField(element as JsonataPath)
            }
        }

        /** `?:` and `??` of JSONata 2.1, which the JSONata of Step Functions does not know; the lexer reads none in a signature. */
        private fun newerOperators() {
            var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
            while (leaf != null) {
                val type = leaf.node.elementType
                if ((type == JsonataTokens.ELVIS || type == JsonataTokens.COALESCE) && PsiTreeUtil.getParentOfType(leaf, JsonataElement::class.java)
                        ?.node?.elementType != E.SIGNATURE) {
                    report(AslProblem.NEWER_JSONATA_OPERATOR, leaf,
                        "The operator '${leaf.text}' is not available: Step Functions runs a JSONata version without it")
                }
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
        }

        /** The callee of a call that is neither a local variable nor a known function, and any use of a function Step Functions does not run. */
        private fun function(variable: JsonataVariableRef) {
            val name = JsonataScopes.localName(variable) ?: return
            if (JsonataScopes.declarations(variable).isNotEmpty()) return
            val known = AslSpec.jsonataFunction(name)
            val call = variable.parent as? JsonataCall
            when {
                known != null && !known.supported ->
                    report(AslProblem.UNSUPPORTED_JSONATA_FUNCTION, variable, "Step Functions does not support the function '\$$name'")
                known == null && call != null && call.callee === variable ->
                    report(AslProblem.UNKNOWN_JSONATA_FUNCTION, variable, "Unknown function '\$$name'")
            }
        }

        /** `$states.name`, where `$states` is the built-in: a name that is no field of it, or a field that a use of the expression does not have. */
        private fun statesField(path: JsonataPath) {
            val lhs = path.lhs as? JsonataVariableRef ?: return
            if (lhs.node.elementType != E.VARIABLE_REF || JsonataScopes.localName(lhs) != "states") return
            if (JsonataScopes.declarations(lhs).isNotEmpty()) return
            val rhs = path.rhs?.takeIf { it.node.elementType == E.FIELD } ?: return
            val name = name(rhs)
            val field = StatesField.entries.firstOrNull { it.text == name }
            if (field == null) {
                report(AslProblem.STATES_FIELD_UNAVAILABLE, rhs,
                    "Field '\$states.$name' does not exist; the fields of \$states are input, context, result and errorOutput")
                return
            }
            val site = sites.firstOrNull { field !in it.provides } ?: return
            val where = if (field == StatesField.RESULT) "the Output and Assign of a Task, Map or Parallel state" else "the Output and Assign of a catcher"
            report(AslProblem.STATES_FIELD_UNAVAILABLE, rhs,
                "Field '\$states.$name' does not exist here, in state '${site.state.name}'; it exists only in $where")
        }

        private fun withoutInput(element: PsiElement, what: String) =
            report(AslProblem.JSONATA_WITHOUT_INPUT, element,
                "$what refers to the input of the expression, which Step Functions does not provide; use \$states.input")

        /** The name of a `FIELD`, decoded, without backquotes. */
        private fun name(field: PsiElement): String {
            val text = manager.getUnescapedText(field)
            return if (text.length >= 2 && text.startsWith('`') && text.endsWith('`')) text.substring(1, text.length - 1) else text
        }

        private fun report(problem: AslProblem, element: PsiElement, message: String) {
            val range = JsonataPsi.rangeInHost(file, element.textRange)
                ?.takeIf { it.first == host && !it.second.isEmpty && it.second.endOffset <= host.textLength }?.second
            out += AslFinding(problem, host, message, range)
        }
    }

    /**
     * Whether [child], the operand at [index] of [parent], is evaluated against the same input as [parent]. This decides
     * between an error and silence, so it lists only what AWS was shown to reject without an input; every other place
     * is taken to have an input. Inheriting: the left side of a path and of `~>`, the items of an array, the pairs of an
     * object constructor and both their parts, a block, the operands of a negation, of a binary operator and of a
     * conditional, the arguments of a call of a variable or a function definition (unless an argument is a partial
     * application's `?`), and the body of a function definition that is called directly or given to one of the built-in
     * functions that call their function argument.
     */
    private fun inherits(parent: PsiElement, child: PsiElement, index: Int): Boolean {
        if (child.node.elementType == E.LAMBDA && !calledLambda(parent, child, index)) return false
        return when (parent.node.elementType) {
            E.BLOCK, E.ARRAY, E.OBJECT, E.NEGATION, E.CONDITIONAL, E.LAMBDA -> true
            E.PAIR -> parent.parent?.node?.elementType == E.OBJECT
            E.PATH -> index == 0
            E.BINARY -> index == 0 || parent.node.findChildByType(JsonataTokens.CHAIN) == null
            E.CALL -> {
                val callee = (parent as JsonataCall).callee
                if (index == 0) {
                    child.node.elementType == E.LAMBDA
                } else {
                    val type = callee?.node?.elementType
                    (type == E.VARIABLE_REF || type == E.LAMBDA) && parent.operands.none { it.node.elementType == E.PARTIAL_ARGUMENT }
                }
            }
            else -> false
        }
    }

    /** A function definition that is the callee of a call, or an argument of a built-in function that calls it with each item. */
    private fun calledLambda(parent: PsiElement, child: PsiElement, index: Int): Boolean {
        if (parent !is JsonataCall) return false
        if (index == 0) return true
        val callee = parent.callee as? JsonataVariableRef ?: return false
        if (callee.node.elementType != E.VARIABLE_REF || JsonataScopes.declarations(callee).isNotEmpty()) return false
        return JsonataScopes.localName(callee) in CALLING
    }

    /** The built-in functions that call their function argument for each item, as `$map` does (AWS was shown `$map`). */
    private val CALLING = setOf("map", "filter", "single", "reduce", "sift", "each", "sort")

    /** `$` or `$$` as the variable a `@`, `#` or `:=` binds, which names no input. */
    private fun bound(ref: PsiElement): Boolean {
        val parent = ref.parent as? JsonataElement ?: return false
        return when (parent.node.elementType) {
            E.FOCUS_BINDING, E.INDEX_BINDING -> parent.operands.lastOrNull() === ref
            E.ASSIGNMENT -> parent.operands.firstOrNull() === ref
            E.FILTER -> parent.operands.size == 1 && (parent.parent as? JsonataElement)?.let {
                it.node.elementType == E.ASSIGNMENT && it.operands.firstOrNull() === parent
            } == true
            else -> false
        }
    }

    /** An empty expression has no injected file; the error is reported on the closing `%}` found in the host's text. */
    private fun emptyExpression(host: PsiElement, value: String, out: MutableList<AslFinding>) {
        if (value.length < JsonataSites.OPEN.length + JsonataSites.CLOSE.length) return
        if (value.substring(JsonataSites.OPEN.length, value.length - JsonataSites.CLOSE.length).isNotBlank()) return
        val close = host.text.lastIndexOf(JsonataSites.CLOSE)
        val range = if (close < 0) null else TextRange(close, close + JsonataSites.CLOSE.length)
        out += AslFinding(AslProblem.JSONATA_SYNTAX_ERROR, host, message("S0207", null), range)
    }

    private fun finding(host: PsiElement, file: JsonataFile, error: PsiErrorElement): AslFinding {
        val next = skip(error, forward = true)
        val token = next ?: skip(error, forward = false)
        val tokenRange = token?.textRange ?: TextRange(0, file.textLength)
        val range = JsonataPsi.rangeInHost(file, tokenRange)
            ?.takeIf { it.first == host && !it.second.isEmpty && it.second.endOffset <= host.textLength }?.second
        return AslFinding(AslProblem.JSONATA_SYNTAX_ERROR, host, message(JsonataParser.codeOf(error), next?.let { InjectedLanguageManager.getInstance(file.project).getUnescapedText(it) }), range)
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
            "S0211" -> "Symbol${shown?.let { " $it" }.orEmpty()} cannot be used as a unary operator"
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
