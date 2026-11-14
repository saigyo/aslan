package io.github.saigyo.aslan.jsonata.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.saigyo.aslan.jsonata.parser.JsonataElements
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens

/**
 * The local variables of a JSONata expression. A scope is a `LAMBDA`, a `BLOCK`, or the file. A lambda declares its
 * parameters. A block or the file declares every name that is the target of `:=`, or bound by `@` or `#`, inside it and
 * not inside a nested block or lambda. A name refers to the nearest enclosing scope that declares it, wherever in that
 * scope the declaration stands. A name that no scope declares is not local (a function, `$states`, a state machine
 * variable) and has no declarations here. `$` and `$$` are never variables.
 *
 * Every file is its own world; the answers for a file are computed in one walk and cached until the file changes.
 */
object JsonataScopes {
    /** The declarations [variable] refers to, in file order; empty when the name is not declared locally. */
    fun declarations(variable: JsonataVariableRef): List<JsonataVariableRef> =
        bindings(variable)?.declarations?.get(variable).orEmpty()

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
        val names = LinkedHashSet<String>()
        var element: PsiElement? = at
        while (element != null) {
            bindings.declared[element]?.let { names += it.keys }
            if (element is PsiFile) break
            element = element.parent
        }
        return names.toList()
    }

    /** Every declaration and use of the local variable [variable] stands for, in file order; empty for a name that is not local. */
    fun occurrences(variable: JsonataVariableRef): List<JsonataVariableRef> {
        val bindings = bindings(variable) ?: return emptyList()
        val first = bindings.declarations[variable]?.firstOrNull() ?: return emptyList()
        return bindings.occurrences[first].orEmpty()
    }

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
        /** For each local variable, the declarations of the scope it refers to. */
        val declarations: Map<JsonataVariableRef, List<JsonataVariableRef>>,
        /** For each first declaration, every occurrence of its variable in file order. */
        val occurrences: Map<JsonataVariableRef, List<JsonataVariableRef>>,
    )

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
                    val declaring = isDeclaration(element) &&
                        (element.node.elementType == JsonataElements.PARAMETER || scope.node.elementType != JsonataElements.LAMBDA)
                    if (declaring) declared.getOrPut(scope) { LinkedHashMap() }.getOrPut(name) { ArrayList() } += element
                }
            }
            val inner = if (isScope(element)) element.also { enclosing[it] = scope } else scope
            pushChildren(element, inner, stack)
        }
        val declarations = HashMap<JsonataVariableRef, List<JsonataVariableRef>>()
        val occurrences = HashMap<JsonataVariableRef, MutableList<JsonataVariableRef>>()
        for ((variable, start) in variables) {
            val name = localName(variable)!!
            var scope: PsiElement? = start
            while (scope != null) {
                val found = declared[scope]?.get(name)
                if (found != null) {
                    declarations[variable] = found
                    occurrences.getOrPut(found.first()) { ArrayList() } += variable
                    break
                }
                scope = enclosing[scope]
            }
        }
        return Bindings(declared, declarations, occurrences)
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
