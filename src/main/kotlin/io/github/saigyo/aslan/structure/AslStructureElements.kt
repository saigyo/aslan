package io.github.saigyo.aslan.structure

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.AslIcons
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.model.TransitionKind
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import javax.swing.Icon

/**
 * One node of the structure view. [element] is the PSI element the node navigates to; [children] is evaluated when
 * the node is expanded. The tree is rebuilt while the user types, so building must never throw.
 */
open class AslStructureElement(
    private val element: PsiElement,
    private val text: String,
    private val location: String?,
    private val icon: Icon?,
    private val childrenBuilder: () -> List<TreeElement> = { emptyList() },
) : StructureViewTreeElement {
    override fun getValue(): Any = element

    override fun getPresentation(): ItemPresentation = PresentationData(text, location, icon, null)

    override fun getChildren(): Array<TreeElement> = try {
        childrenBuilder().toTypedArray()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: RuntimeException) {
        emptyArray()
    }

    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = (element as? Navigatable)?.canNavigateToSource() == true
}

/** The root node: the file, with the states of the root machine as children. */
class AslFileElement(private val file: PsiFile) :
    AslStructureElement(file, file.name, null, file.getIcon(0), { AslStructureElements.machineStates(AslModel.of(file)?.root) })

internal object AslStructureElements {
    fun machineStates(machine: AslStateMachine?): List<TreeElement> =
        machine?.states.orEmpty().map { state(it) }

    private fun state(state: AslState): TreeElement = AslStructureElement(
        state.entry.entryPsi, state.name, state.typeName, AslIcons.forStateType(state.typeName),
    ) { stateChildren(state) }

    private fun stateChildren(state: AslState): List<TreeElement> = buildList {
        val parallel = state.typeName == "Parallel"
        for ((i, machine) in state.children.withIndex()) {
            if (parallel) {
                add(AslStructureElement(machine.obj.psi, "Branch ${i + 1}", null, AslIcons.Branch) { machineStates(machine) })
            } else {
                addAll(machineStates(machine))
            }
        }
        val assign = state.obj?.get("Assign")?.value as? AslObject
        val jsonPath = state.queryLanguage == QueryLanguage.JSONPATH
        for (entry in assign?.entries.orEmpty()) {
            if (entry.viaMerge) continue
            val name = if (jsonPath) entry.key.removeSuffix(".$") else entry.key
            add(AslStructureElement(entry.entryPsi, "Assign $name", null, AslIcons.Assign))
        }
        for (t in state.transitions) {
            val location = t.targetName?.let { "→ $it" }
            when (t.kind) {
                TransitionKind.CHOICE ->
                    add(AslStructureElement(t.owner.psi, "Rule ${t.index + 1}", location, AslIcons.Rule))
                TransitionKind.CATCH ->
                    add(AslStructureElement(t.owner.psi, catchText(t.owner), location, AslIcons.Catch))
                else -> {}
            }
        }
    }

    private fun catchText(catcher: AslObject): String {
        val names = (catcher["ErrorEquals"]?.value as? AslArray)?.items.orEmpty()
            .mapNotNull { (it as? AslScalar)?.text }
        return if (names.isEmpty()) "Catch" else "Catch ${names.joinToString(", ")}"
    }
}
