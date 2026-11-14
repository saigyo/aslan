package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.project.Project
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.intellij.util.ProcessingContext
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.AslTree
import io.github.saigyo.aslan.tree.ScalarType
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLAnchor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.impl.YAMLPlainTextImpl

/** The longest state name AWS accepts, in Unicode code points. */
private const val MAX_STATE_NAME_LENGTH = 80

/** The model of the file holding [element] when [element] is the entry (`YAMLKeyValue` or `JsonProperty`) of a state. */
private fun documentOfStateEntry(element: PsiElement): AslDocument? {
    if (element !is YAMLKeyValue && element !is JsonProperty) return null
    val file = element.containingFile ?: return null
    if (!AslFiles.isAslFile(file)) return null
    val document = AslModel.of(file) ?: return null
    return document.takeIf { StateTargets.statesDeclaredBy(it, element).isNotEmpty() }
}

private fun isStateEntry(element: PsiElement): Boolean = documentOfStateEntry(element) != null

private fun hasLineBreak(name: String): Boolean = name.any { it == '\n' || it == '\r' }

/**
 * Renames a state: its key and every value that names it, each written so that it reads back as the new name. The
 * platform's own rename writes a YAML key as typed, which breaks names such as `Needs: review`.
 */
class AslStateRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = isStateEntry(element)

    /** Each alias that the rename would leave on the old name, or change although it names another machine's state. */
    override fun findCollisions(element: PsiElement, newName: String, allRenames: Map<out PsiElement, String>, result: MutableList<UsageInfo>) {
        val document = documentOfStateEntry(element) ?: return
        val aliases = StateTargets.statesDeclaredBy(document, element).flatMap { StateTargets.aliasesBrokenByRename(document, it) }.distinct()
        for (alias in aliases) result += object : UnresolvableCollisionUsageInfo(alias, element) {
            override fun getDescription(): String =
                "The transition '${alias.parent?.text ?: alias.text}' is written through an alias, so renaming cannot update it correctly"
        }
    }

    /** State names are unique across the whole definition, nested machines included. */
    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: MultiMap<PsiElement, String>) {
        val document = documentOfStateEntry(element) ?: return
        for (state in document.allStates()) {
            if (state.name == newName && state.entry.entryPsi != element) conflicts.putValue(state.entry.entryPsi, "A state named '$newName' already exists")
        }
    }

    override fun renameElement(element: PsiElement, newName: String, usages: Array<UsageInfo>, listener: RefactoringElementListener?) {
        for (usage in usages) if (usage !is UnresolvableCollisionUsageInfo) usage.reference?.handleElementRename(newName)
        val renamed = NameWriter.writeKey(element, newName)
        listener?.elementRenamed(renamed)
    }
}

/**
 * Accepts a state name that is not blank, fits on one line and has at most 80 code points. [NameWriter] quotes it where
 * needed, so the YAML plugin's key rules do not apply.
 */
class AslStateNameInputValidator : RenameInputValidator {
    override fun getPattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement().with(object : PatternCondition<PsiElement>("aslStateEntry") {
            override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean = isStateEntry(element)
        })

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext): Boolean =
        newName.isNotBlank() && !hasLineBreak(newName) && newName.codePointCount(0, newName.length) <= MAX_STATE_NAME_LENGTH
}

/**
 * Writes a name as a YAML or JSON key or value so that it reads back as that name. JSON strings are always quoted and
 * escaped. In YAML a quoted scalar keeps its quotes (single quotes become double ones for a name with a line break),
 * a plain one stays plain unless the name would not read back as the same string there, and anything else becomes
 * double-quoted. A tag or anchor in front of a YAML value is kept.
 */
internal object NameWriter {
    /** Replaces the value [element] (a `YAMLScalar` or `JsonStringLiteral`) with [name]; returns the new element. */
    fun writeValue(element: PsiElement, name: String): PsiElement {
        if (element !is YAMLScalar) return ElementManipulators.handleContentChange(element, name)
        val project = element.project
        val content = when {
            element is YAMLQuotedText && element.isSingleQuote && !hasLineBreak(name) -> yamlQuoted(project, '\'', name).text
            element is YAMLQuotedText -> doubleQuoted(project, name)
            element is YAMLPlainTextImpl && isPlainSafe(project, name) -> name
            else -> doubleQuoted(project, name)
        }
        val prefix = element.text.substring(0, YamlScalarContent.start(element))
        val file = YAMLElementGenerator.getInstance(project).createDummyYamlWithText("k: $prefix$content")
        val fresh = PsiTreeUtil.findChildOfType(file, YAMLScalar::class.java) ?: error("no scalar in dummy YAML")
        return element.replace(fresh)
    }

    /** Replaces the key of the entry [entry] (a `YAMLKeyValue` or `JsonProperty`) with [name]; returns the entry. */
    fun writeKey(entry: PsiElement, name: String): PsiElement {
        when (entry) {
            is JsonProperty -> entry.nameElement.replace(JsonElementGenerator(entry.project).createStringLiteral(name))
            is YAMLKeyValue -> {
                val key = entry.key ?: return entry
                val project = entry.project
                val text = when {
                    key.text.startsWith("'") && !hasLineBreak(name) -> yamlQuoted(project, '\'', name).text
                    key.text.startsWith("'") || key.text.startsWith("\"") -> doubleQuoted(project, name)
                    isPlainSafe(project, name) -> name
                    else -> doubleQuoted(project, name)
                }
                val newKey = YAMLElementGenerator.getInstance(project).createYamlKeyValue(text, "x").key ?: return entry
                key.replace(newKey)
            }
        }
        return entry
    }

    /** [name] as a plain YAML value where it reads back as the same string there, else double-quoted. */
    fun yamlValue(project: Project, name: String): String = if (isPlainSafe(project, name)) name else doubleQuoted(project, name)

    /**
     * [name] as a double-quoted YAML scalar. The YAML plugin's encoder writes a line break as is, which YAML folds to a
     * space, so a name with a line break is escaped by the JSON plugin instead; its escapes are valid in YAML too.
     */
    private fun doubleQuoted(project: Project, name: String): String =
        if (hasLineBreak(name)) JsonElementGenerator(project).createStringLiteral(name).text else yamlQuoted(project, '"', name).text

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
        if (name.isEmpty() || hasLineBreak(name)) return false
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

/** Where the content of a YAML scalar starts, after any tag and anchor written in front of it. */
internal object YamlScalarContent {
    /** The offset in [scalar] at which its content starts. */
    fun start(scalar: YAMLScalar): Int {
        var child = scalar.firstChild
        while (child != null && (child is YAMLAnchor || child is PsiWhiteSpace || child.node.elementType == YAMLTokenTypes.TAG)) {
            child = child.nextSibling
        }
        return child?.startOffsetInParent ?: scalar.textLength
    }
}
