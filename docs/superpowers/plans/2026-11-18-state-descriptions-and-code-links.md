# State descriptions and links from comments to code Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a state's description and comment lines in the details panel, and make file paths with an optional `#Type` in comments links to code, in the editor and in the panel.

**Architecture:** A new package `io.github.saigyo.aslan.links` holds the rule of what is a path (`CodePaths`), its resolution and the jump to a type (`CodeLinks`), the editor references and the inspection. `StateComments` (package `model`) reads a state's description and notes from the file's comment tokens, indexed once per document. The details rows gain two rows of text with links; the panel shows them with a small HTML pane and hands a click to the controller, which opens the file.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2.3 (YAML and JSON plugins), JUnit 3 style tests on `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-11-18-state-descriptions-and-code-links-design.md`

## Global Constraints

- No dependency on the TypeScript or any other language plugin; `plugin.xml` gains no `<depends>`.
- "A word in a comment counts as a path when it holds at least one `/`, its last part has a file extension (letters and digits after a dot), and it is not part of a URL (no `://` in the word)."
- Resolution order: "the folder of the ASL file; the content roots that hold the ASL file, innermost first; the root of its version control repository; the project's base folder." "A path starting with `/` skips the ASL file's folder. A path that resolves to no file, or to a folder, is not a link."
- Warning texts, verbatim: `File not found: <path>` and `Type <name> not found in <file name>`; severity weak warning; one inspection that can be turned off.
- Text from comments is literal in the panel, never markup, and starts where the other values start.
- A click on a link opens the file "in an editor tab of its own, at the type when there is one"; the ASL file stays open; a link that no longer resolves does nothing.
- Never read, open or use `verarbeitung*.asl.yaml`; every test file is made up. Do not touch `src/test/testData/fixtures/film-archive-intake.asl.yaml` (it has the commissioner's local changes). Stage files by explicit path only.
- Every commit ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01CKu1kuBh3LXs1XXzTNAyDh
  ```
- Run Gradle with `--no-watch-fs`.

## Review Focus

- A `#` inside a quoted string or a block scalar is not a comment: it is neither a description nor a note, and gets no reference (test in Task 4).
- A path to a binary file (an image, a zip) with a `#Type`: no search in its bytes; the link opens the file and the type gets the "not found" warning (test in Task 2).
- A comment in a YAML file that is not an ASL file gets no reference and no warning (test in Task 3).
- A half-typed state (`Scan:` with no value, `Scan: oops`) or a state reached through a YAML merge key: rows are made without an exception (test in Task 4).
- Renaming or moving the target file does not fail because of a reference in a comment, and leaves the comment as it is (test in Task 3).

---

### Task 1: What counts as a path

**Files:**
- Create: `src/main/kotlin/io/github/saigyo/aslan/links/CodePaths.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/links/CodePathsTest.kt` (plain `junit.framework.TestCase`, like `ViewportTest`)

**Interfaces:**
- Produces: `data class CodePath(val start: Int, val end: Int, val path: String, val type: String?, val typeStart: Int)` — `start`/`end` cover the path and its `#Type` in the text given, `path` without the type, `typeStart` the offset of the type's first character (or -1); `object CodePaths { fun find(text: String): List<CodePath> }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class CodePathsTest : TestCase() {
    private fun paths(text: String) = CodePaths.find(text).map { it.path to it.type }

    fun `test a relative path is found`() =
        assertEquals(listOf("lambdas/src/intake/handler.ts" to null), paths("Function: lambdas/src/intake/handler.ts"))
    fun `test a type follows a hash`() =
        assertEquals(listOf("lambdas/src/shared/contracts.ts" to "IntakeInput"), paths("Input: lambdas/src/shared/contracts.ts#IntakeInput"))
    fun `test dots and parents at the start belong to the path`() =
        assertEquals(listOf("./a/b.json" to null, "../schemas/order.json" to null), paths("./a/b.json and ../schemas/order.json"))
    fun `test wrapping and trailing punctuation are not part of it`() =
        assertEquals(listOf("a/b.ts" to null, "c/d.yaml" to null, "e/f.ts" to "G", "h/i.ts" to null),
            paths("`a/b.ts`, see (c/d.yaml). Also \"e/f.ts#G\"; h/i.ts:"))
    fun `test a URL, a version and a word with a slash are no paths`() =
        assertEquals(emptyList<Pair<String, String?>>(), paths("https://example.com/a.json v1.2 and/or x/y"))
    fun `test a type must be an identifier`() =
        assertEquals(listOf("a/b.ts" to null), paths("a/b.ts#1x"))
    fun `test a type may hold dollar and underscore`() =
        assertEquals(listOf("a/b.ts" to "\$Intake_Input2"), paths("a/b.ts#\$Intake_Input2"))
    fun `test the offsets cover the path and its type`() {
        val text = "see a/b.ts#T."
        val p = CodePaths.find(text).single()
        assertEquals("a/b.ts#T", text.substring(p.start, p.end))
        assertEquals("T", text.substring(p.typeStart, p.end))
    }
    fun `test an absolute path is found`() =
        assertEquals(listOf("/lambdas/a.ts" to null), paths("at /lambdas/a.ts"))
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-watch-fs test --tests '*CodePathsTest*'`
Expected: FAIL, `CodePaths` unresolved.

- [ ] **Step 3: Implement `CodePaths.find(text: String): List<CodePath>`**

Split the text into words at whitespace; strip leading `` ` ``, `'`, `"`, `(`, `[`, `<` and trailing `` ` ``, `'`, `"`, `)`, `]`, `>`, `,`, `.`, `:`, `;` (repeatedly); drop a word holding `://`; split off `#` and an identifier `[A-Za-z_$][A-Za-z0-9_$]*` at the end (a `#` followed by no identifier ends the path before the `#` and gives no type); keep the rest when it holds a `/` and its last segment matches `.+\.[A-Za-z0-9]+`. Offsets are those in `text`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-watch-fs test --tests '*CodePathsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/saigyo/aslan/links/CodePaths.kt src/test/kotlin/io/github/saigyo/aslan/links/CodePathsTest.kt
git commit -m "feat: recognise file paths with an optional type in comment text"
```

### Task 2: Resolving a path and finding a type

**Files:**
- Create: `src/main/kotlin/io/github/saigyo/aslan/links/CodeLinks.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/links/CodeLinksTest.kt` (extends `AslTestCase`)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces, in `object CodeLinks` (all called in a read action, except `open`):
  - `fun bases(project: Project, aslFile: VirtualFile, absolute: Boolean): List<VirtualFile>` — the folders of the spec's order, without duplicates; `absolute` leaves out the ASL file's folder.
  - `fun resolve(project: Project, aslFile: VirtualFile, path: String): VirtualFile?` — the first base that holds a file (not a folder) at `path` (a leading `/` stripped for the lookup).
  - `sealed interface TypeTarget { val offset: Int; class Declared(val element: PsiElement, override val offset: Int) : TypeTarget; class Text(override val offset: Int) : TypeTarget }`
  - `fun findType(file: PsiFile, name: String): TypeTarget?` — the first `PsiNamedElement` in document order, other than the file itself, whose `name == name` (offset: its name identifier's start when it is a `PsiNameIdentifierOwner`, else its `textOffset`); else the first whole-word occurrence in the text; null when the name does not occur or the file is binary (`file.fileType.isBinary` or a `PsiBinaryFile`).
  - `fun open(project: Project, aslFile: VirtualFile, path: String, type: String?): Boolean` — UI thread; resolves in a read action, then `OpenFileDescriptor(project, target, offset).navigate(true)` with the type's offset, or 0; false and nothing opened when the path no longer resolves.

- [ ] **Step 1: Write the failing tests**

Use `myFixture.addFileToProject(...)` for targets and the ASL file (`infra/sm.asl.yaml`, `lambdas/src/a.ts` as plain text, `lambdas/src/contracts.json`, `infra/local/b.txt`, `lambdas/img.png` with bytes).

```kotlin
fun `test a path is resolved against the ASL file's folder first`()      // infra/local/b.txt from "local/b.txt"
fun `test a path is resolved against the content root next`()             // "lambdas/src/a.ts" from infra/sm.asl.yaml
fun `test a path starting with a slash skips the ASL file's folder`()     // "/infra/local/b.txt" resolves, "/local/b.txt" does not
fun `test a folder or a missing file does not resolve`()                  // "lambdas/src" -> null, "lambdas/src/none.ts" -> null
fun `test a type declared in a file the IDE reads is found as a declaration`()
    // contracts.json {"IntakeInput": {}, "Other": {}}: Declared, offset == text.indexOf("\"IntakeInput\"") (+1 if the name identifier excludes the quote: assert the text at offset starts with the name or the quoted name)
fun `test a type in a plain text file is found as text`()                 // a.ts as plain text "interface IntakeInput {}": Text(offset of the whole word, not of "IntakeInputs")
fun `test a missing type and a binary file give null`()                   // name absent -> null; img.png with "IntakeInput" bytes -> null
fun `test open goes to the type in a tab of its own`()
    // open(project, asl, "lambdas/src/a.ts", "IntakeInput") == true; FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile.path ends with "a.ts", caret at the type's offset; the ASL file is still among FileEditorManager.openFiles
fun `test open does nothing for a path that no longer resolves`()         // returns false, open files unchanged
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-watch-fs test --tests '*CodeLinksTest*'`
Expected: FAIL, `CodeLinks` unresolved.

- [ ] **Step 3: Implement `CodeLinks`**

Content roots: `ProjectFileIndex.getContentRootForFile` gives the innermost; walk up from it through its parents, keeping each folder for which `ProjectFileIndex.isInContent` is true and that is a content root (`ProjectRootManager.getInstance(project).contentRoots` contains it). Version control root: `ProjectLevelVcsManager.getInstance(project).getVcsRootFor(aslFile)` (null in tests is fine). Base folder: `project.guessProjectDir()`. Look up with `base.findFileByRelativePath(path.removePrefix("/"))`. Whole-word search: the name with no letter, digit, `_` or `$` on either side.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-watch-fs test --tests '*CodeLinksTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/saigyo/aslan/links/CodeLinks.kt src/test/kotlin/io/github/saigyo/aslan/links/CodeLinksTest.kt
git commit -m "feat: resolve a path from a comment and find a type in its file"
```

### Task 3: Links in the editor

**Files:**
- Create: `src/main/kotlin/io/github/saigyo/aslan/links/CodeLinkReferences.kt` (contributor, the two reference classes, the text target)
- Modify: `src/main/resources/META-INF/plugin.xml` (two `psi.referenceContributor` lines, `language="yaml"` and `language="JSON"`)
- Test: `src/test/kotlin/io/github/saigyo/aslan/links/CodeLinkReferencesTest.kt` (extends `AslTestCase`)

**Interfaces:**
- Consumes: `CodePaths.find`, `CodeLinks.resolve`, `CodeLinks.findType`, `CodeLinks.TypeTarget`.
- Produces: `object CodeLinkSites { fun texts(element: PsiElement): List<Pair<TextRange, String>> }` — for a `PsiComment` in an ASL YAML file, or the string value of an entry with key `Comment` in an ASL file (YAML scalar or JSON string literal; **plan decision:** any `Comment` field, not only a state's, since the rule is the same and the machine's own `Comment` deserves links too), the ranges within the element whose text is searched for paths, with that text; empty for anything else. Task 5 uses the same sites for the inspection. `class CodeFileReference` and `class CodeTypeReference`, both soft (`PsiReferenceBase(element, range, true)`).

- [ ] **Step 1: Write the failing tests**

Targets added with `myFixture.addFileToProject`: `lambdas/src/handler.ts` (plain text), `lambdas/src/contracts.json` (`{"IntakeInput": {}}`).

```kotlin
fun `test a path in a comment resolves to its file`()
    // "# Function: lambdas/src/hand<caret>ler.ts" above a state; myFixture.getReferenceAtCaretPositionWithAssertion().resolve() is the PsiFile handler.ts
fun `test a type after the path resolves to its declaration`()
    // "# Input: lambdas/src/contracts.json#Intake<caret>Input": resolve() text is the JSON property IntakeInput
fun `test a type found only as text resolves to a place in the file`()
    // "# lambdas/src/handler.ts#Hand<caret>ler" where handler.ts holds "export interface Handler {}": resolve() is not null; navigating it puts the caret of the opened editor at the word
fun `test a path in a Comment field resolves, in YAML and JSON`()
fun `test a word that is no path and a missing file carry no resolving reference`()   // "# and/or", "# lambdas/none.ts": no reference, or one whose resolve() is null
fun `test a hash in a quoted string is no comment`()                                  // Comment: 'x # lambdas/src/handler.ts' in a field other than Comment, e.g. Parameters value: no reference
fun `test a comment in a YAML file that is no ASL file carries no reference`()        // name "other.yaml"
fun `test renaming the target file leaves the comment as it is`()
    // myFixture.renameElement(handler.ts PsiFile, "main.ts") does not throw; the ASL text still holds "lambdas/src/handler.ts"
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-watch-fs test --tests '*CodeLinkReferencesTest*'`
Expected: FAIL (no references).

- [ ] **Step 3: Implement the contributor and the references**

Register providers on `PlatformPatterns.psiElement(PsiComment::class.java)`, `YAMLScalar` and `JsonStringLiteral`; each returns references only for `CodeLinkSites.texts(element)`. Per `CodePath`: a `CodeFileReference` over the path's range (resolves to the `PsiFile` of `CodeLinks.resolve`, or null) and, with a type and a resolved file, a `CodeTypeReference` over the type's range (resolves to `Declared.element`, or for `Text` to a `FakePsiElement` subclass whose `navigate` opens `OpenFileDescriptor(project, file, offset)` and whose parent is the target file; null when `findType` is null). Both `handleElementRename` and `bindToElement` return the element unchanged. **Plan decision:** if a YAML `PsiComment` turns out not to ask the reference providers (its `getReferences()` stays empty although the provider is registered), implement a `GotoDeclarationHandler` for comments instead and record a ruling; the tests above then assert through `GotoDeclarationAction.findAllTargetElements` instead of `resolve()`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-watch-fs test --tests '*CodeLinkReferencesTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/saigyo/aslan/links/CodeLinkReferences.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/io/github/saigyo/aslan/links/CodeLinkReferencesTest.kt
git commit -m "feat: paths in comments link to their file and type in the editor"
```

### Task 4: A state's description and notes

**Files:**
- Create: `src/main/kotlin/io/github/saigyo/aslan/model/StateComments.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/model/StateCommentsTest.kt` (extends `AslTestCase`)

**Interfaces:**
- Consumes: `AslDocument`, `AslState` (`state.entry.keyPsi`, `state.obj`, `state.children`, `state.machine.states`).
- Produces: `class StateComments private constructor(...) { fun of(state: AslState): Comments; companion object { fun of(doc: AslDocument): StateComments } }` and `data class Comments(val description: List<String>, val notes: List<String>)` — `description` is paragraphs (the `Comment` field's text first, as one paragraph, when it is a string), `notes` one entry per comment line. The index reads the file's comment tokens once (`PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java)`), so a large file is not scanned once per state.

- [ ] **Step 1: Write the failing tests**

Each test parses a small made-up definition with `yaml(...)`/`json(...)`, builds `AslModel` as `DiagramDetailsTest` does, and asserts `StateComments.of(doc).of(state)`.

```kotlin
fun `test the block above the key is the description, joined into a paragraph`()
    // "  # Scans the reels\n  # of one delivery.\n  Scan:" -> description ["Scans the reels of one delivery."]
fun `test a lone hash line starts a new paragraph`()          // ["First.", "Second."]
fun `test a blank line ends the block`()                       // only the lines after the blank line count
fun `test a line in another column is not part of the block`() // "    # deeper" directly above "  Scan:" is a note of the state before, not Scan's description
fun `test the Comment field comes first`()                     // Comment: Short. + block above -> ["Short.", "Block text."]
fun `test comment lines inside the state are notes, as written`()
    // "    # Function: lambdas/src/handler.ts" and "    # Output:  x" -> notes ["Function: lambdas/src/handler.ts", "Output:  x"]
fun `test a comment after the last field is a note`()
fun `test a section heading between states belongs to no state`()
    // "  Scan:\n    Type: Pass\n    Next: Done\n\n  # ---- Phase 2 ----\n\n  Done:" -> heading neither in Scan's notes nor in Done's description
fun `test the next state's description is not a note`()
fun `test a comment at the end of a line is a note`()          // "    Type: Task  # calls the scanner" -> note "calls the scanner"
fun `test notes of a nested state stay with it`()
    // a Parallel with a branch state that has a description and a note: neither is in the Parallel's notes; the nested state has both
fun `test a hash in a quoted string or a block scalar is no comment`()
fun `test a state in JSON has the Comment field as its description and no notes`()
fun `test a half-typed state has comments and no exception`()  // "  Scan:" with no value and "  Scan: oops", each with a block above
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-watch-fs test --tests '*StateCommentsTest*'`
Expected: FAIL, `StateComments` unresolved.

- [ ] **Step 3: Implement `StateComments`**

Work on lines of the document text and the comment tokens. A comment is standalone when only whitespace precedes it on its line. For a state with key line `k` and key column `c`: the description block is the standalone comments in column `c` on lines `k-1, k-2, …` with no other line between; the notes are the comments on lines `k … b-1` in a column greater than `c` (an end-of-line comment on line `k` included), where `b` is the first line after `k` that is neither blank nor comment-only and starts in a column `<= c` (or the end of the file); left out are comments that belong to a nested state (as its description or note) and comments inside a nested machine's `States` entry. Strip `#` and one following space; trim the end of each line. Join a block's lines with single spaces into paragraphs, split at lines that are empty after stripping. For JSON files the notes and the block are empty.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-watch-fs test --tests '*StateCommentsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/saigyo/aslan/model/StateComments.kt src/test/kotlin/io/github/saigyo/aslan/model/StateCommentsTest.kt
git commit -m "feat: read a state's description and notes from its comments"
```

### Task 5: The warning for a path or type that is not found

**Files:**
- Create: `src/main/kotlin/io/github/saigyo/aslan/links/AslCodeLinkInspection.kt`
- Create: `src/main/resources/inspectionDescriptions/AslCodeLink.html`
- Modify: `src/main/resources/META-INF/plugin.xml` (`<localInspection shortName="AslCodeLink" implementationClass="io.github.saigyo.aslan.links.AslCodeLinkInspection" displayName="Path in a comment names no file or type" groupName="Amazon States Language" enabledByDefault="true" level="WEAK WARNING"/>`)
- Test: `src/test/kotlin/io/github/saigyo/aslan/links/AslCodeLinkInspectionTest.kt` (extends `AslTestCase`)

**Interfaces:**
- Consumes: `CodeLinkSites.texts`, `CodePaths.find`, `CodeLinks.resolve`, `CodeLinks.findType`.
- Produces: `class AslCodeLinkInspection : LocalInspectionTool` (available only for ASL files). It is not an `AslProblemInspection` and is not added to `AslInspections.all()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
fun `test a missing file is a weak warning on the path`()
    // myFixture.enableInspections(AslCodeLinkInspection()); "# Function: <weak_warning descr=\"File not found: lambdas/none.ts\">lambdas/none.ts</weak_warning>"; myFixture.checkHighlighting(false, false, true)
fun `test a missing type is a weak warning on the type`()
    // "lambdas/src/contracts.json#<weak_warning descr=\"Type Missing not found in contracts.json\">Missing</weak_warning>"
fun `test a type found as text is no warning`()
fun `test a binary file with a type warns about the type`()
fun `test words that are no paths are never marked`()  // "# https://example.com/a.json v1.2 and/or"
fun `test the inspection does nothing in a YAML file that is no ASL file`()
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-watch-fs test --tests '*AslCodeLinkInspectionTest*'`
Expected: FAIL, class unresolved.

- [ ] **Step 3: Implement the inspection and its description**

`buildVisitor` visits every element for which `CodeLinkSites.texts` is not empty and registers `ProblemHighlightType.WEAK_WARNING` on the path's or type's range. The description page says what it reports and that only words that look like paths are checked.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-watch-fs test --tests '*AslCodeLinkInspectionTest*' --tests '*InspectionsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/saigyo/aslan/links/AslCodeLinkInspection.kt src/main/resources/inspectionDescriptions/AslCodeLink.html src/main/resources/META-INF/plugin.xml src/test/kotlin/io/github/saigyo/aslan/links/AslCodeLinkInspectionTest.kt
git commit -m "feat: a weak warning for a path in a comment that names no file or type"
```

### Task 6: Description and notes in the details rows

**Files:**
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramModel.kt` (`DetailItem`, new `CodeLink`)
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramDetails.kt` (`rows`, `Variables` gains the comments index or a sibling parameter)
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramDetailsTest.kt`

**Interfaces:**
- Consumes: `StateComments`, `CodePaths.find`, `CodeLinks.resolve`.
- Produces: `class CodeLink(val start: Int, val end: Int, val path: String, val type: String?)` (offsets within the item's text, covering path and type); `DetailItem(..., val links: List<CodeLink> = emptyList())`; `DetailItem.Kind.TEXT` — a text the panel wraps, with [links]; rows `Description` (one `TEXT` item per paragraph) and `Notes` (one `TEXT` item per line), placed before `Type`, left out when empty. A path is a link only when `CodeLinks.resolve` finds its file when the rows are made (**plan decision**, the spec leaves it open: resolving then is cheap, keeps unresolvable words plain, and the click resolves again).

- [ ] **Step 1: Write the failing tests**

```kotlin
fun `test a state's rows start with its description and notes`()
    // labels of rows(...) start with ["Description", "Notes", "Type"]; texts as in Task 4
fun `test a resolvable path in a note is a link with its type`()
    // with lambdas/src/contracts.json added: item.links.single() has path "lambdas/src/contracts.json", type "IntakeInput", and text.substring(start, end) == "lambdas/src/contracts.json#IntakeInput"
fun `test a path that resolves to nothing is no link`()
fun `test a state without comments has neither row`()
fun `test the rows of a JSON state show its Comment field`()
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-watch-fs test --tests '*DiagramDetailsTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`DiagramDetails.rows(doc, state)` and `DiagramBuilder.build` build the `StateComments` index once per document, as they do `Variables`; the ASL file is `doc.file.virtualFile` (or `originalFile.virtualFile`), the project `doc.file.project`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-watch-fs test --tests '*DiagramDetailsTest*' --tests '*DiagramBuilderTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramModel.kt src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramDetails.kt src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramDetailsTest.kt
git commit -m "feat: a state's description and notes in its details rows"
```

### Task 7: The panel shows the text with links, and a click opens the code

**Files:**
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramDetailsPanel.kt`
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramPane.kt` (`DiagramView.onOpenCode`, wiring)
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramController.kt` (`view.onOpenCode`)
- Modify: the two `Recorder` classes in `DiagramControllerTest.kt` and `DiagramSelectionTest.kt` (new property)
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramPaneTest.kt`, `DiagramSelectionTest.kt`

**Interfaces:**
- Consumes: `DetailItem.Kind.TEXT`, `DetailItem.links`, `CodeLink`, `CodeLinks.open`.
- Produces: `DiagramView.onOpenCode: (CodeLink) -> Unit` ("A link to code in the details panel"); `DiagramDetailsPanel(onNavigate: (Int) -> Unit, onOpenCode: (CodeLink) -> Unit)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// DiagramPaneTest
fun `test a description and notes show as wrapping text with their links`()
    // show a model whose rows hold a Description and a Notes row; the panel holds one text component per item; its visible text equals the item's text; it has exactly as many hyperlinks as item.links
fun `test text from a comment is literal`()            // "<b>bold</b> & <i>" shows as written
fun `test a click on a link reports it`()               // fire the component's hyperlink event for the first link: onOpenCode got that CodeLink
fun `test the text starts where the other values start`() // as the existing test for the wrapped condition
// DiagramSelectionTest
fun `test a link to code opens the file at its type`()
    // file with "# Input: lambdas/src/contracts.json#IntakeInput" inside a state, target added; view.onOpenCode(link) opens contracts.json with the caret at IntakeInput, the ASL file still open
fun `test a link to code that no longer resolves does nothing`()
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-watch-fs test --tests '*DiagramPaneTest*' --tests '*DiagramSelectionTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

A `LinkedText` component in `DiagramDetailsPanel.kt`: a non-editable, non-opaque `JEditorPane` built with `HTMLEditorKitBuilder().withWordWrapViewFactory()`, its HTML made from the item's text with every character escaped (`StringUtil.escapeXmlEntities`) and each link as `<a href="n">…</a>`, the panel's label font and colours, `border = JBUI.Borders.emptyLeft(ITEM_GAP)` like `WrappedText`, a `HyperlinkListener` that calls `onOpenCode(item.links[n])`. A row whose items are all `TEXT` shows them stacked vertically. Keep the existing width logic (`least()`) working: a `LinkedText` counts as wrapping, like `WrappedText`. The controller sets `view.onOpenCode = { link -> if (!disposed) CodeLinks.open(project, file, link.path, link.type) }`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-watch-fs test --tests '*DiagramPaneTest*' --tests '*DiagramSelectionTest*' --tests '*DiagramControllerTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramDetailsPanel.kt src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramPane.kt src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramController.kt src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramPaneTest.kt src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramSelectionTest.kt src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramControllerTest.kt
git commit -m "feat: the details panel shows descriptions and notes, and opens linked code"
```

### Task 8: Documents and the whole check

**Files:**
- Modify: `docs/superpowers/specs/2026-11-13-aslan-design.md` (section 6.5, details panel: the two new rows and links to code; a short paragraph on links in comments)
- Modify: `README.md` (details panel and links in comments, with the path rule and `#Type`)
- Create: `.superpowers/ledgers/2026-11-18-state-descriptions-and-code-links.md` (moved from the plan's workspace at the end)

- [ ] **Step 1: Update the documents** to state what was built, in the spec's words, with the calls made while building marked "(my call, made while building it)".

- [ ] **Step 2: Run the whole check and the private check**

Run: `./gradlew --no-watch-fs check` and `./gradlew --no-watch-fs test --tests '*PrivateFilesTest*' -Paslan.privateFixtures="$PWD"`
Expected: BUILD SUCCESSFUL for both; the test count is 993 plus the new tests.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-11-13-aslan-design.md README.md
git commit -m "docs: descriptions, notes and links to code"
```
