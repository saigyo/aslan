package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.project.Project
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.ProcessingContext
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.AslTree
import io.github.saigyo.aslan.tree.ScalarType
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.impl.YAMLPlainTextImpl

/** Whether [element] is the entry (`YAMLKeyValue` or `JsonProperty`) of a state in an ASL file. */
private fun isStateEntry(element: PsiElement): Boolean {
    if (element !is YAMLKeyValue && element !is JsonProperty) return false
    val file = element.containingFile ?: return false
    if (!AslFiles.isAslFile(file)) return false
    val document = AslModel.of(file) ?: return false
    return StateTargets.statesDeclaredBy(document, element).isNotEmpty()
}

/**
 * Renames a state: its key and every value that names it, each written so that it reads back as the new name. The
 * platform's own rename writes a YAML key as typed, which breaks names such as `Needs: review`.
 */
class AslStateRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = isStateEntry(element)

    override fun renameElement(element: PsiElement, newName: String, usages: Array<UsageInfo>, listener: RefactoringElementListener?) {
        for (usage in usages) usage.reference?.handleElementRename(newName)
        val renamed = NameWriter.writeKey(element, newName)
        listener?.elementRenamed(renamed)
    }
}

/** Accepts any non-empty state name; [NameWriter] quotes it where needed, so the YAML plugin's key rules do not apply. */
class AslStateNameInputValidator : RenameInputValidator {
    override fun getPattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement().with(object : PatternCondition<PsiElement>("aslStateEntry") {
            override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean = isStateEntry(element)
        })

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext): Boolean = newName.isNotEmpty()
}

/**
 * Writes a name as a YAML or JSON key or value so that it reads back as that name. JSON strings are always quoted and
 * escaped. In YAML a quoted scalar keeps its quotes, and a plain one stays plain unless the name would not read back
 * as the same string there; then it becomes double-quoted.
 */
internal object NameWriter {
    /** Replaces the value [element] (a `YAMLScalar` or `JsonStringLiteral`) with [name]; returns the new element. */
    fun writeValue(element: PsiElement, name: String): PsiElement {
        if (element is YAMLScalar && element !is YAMLQuotedText && !isPlainSafe(element.project, name)) {
            return element.replace(yamlQuoted(element.project, '"', name))
        }
        return ElementManipulators.handleContentChange(element, name)
    }

    /** Replaces the key of the entry [entry] (a `YAMLKeyValue` or `JsonProperty`) with [name]; returns the entry. */
    fun writeKey(entry: PsiElement, name: String): PsiElement {
        when (entry) {
            is JsonProperty -> entry.nameElement.replace(JsonElementGenerator(entry.project).createStringLiteral(name))
            is YAMLKeyValue -> {
                val key = entry.key ?: return entry
                val project = entry.project
                val singleLine = name.none { it == '\n' || it == '\r' }
                val text = when {
                    key.text.startsWith("'") && singleLine -> yamlQuoted(project, '\'', name).text
                    key.text.startsWith("'") || key.text.startsWith("\"") -> yamlQuoted(project, '"', name).text
                    isPlainSafe(project, name) -> name
                    else -> yamlQuoted(project, '"', name).text
                }
                val newKey = YAMLElementGenerator.getInstance(project).createYamlKeyValue(text, "x").key ?: return entry
                key.replace(newKey)
            }
        }
        return entry
    }

    /** A YAML scalar quoted with [quote] whose value is [name], encoded by the YAML plugin's manipulator. */
    private fun yamlQuoted(project: Project, quote: Char, name: String): YAMLScalar {
        val file = YAMLElementGenerator.getInstance(project).createDummyYamlWithText("k: $quote$quote")
        val empty = PsiTreeUtil.findChildOfType(file, YAMLQuotedText::class.java) ?: error("no quoted scalar in dummy YAML")
        return ElementManipulators.handleContentChange(empty, name) as YAMLScalar
    }

    /**
     * Whether [name] reads back as the same string when written plain, as a key and as a value, in block and in flow
     * style. Names that hold `: ` or ` #`, start with an indicator character, or read as another type fail this.
     */
    fun isPlainSafe(project: Project, name: String): Boolean {
        if (name.isEmpty() || name.any { it == '\n' || it == '\r' }) return false
        val generator = YAMLElementGenerator.getInstance(project)
        return listOf("k: $name", "{k: $name}").all { readsBack(generator.createDummyYamlWithText(it), name, asKey = false) } &&
            listOf("$name: v", "{$name: v}").all { readsBack(generator.createDummyYamlWithText(it), name, asKey = true) }
    }

    private fun readsBack(file: YAMLFile, name: String, asKey: Boolean): Boolean {
        if (PsiTreeUtil.hasErrorElements(file)) return false
        val entry = (AslTree.root(file) as? AslObject)?.entries?.singleOrNull() ?: return false
        val value = entry.value as? AslScalar ?: return false
        return if (asKey) {
            entry.key == name && value.text == "v"
        } else {
            entry.key == "k" && value.psi is YAMLPlainTextImpl && value.type == ScalarType.STRING && !value.ambiguous && value.text == name
        }
    }
}
