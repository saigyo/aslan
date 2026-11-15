package io.github.saigyo.aslan.variables

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import com.intellij.usages.UsageTarget
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.refs.AslVariableDescriptionProvider
import io.github.saigyo.aslan.refs.AslVariableRename
import io.github.saigyo.aslan.refs.AslVariableRenameDialog
import io.github.saigyo.aslan.refs.AslVariableRenameProcessor
import io.github.saigyo.aslan.refs.AslVariableTarget
import io.github.saigyo.aslan.refs.AslVariableTargets
import io.github.saigyo.aslan.refs.AslVariableUsageTypeProvider
import io.github.saigyo.aslan.refs.RenameConflictKind
import io.github.saigyo.aslan.refs.RenameConflictKind.ESCAPED_OCCURRENCE
import io.github.saigyo.aslan.refs.RenameConflictKind.LOCAL_OF_NEW_NAME
import io.github.saigyo.aslan.refs.RenameConflictKind.MERGE_KEY_CLASH
import io.github.saigyo.aslan.refs.RenameConflictKind.NAME_IN_REGION
import io.github.saigyo.aslan.refs.RenameConflictKind.NEW_NAME_MENTIONED
import io.github.saigyo.aslan.refs.RenameConflictKind.OUTER_ASSIGNMENT
import io.github.saigyo.aslan.refs.RenameConflictKind.REGION_OPEN
import io.github.saigyo.aslan.refs.RenameConflictKind.SHARED_OCCURRENCE
import io.github.saigyo.aslan.refs.RenameConflictKind.UNREAD_MENTION
import io.github.saigyo.aslan.refs.variableNameProblem
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.File

class VariableRenameTest : VariableTestCase() {
    /**
     * `reel` is assigned in Load, in its Catcher and in the second Choice rule of Check, and read in Choice conditions,
     * in the Choice rule's own Assign, in the Arguments of Label, in two JSONPath strings of Shelve and in the Output of
     * Done. The JSONata object key `"reel"`, the field `.reel` of `$states` and the Comment are no reads. `§` stands
     * for `$`.
     */
    private val archive = """
        StartAt: Load
        States:
          Load:
            Type: Task
            QueryLanguage: JSONata
            Resource: arn:aws:states:::lambda:invoke
            Arguments:
              FunctionName: catalog-intake
            Assign:
              reel: "{% §states.result.Payload.reel %}"
              shelf: 4
            Catch:
              - ErrorEquals: [States.ALL]
                Assign:
                  reel: none
                Next: Check
            Next: Check
          Check:
            Type: Choice
            QueryLanguage: JSONata
            Choices:
              - Condition: "{% §reel = 'none' %}"
                Next: Label
              - Condition: "{% §exists(§reel) %}"
                Assign:
                  reel: "{% §reel & '-checked' %}"
                Next: Label
            Default: Label
          Label:
            Type: Task
            QueryLanguage: JSONata
            Resource: arn:aws:states:::lambda:invoke
            Arguments:
              FunctionName: catalog-label
              Payload: '{% {"reel": §reel, "shelf": §shelf} %}'
            Next: Shelve
          Shelve:
            Type: Pass
            Comment: Shelves §reel on §shelf
            InputPath: §reel.title
            Parameters:
              label.§: States.Format('{} on {}', §reel, §shelf)
            Next: Done
          Done:
            Type: Pass
            QueryLanguage: JSONata
            Output: |-
              {% §reel %}
            End: true
    """.trimIndent()

    /** [archive] with `reel` renamed to `film`. */
    private val renamedArchive = """
        StartAt: Load
        States:
          Load:
            Type: Task
            QueryLanguage: JSONata
            Resource: arn:aws:states:::lambda:invoke
            Arguments:
              FunctionName: catalog-intake
            Assign:
              film: "{% §states.result.Payload.reel %}"
              shelf: 4
            Catch:
              - ErrorEquals: [States.ALL]
                Assign:
                  film: none
                Next: Check
            Next: Check
          Check:
            Type: Choice
            QueryLanguage: JSONata
            Choices:
              - Condition: "{% §film = 'none' %}"
                Next: Label
              - Condition: "{% §exists(§film) %}"
                Assign:
                  film: "{% §film & '-checked' %}"
                Next: Label
            Default: Label
          Label:
            Type: Task
            QueryLanguage: JSONata
            Resource: arn:aws:states:::lambda:invoke
            Arguments:
              FunctionName: catalog-label
              Payload: '{% {"reel": §film, "shelf": §shelf} %}'
            Next: Shelve
          Shelve:
            Type: Pass
            Comment: Shelves §reel on §shelf
            InputPath: §film.title
            Parameters:
              label.§: States.Format('{} on {}', §film, §shelf)
            Next: Done
          Done:
            Type: Pass
            QueryLanguage: JSONata
            Output: |-
              {% §film %}
            End: true
    """.trimIndent()

    /** A JSONata machine with the states [states] (YAML, indented here) and `reel` assigned in Load. */
    private fun jsonataMachine(states: String): String = machine(
        """
        Load:
          Type: Pass
          Assign:
            reel: 1
          Next: Show
        """.trimIndent() + "\n" + states.trimIndent(),
        top = "QueryLanguage: JSONata",
    )

    private fun configure(text: String, name: String = "test.asl.yaml"): PsiFile =
        myFixture.configureByText(name, text.replace("§", "$"))

    /** [text] with `<caret>` put in place of the first [marker], which must hold `|` where the caret goes. */
    private fun withCaret(text: String, marker: String): String {
        val plain = marker.replace("|", "")
        assertTrue("no '$plain' in the text", plain in text)
        return text.replaceFirst(plain, marker.replace("|", "<caret>"))
    }

    /** The configured file; the fixture hands out the injected file when the caret is in one. */
    private fun hostFile(): PsiFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)

    private fun hostText(): String {
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitAllDocuments()
        return documents.getDocument(hostFile())!!.text
    }

    /** The assignment entry whose key starts the [n]th occurrence of [key] in the host file. */
    private fun entry(key: String, n: Int = 0): YAMLKeyValue {
        val offset = Regex(Regex.escape(key)).findAll(hostFile().text).elementAt(n).range.first
        return PsiTreeUtil.getParentOfType(hostFile().findElementAt(offset), YAMLKeyValue::class.java)!!
    }

    private fun renameAtCaret(text: String, newName: String, name: String = "test.asl.yaml"): String {
        configure(text, name)
        myFixture.renameElementAtCaret(newName)
        return hostText()
    }

    /** What a rename started at the caret would rename: the target the platform finds there. */
    private fun targetAtCaret(): PsiElement = TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted)!!

    /**
     * Renames at the caret of [before] to [newName], expects a conflict and an unchanged file, and gives the kinds of
     * conflict found.
     */
    private fun assertRenameConflict(before: String, newName: String, name: String = "test.asl.yaml"): Set<RenameConflictKind> {
        configure(before, name)
        val kinds = AslVariableRename.of(targetAtCaret())!!.conflicts(newName).map { it.kind }.toSet()
        try {
            myFixture.renameElementAtCaret(newName)
            fail("expected a rename conflict for $newName")
        } catch (e: ConflictsInTestsException) {
            assertFalse(e.messages.isEmpty())
        }
        assertEquals(before.replace("<caret>", "").replace("§", "$"), hostText())
        return kinds
    }

    fun `test rename from an assignment changes every occurrence`() {
        assertEquals(renamedArchive.replace("§", "$"), renameAtCaret(withCaret(archive, "    re|el: none"), "film"))
    }

    fun `test rename from a JSONata read and from a JSONPath read`() {
        assertEquals(renamedArchive.replace("§", "$"), renameAtCaret(withCaret(archive, "{% §re|el %}"), "film"))
        assertEquals(renamedArchive.replace("§", "$"), renameAtCaret(withCaret(archive, "InputPath: §re|el"), "film"))
        assertEquals(renamedArchive.replace("§", "$"), renameAtCaret(withCaret(archive, "§re|el, §shelf)"), "film"))
    }

    /**
     * The rename of [element] to [newName] as the Rename dialog runs it: in its default scope Project Files, without
     * comments and text occurrences; with the usages the platform shows in the preview.
     */
    private class PreviewProcessor(element: PsiElement, newName: String = "film") :
        RenameProcessor(element.project, element, newName, GlobalSearchScope.projectScope(element.project), false, false) {
        fun usages(): List<UsageInfo> = findUsages().toList()
    }

    /**
     * A PSI change in another file, as happens while the Rename dialog is open: it drops every model, whose variables
     * and go-to targets then name scopes the new model no longer has.
     */
    private fun changeAnotherFile() {
        val other = myFixture.addFileToProject("notes-${System.nanoTime()}.txt", "notes")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(other)!!
            document.insertString(0, "more ")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    /** The host offset where the name ends in [usage]. */
    private fun nameEnd(usage: UsageInfo): Int {
        val element = usage.element!!
        val range = usage.rangeInElement!!.shiftRight(element.textRange.startOffset)
        return InjectedLanguageManager.getInstance(project).injectedToHost(element, range).endOffset
    }

    /**
     * The preview of a rename lists every place it writes: the element it starts on, the keys of the other assignments
     * as writes and every read, each once and each with its usage type, also after a PSI change while the dialog is
     * open. An assignment the rename starts on is presented by the variable's name and its place, not by its value.
     */
    fun `test the rename preview lists every assignment and every read`() {
        val everyName = Regex("film").findAll(renamedArchive.replace("§", "$")).map { it.range.last + 1 }.toList()
        for (marker in listOf("    re|el: none", "{% §re|el %}", "InputPath: §re|el")) {
            configure(withCaret(archive, marker))
            val element = targetAtCaret()
            changeAnotherFile()
            val usages = PreviewProcessor(element).usages()
            val entry = AslVariableTargets.assignmentEntryAt(element) as? YAMLKeyValue
            val own = listOfNotNull(entry?.key?.textRange?.endOffset)
            assertEquals(marker, everyName, (usages.map { nameEnd(it) } + own).sorted())
            val target = arrayOf<UsageTarget>(PsiElement2UsageTargetAdapter(element, true))
            val types = usages.map { AslVariableUsageTypeProvider().getUsageType(it.element!!, target) }
            assertEquals(marker, 3 - own.size, types.count { it == AslVariableUsageTypeProvider.ASSIGNMENT })
            assertEquals(marker, usages.size - 3 + own.size, types.count { it == AslVariableUsageTypeProvider.READ })
            val detector = ReadWriteAccessDetector.findDetector(entry("reel:"))!!
            val writes = usages.count { detector.getReferenceAccess(entry("reel:"), it.reference!!) == ReadWriteAccessDetector.Access.Write }
            assertEquals(marker, 3 - own.size, writes)
        }
        configure(withCaret(archive, "    re|el: none"))
        val presentation = PsiElement2UsageTargetAdapter(targetAtCaret(), true).presentation
        assertEquals("reel", presentation.presentableText)
        assertEquals("Load, first Catcher", presentation.locationString)
        assertEquals(AslVariableDescriptionProvider.TYPE, UsageViewUtil.getType(targetAtCaret()))
    }

    /**
     * The commissioner's case: Rename from the key `rejection` of WeighVerdicts' first Choice rule in the film archive
     * fixture, Preview, then Refactor. The preview lists the other 18 keys and the 5 reads; the rename writes all 24.
     */
    fun `test the rename of rejection from a key lists and writes every occurrence`() {
        val intake = File("src/test/testData/clean/film-archive-intake.asl.yaml").readText()
        val keys = Regex("""(?m)^\s+rejection:""").findAll(intake).toList()
        val start = keys[18].range.last - 1
        configure(intake.substring(0, start) + "<caret>" + intake.substring(start), "film-archive-intake.asl.yaml")
        val element = targetAtCaret()
        assertEquals("WeighVerdicts, first Choice rule", PsiElement2UsageTargetAdapter(element, true).presentation.locationString)
        changeAnotherFile()
        val processor = PreviewProcessor(element, "rejection2")
        val usages = processor.usages()
        assertEquals(23, usages.size)
        changeAnotherFile()
        processor.run()
        val reads = Regex("""\${'$'}rejection\b""")
        assertEquals(5, reads.findAll(intake).count())
        val expected = intake.replace(Regex("""(?m)^([ \t]+)rejection:"""), "\$1rejection2:").replace(reads, "\\\$rejection2")
        assertEquals(24, Regex("""rejection2\b""").findAll(expected).count())
        assertEquals(expected, hostText())
    }

    /**
     * Each element a rename of `reel` can start on is renamed by the variable rename, and gives its text. The
     * platform's own rename of a key would also edit the reads through their references, and break them.
     */
    fun `test every start of a rename gives the text of the variable rename`() {
        val starts = listOf<Pair<String, () -> PsiElement>>(
            "the entry" to { entry("reel:", 1) },
            "the key" to { entry("reel:", 1).key!! },
            "a go-to target" to {
                val entry = entry("reel:", 2)
                AslVariableTarget(AslVariableTargets.variablesAt(entry).single(), entry, "Check, second Choice rule")
            },
            "a JSONata read" to {
                val host = PsiTreeUtil.getParentOfType(hostFile().findElementAt(hostFile().text.indexOf("\$reel %}")), YAMLScalar::class.java)!!
                PsiTreeUtil.findChildOfType(JsonataPsi.injectedFile(host)!!, JsonataVariableRef::class.java)!!
            },
        )
        for ((start, element) in starts) {
            configure(archive)
            val target = element()
            assertInstanceOf(RenamePsiElementProcessor.forElement(target), AslVariableRenameProcessor::class.java)
            myFixture.renameElement(target, "film")
            assertEquals(start, renamedArchive.replace("§", "$"), hostText())
        }
        for (marker in listOf("    re|el: none", "{% §re|el %}", "InputPath: §re|el")) {
            configure(withCaret(archive, marker))
            assertTrue(marker, RenamePsiElementProcessor.forElement(targetAtCaret()) is AslVariableRenameProcessor)
        }
    }

    fun `test rename keeps the dollar suffix of a JSONPath key`() {
        val before = """
            StartAt: Load
            States:
              Load:
                Type: Pass
                Assign:
                  re<caret>el.§: §.reel
                  count: 1
                Next: Store
              Store:
                Type: Pass
                Assign:
                  'reel.§': States.Format('{}-copy', §reel)
                OutputPath: §reel
                Next: Show
              Show:
                Type: Pass
                QueryLanguage: JSONata
                Output: "{% §reel %}"
                End: true
        """.trimIndent()
        val after = """
            StartAt: Load
            States:
              Load:
                Type: Pass
                Assign:
                  film.§: §.reel
                  count: 1
                Next: Store
              Store:
                Type: Pass
                Assign:
                  'film.§': States.Format('{}-copy', §film)
                OutputPath: §film
                Next: Show
              Show:
                Type: Pass
                QueryLanguage: JSONata
                Output: "{% §film %}"
                End: true
        """.trimIndent()
        configure(before)
        val dialog = RenamePsiElementProcessor.forElement(targetAtCaret()).createRenameDialog(project, targetAtCaret(), null, null)
        try {
            assertInstanceOf(dialog, AslVariableRenameDialog::class.java)
            // the key is `reel.$`; the dialog offers the variable's name
            assertEquals("reel", dialog.suggestedNames.first())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
        assertEquals(after.replace("§", "$"), renameAtCaret(before, "film"))
    }

    fun `test rename in a JSON definition`() {
        val before = """
            {
              "QueryLanguage": "JSONata",
              "StartAt": "Load",
              "States": {
                "Load": {
                  "Type": "Pass",
                  "Assign": {"re<caret>el": "{% §states.input.reel %}", "shelf": 4},
                  "Next": "Store"
                },
                "Store": {
                  "Type": "Task",
                  "Resource": "arn:aws:states:::lambda:invoke",
                  "Arguments": {"FunctionName": "catalog-store", "Payload": "{% §reel & §shelf %}"},
                  "Catch": [{"ErrorEquals": ["States.ALL"], "Assign": {"reel": "{% §reel & '-failed' %}"}, "Next": "Shelve"}],
                  "Next": "Shelve"
                },
                "Shelve": {
                  "Type": "Pass",
                  "QueryLanguage": "JSONPath",
                  "InputPath": "§reel",
                  "End": true
                }
              }
            }
        """.trimIndent()
        val after = """
            {
              "QueryLanguage": "JSONata",
              "StartAt": "Load",
              "States": {
                "Load": {
                  "Type": "Pass",
                  "Assign": {"film": "{% §states.input.reel %}", "shelf": 4},
                  "Next": "Store"
                },
                "Store": {
                  "Type": "Task",
                  "Resource": "arn:aws:states:::lambda:invoke",
                  "Arguments": {"FunctionName": "catalog-store", "Payload": "{% §film & §shelf %}"},
                  "Catch": [{"ErrorEquals": ["States.ALL"], "Assign": {"film": "{% §film & '-failed' %}"}, "Next": "Shelve"}],
                  "Next": "Shelve"
                },
                "Shelve": {
                  "Type": "Pass",
                  "QueryLanguage": "JSONPath",
                  "InputPath": "§film",
                  "End": true
                }
              }
            }
        """.trimIndent()
        assertEquals(after.replace("§", "$"), renameAtCaret(before, "film", "test.asl.json"))
    }

    fun `test rename rejects a name that breaks the rule`() {
        configure(archive)
        val entry = entry("reel:")
        for (name in listOf("", "1a", "_a", "a-b", "a b", "states", "a".repeat(81))) {
            assertNotNull(name, variableNameProblem(name))
            assertFalse(name, RenameUtil.isValidName(project, entry, name))
        }
        for (name in listOf("a_", "bobine_nº", "a".repeat(80))) {
            assertNull(name, variableNameProblem(name))
            assertTrue(name, RenameUtil.isValidName(project, entry, name))
        }
    }

    fun `test conflict 1, another variable of the new name in the region`() {
        // a variable assigned in the same machine
        assertEquals(setOf(NAME_IN_REGION), assertRenameConflict(withCaret(archive, "    re|el: none"), "shelf"))
        // a read of the new name that nothing assigns
        assertEquals(
            setOf(NAME_IN_REGION),
            assertRenameConflict(withCaret(jsonataMachine("Show:\n  Type: Pass\n  Output: \"{% §reel & §box %}\"\n  End: true"), "re|el: 1"), "box"),
        )
        // a variable of an outer machine that the inner one reaches
        val nested = machine(
            """
            Tag:
              Type: Pass
              Assign:
                film: 1
              Next: Each
            Each:
              Type: Map
              ItemProcessor:
                StartAt: Mark
                States:
                  Mark:
                    Type: Pass
                    Assign:
                      it<caret>em: "{% §states.input %}"
                    Output: "{% §item %}"
                    End: true
              End: true
            """.trimIndent(),
            top = "QueryLanguage: JSONata",
        )
        // ... which AWS would also reject as an inner assignment of a name assigned before the Map
        assertEquals(setOf(NAME_IN_REGION, OUTER_ASSIGNMENT), assertRenameConflict(nested, "film"))
        // a machine of the region whose names the file does not say
        val open = jsonataMachine("Show:\n  Type: Pass\n  Assign: '{% {\"box\": 1} %}'\n  Output: \"{% §reel %}\"\n  End: true")
        assertEquals(setOf(REGION_OPEN), assertRenameConflict(withCaret(open, "re|el: 1"), "film"))
    }

    fun `test conflict 2, a local variable of the new name where the variable is read`() {
        val text = jsonataMachine("Show:\n  Type: Pass\n  Output: \"{% (§film := 1; §reel & §film) %}\"\n  End: true")
        // the local `$film` is also a mention of the new name that is no read (conflict 3)
        assertTrue(LOCAL_OF_NEW_NAME in assertRenameConflict(withCaret(text, "re|el: 1"), "film"))
    }

    fun `test conflict 3, a built-in function of the new name in the region`() {
        val text = jsonataMachine("Show:\n  Type: Pass\n  Output: \"{% §sum([§reel, 1]) %}\"\n  End: true")
        assertEquals(setOf(NEW_NAME_MENTIONED), assertRenameConflict(withCaret(text, "re|el: 1"), "sum"))
    }

    fun `test conflict 4, an occurrence that belongs to two variables`() {
        val sharedAssign = """
            QueryLanguage: JSONata
            StartAt: Split
            States:
              Split:
                Type: Parallel
                Branches:
                  - StartAt: Tag
                    States:
                      Tag:
                        Type: Pass
                        Assign: &shared
                          re<caret>el: 1
                        Next: Show
                      Show:
                        Type: Pass
                        Output: "{% §reel %}"
                        End: true
                  - StartAt: Tag
                    States:
                      Tag:
                        Type: Pass
                        Assign: *shared
                        Next: Show
                      Show:
                        Type: Pass
                        Output: "{% §reel %}"
                        End: true
                End: true
        """.trimIndent()
        assertEquals(setOf(SHARED_OCCURRENCE), assertRenameConflict(sharedAssign, "film"))
        val sharedRead = """
            QueryLanguage: JSONata
            StartAt: Split
            States:
              Split:
                Type: Parallel
                Branches:
                  - StartAt: Tag
                    States:
                      Tag:
                        Type: Pass
                        Assign:
                          re<caret>el: 1
                        Next: Show
                      Show:
                        Type: Pass
                        Output: &out "{% §reel %}"
                        End: true
                  - StartAt: Tag
                    States:
                      Tag:
                        Type: Pass
                        Assign:
                          reel: 2
                        Next: Show
                      Show:
                        Type: Pass
                        Output: *out
                        End: true
                End: true
        """.trimIndent()
        assertEquals(setOf(SHARED_OCCURRENCE), assertRenameConflict(sharedRead, "film"))
    }

    fun `test conflict 4, an occurrence that YAML also uses in a place that is no occurrence`() {
        val fields = """
            StartAt: Store
            States:
              Store:
                Type: Pass
                Parameters: &fields
                  re<caret>el.§: §.reel
                Assign: *fields
                Next: Show
              Show:
                Type: Pass
                InputPath: §reel
                End: true
        """.trimIndent()
        // the rename would also change the key of Parameters, and so the state's output
        assertEquals(setOf(SHARED_OCCURRENCE), assertRenameConflict(fields, "film"))
        val merged = fields.replace("Assign: *fields", "Assign:\n      <<: *fields")
        assertTrue(merged, "<<: *fields" in merged)
        assertEquals(setOf(SHARED_OCCURRENCE), assertRenameConflict(merged, "film"))
        // an expression aliased into a JSONPath state, where it is a plain string
        val expression = jsonataMachine(
            """
            Show:
              Type: Pass
              Output: &shown "{% §reel %}"
              Next: Note
            Note:
              Type: Pass
              QueryLanguage: JSONPath
              Parameters:
                note: *shown
              End: true
            """.trimIndent(),
        )
        assertEquals(setOf(SHARED_OCCURRENCE), assertRenameConflict(withCaret(expression, "re|el: 1"), "film"))
    }

    fun `test a rename refuses to unmask a merged key of the old name`() {
        val text = """
            StartAt: Store
            States:
              Store:
                Type: Pass
                Parameters: &defaults
                  reel: 1
                Assign:
                  <<: *defaults
                  re<caret>el: 2
                Next: Show
              Show:
                Type: Pass
                InputPath: §reel
                End: true
        """.trimIndent()
        // after a rename to film, the merged `reel: 1` would take effect and Assign would assign reel as well
        assertEquals(setOf(MERGE_KEY_CLASH), assertRenameConflict(text, "film"))
    }

    fun `test a rename refuses to mask a merged key of the new name`() {
        val text = """
            StartAt: Store
            States:
              Store:
                Type: Pass
                Parameters: &defaults
                  film: 1
                Assign:
                  <<: *defaults
                  re<caret>el: 2
                Next: Show
              Show:
                Type: Pass
                InputPath: §reel
                End: true
        """.trimIndent()
        // the merged `film` is an assignment of the new name too; the renamed key would mask it
        assertEquals(setOf(NAME_IN_REGION, MERGE_KEY_CLASH), assertRenameConflict(text, "film"))
    }

    /**
     * Store's `Assign` merges [merge] and has its own `reel: 2`, the key renamed. `Parameters` (`&defaults`) holds
     * `reel: 1` (written as [defaultsKey]), `Result` (`&more`) holds `shelf: 3` and, with [nested], merges `defaults`.
     */
    private fun mergedInto(merge: String, defaultsKey: String = "reel", nested: Boolean = false): String = """
        StartAt: Store
        States:
          Store:
            Type: Pass
            Parameters: &defaults
              DEFAULTS_KEY: 1
            Result: &more
              NESTED
              shelf: 3
            Assign:
              <<: MERGE
              re<caret>el: 2
            Next: Show
          Show:
            Type: Pass
            InputPath: §reel
            End: true
    """.trimIndent()
        .replace("DEFAULTS_KEY", defaultsKey)
        .replace(if (nested) "NESTED" else "      NESTED\n", if (nested) "<<: *defaults" else "")
        .replace("MERGE", merge)

    fun `test a rename refuses next to an inline or unresolved merge`() {
        // the value tree leaves such a merge key unapplied, so the machine is open as well
        for (merge in listOf("{reel: 1}", "[{reel: 1}, *more]", "*missing", "[*more, plain]")) {
            assertEquals(merge, setOf(REGION_OPEN, MERGE_KEY_CLASH), assertRenameConflict(mergedInto(merge), "film"))
        }
    }

    fun `test a rename refuses next to a key merged through aliases`() {
        // a list of two aliases, the second bringing in reel
        assertEquals(setOf(MERGE_KEY_CLASH), assertRenameConflict(mergedInto("[*more, *defaults]"), "film"))
        // a nested merge: Assign merges Result, which merges Parameters, which holds reel
        val nested = mergedInto("*more", nested = true)
        assertTrue(nested, "      <<: *defaults\n      shelf: 3" in nested)
        assertEquals(setOf(MERGE_KEY_CLASH), assertRenameConflict(nested, "film"))
        // a merged key written with an escape compares by its decoded name
        val e = "\\" + "u0065"
        val escaped = mergedInto("*defaults", defaultsKey = "\"r${e}el\"")
        assertTrue(escaped, "\"r" + e + "el\": 1" in escaped)
        assertEquals(setOf(MERGE_KEY_CLASH), assertRenameConflict(escaped, "film"))
    }

    fun `test a rename refuses where another mapping merges the renamed key's mapping`() {
        val text = """
            StartAt: Store
            States:
              Store:
                Type: Pass
                Parameters: &defaults
                  reel: 1
                Assign: &assign
                  re<caret>el: 2
                Next: Show
              Show:
                Type: Pass
                Assign:
                  <<: [*assign, *defaults]
                InputPath: §reel
                End: true
        """.trimIndent()
        // in Show, the renamed key would no longer mask the reel of Parameters
        assertEquals(setOf(MERGE_KEY_CLASH), assertRenameConflict(text, "film"))
    }

    fun `test an alias that every use reads the variable through is renamed once`() {
        val before = jsonataMachine(
            """
            Show:
              Type: Pass
              Output: &shown "{% §reel %}"
              Next: Again
            Again:
              Type: Pass
              Output: *shown
              End: true
            """.trimIndent(),
        )
        val after = before.replace("reel: 1", "film: 1").replace("{% §reel %}", "{% §film %}")
        assertEquals(after.replace("§", "$"), renameAtCaret(withCaret(before, "re|el: 1"), "film"))
    }

    fun `test a rename refuses where an expression with a syntax error mentions the old name`() {
        val text = jsonataMachine("Show:\n  Type: Pass\n  Output: \"{% §reel + %}\"\n  End: true")
        assertEquals(setOf(UNREAD_MENTION), assertRenameConflict(withCaret(text, "re|el: 1"), "film"))
    }

    fun `test a name that YAML reads as another type is quoted as a key`() {
        val before = jsonataMachine("Show:\n  Type: Pass\n  Output: \"{% §reel %}\"\n  End: true")
        val after = before.replace("reel: 1", "\"true\": 1").replace("{% §reel %}", "{% §true %}")
        assertEquals(after.replace("§", "$"), renameAtCaret(withCaret(before, "re|el: 1"), "true"))
    }

    fun `test rename in multi-line quoted and folded scalars`() {
        val before = jsonataMachine(
            """
            Show:
              Type: Pass
              Output:
                quoted: "{% §reel &
                  §reel %}"
                folded: >-
                  {% §reel
                  & §reel %}
              End: true
            """.trimIndent(),
        )
        val after = before.replace("reel: 1", "film: 1").replace("§reel", "§film")
        assertEquals(after.replace("§", "$"), renameAtCaret(withCaret(before, "re|el: 1"), "film"))
    }

    fun `test conflict 5, an occurrence written with an escape`() {
        val dollar = "\\" + "u0024"
        val yaml = jsonataMachine("Show:\n  Type: Pass\n  Output: \"{% ${dollar}reel %}\"\n  End: true")
        assertTrue(yaml, "\\u0024reel" in yaml)
        assertEquals(setOf(ESCAPED_OCCURRENCE), assertRenameConflict(withCaret(yaml, "re|el: 1"), "film"))

        val e = "\\" + "u0065"
        val json = """
            {
              "QueryLanguage": "JSONata",
              "StartAt": "Tag",
              "States": {
                "Tag": {"Type": "Pass", "Assign": {"r${e}el": 1}, "Next": "Show"},
                "Show": {"Type": "Pass", "Output": "{% §re<caret>el %}", "End": true}
              }
            }
        """.trimIndent()
        assertTrue(json, "r\\u0065el" in json)
        assertEquals(setOf(ESCAPED_OCCURRENCE), assertRenameConflict(json, "film", "test.asl.json"))
    }

    fun `test conflict 6, a mention of the old name that is no known read`() {
        val cases = listOf(
            // a use of a local whose first assignment may not run: at run time it may read the state variable
            "Pass" to "\"{% (true ? 0 : §reel := 2; §reel) %}\"",
            // a use in a function that may run before the local it refers to is assigned
            "Pass" to "\"{% (§f := function(){ §reel }; §f(); §reel := 0) %}\"",
            // an expression whose delimiters come from an escape, so no JSONata is injected into it
            "Pass" to "\"\\x7b% §reel %}\"",
            // a state whose type is being typed, so its strings are no sites
            "Pas" to "\"{% §reel %}\"",
        )
        for ((type, output) in cases) {
            val text = jsonataMachine("Show:\n  Type: $type\n  Output: $output\n  End: true")
            assertEquals(output, setOf(UNREAD_MENTION), assertRenameConflict(withCaret(text, "re|el: 1"), "film"))
        }
        // a Comment is no evaluated string
        val commented = jsonataMachine("Show:\n  Type: Pass\n  Comment: Shows §reel\n  Output: \"{% §reel %}\"\n  End: true")
        assertEquals(
            commented.replace("reel: 1", "film: 1").replace("{% §reel", "{% §film").replace("§", "$"),
            renameAtCaret(withCaret(commented, "re|el: 1"), "film"),
        )
    }

    fun `test a rename leaves the Result of a Pass state as it is`() {
        // AWS does not evaluate Result (r3-AC-pass-result-key): a name there is data
        val before = machine(
            """
            Load:
              Type: Pass
              Assign:
                reel: 1
              Next: Show
            Show:
              Type: Pass
              Result:
                query.§: §reel
              InputPath: §reel
              End: true
            """.trimIndent(),
        )
        val after = before.replace("reel: 1", "film: 1").replace("InputPath: §reel", "InputPath: §film")
        assertEquals(after.replace("§", "$"), renameAtCaret(withCaret(before, "re|el: 1"), "film"))
    }

    /** The top level assigns and reads `reel` before a Distributed Map whose processor assigns and reads `count`. */
    private val acrossDistributedMap = machine(
        """
        Load:
          Type: Pass
          Assign:
            reel: 1
          Output: "{% §reel %}"
          Next: Each
        Each:
          Type: Map
          Items: "{% [1, 2] %}"
          ItemProcessor:
            ProcessorConfig:
              Mode: DISTRIBUTED
              ExecutionType: STANDARD
            StartAt: Count
            States:
              Count:
                Type: Pass
                Assign:
                  count: 2
                Output: "{% §count %}"
                End: true
          End: true
        """.trimIndent(),
        top = "QueryLanguage: JSONata",
    )

    fun `test a rename refuses an outer assignment of the new name across a Distributed Map`() {
        // the reads keep their variables, but AWS rejects the inner assignment of a name assigned before the Map
        assertEquals(setOf(OUTER_ASSIGNMENT), assertRenameConflict(withCaret(acrossDistributedMap, "re|el: 1"), "count"))
        assertEquals(setOf(OUTER_ASSIGNMENT), assertRenameConflict(withCaret(acrossDistributedMap, "co|unt: 2"), "reel"))
        // control: a name used on neither side
        val renamed = acrossDistributedMap.replace("reel: 1", "film: 1").replace("{% §reel", "{% §film")
        assertEquals(renamed.replace("§", "$"), renameAtCaret(withCaret(acrossDistributedMap, "re|el: 1"), "film"))
    }

    fun `test conflict 6, a placeholder that may stand for an expression`() {
        val text = jsonataMachine("Show:\n  Type: Pass\n  Output: §{OutputExpr}\n  Next: Read\nRead:\n  Type: Pass\n  Output: \"{% §reel %}\"\n  End: true")
        // it may read the old name, which the rename would not change, and the new one, which the rename would make read
        assertEquals(setOf(UNREAD_MENTION, NEW_NAME_MENTIONED), assertRenameConflict(withCaret(text, "re|el: 1"), "film"))
        // control: a placeholder inside free-form data is no expression of its own
        val nested = text.replace("Output: §{OutputExpr}", "Output:\n      title: §{Title}")
        assertEquals(
            nested.replace("reel: 1", "film: 1").replace("{% §reel", "{% §film").replace("§", "$"),
            renameAtCaret(withCaret(nested, "re|el: 1"), "film"),
        )
    }

    fun `test a rename leaves another branch's variable alone`() {
        val before = """
            QueryLanguage: JSONata
            StartAt: Split
            States:
              Split:
                Type: Parallel
                Branches:
                  - StartAt: Tag
                    States:
                      Tag:
                        Type: Pass
                        Assign:
                          re<caret>el: 1
                        Next: Show
                      Show:
                        Type: Pass
                        Output: "{% §reel %}"
                        End: true
                  - StartAt: Tag
                    States:
                      Tag:
                        Type: Pass
                        Assign:
                          reel: 2
                        Next: Show
                      Show:
                        Type: Pass
                        Output: "{% §reel %}"
                        End: true
                End: true
        """.trimIndent()
        val after = before.replace("re<caret>el: 1", "film: 1").replaceFirst("{% §reel %}", "{% §film %}")
        assertEquals(after.replace("§", "$"), renameAtCaret(before, "film"))
    }

    fun `test the model of the archive`() {
        // guards the fixture: the reads the expected texts rename are the ones the model knows
        configure(archive)
        val variables = AslModel.of(hostFile())!!.variables
        assertEquals(3, variables.assignments.count { it.name == "reel" })
        assertEquals(7, variables.reads.count { it.name == "reel" })
    }

    fun `test a rename refuses when a Result that an alias reuses mentions the name`() {
        val text = machine(
            "Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Seed\n" +
                "Seed:\n  Type: Pass\n  Result: &shared\n    query.$: §reel\n  Next: Call\n" +
                "Call:\n  Type: §{CallType}\n  Parameters: *shared\n  End: true",
        )
        assertEquals(setOf(UNREAD_MENTION), assertRenameConflict(withCaret(text, "re|el: 1"), "film"))
    }

    fun `test a rename refuses when a Result reused inside, through an ancestor or a merge key mentions the name`() {
        val reused = listOf(
            "Seed:\n  Type: Pass\n  Result:\n    a: &inner\n      query.$: §reel\n  Next: Call\nCall:\n  Type: §{CallType}\n  Parameters: *inner\n  End: true",
            "Seed: &seed\n  Type: Pass\n  Result:\n    query.$: §reel\n  Next: Call\nCall:\n  Type: §{CallType}\n  Parameters: *seed\n  End: true",
            "Seed:\n  Type: Pass\n  Result: &shared\n    query.$: §reel\n  Next: Call\n" +
                "Call:\n  Type: §{CallType}\n  Parameters:\n    <<: *shared\n    extra: 1\n  End: true",
        )
        for (states in reused) {
            val text = machine("Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Seed\n$states")
            assertEquals(states, setOf(UNREAD_MENTION), assertRenameConflict(withCaret(text, "re|el: 1"), "film"))
        }
    }

    /**
     * `reel` assigned in Load before the Distributed Map Each, whose processor assigns `count`; Route ends the machine
     * with [routeTail] (a placeholder `Next` leaves the flow of the top level unknown).
     */
    private fun countingMap(routeTail: String) = machine(
        "Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Each\n" +
            "Each:\n  Type: Map\n  Items: '{% [1, 2] %}'\n  ItemProcessor:\n    ProcessorConfig:\n      Mode: DISTRIBUTED\n" +
            "      ExecutionType: STANDARD\n    StartAt: Count\n    States:\n      Count:\n        Type: Pass\n        Assign:\n" +
            "          count: 1\n        End: true\n  Next: Route\n" +
            "Route:\n  Type: Pass\n  $routeTail",
        top = "QueryLanguage: JSONata",
    )

    fun `test a rename refuses where an outer flow that is not known may put the new name before a nested one`() {
        val unknown = countingMap("Next: §{NextState}")
        assertTrue(RenameConflictKind.OUTER_ASSIGNMENT in assertRenameConflict(withCaret(unknown, "re|el: 1"), "count"))
        // the known path refuses too
        val known = countingMap("End: true")
        assertTrue(RenameConflictKind.OUTER_ASSIGNMENT in assertRenameConflict(withCaret(known, "re|el: 1"), "count", "known.asl.yaml"))
        // with the flow unknown but the new name assigned nowhere nested, the rename runs
        assertEquals(unknown.replace("reel: 1", "film: 1").replace("§", "$"), renameAtCaret(withCaret(unknown, "re|el: 1"), "film", "film.asl.yaml"))
    }

    /** `reel` assigned in Load, then Later of type [type], whose item processor is [processor] (YAML lines below it). */
    private fun laterProcessor(type: String, processor: String) = machine(
        "Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Later\n" +
            "Later:\n  Type: $type\n  Items: '{% [1] %}'\n  ItemProcessor:" + processor + "\n  End: true",
        top = "QueryLanguage: JSONata",
    )

    private val filmProcessor = "\n    StartAt: Inner\n    States:\n      Inner:\n        Type: Pass\n        Assign:\n          film: 1\n        End: true"

    fun `test a rename refuses where a nested machine the model cannot follow may hold the new name`() {
        // a state whose type is a placeholder: if it becomes a Map, AWS rejects film assigned inside after Load
        val placeholderType = laterProcessor("§{StateType}", filmProcessor)
        assertTrue(RenameConflictKind.REGION_OPEN in assertRenameConflict(withCaret(placeholderType, "re|el: 1"), "film"))
        // a placeholder processor may hold anything
        val placeholderProcessor = laterProcessor("Map", " §{Processor}")
        assertTrue(RenameConflictKind.REGION_OPEN in assertRenameConflict(withCaret(placeholderProcessor, "re|el: 1"), "frame", "processor.asl.yaml"))
    }

    fun `test a nested machine the model cannot follow that holds neither name does not refuse a rename`() {
        val placeholderType = laterProcessor("§{StateType}", filmProcessor)
        assertEquals(placeholderType.replace("reel: 1", "frame: 1").replace("§", "$"), renameAtCaret(withCaret(placeholderType, "re|el: 1"), "frame"))
        // and without the placeholder type the file renames as usual
        val map = laterProcessor("Map", filmProcessor)
        assertEquals(map.replace("reel: 1", "frame: 1").replace("§", "$"), renameAtCaret(withCaret(map, "re|el: 1"), "frame", "map.asl.yaml"))
    }

    fun `test a rename refuses where a whole structure the file does not settle may read the name`() {
        fun readerMap(reader: String) = machine(
            "Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Each\n" +
                "Each:\n  Type: Map\n  ItemReader:\n    Resource: arn:aws:states:::s3:getObject\n" + reader.prependIndent("    ") + "\n" +
                "  ItemProcessor:\n    ProcessorConfig:\n      Mode: DISTRIBUTED\n      ExecutionType: STANDARD\n    StartAt: Show\n" +
                "    States:\n      Show:\n        Type: Pass\n        End: true\n  End: true",
            top = "QueryLanguage: JSONata",
        )
        assertEquals(setOf(UNREAD_MENTION), assertRenameConflict(withCaret(readerMap("ReaderConfig: §{ReaderConfig}"), "re|el: 1"), "film"))
        // a placeholder inside payload data does not refuse
        val payload = readerMap("Arguments:\n  a: §{A}")
        assertEquals(payload.replace("reel: 1", "film: 1").replace("§", "$"), renameAtCaret(withCaret(payload, "re|el: 1"), "film", "payload.asl.yaml"))
    }

    fun `test a rename refuses where a combining mark follows the name in a JSONPath read`() {
        val acute = String(Character.toChars(0x0301))
        val text = machine("Load:\n  Type: Pass\n  Assign:\n    a: 1\n    a$acute: 2\n  Next: Show\nShow:\n  Type: Pass\n  InputPath: §a$acute\n  End: true")
        // the file stays as it is, the read with the mark included
        assertEquals(setOf(UNREAD_MENTION), assertRenameConflict(withCaret(text, "    |a: 1"), "b"))
    }

    /** U+10400, a letter outside the Basic Multilingual Plane: two UTF-16 units. */
    private val deseret = String(Character.toChars(0x10400))

    fun `test a rename leaves a read of a name with a supplementary letter after it alone`() {
        val before = machine("Load:\n  Type: Pass\n  Assign:\n    a: 1\n    a$deseret: 2\n  Next: Show\nShow:\n  Type: Pass\n  InputPath: §a$deseret\n  End: true")
        val after = renameAtCaret(withCaret(before, "    |a: 1"), "b")
        assertEquals(before.replace("    a: 1", "    b: 1").replace("§", "$"), after)
    }

    fun `test a name with a supplementary letter after it does not refuse a rename`() {
        val before = jsonataMachine("Show:\n  Type: Pass\n  Output: '{% §reel$deseret %}'\n  End: true")
        val after = renameAtCaret(withCaret(before, "re|el: 1"), "film")
        assertEquals(before.replace("reel: 1", "film: 1").replace("§", "$"), after)
    }
}
