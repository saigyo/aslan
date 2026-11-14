package io.github.saigyo.aslan.model

import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.settings.AslSettings
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslEntry
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.AslTree
import io.github.saigyo.aslan.tree.ScalarType
import java.util.IdentityHashMap

/** Builds the [AslDocument] of a file and caches it until the file or the settings change. */
object AslModel {
    /** The model of [file], or null when it is not an ASL file. */
    fun of(file: PsiFile): AslDocument? = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(build(file), PsiModificationTracker.MODIFICATION_COUNT, AslSettings.getInstance())
    }

    private fun build(file: PsiFile): AslDocument? {
        if (!AslFiles.isAslFile(file)) return null
        val rootNode = AslTree.root(file)
        val rootObj = rootNode as? AslObject ?: return AslDocument(file, rootNode, null, SchemaIndex.EMPTY, QueryLanguage.JSONPATH, true)
        val topLevel = QueryLanguageSetting.of(rootObj["QueryLanguage"])
        val builder = Builder(topLevel)
        val root = builder.machine(rootObj, null)
        val language = topLevel.language ?: QueryLanguage.JSONPATH
        return AslDocument(file, rootNode, root, SchemaIndex.build(root, language, builder.machines), language, !topLevel.uncertain)
    }

    /**
     * A `QueryLanguage` field as written: the language it names, and whether it leaves the language open. Any value other
     * than exactly `JSONPath` or `JSONata` (a placeholder, a value being typed, a value of another kind) leaves it open.
     */
    private class QueryLanguageSetting(val language: QueryLanguage?, val uncertain: Boolean) {
        companion object {
            fun of(entry: AslEntry?): QueryLanguageSetting {
                if (entry == null) return QueryLanguageSetting(null, false)
                val scalar = entry.value as? AslScalar
                val language = QueryLanguage.entries.firstOrNull { scalar?.type == ScalarType.STRING && !scalar.ambiguous && it.text == scalar.text }
                return QueryLanguageSetting(language, language == null)
            }
        }
    }

    private class Builder(val topLevel: QueryLanguageSetting) {
        val machines = IdentityHashMap<AslObject, AslStateMachine>()

        fun machine(obj: AslObject, owner: AslState?): AslStateMachine {
            val statesEntry = obj["States"]
            val states = ArrayList<AslState>()
            val machine = AslStateMachine(obj, owner, obj["StartAt"], statesEntry, states)
            machines[obj] = machine
            val statesObj = statesEntry?.value as? AslObject
            if (statesObj != null) for (entry in statesObj.entries) if (!isUnappliedMerge(entry)) states += state(entry, machine)
            return machine
        }

        private fun state(entry: AslEntry, machine: AslStateMachine): AslState {
            val obj = entry.value as? AslObject
            val typeName = (obj?.get("Type")?.value as? AslScalar)?.takeIf { it.type != ScalarType.NULL }?.text
            val type = typeName?.let { AslSpec.stateType(it) }
            val own = QueryLanguageSetting.of(obj?.get("QueryLanguage"))
            val language = own.language ?: topLevel.language ?: QueryLanguage.JSONPATH
            val known = own.language != null || (!own.uncertain && !topLevel.uncertain)
            val children = ArrayList<AslStateMachine>()
            val transitions = ArrayList<AslTransition>()
            val state = AslState(entry.key, entry, obj, machine, typeName, type, language, known, children, transitions)
            if (obj != null) {
                children += children(obj, type?.structure, state)
                transitions += transitions(obj, state)
            }
            return state
        }

        private fun children(obj: AslObject, structure: StructureId?, state: AslState): List<AslStateMachine> = when (structure) {
            StructureId.MAP -> listOfNotNull(((obj["ItemProcessor"] ?: obj["Iterator"])?.value as? AslObject)?.let { machine(it, state) })
            StructureId.PARALLEL -> (obj["Branches"]?.value as? AslArray)?.items.orEmpty().filterIsInstance<AslObject>().map { machine(it, state) }
            else -> emptyList()
        }

        private fun transitions(obj: AslObject, state: AslState): List<AslTransition> = buildList {
            obj["Next"]?.let { add(AslTransition(TransitionKind.NEXT, state, it.value as? AslScalar, obj, 0)) }
            listTransitions(obj, "Choices", TransitionKind.CHOICE, state)
            obj["Default"]?.let { add(AslTransition(TransitionKind.DEFAULT, state, it.value as? AslScalar, obj, 0)) }
            listTransitions(obj, "Catch", TransitionKind.CATCH, state)
        }

        /** One transition per object in the list value of [key], indexed by its position in the list. */
        private fun MutableList<AslTransition>.listTransitions(obj: AslObject, key: String, kind: TransitionKind, state: AslState) {
            (obj[key]?.value as? AslArray)?.items.orEmpty().forEachIndexed { i, item ->
                if (item is AslObject) add(AslTransition(kind, state, item["Next"]?.value as? AslScalar, item, i))
            }
        }
    }
}
