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
    /** The first child is the variable assigned. */
    @JvmField val ASSIGNMENT = JsonataElementType("ASSIGNMENT")
    @JvmField val CONDITIONAL = JsonataElementType("CONDITIONAL")
    /** The first child is the callee, the others are the arguments. */
    @JvmField val CALL = JsonataElementType("CALL")
    @JvmField val PARTIAL_ARGUMENT = JsonataElementType("PARTIAL_ARGUMENT")
    /** Children: the [PARAMETER]s, an optional [SIGNATURE], the body last. */
    @JvmField val LAMBDA = JsonataElementType("LAMBDA")
    @JvmField val PARAMETER = JsonataElementType("PARAMETER")
    @JvmField val SIGNATURE = JsonataElementType("SIGNATURE")
    /** `e[p]`, and `e[]` with the single child `e`. */
    @JvmField val FILTER = JsonataElementType("FILTER")
    @JvmField val GROUP_BY = JsonataElementType("GROUP_BY")
    @JvmField val ORDER_BY = JsonataElementType("ORDER_BY")
    @JvmField val SORT_TERM = JsonataElementType("SORT_TERM")
    /** The last child is the variable bound. */
    @JvmField val FOCUS_BINDING = JsonataElementType("FOCUS_BINDING")
    /** The last child is the variable bound. */
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
