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
 */
class VariableUsagesHandlerFactory : HighlightUsagesHandlerFactory, DumbAware {
    override fun createHighlightUsagesHandler(editor: Editor, injectedFile: PsiFile): HighlightUsagesHandlerBase<*>? {
        val manager = InjectedLanguageManager.getInstance(injectedFile.project)
        val file = manager.getTopLevelFile(injectedFile)
        val hostEditor = if (file === injectedFile) editor else InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        if (!AslFiles.isAslFile(file)) return null
        val offset = hostEditor.caretModel.offset
        for (at in listOf(offset, offset - 1)) {
            if (at < 0) continue
            val variables = variablesAt(file, at, offset)
            if (variables.isNotEmpty()) return VariableHandler(hostEditor, file, variables, file.findElementAt(at) ?: file)
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

    private class VariableHandler(
        editor: Editor,
        file: PsiFile,
        private val variables: List<AslVariable>,
        private val target: PsiElement,
    ) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {
        override fun getTargets(): List<PsiElement> = listOf(target)

        override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) =
            selectionConsumer.consume(targets)

        override fun computeUsages(targets: List<PsiElement>) {
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
