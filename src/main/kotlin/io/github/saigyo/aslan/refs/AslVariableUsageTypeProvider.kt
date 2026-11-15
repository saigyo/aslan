package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.PsiElementUsageTarget
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProviderEx
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Groups the usages of a state machine variable in Find Usages and in the rename preview: the key of an assignment
 * is a "Variable assignment", a `$name` in JSONata (or the string that holds the expression, as Find Usages reports
 * it) or a name in a JSONPath string that reads the variable is a "Variable read". A search for anything else keeps
 * the other providers' types.
 */
class AslVariableUsageTypeProvider : UsageTypeProviderEx {
    override fun getUsageType(element: PsiElement): UsageType? = getUsageType(element, UsageTarget.EMPTY_ARRAY)

    override fun getUsageType(element: PsiElement, targets: Array<out UsageTarget>): UsageType? {
        // it runs for every usage of every search; only a usage in an ASL file, or in an expression of one, is ours
        val file = element.containingFile ?: return null
        if (!AslFiles.isAslFile(InjectedLanguageManager.getInstance(element.project).getTopLevelFile(file))) return null
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
        // Find Usages reports a read in JSONata as a usage of the string that holds the expression
        return AslVariableTargets.pathReadReferences(host).isNotEmpty() || AslVariableTargets.readsInJsonata(host)
    }

    companion object {
        val ASSIGNMENT = UsageType { "Variable assignment" }
        val READ = UsageType { "Variable read" }
    }
}
