package io.github.saigyo.aslan.variables

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewUtil
import com.intellij.usages.UsageTarget
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.jsonata.refs.JsonataVariableReference
import io.github.saigyo.aslan.refs.AslVariableDescriptionProvider
import io.github.saigyo.aslan.refs.AslVariableFindUsagesHandlerFactory
import io.github.saigyo.aslan.refs.AslVariableGotoDeclarationHandler
import io.github.saigyo.aslan.refs.AslVariableReadReference
import io.github.saigyo.aslan.refs.AslVariableTarget
import io.github.saigyo.aslan.refs.AslVariableTargets
import io.github.saigyo.aslan.refs.AslVariableUsageTypeProvider
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class VariableNavigationTest : VariableTestCase() {
    /**
     * `reel` is assigned in the state Load, in its first Catcher and in the second Choice rule of Check, and read in
     * JSONata (Check, Done) and in JSONPath strings (Shelve); `shelf` is assigned in Load and read in Shelve. `§`
     * stands for `$`; [marker] in the text is replaced by [replacement].
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
                Next: Shelve
              - Condition: "{% §exists(§reel) %}"
                Assign:
                  reel: "{% §reel & '-checked' %}"
                Next: Shelve
            Default: Shelve
          Shelve:
            Type: Pass
            InputPath: §reel.title
            Parameters:
              label.$: States.Format('{} on {}', §reel, §shelf)
            Next: Done
          Done:
            Type: Pass
            QueryLanguage: JSONata
            Output: "{% §reel %}"
            End: true
    """.trimIndent()

    private fun configure(text: String, marker: String = "", replacement: String = ""): PsiFile =
        yaml(text.replaceFirst(marker, replacement).replace("§", "$"))

    /** The configured file; the fixture hands out the injected file when the caret is in one. */
    private fun hostFile(): PsiFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)

    /** The start offsets of [word] in the host text. */
    private fun offsetsOf(word: String): List<Int> = Regex(Regex.escape(word)).findAll(hostFile().text).map { it.range.first }.toList()

    /** The entry whose key starts at [offset] in the host file. */
    private fun entryAt(offset: Int): YAMLKeyValue = PsiTreeUtil.getParentOfType(hostFile().findElementAt(offset), YAMLKeyValue::class.java)!!

    /** The text of the host file at [range] of [element], and its start offset in the host file. */
    private fun inHost(element: PsiElement, range: TextRange): Pair<String, Int> {
        val inFile = range.shiftRight(element.textRange.startOffset)
        val host = InjectedLanguageManager.getInstance(project).injectedToHost(element, inFile)
        return hostFile().text.substring(host.startOffset, host.endOffset) to host.startOffset
    }

    private fun inHost(usage: UsageInfo): Pair<String, Int> = inHost(usage.element!!, usage.rangeInElement!!)

    fun `test a JSONata read refers to the assignments`() {
        configure(archive, "Output: \"{% §reel", "Output: \"{% §re<caret>el")
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertTrue(reference is AslVariableReadReference)
        assertEquals("\$reel", reference!!.rangeInElement.substring(reference.element.text))
        val entries = offsetsOf("reel:").map { entryAt(it) }
        assertEquals(3, entries.size)
        assertEquals(entries.first(), reference.resolve())
        assertEquals(entries, (reference as AslVariableReadReference).multiResolve(false).map { it.element })
        for (entry in entries) assertTrue(reference.isReferenceTo(entry))
        assertFalse(reference.isReferenceTo(entryAt(offsetsOf("shelf:").single())))
        // find usages and rename started on the read reach the variable through the first assignment
        assertEquals(entries.first(), TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted))
    }

    fun `test a JSONPath read refers to the assignments`() {
        configure(archive, "InputPath: §reel", "InputPath: §re<caret>el")
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertTrue(reference is AslVariableReadReference)
        assertEquals("\$reel", reference!!.rangeInElement.substring(reference.element.text))
        assertEquals(entryAt(offsetsOf("reel:").first()), reference.resolve())

        val format = PsiTreeUtil.getParentOfType(hostFile().findElementAt(offsetsOf("States.Format").single()), YAMLScalar::class.java)!!
        val references = format.references.filterIsInstance<AslVariableReadReference>()
        assertEquals(listOf("\$reel", "\$shelf"), references.map { it.rangeInElement.substring(format.text) })
        assertEquals(listOf(offsetsOf("reel:").first(), offsetsOf("shelf:").single()), references.map { it.resolve()!!.textRange.startOffset })
    }

    fun `test go to declaration names each place`() {
        configure(archive, "Output: \"{% §reel", "Output: \"{% §re<caret>el")
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset).toList()
        assertEquals(offsetsOf("reel:").map { entryAt(it) }, targets.map { it.navigationElement })
        assertEquals(offsetsOf("reel:"), targets.map { it.navigationElement.textOffset })
        val presentations = targets.map { (it as NavigationItem).presentation!! }
        assertEquals(listOf("Load", "Load, first Catcher", "Check, second Choice rule"), presentations.map { it.locationString })
        assertEquals(List(3) { "reel" }, presentations.map { it.presentableText })

        configure(archive, "§shelf)", "§sh<caret>elf)")
        val shelf = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset).toList()
        assertEquals(listOf(entryAt(offsetsOf("shelf:").single())), shelf.map { it.navigationElement })
        assertEquals(listOf("Load"), shelf.map { (it as NavigationItem).presentation!!.locationString })
    }

    fun `test go to declaration on an assignment key gives no targets, in JSON as in YAML`() {
        val handler = AslVariableGotoDeclarationHandler()
        configure(archive, "    shelf: 4", "    sh<caret>elf: 4")
        assertNull(handler.getGotoDeclarationTargets(myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset, myFixture.editor))
        val json = """
            {
              "StartAt": "Load",
              "States": {
                "Load": {"Type": "Pass", "Assign": {"sh<caret>elf": 4}, "Next": "Shelve"},
                "Shelve": {"Type": "Pass", "InputPath": "§shelf", "End": true}
              }
            }
        """.trimIndent().replace("§", "$")
        myFixture.configureByText("test.asl.json", json)
        assertNull(handler.getGotoDeclarationTargets(myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset, myFixture.editor))
        // control: the read still goes to the assignment
        myFixture.configureByText("read.asl.json", json.replace("sh<caret>elf", "shelf").replace("\$shelf", "\$sh<caret>elf"))
        val targets = handler.getGotoDeclarationTargets(myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset, myFixture.editor)
        assertEquals(1, targets?.size)
    }

    fun `test find usages lists assignments and reads`() {
        configure(archive)
        val keys = offsetsOf("reel:")
        val catcher = entryAt(keys[1])
        val usages = myFixture.findUsages(catcher).sortedBy { inHost(it).second }
        val expected = (listOf(keys[0], keys[2]).map { "reel" to it } + offsetsOf("\$reel").map { "\$reel" to it }).sortedBy { it.second }
        assertEquals(6, offsetsOf("\$reel").size)
        assertEquals(expected, usages.map { inHost(it) })

        val detector = ReadWriteAccessDetector.findDetector(catcher)!!
        assertTrue(detector.isDeclarationWriteAccess(catcher))
        val accesses = usages.map { usage -> usage.reference?.let { detector.getReferenceAccess(catcher, it) } ?: detector.getExpressionAccess(usage.element!!) }
        assertEquals(expected.map { if (it.first == "reel") Access.Write else Access.Read }, accesses)
    }

    fun `test find usages presents each assignment by the variable's name and its place`() {
        configure(archive)
        val catcher = entryAt(offsetsOf("reel:")[1])
        val primary = AslVariableFindUsagesHandlerFactory().createFindUsagesHandler(catcher, false)!!.primaryElements.toList()
        assertEquals(offsetsOf("reel:").map { entryAt(it) }, primary.map { it.navigationElement })
        val presentations = primary.map { PsiElement2UsageTargetAdapter(it, true).presentation!! }
        assertEquals(List(3) { "reel" }, presentations.map { it.presentableText })
        assertEquals(listOf("Load", "Load, first Catcher", "Check, second Choice rule"), presentations.map { it.locationString })
        // the assignment entries and the targets are described as the variable, not as a key-value and its value
        for (element in primary + catcher) {
            assertEquals(AslVariableDescriptionProvider.TYPE, UsageViewUtil.getType(element))
            assertEquals("reel", UsageViewUtil.getShortName(element))
            assertEquals("reel", UsageViewUtil.getLongName(element))
            assertEquals("reel", ElementDescriptionUtil.getElementDescription(element, UsageViewNodeTextLocation.INSTANCE))
        }
        // anything else keeps its own description
        val shelve = entryAt(offsetsOf("Shelve:").last())
        assertFalse(UsageViewUtil.getType(shelve) == AslVariableDescriptionProvider.TYPE)
    }

    fun `test find usages still finds everything after a PSI change between the handler and the search`() {
        configure(archive)
        val catcher = entryAt(offsetsOf("reel:")[1])
        val handler = AslVariableFindUsagesHandlerFactory().createFindUsagesHandler(catcher, false)!!
        // any PSI change drops every model, as typing elsewhere does while the search waits
        val other = myFixture.addFileToProject("notes.txt", "notes")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(other)!!
            document.insertString(0, "more ")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val found = ArrayList<UsageInfo>()
        val options = handler.getFindUsagesOptions(null)
        for (element in handler.primaryElements) handler.processElementUsages(element, { found += it; true }, options)
        assertEquals(2 + offsetsOf("\$reel").size, found.size)
    }

    fun `test find usages groups assignments and reads by usage type`() {
        configure(archive)
        val catcher = entryAt(offsetsOf("reel:")[1])
        val targets = arrayOf<UsageTarget>(PsiElement2UsageTargetAdapter(catcher, true))
        val provider = AslVariableUsageTypeProvider()
        val usages = myFixture.findUsages(catcher).sortedBy { inHost(it).second }
        val expected = usages.map { if (inHost(it).first == "reel") AslVariableUsageTypeProvider.ASSIGNMENT else AslVariableUsageTypeProvider.READ }
        assertEquals(expected, usages.map { provider.getUsageType(it.element!!, targets) })
        // the JSONata reads, the JSONPath reads and the other keys are all there
        assertEquals(2, expected.count { it == AslVariableUsageTypeProvider.ASSIGNMENT })
        assertEquals(6, expected.count { it == AslVariableUsageTypeProvider.READ })
        // a search for a state keeps the other providers' types
        val stateTargets = arrayOf<UsageTarget>(PsiElement2UsageTargetAdapter(entryAt(offsetsOf("Shelve:").single()), true))
        assertNull(provider.getUsageType(usages.first().element!!, stateTargets))
    }

    fun `test a go-to target from an older model stands for no variable`() {
        configure(archive)
        val entry = entryAt(offsetsOf("shelf:").single())
        val target = AslVariableTarget(AslVariableTargets.variablesAt(entry).single(), entry, "Load")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(hostFile())!!
            document.insertString(document.textLength, "\n# shelved\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEmpty(AslVariableTargets.variablesAt(target))
        assertNull(AslVariableFindUsagesHandlerFactory().createFindUsagesHandler(target, false))
    }

    fun `test a local JSONata variable is not affected`() {
        configure(archive, "Output: \"{% §reel %}\"", "Output: \"{% (§reel := 1; §re<caret>el) %}\"")
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertTrue(reference is JsonataVariableReference)
        val variables = PsiTreeUtil.findChildrenOfType(myFixture.file, JsonataVariableRef::class.java)
        assertEquals(2, variables.size)
        assertEquals(variables.first(), reference!!.resolve())
        for (variable in variables) assertFalse(variable.references.any { it is AslVariableReadReference })
        // the reads elsewhere still refer to the state variable
        val condition = JsonataPsi.injectedFile(PsiTreeUtil.getParentOfType(hostFile().findElementAt(offsetsOf("\$reel = 'none'").single()), YAMLScalar::class.java)!!)!!
        assertTrue(PsiTreeUtil.findChildOfType(condition, JsonataVariableRef::class.java)!!.reference is AslVariableReadReference)
    }

    fun `test reads of separate variables do not mix`() {
        configure(
            """
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
                          reel: 1
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
            """.trimIndent(),
        )
        val keys = offsetsOf("reel:")
        val reads = offsetsOf("\$reel")
        assertEquals(listOf("\$reel" to reads[0]), myFixture.findUsages(entryAt(keys[0])).map { inHost(it) })
        assertEquals(listOf("\$reel" to reads[1]), myFixture.findUsages(entryAt(keys[1])).map { inHost(it) })
    }
}
