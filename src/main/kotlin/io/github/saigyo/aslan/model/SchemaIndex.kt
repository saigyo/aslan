package io.github.saigyo.aslan.model

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslEntry
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject

/** Which ASL structure an object is, with the query language that applies there and the state it belongs to. */
data class ObjectInfo(val structure: StructureSpec, val queryLanguage: QueryLanguage, val state: AslState?)

/** Which ASL field a key is, in which object. */
data class FieldInfo(val spec: FieldSpec, val owner: ObjectInfo, val entry: AslEntry)

/**
 * Which ASL structure each tree object is and which ASL field each key is, found by following the reference data
 * from the top-level object. Free-form data is marked and not looked into.
 */
class SchemaIndex private constructor() {
    private val objects = HashMap<PsiElement, ObjectInfo>()
    private val fields = HashMap<PsiElement, FieldInfo>()
    private val stateKeys = HashMap<PsiElement, AslState>()
    private val freeForm = HashSet<PsiElement>()

    /** The structure of the object whose [com.intellij.psi.PsiElement] is [objectPsi] (an `AslObject.psi`). */
    fun info(objectPsi: PsiElement): ObjectInfo? = objects[objectPsi]

    /** The field a key declares (an `AslEntry.keyPsi`); null for unknown keys and inside free-form data. */
    fun field(keyPsi: PsiElement): FieldInfo? = fields[keyPsi]

    /** The state whose name [keyPsi] is, or null when the key does not declare a state. */
    fun stateNameKey(keyPsi: PsiElement): AslState? = stateKeys[keyPsi]

    /** Whether [element] lies inside the value of a free-form field. */
    fun isFreeForm(element: PsiElement): Boolean {
        if (freeForm.isEmpty()) return false
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            if (current in freeForm) return true
            current = current.parent
        }
        return false
    }

    internal companion object {
        val EMPTY = SchemaIndex()

        /** Indexes [root] and everything below it; [topLevel] is the query language the top-level object uses. */
        fun build(root: AslStateMachine, topLevel: QueryLanguage, machines: Map<AslObject, AslStateMachine>): SchemaIndex =
            SchemaIndex().also { Walk(it, machines).obj(root.obj, AslSpec.structure(StructureId.STATE_MACHINE), topLevel, null) }
    }

    /** [machines] maps the object of every state machine in the model to it, by identity. */
    private class Walk(val index: SchemaIndex, val machines: Map<AslObject, AslStateMachine>) {
        /** Records [obj] as [structure] and walks its entries, unless the same object was already indexed. */
        fun obj(obj: AslObject, structure: StructureSpec, ql: QueryLanguage, state: AslState?) {
            if (obj.psi in index.objects) return
            val info = ObjectInfo(structure, ql, state)
            index.objects[obj.psi] = info
            for (entry in obj.entries) {
                if (isUnappliedMerge(entry)) continue
                val spec = structure.field(entry.key) ?: continue
                index.fields.putIfAbsent(entry.keyPsi, FieldInfo(spec, info, entry))
                value(entry.value, spec.kind, obj, info, entry)
            }
        }

        private fun value(node: AslNode, kind: ValueKind, owner: AslObject, info: ObjectInfo, entry: AslEntry) {
            when (kind) {
                is ValueKind.Struct -> if (node is AslObject) obj(node, AslSpec.structure(kind.id), info.queryLanguage, info.state)
                is ValueKind.ListOf -> if (node is AslArray) for (item in node.items) value(item, kind.element, owner, info, entry)
                ValueKind.StatesMap -> machines[owner]?.let { states(it) }
                ValueKind.FreeForm -> if (node.usePsi !== entry.keyPsi) index.freeForm += node.usePsi
                else -> {}
            }
        }

        /** Walks the states of [machine] as read by the model, so the index and the model agree on what a state is. */
        private fun states(machine: AslStateMachine) {
            for (state in machine.states) {
                index.stateKeys.putIfAbsent(state.entry.keyPsi, state)
                val obj = state.obj ?: continue
                obj(obj, AslSpec.structure(state.type?.structure ?: StructureId.STATE), state.queryLanguage, state)
            }
        }
    }
}
