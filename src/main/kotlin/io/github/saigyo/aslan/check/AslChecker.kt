package io.github.saigyo.aslan.check

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.settings.AslSettings

/** Runs every check over a file and caches the findings until the file or the settings change. */
object AslChecker {
    private val log = logger<AslChecker>()

    /**
     * The findings of [file], sorted by the start offset of their element and then by problem, with no two of the
     * same problem, element, message and range; empty for a file that is not an ASL file.
     */
    fun findings(file: PsiFile): List<AslFinding> {
        if (!AslFiles.isAslFile(file)) return emptyList()
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(compute(file), PsiModificationTracker.MODIFICATION_COUNT, AslSettings.getInstance())
        }
    }

    private fun compute(file: PsiFile): List<AslFinding> {
        val out = ArrayList<AslFinding>()
        try {
            val doc = AslModel.of(file) ?: return emptyList()
            StructureChecks.check(doc, out)
            TransitionChecks.check(doc, out)
            WarningChecks.check(doc, out)
            JsonataChecks.check(doc, out)
            VariableChecks.check(doc, out)
        } catch (e: Exception) {
            // The checks run on every keystroke over half-typed files: a bug in one must not break the editor.
            if (e is ControlFlowException) throw e
            log.error("ASL checks failed on ${file.name}", e)
        }
        return out.distinctBy { listOf(it.problem, it.element, it.message, it.range) }
            .sortedWith(compareBy<AslFinding> { it.element.textRange.startOffset }.thenBy { it.problem.ordinal }.thenBy { it.range?.startOffset ?: 0 })
    }
}
