package io.github.saigyo.aslan.jsonata.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.saigyo.aslan.jsonata.parser.JsonataElements
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens

/**
 * The local variables of a JSONata expression. A scope is a `LAMBDA`, a `BLOCK`, or the file. A lambda declares its
 * parameters; a lambda, a block or the file declares every name that is the target of `:=`, or bound by `@` or `#`,
 * inside it and not inside a nested block or lambda.
 *
 * A parameter is visible in the whole lambda. A name declared by `:=`, `@` or `#` is visible in its scope from the end
 * of its first declaration there on (for `:=` after the right side of that first assignment, which JSONata evaluates
 * before it binds); an occurrence before that end does not refer to this scope and is resolved outward from the
 * enclosing one. An occurrence inside a lambda nested in the scope refers to it wherever it stands, since the function
 * runs later. The target of the first declaration is the declaration itself. A name that no scope makes visible is not
 * local (a function, `$states`, a state machine variable) and has no declarations here. `$` and `$$` are never
 * variables.
 *
 * Every file is its own world; the answers for a file are computed in one walk and cached until the file changes.
 */
object JsonataScopes {
    /** The declarations [variable] refers to, in file order; empty when the name is not declared locally. */
    fun declarations(variable: JsonataVariableRef): List<JsonataVariableRef> {
        val bindings = bindings(variable) ?: return emptyList()
        return bindings.declarations[bindings.firstOf[variable]].orEmpty()
    }

    /** Whether [variable] is a parameter, the target of `:=`, or bound by `@` or `#`. */
    fun isDeclaration(variable: JsonataVariableRef): Boolean {
        if (localName(variable) == null) return false
        if (variable.node.elementType == JsonataElements.PARAMETER) return true
        val parent = variable.parent as? JsonataElement ?: return false
        return when (parent.node.elementType) {
            JsonataElements.ASSIGNMENT -> parent.operands.firstOrNull() === variable
            JsonataElements.FILTER -> {
                // `$x[] := 1`: the filter has the variable as its single child and is the target of the assignment
                val assignment = parent.parent as? JsonataElement
                parent.operands.size == 1 && assignment != null &&
                    assignment.node.elementType == JsonataElements.ASSIGNMENT && assignment.operands.firstOrNull() === parent
            }
            JsonataElements.FOCUS_BINDING, JsonataElements.INDEX_BINDING ->
                parent.operands.let { it.size >= 2 && it.last() === variable }
            else -> false
        }
    }

    /** The local names visible at [at], innermost scope first; within a scope in the order of their first declaration. */
    fun visibleNames(at: PsiElement): List<String> {
        val file = at.containingFile as? JsonataFile ?: return emptyList()
        val bindings = bindings(file)
        val offset = at.textRange.startOffset
        val names = LinkedHashSet<String>()
        var nested = false
        var element: PsiElement? = at
        while (element != null) {
            bindings.declared[element]?.forEach { (name, found) -> if (visible(offset, found.first(), nested)) names += name }
            if (element is PsiFile) break
            if (element.node.elementType == JsonataElements.LAMBDA) nested = true
            element = element.parent
        }
        return names.toList()
    }

    /** Every declaration and use of the local variable [variable] stands for, in file order; empty for a name that is not local. */
    fun occurrences(variable: JsonataVariableRef): List<JsonataVariableRef> {
        val bindings = bindings(variable) ?: return emptyList()
        val first = bindings.firstOf[variable] ?: return emptyList()
        return bindings.occurrences[first].orEmpty()
    }

    /** Every `$name` of [file] that can be a local variable (see [localName]), local or not, in file order. */
    internal fun variables(file: JsonataFile): List<JsonataVariableRef> = bindings(file).variables

    /** The name of [variable] without `$`, or null where it cannot be a local variable: `$`, `$$`, a placeholder. */
    internal fun localName(variable: JsonataVariableRef): String? {
        val token = variable.node.firstChildNode ?: return null
        if (token.elementType != JsonataTokens.VARIABLE) return null
        val name = token.text.substring(1)
        return name.takeIf { it.isNotEmpty() && !it.startsWith("$") }
    }

    private class Bindings(
        /** For each scope, its declarations by name, names in the order of their first declaration. */
        val declared: Map<PsiElement, Map<String, List<JsonataVariableRef>>>,
        /** For each local variable, the first declaration of the variable it stands for. */
        val firstOf: Map<JsonataVariableRef, JsonataVariableRef>,
        /** For each first declaration, the declarations of its variable in file order. */
        val declarations: Map<JsonataVariableRef, List<JsonataVariableRef>>,
        /** For each first declaration, every occurrence of its variable in file order. */
        val occurrences: Map<JsonataVariableRef, List<JsonataVariableRef>>,
        /** Every variable with a [localName], in file order. */
        val variables: List<JsonataVariableRef>,
    )

    /**
     * Whether a name whose first declaration in a scope is [first] is visible at [offset]: always for a parameter or
     * from inside a lambda [nested] in the scope, otherwise from the end of that declaration on.
     */
    private fun visible(offset: Int, first: JsonataVariableRef, nested: Boolean): Boolean =
        nested || first.node.elementType == JsonataElements.PARAMETER || offset >= declarationEnd(first)

    /** Where the declaration with the target [variable] ends: the whole assignment for `:=`, the variable for `@` and `#`. */
    private fun declarationEnd(variable: JsonataVariableRef): Int {
        var element: PsiElement = variable
        val parent = element.parent
        if (parent != null && parent.node.elementType == JsonataElements.FILTER) element = parent
        val assignment = element.parent
        if (assignment != null && assignment.node.elementType == JsonataElements.ASSIGNMENT) return assignment.textRange.endOffset
        return variable.textRange.endOffset
    }

    private fun bindings(variable: JsonataVariableRef): Bindings? =
        (variable.containingFile as? JsonataFile)?.let { bindings(it) }

    private fun bindings(file: JsonataFile): Bindings =
        CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(compute(file), file) }

    private fun isScope(element: PsiElement): Boolean =
        element.node.elementType.let { it == JsonataElements.BLOCK || it == JsonataElements.LAMBDA }

    /** One walk in file order with an explicit stack (trees can be hundreds of levels deep), then one lookup per variable. */
    private fun compute(file: JsonataFile): Bindings {
        val declared = HashMap<PsiElement, LinkedHashMap<String, MutableList<JsonataVariableRef>>>()
        val enclosing = HashMap<PsiElement, PsiElement>()
        val variables = ArrayList<Pair<JsonataVariableRef, PsiElement>>()
        val stack = ArrayList<Pair<PsiElement, PsiElement>>()
        pushChildren(file, file, stack)
        while (stack.isNotEmpty()) {
            val (element, scope) = stack.removeAt(stack.size - 1)
            if (element is JsonataVariableRef) {
                val name = localName(element)
                if (name != null) {
                    variables += element to scope
                    if (isDeclaration(element)) declared.getOrPut(scope) { LinkedHashMap() }.getOrPut(name) { ArrayList() } += element
                }
            }
            val inner = if (isScope(element)) element.also { enclosing[it] = scope } else scope
            pushChildren(element, inner, stack)
        }
        val firstOf = HashMap<JsonataVariableRef, JsonataVariableRef>()
        val occurrences = HashMap<JsonataVariableRef, MutableList<JsonataVariableRef>>()
        val declaring = HashSet<JsonataVariableRef>()
        for ((variable, start) in variables) {
            val name = localName(variable)!!
            val offset = variable.textRange.startOffset
            var nested = false
            var scope: PsiElement? = start
            while (scope != null) {
                val first = declared[scope]?.get(name)?.first()
                if (first != null && (first === variable || visible(offset, first, nested))) {
                    firstOf[variable] = first
                    occurrences.getOrPut(first) { ArrayList() } += variable
                    if (scope === start && isDeclaration(variable)) declaring += variable
                    break
                }
                if (scope.node.elementType == JsonataElements.LAMBDA) nested = true
                scope = enclosing[scope]
            }
        }
        // a declaration that stands before the end of the first one of its scope (`$a := $a := 1`) is resolved outward
        // like a use and declares nothing there
        val declarations = occurrences.mapValues { (_, all) -> all.filter { it in declaring } }
        return Bindings(declared, firstOf, declarations, occurrences, variables.map { it.first })
    }

    /** Pushes the composite children of [element], each with the scope it stands in, so that they are popped in file order. */
    private fun pushChildren(element: PsiElement, scope: PsiElement, stack: MutableList<Pair<PsiElement, PsiElement>>) {
        val at = stack.size
        var child = element.firstChild
        while (child != null) {
            if (child.firstChild != null) stack.add(child to scope)
            child = child.nextSibling
        }
        stack.subList(at, stack.size).reverse()
    }
}
