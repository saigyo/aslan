package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.PsiElementUsageTarget
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProviderEx
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Groups the usages of a state machine variable in Find Usages and in the rename preview: the key of an assignment
 * is a "Variable assignment", a `$name` in JSONata or a name in a JSONPath string that reads the variable is a
 * "Variable read". A search for anything else keeps the other providers' types.
 */
class AslVariableUsageTypeProvider : UsageTypeProviderEx {
    override fun getUsageType(element: PsiElement): UsageType? = getUsageType(element, UsageTarget.EMPTY_ARRAY)

    override fun getUsageType(element: PsiElement, targets: Array<out UsageTarget>): UsageType? {
        if (targets.isNotEmpty() && targets.none { isVariable(it) }) return null
        return when {
            AslVariableTargets.assignmentEntryAt(element) != null -> ASSIGNMENT
            isRead(element) -> READ
            else -> null
        }
    }

    private fun isVariable(target: UsageTarget): Boolean =
        (target as? PsiElementUsageTarget)?.element?.let { renamedVariables(it).isNotEmpty() } == true

    private fun isRead(element: PsiElement): Boolean {
        PsiTreeUtil.getNonStrictParentOfType(element, JsonataVariableRef::class.java)?.let { return AslVariableTargets.readReference(it) != null }
        val host = element as? YAMLScalar ?: element as? JsonStringLiteral ?: return false
        return AslVariableTargets.pathReadReferences(host).isNotEmpty()
    }

    companion object {
        val ASSIGNMENT = UsageType { "Variable assignment" }
        val READ = UsageType { "Variable read" }
    }
}
