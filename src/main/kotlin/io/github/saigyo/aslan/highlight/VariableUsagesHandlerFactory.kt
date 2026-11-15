package io.github.saigyo.aslan.highlight

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.model.AslVariable
import io.github.saigyo.aslan.model.JsonataRead
import io.github.saigyo.aslan.model.PathRead
import io.github.saigyo.aslan.refs.AslVariableTargets
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Highlight Usages with the caret on an assignment of a state machine variable, or on a read of one (in JSONata or in a
 * JSONPath string), marks the name of every assignment as a write and every read as a read. Anywhere else, local
 * JSONata variables included, it does nothing, so the other handlers apply.
 *
 * The platform asks the same handler twice: the action Highlight Usages calls [HighlightUsagesHandlerBase.highlightUsages]
 * and marks host ranges in the host editor; the automatic highlighting of the element under the caret reads the usages
 * directly, in the coordinates of the file it was given. With the caret inside an expression that file is the injected
 * one, so the automatic highlighting gets only the reads in this expression, in its coordinates.
 */
class VariableUsagesHandlerFactory : HighlightUsagesHandlerFactory, DumbAware {
    override fun createHighlightUsagesHandler(editor: Editor, injectedFile: PsiFile): HighlightUsagesHandlerBase<*>? {
        val manager = InjectedLanguageManager.getInstance(injectedFile.project)
        val file = manager.getTopLevelFile(injectedFile)
        val hostEditor = if (file === injectedFile) editor else InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        if (!AslFiles.isAslFile(file)) return null
        val injection = if (file === injectedFile) null else manager.getInjectionHost(injectedFile) ?: return null
        val offset = hostEditor.caretModel.offset
        for (at in listOf(offset, offset - 1)) {
            if (at < 0) continue
            val variables = variablesAt(file, at, offset)
            if (variables.isNotEmpty()) return VariableHandler(hostEditor, file, variables, file.findElementAt(at) ?: file, injection)
        }
        return null
    }

    private fun variablesAt(file: PsiFile, at: Int, caret: Int): List<AslVariable> {
        val manager = InjectedLanguageManager.getInstance(file.project)
        manager.findInjectedElementAt(file, at)?.let { return AslVariableTargets.variablesAt(it) }
        val leaf = file.findElementAt(at) ?: return emptyList()
        AslVariableTargets.variablesAt(leaf).takeIf { it.isNotEmpty() }?.let { return it }
        val host = PsiTreeUtil.getNonStrictParentOfType(leaf, YAMLScalar::class.java, JsonStringLiteral::class.java) ?: return emptyList()
        return AslVariableTargets.variablesAt(host, caret - host.textRange.startOffset)
    }

    /**
     * [injection] is the host of the injected file the platform asked for, or null when it asked for the host file.
     */
    private class VariableHandler(
        editor: Editor,
        file: PsiFile,
        private val variables: List<AslVariable>,
        private val target: PsiElement,
        private val injection: PsiElement?,
    ) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {
        /** Whether [computeUsages] gives host ranges; only the automatic highlighting in an injection wants others. */
        private var inHost = injection == null

        override fun getTargets(): List<PsiElement> = listOf(target)

        override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) =
            selectionConsumer.consume(targets)

        /** The action Highlight Usages: every usage, marked in the host editor. */
        override fun highlightUsages() {
            inHost = true
            super.highlightUsages()
        }

        override fun computeUsages(targets: List<PsiElement>) {
            if (inHost) computeHostUsages() else computeInjectedUsages()
        }

        private fun computeHostUsages() {
            val writes = LinkedHashSet<TextRange>()
            val reads = LinkedHashSet<TextRange>()
            for (variable in variables) {
                val model = AslVariableTargets.modelOf(variable) ?: continue
                for (assignment in model.assignmentsOf(variable)) nameRange(assignment.entry.keyPsi, assignment.name)?.let { writes += it }
                for (read in model.readsOf(variable)) {
                    when (read) {
                        is JsonataRead -> {
                            val jsonata = read.variable.containingFile as? JsonataFile ?: continue
                            val (host, range) = JsonataPsi.rangeInHost(jsonata, read.variable.textRange) ?: continue
                            reads += range.shiftRight(host.textRange.startOffset)
                        }
                        is PathRead -> reads += read.range.shiftRight(read.scalar.psi.textRange.startOffset)
                    }
                }
            }
            myWriteUsages.addAll(writes)
            myReadUsages.addAll(reads)
        }

        /**
         * The reads in the injection the caret is in, in the injected file's own coordinates. Assignments and JSONPath
         * reads are never inside a JSONata expression, so none of them is in it.
         */
        private fun computeInjectedUsages() {
            val manager = InjectedLanguageManager.getInstance(target.project)
            val reads = LinkedHashSet<TextRange>()
            for (variable in variables) {
                val model = AslVariableTargets.modelOf(variable) ?: continue
                for (read in model.readsOf(variable)) {
                    if (read !is JsonataRead) continue
                    val jsonata = read.variable.containingFile as? JsonataFile ?: continue
                    if (manager.getInjectionHost(jsonata) == injection) reads += read.variable.textRange
                }
            }
            myReadUsages.addAll(reads)
        }

        /** The name in the key's text: without quotes and without a `.$` suffix. */
        private fun nameRange(key: PsiElement, name: String): TextRange? {
            val text = key.text
            var start = 0
            var end = text.length
            if (end >= 2 && (text[0] == '"' || text[0] == '\'') && text[end - 1] == text[0]) {
                start = 1
                end--
            }
            if (!name.endsWith(".$") && end - start > 2 && text.startsWith(".$", end - 2)) end -= 2
            if (end <= start) return null
            return TextRange(start, end).shiftRight(key.textRange.startOffset)
        }
    }
}
