package io.github.saigyo.aslan.spec

import io.github.saigyo.aslan.spec.data.choiceRuleStructures
import io.github.saigyo.aslan.spec.data.errorHandlingStructures
import io.github.saigyo.aslan.spec.data.mapStructures
import io.github.saigyo.aslan.spec.data.optimisedResourceArns
import io.github.saigyo.aslan.spec.data.predefinedErrorNames
import io.github.saigyo.aslan.spec.data.stateMachineStructures
import io.github.saigyo.aslan.spec.data.stateStructures
import io.github.saigyo.aslan.spec.data.stateTypeSpecs

/** The bundled description of the Amazon States Language that completion, validation and documentation work from. */
object AslSpec {
    private val structures: Map<StructureId, StructureSpec> =
        (stateMachineStructures + stateStructures + choiceRuleStructures + errorHandlingStructures + mapStructures).associateBy { it.id }

    init {
        check(structures.keys == StructureId.entries.toSet()) { "structures missing: ${StructureId.entries - structures.keys}" }
    }

    fun structure(id: StructureId): StructureSpec = structures.getValue(id)

    /** In the order Task, Pass, Choice, Wait, Succeed, Fail, Parallel, Map. */
    val stateTypes: List<StateTypeSpec> = stateTypeSpecs

    private val stateTypesByName = stateTypes.associateBy { it.name }

    /** The state type with exactly this name; names are case-sensitive. */
    fun stateType(name: String): StateTypeSpec? = stateTypesByName[name]

    /** The predefined `States.` error names. */
    val errorNames: List<NamedDoc> = predefinedErrorNames

    /** Resource ARNs of the optimised service integrations, one per supported integration pattern. */
    val resourceArns: List<NamedDoc> = optimisedResourceArns

    private val allFieldNames: Set<String> = structures.values.flatMap { s -> s.fields.map { it.name } }.toSet()

    /** Whether any ASL structure defines a field of this name. */
    fun definesFieldAnywhere(name: String): Boolean = name in allFieldNames
}
