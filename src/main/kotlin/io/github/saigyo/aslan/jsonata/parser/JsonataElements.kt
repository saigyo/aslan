package io.github.saigyo.aslan.jsonata.parser

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import io.github.saigyo.aslan.jsonata.JsonataLanguage

class JsonataElementType(debugName: String) : IElementType(debugName, JsonataLanguage)

/** The node types of the tree [JsonataScanner] builds. */
object JsonataElements {
    @JvmField val FILE = IFileElementType(JsonataLanguage)
    @JvmField val BLOCK = JsonataElementType("BLOCK")
    @JvmField val ARRAY = JsonataElementType("ARRAY")
    @JvmField val RANGE = JsonataElementType("RANGE")
    @JvmField val OBJECT = JsonataElementType("OBJECT")
    @JvmField val PAIR = JsonataElementType("PAIR")
    @JvmField val NEGATION = JsonataElementType("NEGATION")
    @JvmField val BINARY = JsonataElementType("BINARY")
    /** `lhs . rhs` */
    @JvmField val PATH = JsonataElementType("PATH")
    /**
     * `lhs := rhs`. The first child is what the reference accepts as a variable: a [VARIABLE_REF], a [CONTEXT_REF]
     * (`$ := 1`), a [FILTER] with a single child of these (`$x[] := 1`), or a [PLACEHOLDER_REF].
     */
    @JvmField val ASSIGNMENT = JsonataElementType("ASSIGNMENT")
    @JvmField val CONDITIONAL = JsonataElementType("CONDITIONAL")
    /** The first child is the callee, the others are the arguments. */
    @JvmField val CALL = JsonataElementType("CALL")
    @JvmField val PARTIAL_ARGUMENT = JsonataElementType("PARTIAL_ARGUMENT")
    /** Children: the [PARAMETER]s, an optional [SIGNATURE], the body last. The callee `function` or `λ` is no child. */
    @JvmField val LAMBDA = JsonataElementType("LAMBDA")
    /**
     * The single token of a parameter: a variable (also `$` and `$$`) or a placeholder. For `function($x[]){…}`, which
     * the reference accepts, it covers `$x`, and `[]` are tokens of the [LAMBDA] outside any child.
     */
    @JvmField val PARAMETER = JsonataElementType("PARAMETER")
    @JvmField val SIGNATURE = JsonataElementType("SIGNATURE")
    /** `e[p]`, and `e[]` with the single child `e`. */
    @JvmField val FILTER = JsonataElementType("FILTER")
    @JvmField val GROUP_BY = JsonataElementType("GROUP_BY")
    @JvmField val ORDER_BY = JsonataElementType("ORDER_BY")
    @JvmField val SORT_TERM = JsonataElementType("SORT_TERM")
    /** `e@$v`. The last child is the variable bound: a [VARIABLE_REF], a [CONTEXT_REF] (`a@$`) or a [PLACEHOLDER_REF]. */
    @JvmField val FOCUS_BINDING = JsonataElementType("FOCUS_BINDING")
    /** `e#$v`. The last child is the variable bound: a [VARIABLE_REF], a [CONTEXT_REF] or a [PLACEHOLDER_REF]. */
    @JvmField val INDEX_BINDING = JsonataElementType("INDEX_BINDING")
    @JvmField val TRANSFORM = JsonataElementType("TRANSFORM")
    @JvmField val VARIABLE_REF = JsonataElementType("VARIABLE_REF")
    /** `$` and `$$` */
    @JvmField val CONTEXT_REF = JsonataElementType("CONTEXT_REF")
    /** A name, a backtick name, or `and`, `or`, `in` used as a name. */
    @JvmField val FIELD = JsonataElementType("FIELD")
    @JvmField val LITERAL = JsonataElementType("LITERAL")
    @JvmField val REGEX_LITERAL = JsonataElementType("REGEX_LITERAL")
    @JvmField val WILDCARD = JsonataElementType("WILDCARD")
    @JvmField val DESCENDANTS = JsonataElementType("DESCENDANTS")
    @JvmField val PARENT = JsonataElementType("PARENT")
    @JvmField val PLACEHOLDER_REF = JsonataElementType("PLACEHOLDER_REF")
}
