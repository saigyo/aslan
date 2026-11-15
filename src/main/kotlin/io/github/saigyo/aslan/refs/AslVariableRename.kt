package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameDialog
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import io.github.saigyo.aslan.check.OuterAssignments
import io.github.saigyo.aslan.jsonata.parser.JsonataScanner
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.jsonata.psi.JsonataElementFactory
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.AslRead
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.model.AslVariable
import io.github.saigyo.aslan.model.AslVariables
import io.github.saigyo.aslan.model.JsonataRead
import io.github.saigyo.aslan.model.PathRead
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslEntry
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.QuotedScalars
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import java.awt.GridBagConstraints
import javax.swing.JCheckBox
import javax.swing.JPanel

/** The longest variable name the plugin accepts, in Unicode code points (decision 7). */
private const val MAX_VARIABLE_NAME_LENGTH = 80

/**
 * Why [name] cannot be the name of a state machine variable, or null when it can (decision 7): a letter, followed by
 * letters, digits and `_` (Unicode letters and digits count), at most 80 code points, not `states`, and `$name` scans
 * as one JSONata variable token. AWS rejects a leading `_` (`r11-a-underscore`).
 */
internal fun variableNameProblem(name: String): String? {
    if (name.isEmpty()) return "A variable name cannot be empty"
    val codePoints = name.codePoints().toArray()
    return when {
        codePoints.size > MAX_VARIABLE_NAME_LENGTH -> "A variable name can have at most $MAX_VARIABLE_NAME_LENGTH characters"
        !Character.isLetter(codePoints[0]) -> "A variable name must start with a letter"
        codePoints.any { !Character.isLetter(it) && !Character.isDigit(it) && it != '_'.code } ->
            "A variable name can hold only letters, digits and _"
        name == "states" -> "\$states is reserved by Step Functions"
        !scansAsOneVariable(name) -> "\$$name is not one JSONata variable"
        else -> null
    }
}

/** Whether `$name` is one variable token for the scanner, the one [JsonataElementFactory.variableToken] parses with. */
private fun scansAsOneVariable(name: String): Boolean {
    val text = "$$name"
    val token = JsonataScanner.scan(text).tokens.singleOrNull() ?: return false
    return token.type == JsonataTokens.VARIABLE && token.start == 0 && token.end == text.length
}

/** The kinds of reason a rename of a state machine variable refuses to run; each is a conflict of the rename. */
internal enum class RenameConflictKind {
    /** Rule 1: an assignment or read of the new name in the region. */
    NAME_IN_REGION,

    /** Rule 1 cannot be checked: a machine of the region does not say every name assigned in it. */
    REGION_OPEN,

    /**
     * An assignment of the new name in a nested state machine after an assignment of it in a machine around it, as
     * the outer-assignment check sees it ([io.github.saigyo.aslan.check.OuterAssignments]): AWS rejects that, also
     * across a Distributed Map, which the region stops at.
     */
    OUTER_ASSIGNMENT,

    /** Rule 2: a local JSONata variable of the new name declared in an expression that reads the variable. */
    LOCAL_OF_NEW_NAME,

    /**
     * Rule 3: `$newName` in a string that is no known read, such as a built-in function or a local variable. Taken
     * over every string of the file, not only the expressions of the region, so that a use the model leaves out (an
     * expression with no injected file, a state of unknown type) counts too; this refuses some safe renames.
     */
    NEW_NAME_MENTIONED,

    /** Rule 4: an occurrence that also belongs to another variable, or to a read of no variable (a YAML alias). */
    SHARED_OCCURRENCE,

    /**
     * A key renamed in a mapping that also takes keys from a YAML merge key, where a merged key has the old or the new
     * name: the rename would let a merged key the local one masks take effect, or make the renamed key mask one.
     */
    MERGE_KEY_CLASH,

    /** Rule 5: an occurrence whose text in the host is not the old name, such as one written with an escape. */
    ESCAPED_OCCURRENCE,

    /** Rule 6: `$oldName` in a string that is no known read. */
    UNREAD_MENTION,
}

internal class RenameConflict(val kind: RenameConflictKind, val element: PsiElement, val description: String)

/**
 * The rename of a state machine variable: every place that assigns or reads it, the reasons it may not run, and the
 * writer. The variable is identified as in [AslVariables]; [variables] holds more than one when the starting element
 * is used through a YAML alias by states of different scopes, which is a conflict.
 *
 * The *region* of the variable is its scope and every machine whose chain reaches the scope or that the scope's chain
 * reaches. After the rename an occurrence in a machine of the region resolves to the same scope as before, provided
 * nothing in the region assigns or reads the new name (rule 1) and the region says every name it assigns. A machine
 * outside the region cannot see the scope, so its reads do not change.
 */
internal class AslVariableRename private constructor(
    private val document: AslDocument,
    private val model: AslVariables,
    val variables: List<AslVariable>,
) {
    val oldName: String = variables.first().name

    /** An assignment entry, and what its key holds after the name: `.$` in a JSONPath state, else nothing. */
    private class Key(val entry: AslEntry, val suffix: String)

    /**
     * A read: its host and the range of `$` and the name in the host's text, null when it cannot be located; the
     * variables of its uses; the string that holds it, and how many uses of that string the model counts for it.
     */
    private class Read(
        val element: PsiElement,
        val host: PsiElement?,
        val range: TextRange?,
        val uses: Set<AslVariable?>,
        val string: PsiElement,
        val countedUses: Int,
    )

    private val keys: List<Key> = variables.flatMap { model.assignmentsOf(it) }
        .distinctBy { it.entry.entryPsi }
        .sortedBy { it.entry.keyPsi.textRange.startOffset }
        .map { Key(it.entry, it.entry.key.removePrefix(it.name)) }

    private val reads: List<Read> = variables.flatMap { model.readsOf(it) }.map { read ->
        when (read) {
            is JsonataRead -> {
                val file = read.variable.containingFile as? JsonataFile
                val inHost = file?.let { JsonataPsi.rangeInHost(it, read.variable.textRange) }
                val uses = model.readsAt(read.variable)
                Read(read.variable, inHost?.first, inHost?.second, uses.mapTo(HashSet()) { model.variableOf(it) }, read.site.scalar.psi, uses.size)
            }
            is PathRead -> {
                val uses = model.pathReadsAt(read.scalar.psi).filter { it.range == read.range }
                Read(read.scalar.psi, read.scalar.psi, read.range, uses.mapTo(HashSet()) { model.variableOf(it) }, read.scalar.psi, uses.size)
            }
        }
    }.distinctBy { if (it.host != null && it.range != null) it.host to it.range.startOffset else it.element }

    /** The union of the regions of [variables]: each scope, and every machine whose chain reaches it or that its chain reaches. */
    private val region: Set<AslStateMachine> = variables.mapTo(HashSet()) { it.scope }.let { scopes ->
        document.allMachines().filterTo(HashSet()) { machine ->
            scopes.any { scope -> machine in model.chain(scope) || scope in model.chain(machine) }
        }
    }

    private val treeUses = YamlTreeUses(document.file)

    /** Every reason a rename to [newName] could change what the definition reads; empty for the old name. */
    fun conflicts(newName: String): List<RenameConflict> {
        if (newName == oldName) return emptyList()
        val out = ArrayList<RenameConflict>()
        val anchor = keys.firstOrNull()?.entry?.entryPsi ?: document.file

        // 1: another variable, assignment or read, of the new name in the region; or a region that does not say all its names
        for (assignment in model.assignments) {
            if (assignment.name == newName && assignment.state.machine in region) {
                out += RenameConflict(RenameConflictKind.NAME_IN_REGION, assignment.entry.entryPsi, "\$$newName is already assigned in ${assignment.placeText}")
            }
        }
        // ... or, in any machine around or inside, an assignment of the new name the rename would put before or after
        // a renamed one in the flow, which AWS rejects across every Map and Parallel state, Distributed Map included
        val renamedAssignments = variables.flatMapTo(HashSet()) { model.assignmentsOf(it) }
        val order = OuterAssignments(model.assignments)
        for (assignment in model.assignments) {
            val renamed = assignment in renamedAssignments
            if (!renamed && assignment.name != newName) continue
            val clash = order.order(assignment.state.machine) { outer ->
                if (renamed) outer.name == newName && outer !in renamedAssignments else outer in renamedAssignments
            }
            when (clash) {
                OuterAssignments.Order.BEFORE -> out += RenameConflict(
                    RenameConflictKind.OUTER_ASSIGNMENT, assignment.entry.entryPsi,
                    "After the rename, \$$newName would be assigned here after an outer state machine assigns it, which Step Functions rejects",
                )
                // the checks stay silent where the outer flow is not known; the rename cannot tell that it is safe
                OuterAssignments.Order.UNKNOWN -> out += RenameConflict(
                    RenameConflictKind.OUTER_ASSIGNMENT, assignment.entry.entryPsi,
                    "After the rename, an outer state machine whose flow the definition does not settle would also assign \$$newName, possibly before this one",
                )
                OuterAssignments.Order.NOT_BEFORE -> {}
            }
        }
        for (read in model.reads) {
            if (read.name == newName && read.state.machine in region) {
                out += RenameConflict(RenameConflictKind.NAME_IN_REGION, elementOf(read), "\$$newName is already read in ${read.state.name}")
            }
        }
        if (region.any { model.isOpen(it) }) {
            out += RenameConflict(
                RenameConflictKind.REGION_OPEN, anchor,
                "The definition does not say every variable that is assigned where \$$oldName is visible, so \$$newName may already exist",
            )
        }
        // ... or a nested machine the model cannot follow (of a state whose type is a placeholder, or a placeholder
        // processor) in a machine that holds, encloses or lies inside a renamed variable's scope: one that is no
        // mapping or list may hold anything, one that is may assign or read the old or the new name
        for (machine in document.allMachines().filter { m -> variables.any { inLine(m, it.scope) } }) {
            for (node in model.unresolvedNested(machine)) {
                val settled = node is AslObject || node is AslArray
                if (settled && node.psi.text.let { oldName !in it && newName !in it }) continue
                out += RenameConflict(
                    RenameConflictKind.REGION_OPEN, node.psi,
                    "A nested state machine here is not settled by the definition and may assign or read \$$oldName or \$$newName",
                )
            }
        }

        // 2: a local variable of the new name declared in an expression that reads the variable
        for (file in variables.flatMap { model.readsOf(it) }.filterIsInstance<JsonataRead>().mapNotNull { it.variable.containingFile as? JsonataFile }.distinct()) {
            for (variable in JsonataScopes.variables(file)) {
                if (JsonataScopes.localName(variable) == newName && (JsonataScopes.isDeclaration(variable) || JsonataScopes.declarations(variable).isNotEmpty())) {
                    out += RenameConflict(RenameConflictKind.LOCAL_OF_NEW_NAME, variable, "A local variable \$$newName would hide the renamed \$$oldName")
                }
            }
        }

        // 3: `$newName` that is no known read, such as a built-in function the rename would turn into a read
        for (host in model.unknownMentions(newName)) {
            out += RenameConflict(RenameConflictKind.NEW_NAME_MENTIONED, host, "\$$newName is used here as no state variable, and the rename could make it one")
        }

        // 4: an occurrence that belongs to more than one variable
        for (key in keys) {
            if (model.assignmentsAt(key.entry.entryPsi).mapTo(HashSet()) { model.variableOf(it) }.size > 1) {
                out += RenameConflict(RenameConflictKind.SHARED_OCCURRENCE, key.entry.entryPsi, "This assignment is used through an alias by states that have separate variables \$$oldName")
            }
        }
        for (read in reads) {
            if (read.uses.size > 1) {
                out += RenameConflict(RenameConflictKind.SHARED_OCCURRENCE, read.element, "This read is used through an alias by states that read different variables \$$oldName")
            }
        }
        // ... or that YAML also uses, through an alias or merge key, in a place that is no occurrence of a variable
        for (key in keys) {
            if (treeUses.of(key.entry.entryPsi) != model.assignmentsAt(key.entry.entryPsi).size.toLong()) {
                out += RenameConflict(RenameConflictKind.SHARED_OCCURRENCE, key.entry.entryPsi, "This assignment is also used through an alias or merge key in a place the rename cannot follow")
            }
        }
        for (read in reads.distinctBy { it.element }) {
            if (treeUses.of(read.string) != read.countedUses.toLong()) {
                out += RenameConflict(RenameConflictKind.SHARED_OCCURRENCE, read.element, "This read is also used through an alias where it is no read of \$$oldName")
            }
        }

        // a merged key of the old or the new name next to a renamed key, which the model does not show when masked
        val ownEntries = keys.mapTo(HashSet()) { it.entry.entryPsi }
        for (key in keys) {
            val besides = YamlMerges.keysBesides(key.entry.entryPsi)
            val clash = besides.keys.firstOrNull { other ->
                other !in ownEntries && YamlMerges.name(other).removeSuffix(".$").let { it == oldName || it == newName }
            }
            when {
                !besides.resolved -> out += RenameConflict(
                    RenameConflictKind.MERGE_KEY_CLASH, key.entry.entryPsi,
                    "A merge key next to this assignment does not say which keys it brings in",
                )
                clash != null -> out += RenameConflict(
                    RenameConflictKind.MERGE_KEY_CLASH, key.entry.entryPsi,
                    "A key '${YamlMerges.name(clash)}' merged into the same mapping would then take effect or be masked",
                )
            }
        }

        // 5: an occurrence whose raw host text is not the old name
        for (key in keys) {
            if (namePart(key.entry.keyPsi.text) != oldName + key.suffix) {
                out += RenameConflict(RenameConflictKind.ESCAPED_OCCURRENCE, key.entry.entryPsi, "The key of this assignment is not written as plain '$oldName${key.suffix}'")
            }
        }
        for (read in reads) {
            if (rawText(read) != "$$oldName") {
                out += RenameConflict(RenameConflictKind.ESCAPED_OCCURRENCE, read.element, "This read is not written as plain \$$oldName")
            }
        }

        // 6: `$oldName` that is no known read: a use the model leaves out, which the rename would not change
        for (host in model.unknownMentions(oldName)) {
            out += RenameConflict(RenameConflictKind.UNREAD_MENTION, host, "\$$oldName is used here in a way the rename cannot follow")
        }
        return out
    }

    /**
     * Writes [newName] into every occurrence with one change of the host document per range, from the end backwards,
     * and commits once; gives the entry of the first assignment afterwards. A plain YAML key is written whole, quoted
     * where the name would not read back plain; a quoted key gets the text between its quotes; a read gets the name
     * after its `$`.
     */
    fun write(project: Project, newName: String): PsiElement? {
        variableNameProblem(newName)?.let { throw IncorrectOperationException(it) }
        JsonataElementFactory.variableToken(project, newName)
        if (newName == oldName) return keys.firstOrNull()?.entry?.entryPsi
        val documents = PsiDocumentManager.getInstance(project)
        val hostDocument = documents.getDocument(document.file) ?: throw IncorrectOperationException("the definition cannot be edited")
        val edits = ArrayList<Pair<TextRange, String>>()
        for (key in keys) {
            val keyPsi = key.entry.keyPsi
            val range = keyPsi.textRange
            edits += when {
                isQuoted(keyPsi.text) -> TextRange(range.startOffset + 1, range.endOffset - 1) to newName + key.suffix
                key.entry.entryPsi is YAMLKeyValue -> range to NameWriter.yamlValue(project, newName + key.suffix)
                else -> range to newName + key.suffix
            }
        }
        for (read in reads) {
            val range = fileRange(read) ?: throw IncorrectOperationException("a read of \$$oldName cannot be located in the definition")
            val raw = range.substring(hostDocument.text)
            edits += if (raw.startsWith("$")) TextRange(range.startOffset + 1, range.endOffset) to newName else range to "$$newName"
        }
        val ordered = edits.distinct().sortedBy { it.first.startOffset }
        for (i in 1 until ordered.size) {
            if (ordered[i].first.startOffset < ordered[i - 1].first.endOffset) throw IncorrectOperationException("two occurrences of \$$oldName overlap")
        }
        val first = keys.firstOrNull()?.entry?.keyPsi?.textRange?.startOffset
        documents.doPostponedOperationsAndUnblockDocument(hostDocument)
        for ((range, text) in ordered.asReversed()) hostDocument.replaceString(range.startOffset, range.endOffset, text)
        documents.commitDocument(hostDocument)
        if (first == null) return null
        val shift = ordered.filter { it.first.endOffset <= first }.sumOf { it.second.length - it.first.length }
        val file = documents.getPsiFile(hostDocument) ?: return null
        return PsiTreeUtil.getParentOfType(file.findElementAt(first + shift), YAMLKeyValue::class.java, JsonProperty::class.java)
    }

    /** The references of the reads, for the preview; the rename writes the reads itself. */
    fun readReferences(): List<PsiReference> = variables.flatMap { AslVariableTargets.readReferences(it) }

    /**
     * A reference on the name in the key of every assignment but the one [element] stands for, so the preview lists
     * every key the rename writes. They resolve to [element]; the rename writes the keys itself.
     */
    fun keyReferences(element: PsiElement): List<PsiReference> {
        val own = (element as? AslVariableTarget)?.entry ?: AslVariableTargets.assignmentEntryAt(element)
        return keys.filter { it.entry.entryPsi != own }.map { key ->
            val keyPsi = key.entry.keyPsi
            val text = keyPsi.text
            val quote = if (isQuoted(text)) 1 else 0
            val end = text.length - quote - key.suffix.length
            AslVariableKeyReference(keyPsi, if (end > quote) TextRange(quote, end) else TextRange(0, text.length), element)
        }
    }

    private fun elementOf(read: AslRead): PsiElement = when (read) {
        is JsonataRead -> read.variable
        is PathRead -> read.scalar.psi
    }

    private fun fileRange(read: Read): TextRange? {
        val host = read.host ?: return null
        val range = read.range ?: return null
        if (host.containingFile?.viewProvider?.virtualFile != document.file.viewProvider.virtualFile) return null
        return range.shiftRight(host.textRange.startOffset)
    }

    private fun rawText(read: Read): String? = fileRange(read)?.let { range ->
        val text = document.file.text
        if (range.endOffset > text.length) null else range.substring(text)
    }

    companion object {
        /** The rename of the variables [element] stands for, or null when it stands for none (see [renamedVariables]). */
        fun of(element: PsiElement): AslVariableRename? {
            val variables = renamedVariables(element).takeIf { it.isNotEmpty() } ?: return null
            val model = AslVariableTargets.modelOf(variables.first()) ?: return null
            val document = AslModel.of(variables.first().scope.obj.psi.containingFile) ?: return null
            return AslVariableRename(document, model, variables)
        }

        private fun isQuoted(text: String): Boolean = text.length >= 2 && (text[0] == '"' || text[0] == '\'') && text.last() == text[0]

        /** Whether [machine] is [scope], encloses it or lies inside it, at any depth. */
        private fun inLine(machine: AslStateMachine, scope: AslStateMachine): Boolean {
            fun outward(m: AslStateMachine) = generateSequence(m) { it.owner?.machine }
            return scope in outward(machine) || machine in outward(scope)
        }

        /** The text of a key without its quotes. */
        private fun namePart(text: String): String = if (isQuoted(text)) text.substring(1, text.length - 1) else text
    }
}

/**
 * How many times the YAML value tree uses an element of [file]: once through its place, plus once for each use of
 * every alias (or merge key) of an anchor on the element or on one of its ancestors. An element of a JSON file, or of
 * a YAML file without aliases, is used once. A cycle of aliases counts as more uses than any model counts.
 */
private class YamlTreeUses(file: PsiFile) {
    private val aliasesOf: Map<PsiElement, List<YAMLAlias>> =
        if (file !is YAMLFile) emptyMap()
        else PsiTreeUtil.findChildrenOfType(file, YAMLAlias::class.java)
            .mapNotNull { alias -> alias.reference?.resolve()?.markedValue?.let { it to alias } }
            .groupBy({ it.first }, { it.second })
    private val counted = HashMap<PsiElement, Long>()
    private val visiting = HashSet<PsiElement>()

    fun of(element: PsiElement): Long {
        if (aliasesOf.isEmpty() || element is PsiFile) return 1
        counted[element]?.let { return it }
        if (!visiting.add(element)) return MANY
        try {
            var uses = element.parent?.let { of(it) } ?: 1
            for (alias in aliasesOf[element].orEmpty()) uses = minOf(MANY, uses + of(alias))
            counted[element] = uses
            return uses
        } finally {
            visiting.remove(element)
        }
    }

    private companion object {
        const val MANY = 1_000_000L
    }
}

/**
 * The keys that stand next to a key in a mapping that takes keys from a YAML merge key (`<<`). The YAML tree drops a
 * merged key that an explicit one masks, so the model does not show these.
 */
private object YamlMerges {
    private const val MERGE_KEY = "<<"

    /** The keys found, and whether every merge key on the way resolved to mappings; when not, [keys] may miss some. */
    class Keys(val keys: List<YAMLKeyValue>, val resolved: Boolean)

    /** The mappings a merge key brings in, and whether its value is nothing but mappings and aliases of mappings. */
    private class Targets(val mappings: List<YAMLMapping>, val resolved: Boolean)

    /**
     * Every key, explicit or merged (through nested merge keys too), of each mapping that holds [entry] or merges the
     * mapping that holds it, where that mapping has a merge key; [entry] itself excluded. Empty outside YAML.
     */
    fun keysBesides(entry: PsiElement): Keys {
        val own = entry.parent as? YAMLMapping ?: return Keys(emptyList(), resolved = true)
        val file = entry.containingFile as? YAMLFile ?: return Keys(emptyList(), resolved = true)
        val merges = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java).filter { isMerge(it) }
        // the mapping of the entry, and every mapping that merges one of these; a merge key that resolves to no
        // mapping cannot merge one of them, and counts below only where a holder has it
        val holders = linkedSetOf(own)
        val pending = ArrayDeque(listOf(own))
        while (pending.isNotEmpty()) {
            val mapping = pending.removeFirst()
            for (merge in merges) {
                val holder = merge.parentMapping ?: continue
                if (mapping in targets(merge).mappings && holders.add(holder)) pending += holder
            }
        }
        val found = ArrayList<YAMLKeyValue>()
        var resolved = true
        for (holder in holders) {
            if (holder.keyValues.none { isMerge(it) }) continue
            if (!collect(holder, HashSet(), found)) resolved = false
        }
        return Keys(found.filter { it !== entry }.distinct(), resolved)
    }

    /** The name of the key of [keyValue], decoded from quotes and escapes as the value tree reads it. */
    fun name(keyValue: YAMLKeyValue): String {
        val raw = keyValue.key?.text?.trim() ?: return keyValue.keyText
        return if (QuotedScalars.isQuoted(raw)) QuotedScalars.decode(raw) else keyValue.keyText
    }

    /** A merge key as the value tree takes it (`YamlTree` compares the key text the same way). */
    private fun isMerge(keyValue: YAMLKeyValue): Boolean = keyValue.keyText == MERGE_KEY

    /**
     * Adds the explicit keys of [mapping] and those its merge keys bring in, recursively, to [into]; false when a merge
     * key on the way does not resolve to mappings, or leads back into a mapping on [path].
     */
    private fun collect(mapping: YAMLMapping, path: MutableSet<YAMLMapping>, into: MutableList<YAMLKeyValue>): Boolean {
        if (!path.add(mapping)) return false
        var resolved = true
        for (keyValue in mapping.keyValues) {
            if (!isMerge(keyValue)) {
                into += keyValue
                continue
            }
            val targets = targets(keyValue)
            if (!targets.resolved) resolved = false
            for (target in targets.mappings) if (!collect(target, path, into)) resolved = false
        }
        path.remove(mapping)
        return resolved
    }

    /** The mappings the merge key [merge] names: a mapping or an alias of one, or a list of these. */
    private fun targets(merge: YAMLKeyValue): Targets {
        val items = when (val value = merge.value) {
            is YAMLSequence -> value.items.map { it.value }
            else -> listOf(value)
        }
        val mappings = items.mapNotNull { item ->
            when (item) {
                is YAMLMapping -> item
                is YAMLAlias -> item.reference?.resolve()?.markedValue as? YAMLMapping
                else -> null
            }
        }
        return Targets(mappings, resolved = items.isNotEmpty() && mappings.size == items.size)
    }
}

/**
 * The variables a rename started on [element] renames: those of an assignment entry or its key, of a go-to target, or
 * of a `$name` in JSONata that reads a state machine variable. A read in a JSONPath string reaches the rename through
 * its reference, which resolves to an assignment entry.
 */
internal fun renamedVariables(element: PsiElement): List<AslVariable> =
    if (!element.isValid) emptyList() else AslVariableTargets.variablesAt(element)

/**
 * Renames a state machine variable: every assignment key, every read in JSONata and every read in a JSONPath string,
 * written into the host document in one pass (decision 6). Each reason the result might read differently is a
 * conflict ([AslVariableRename.conflicts]). It owns every rename started on an element of a variable: the platform's
 * own rename would edit the reads through their references and break them.
 */
class AslVariableRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = renamedVariables(element).isNotEmpty()

    override fun isInplaceRenameSupported(): Boolean = false

    override fun createRenameDialog(project: Project, element: PsiElement, nameSuggestionContext: PsiElement?, editor: Editor?): RenameDialog =
        AslVariableRenameDialog(project, element, nameSuggestionContext, editor)

    /** The reads and the keys of the other assignments, for the preview; [renameElement] writes them all itself. */
    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> =
        AslVariableRename.of(element)?.let { it.readReferences() + it.keyReferences(element) }.orEmpty()

    /** No text search in strings and comments: the rename knows every place it writes. */
    override fun getElementToSearchInStringsAndComments(element: PsiElement): PsiElement? = null

    override fun isToSearchInComments(element: PsiElement): Boolean = false

    override fun setToSearchInComments(element: PsiElement, enabled: Boolean) {}

    override fun isToSearchForTextOccurrences(element: PsiElement): Boolean = false

    override fun setToSearchForTextOccurrences(element: PsiElement, enabled: Boolean) {}

    override fun findCollisions(element: PsiElement, newName: String, allRenames: Map<out PsiElement, String>, result: MutableList<UsageInfo>) {
        val rename = AslVariableRename.of(element) ?: return
        for (conflict in rename.conflicts(newName)) {
            result += object : UnresolvableCollisionUsageInfo(conflict.element, element) {
                override fun getDescription(): String = conflict.description
            }
        }
    }

    override fun renameElement(element: PsiElement, newName: String, usages: Array<UsageInfo>, listener: RefactoringElementListener?) {
        val rename = AslVariableRename.of(element) ?: throw IncorrectOperationException("not a state machine variable")
        val renamed = rename.write(element.project, newName)
        if (renamed != null) listener?.elementRenamed(renamed)
    }
}

/** Accepts a new variable name by [variableNameProblem], for every element a rename of a variable starts on. */
class AslVariableNameInputValidator : RenameInputValidator {
    override fun getPattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement().with(object : PatternCondition<PsiElement>("aslVariable") {
            override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean = renamedVariables(element).isNotEmpty()
        })

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext): Boolean = variableNameProblem(newName) == null
}

/**
 * The Rename dialog for a state machine variable: it offers the variable's name (a JSONPath key is `name.$`), judges a
 * name by [variableNameProblem] only, and has no options to search in comments, strings and text, which would edit
 * text the rename has not checked.
 */
internal class AslVariableRenameDialog(project: Project, element: PsiElement, nameSuggestionContext: PsiElement?, editor: Editor?) :
    RenameDialog(project, element, nameSuggestionContext, editor) {
    override fun getSuggestedNames(): Array<String> =
        renamedVariables(psiElement).firstOrNull()?.let { arrayOf(it.name) } ?: super.getSuggestedNames()

    override fun canRun() {
        variableNameProblem(newName)?.let { throw ConfigurationException(it) }
    }

    override fun createCheckboxes(panel: JPanel, gbConstraints: GridBagConstraints) {
        val before = panel.componentCount
        super.createCheckboxes(panel, gbConstraints)
        for (i in before until panel.componentCount) {
            val component = panel.getComponent(i)
            component.isVisible = false
            (component as? JCheckBox)?.isSelected = false
        }
    }

    override fun isSearchInComments(): Boolean = false

    override fun isSearchInNonJavaFiles(): Boolean = false

    override fun createRenameProcessor(newName: String): RenameProcessor =
        RenameProcessor(project, psiElement, newName, refactoringScope, false, false)
}
