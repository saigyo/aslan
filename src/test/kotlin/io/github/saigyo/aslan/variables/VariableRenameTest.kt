package io.github.saigyo.aslan.variables

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.refs.AslVariableRename
import io.github.saigyo.aslan.refs.AslVariableRenameDialog
import io.github.saigyo.aslan.refs.AslVariableRenameProcessor
import io.github.saigyo.aslan.refs.AslVariableTarget
import io.github.saigyo.aslan.refs.AslVariableTargets
import io.github.saigyo.aslan.refs.RenameConflictKind
import io.github.saigyo.aslan.refs.RenameConflictKind.ESCAPED_OCCURRENCE
import io.github.saigyo.aslan.refs.RenameConflictKind.LOCAL_OF_NEW_NAME
import io.github.saigyo.aslan.refs.RenameConflictKind.NAME_IN_REGION
import io.github.saigyo.aslan.refs.RenameConflictKind.NEW_NAME_MENTIONED
import io.github.saigyo.aslan.refs.RenameConflictKind.REGION_OPEN
import io.github.saigyo.aslan.refs.RenameConflictKind.SHARED_OCCURRENCE
import io.github.saigyo.aslan.refs.RenameConflictKind.UNREAD_MENTION
import io.github.saigyo.aslan.refs.variableNameProblem
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

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
        for (name in listOf("", "1a", "a-b", "a b", "states", "a".repeat(81))) {
            assertNotNull(name, variableNameProblem(name))
            assertFalse(name, RenameUtil.isValidName(project, entry, name))
        }
        for (name in listOf("_a", "bobine_nº", "a".repeat(80))) {
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
        assertEquals(setOf(NAME_IN_REGION), assertRenameConflict(nested, "film"))
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
}
