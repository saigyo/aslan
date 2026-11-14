package io.github.saigyo.aslan.jsonata.parser

import io.github.saigyo.aslan.jsonata.parser.JsonataElements as E

/*
 * Ported from processAST of jsonata-js 2.0.6 (src/parser.js, lines 998 to 1355), © Copyright IBM Corp. 2016, 2018,
 * MIT licence, see META-INF/licenses/jsonata-LICENSE.txt.
 *
 * processAST rewrites the tree into paths of steps and throws while doing so. This port computes, for every node, only
 * what the throw sites S0209, S0210, S0213, S0215 and S0216 read: whether the result is a path, its steps, and whether a
 * step or the path has a group, a predicate or stages, or is a sort. Left out: the parent operator (seekParent,
 * pushAncestry, resolveAncestry and S0217), the tail-call rewriting, keepArray and consarray, and the default case,
 * whose S0207 the scanner raises.
 */
internal object JsonataTreeChecks {
    /** The first error processAST throws for the complete tree [root] of [text], or null. */
    fun check(root: ScanNode, text: CharSequence): ScanError? =
        try {
            Checks(text).process(root)
            null
        } catch (found: Found) {
            found.error
        } catch (_: StackOverflowError) {
            null // nested too deeply to follow; not reported
        }
}

private class Found(val error: ScanError) : RuntimeException(null, null, false, false)

private enum class Kind { NUMBER, VALUE, SORT, OTHER }

/** A node of the reference that is not a path; as a step of a path it may carry a group, predicates or stages. */
private class Step(val kind: Kind, val start: Int, val end: Int) {
    var group = false
    var predicate = false
    var stages = false
}

/** A result of type 'path'. */
private class Path(val steps: MutableList<Step>) {
    var group = false
}

private class Checks(private val text: CharSequence) {
    private fun other(node: ScanNode) = Step(Kind.OTHER, node.start, node.end)

    private fun found(code: String, start: Int, end: Int): Nothing = throw Found(ScanError(code, start, end))

    /** The range of the one-character operator token that follows the left operand [lhs]. */
    private fun operatorAfter(lhs: ScanNode, code: String): Nothing {
        var i = lhs.end
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B') i++
            else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val close = text.indexOf("*/", i + 2)
                i = if (close < 0) text.length else close + 2
            } else break
        }
        found(code, i, minOf(i + 1, text.length))
    }

    private fun literal(node: ScanNode): Step {
        val c = text[node.start]
        val kind = when {
            c in '0'..'9' -> Kind.NUMBER
            c == '"' || c == '\'' -> Kind.OTHER // a string; as a step it becomes a name
            else -> Kind.VALUE // true, false, null
        }
        return Step(kind, node.start, node.end)
    }

    /** parser.js: var processAST. Returns a [Path] or a [Step]. */
    fun process(node: ScanNode): Any {
        val children = node.children
        return when (node.type) {
            E.PATH -> {
                val lstep = process(children[0])
                val result = lstep as? Path ?: Path(mutableListOf(lstep as Step))
                val rest = process(children[1])
                if (rest is Path) result.steps.addAll(rest.steps)
                else {
                    rest as Step
                    if (rest.predicate) {
                        rest.stages = true
                        rest.predicate = false
                    }
                    result.steps.add(rest)
                }
                // any steps within a path that are string literals, should be changed to 'name'
                // don't allow steps to be numbers or the values true/false/null
                for (step in result.steps) if (step.kind == Kind.NUMBER || step.kind == Kind.VALUE) found("S0213", step.start, step.end)
                result
            }
            E.FILTER -> {
                // e[] has no node of its own in the reference: the parser returns e, flagged to keep singleton arrays
                if (children.size == 1) return process(children[0])
                // predicated step
                val result = process(children[0])
                val step = if (result is Path) result.steps.last() else result as Step
                if (step.group) operatorAfter(children[0], "S0209")
                process(children[1])
                if (result is Path) step.stages = true else step.predicate = true
                result
            }
            E.GROUP_BY -> {
                val result = process(children[0])
                if (if (result is Path) result.group else (result as Step).group) operatorAfter(children[0], "S0210")
                // object constructor - process each pair
                for (pair in children.drop(1)) pair.children.forEach { process(it) }
                if (result is Path) result.group = true else (result as Step).group = true
                result
            }
            E.ORDER_BY -> {
                val lhs = process(children[0])
                val result = lhs as? Path ?: Path(mutableListOf(lhs as Step))
                val sortStep = Step(Kind.SORT, node.start, node.end)
                for (term in children.drop(1)) term.children.forEach { process(it) }
                result.steps.add(sortStep)
                result
            }
            E.FOCUS_BINDING -> {
                val result = process(children[0])
                val step = if (result is Path) result.steps.last() else result as Step
                // throw error if there are any predicates defined at this point
                if (step.stages || step.predicate) operatorAfter(children[0], "S0215")
                // also throw if this is applied after an 'order-by' clause
                if (step.kind == Kind.SORT) operatorAfter(children[0], "S0216")
                result
            }
            E.INDEX_BINDING -> {
                val lhs = process(children[0])
                if (lhs is Path) lhs
                else {
                    val step = lhs as Step
                    if (step.predicate) {
                        step.stages = true
                        step.predicate = false
                    }
                    Path(mutableListOf(step))
                }
                // an index stage is added to existing stages, or the step gets an index: neither is read by a throw site
            }
            E.NEGATION -> {
                val expression = process(children[0])
                // if unary minus on a number, then pre-process
                if (expression is Step && expression.kind == Kind.NUMBER) expression else other(node)
            }
            E.CALL -> {
                // the arguments first, then the procedure
                children.drop(1).forEach { process(it) }
                process(children[0])
                other(node)
            }
            E.LAMBDA -> {
                children.lastOrNull()?.takeIf { it.type != E.PARAMETER && it.type != E.SIGNATURE }?.let { process(it) }
                other(node)
            }
            E.FIELD -> Path(mutableListOf(other(node)))
            E.LITERAL -> literal(node)
            E.OBJECT -> {
                for (pair in children) pair.children.forEach { process(it) }
                other(node)
            }
            else -> {
                // binary operators, assignment, function application, range, array, block, condition, transform;
                // and the leaves: variables, regular expressions, wildcards, parent, partial arguments, placeholders
                children.forEach { process(it) }
                other(node)
            }
        }
    }
}
