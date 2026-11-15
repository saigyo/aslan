# Aslan Stage 4: Diagram and Release 1.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An ASL file opens with a live diagram of its state machine beside the editor, sharing its selection with the editor and the structure view; and the plugin is ready to be listed as version 1.0, with a README and a Marketplace description that introduce it, explain its use and show it in screenshots.

**Architecture:** The diagram is native: a Swing panel painted by the plugin, laid out by the Java version of Eclipse ELK. Four steps, each testable without a window: a builder turns the document model into a `DiagramModel` (states, transitions, labels, source offsets, the facts of the details panel); a layouter gives every state and path a place, in a loose or a compact layout; a scene turns the layout and the selection into the items to paint and answers what lies at a point; a painter draws the scene. A controller redraws after typing stops and keeps caret and diagram selection in step. Part B rewrites the README and the plugin description around screenshots taken in a sandbox IDE.

**Tech Stack:** as in stage 3 (Kotlin 2.4, Java 25, IntelliJ Platform Gradle Plugin 2.20.1, IntelliJ Platform 2026.2, JUnit 4 platform tests). New: Eclipse ELK 0.12.1 (`org.eclipse.elk:org.eclipse.elk.alg.layered`, EPL-2.0) with the Eclipse EMF 2.12.0 libraries it needs (EPL-1.0), bundled; Guava from the IDE. No browser, no JavaScript.

**Spec:** `docs/superpowers/specs/2026-11-13-aslan-design.md` as revised on 2026-11-15, sections 4 (diagram layout), 5 (diagram, dependencies), 6.5, 8, 9 and 10, and the mockup `2026-11-13-aslan-design-mockup.html` beside it. Where the mockup and the text disagree, the text wins. The research behind the native route, with the spike's results and the ELK calls that matter, is `docs/superpowers/research/2026-11-15-diagram-rendering.md`; every implementer of Part A reads its sections 5 to 7.

**Branch:** `stage4/diagram`, created from `origin/main` after pull request #4 is merged. Its first commits are the revised design with the research document and its two pictures (`docs/superpowers/research/2026-11-15-diagram-rendering.md`, `docs/superpowers/research/images/layout-loose.png` and `layout-compact.png`; `docs: draw the diagram natively`) and this plan (`docs: plan stage 4, diagram and release 1.0`). The spike branch `spike/elk-java` is not merged; its code may be read, not copied wholesale.

## Decisions made in this plan

These are choices of this plan, not of the commissioner. Each can be overruled before execution starts. Task 14 writes the ones that stand into the design document.

1. **Every ASL file opens in the split editor**, first in split mode. The three modes are the platform's own (`TextEditorWithPreview`): its buttons sit at the top right of the editor, and it remembers the mode chosen last.
2. **Model, layout, scene, painting** are four separate steps (see Architecture). What is painted and what a click hits are decided in the scene, which is plain data, so both are tested without a window.
3. **The toolbar, the legend and the details panel are IDE components** inside the diagram pane: an action toolbar above the diagram, a legend strip and a collapsible details panel below it.
4. **The layout never runs on the UI thread, and never while the read lock is held.** The model is built in a read action; the layout follows on a background thread of its own; the result goes to the UI thread. A result that is older than the latest request is dropped.
5. **One selection**, held by the controller: a state, a path, or nothing. Moving the caret selects the state the caret is in. A click in the diagram selects that state or path and moves the caret, without the caret move changing the selection back. A click on the diagram's background selects nothing and leaves the caret where it is. A selected path stays selected until the next caret move or edit; switching the layout or collapsing a box keeps it.
6. **Out of date.** The last good diagram stays, marked, while the file has a syntax error, its top level is not a mapping, or `States` is not a mapping. Problems the checks report (a missing target, an unknown type) do not stop the diagram.
7. **What is remembered:** the editor mode (by the platform); the catch switch and whether the details panel is collapsed (application-wide); the layout chosen with the switch (for each file, in the editor state the IDE keeps for a file). Which boxes are collapsed is kept only while the file is open. The IDE keeps a file's editor state only as long as the file is in its editor history (the recent files); for a file that has dropped out of it, the size rule decides again.
8. **A transition without a resolvable target is not drawn.** The details panel still names it.
9. **The two layouts start from the options of the spike** (research document, section 7): the loose layout from ELK's defaults with labels of up to 30 characters; the compact layout from network-simplex node placement, the file's order kept, paths into one state sharing a line, and labels of up to 20 characters. The implementer of Task 5 may change the options; the tests of Task 5 decide.
10. **Texts from the file are never read as HTML.** Swing renders a text that starts with `<html>` as markup; names, labels and hover texts are always shown literally.
11. **The Marketplace description carries three screenshots**, as links to the images in the repository on GitHub. They work once the repository is public and this branch is on `main`; the commissioner makes the repository public before publication. Six full-window screenshots are prepared in addition for the Media section of the listing, which JetBrains recommends for screenshots.
12. **Screenshots come from the sandbox IDE on a demo project** that holds copies of the synthetic definitions and nothing else, in the default theme, with the Project tool window hidden.
13. **Version 1.0.0**, with the commissioner's logo and file icon, and change notes. Signing and uploading stay outside this project.
14. **The file icon comes from an icon provider, not from a file type.** ASL files keep their YAML and JSON file types (design, section 5), so the icon is given by a `fileIconProvider` for files the plugin recognises.
15. **Small additions to section 6.5.** A Choice rule whose condition the label cannot say in one line (`And`, `Or`, `Not`, a rule being typed) is labelled "Rule n", and hovering shows the rule as the file has it. Hovering a catch path names its errors. In the details panel every item that has a place in the file is a link, the retriers included. A state of unknown or missing type is drawn in the grey of Pass.

## Open points for the commissioner

- **Downloads.** Gradle fetches seven jars from Maven Central (ELK 0.12.1: `alg.layered`, `alg.common`, `core`, `graph`; EMF 2.12.0: `ecore`, `common`, `ecore.xmi`; about 5.5 MB together, already in the local Gradle cache since the spike). Task 1 also fetches the plain texts of the two licences, a few kilobytes each: `https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt` and `https://www.eclipse.org/org/documents/epl-1.0/EPL-1.0.txt` (both answered with plain text on 2026-11-15). Approving this plan approves these downloads.

## Global Constraints

- Everything in the Global Constraints of the plans of stages 1 to 3 still holds: plugin ID and base package `io.github.saigyo.aslan`, minimum IDE version 2026.2, Java 25, no network requests, no JSON Schema, no Symbol API, the invented film archive domain for all committed test data, and the private definition that is never committed, copied from, opened or named.
- **Read-only.** Nothing in the diagram or the details panel changes the file.
- **No false error** still outranks everything: this stage adds no check.
- The diagram reads `AslDocument` and `AslVariables`. It does not walk YAML or JSON trees itself, with three exceptions: `DiagramBuilder.readable` looks for an error element in the file (the host file only, not injected JSONata), a retrier written as an alias is named by the text of its `usePsi`, and the hover text of a compound Choice rule is the text of the rule's `psi`.
- The builder, the layouter, the scene and the controller never throw on a half-typed file (except to cancel); they run while the user types.
- Decisions 4 and 10 hold everywhere: no layout on the UI thread or under the read lock, and no text from the file read as HTML.
- No API that the plugin verifier reports as internal, deprecated or experimental. `Alarm` and `MergingUpdateQueue` are marked obsolete in 2026.2 and are not used.
- The ELK and EMF jars are bundled unmodified, in exactly the versions named. Guava is not bundled.
- Tests assert identifiers, structure, offsets, places and the labels this plan fixes, not the wording of other texts.
- **Screenshots and the sandbox IDE.** The sandbox IDE is opened only on the demo project (`build/demo/film-archive`), never on the repository. No screenshot shows a path, a user name or anything outside the IDE window. Computer use runs in a dedicated subagent that reports what it sees, not a verdict.
- Stage files by explicit path. Never `git add -A` or `git add .`.
- Every commit message ends with `Co-Authored-By: <the Claude model that made the commit> <noreply@anthropic.com>` and the session line.
- Pushing, opening a pull request and merging are the commissioner's decisions.
- After each task: `./gradlew check` passes, and `./gradlew test -Paslan.privateFixtures="$PWD"` reports the private definition as checked with no errors.

## IDE tools and escapes

The sections "IDE tools" and "Escapes" of the stage 3 plan (`2026-11-14-aslan-stage3-variables.md`) apply unchanged. In Kotlin test strings, `$` in YAML is written as `§` and replaced, as the existing tests do.

## Verified facts

Checked on 2026-11-15 in the jars of IntelliJ IDEA 2026.2.3 and in the spike. The notes on the platform, with the `javap` output, are in `.superpowers/research/2026-11-15-platform-apis-2026.2.md` (not committed); the facts on ELK are in the research document.

- `TextEditorWithPreviewProvider(previewProvider: FileEditorProvider)` is the base class the Markdown plugin uses. It takes the platform's text editor itself, accepts a file when the preview provider does, hides the default editor, and calls `createSplitEditor(TextEditor, FileEditor)`, which a subclass overrides. Its `readState` and `writeState` store the state of both editors (`first_editor`, `second_editor`). Not yet confirmed: that the preview's part goes through the preview provider's own `readState` and `writeState`; a test of Task 1 confirms it.
- `TextEditorWithPreview(editor, preview, name, defaultLayout, isVerticalSplit)` with `Layout.SHOW_EDITOR`, `SHOW_EDITOR_AND_PREVIEW`, `SHOW_PREVIEW`. It stores the mode in `PropertiesComponent` under `name + "Layout"`.
- `ReadAction.nonBlocking(Callable)` with `coalesceBy`, `expireWith`, `finishOnUiThread`, `submit`; `EditorColorsManager.TOPIC`; `CaretModel.addCaretListener(listener, disposable)`; `PropertiesComponent.getValue` and `setValue`. None carries an internal, experimental or deprecated mark.
- ELK 0.12.1 lays out the large synthetic definition (62 states, 112 transitions) in 20 to 200 ms, runs inside the IDE under the plugin's class loader, and passes the plugin verifier for 2026.2.3 and 2026.3 with Guava taken from the IDE (33.5.0 and 33.7.1). The plugin registers the algorithm itself (`LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())`); no service lookup is involved.
- Left out of the layout, catch paths no longer hold the states they lead to in place. So every transition that is drawn in any setting is always part of the layout.

## Review Focus

Inputs the spec implies but does not spell out. Each has a test in the task named.

1. **Half-typed files.** A state without `Type`, a state that is not a mapping, `States` written as a placeholder, a syntax error: no exception, and the last good diagram stays, marked out of date. Tasks 3 and 8.
2. **Transitions that do not behave.** A target that does not exist or is a placeholder, a state whose `Next` is itself, a Choice rule and the `Default` leading to the same state, two states with the same name: unique IDs, no crash, and a layout. Tasks 3 and 5.
3. **Shared content.** A `Catch` list or a retrier used by several states through a YAML alias: the path of each state leads to that state's own line, not to the anchor, and the details panel names a retrier by its alias. Tasks 3 and 4.
4. **Names that Swing would read as markup, and names that do not fit.** A state named `<html><b>Scan`, a condition that starts with `<html>`, a name of 80 characters: shown literally, cut to the node with "…", and whole on hover and in the details panel. Tasks 6 and 7.
5. **Typing in a large file.** With the 1,257-line definition open, no layout runs on the UI thread or under the read lock, a burst of edits leads to one layout, and an outdated result is dropped. Tasks 8 and 10.

## File Structure

Kotlin paths are under `src/main/kotlin/io/github/saigyo/aslan/`, resources under `src/main/resources/`.

| File | Responsibility |
|---|---|
| `diagram/AslSplitEditorProvider.kt` | the split editor of an ASL file, the provider of its preview, the preview's editor state |
| `diagram/DiagramPreviewEditor.kt` | the preview pane as a file editor: owns the pane and the controller |
| `diagram/DiagramLabels.kt` | the resource label of a Task and the label of a Choice rule |
| `diagram/DiagramModel.kt` | the model classes and `DiagramBuilder` |
| `diagram/DiagramDetails.kt` | the rows of the details panel for a state |
| `diagram/DiagramLayout.kt` | `LayoutMode`, the layout classes and `DiagramLayouter` (the only file that touches ELK) |
| `diagram/DiagramScene.kt` | what is painted for a layout and a selection, and what lies at a point |
| `diagram/DiagramPainter.kt` | paints a scene; `DiagramColors` |
| `diagram/Viewport.kt` | zoom, pan, fit and reveal as arithmetic |
| `diagram/DiagramPane.kt` | `DiagramView` and its implementation: toolbar, diagram panel, legend, details panel; `DiagramText` |
| `diagram/DiagramPanel.kt` | the Swing component that shows the scene and takes mouse input |
| `diagram/DiagramDetailsPanel.kt` | the details of the selected state, with links |
| `diagram/DiagramController.kt` | redraws after edits, follows the caret, handles what the pane reports |
| `diagram/DiagramPrefs.kt` | the remembered catch mode and details state |
| `META-INF/licenses/elk-LICENSE.txt`, `emf-LICENSE.txt` | EPL-2.0 and EPL-1.0 |
| `src/test/kotlin/io/github/saigyo/aslan/diagram/*Test.kt` | the tests of the diagram |
| `src/test/kotlin/io/github/saigyo/aslan/files/AslFileIconTest.kt`, `src/test/kotlin/io/github/saigyo/aslan/ListingTest.kt` | the tests of the file icon, and of logo, screenshots, description and version (Tasks 11 to 13 each add to `ListingTest`) |
| `spec/data/ChoiceRuleData.kt` (modified) | gives the names of the comparison operators to the labels |
| `files/AslFiles.kt` (modified) | recognises an ASL file from a `VirtualFile` |
| `gradle.properties` (modified) | version 1.0.0 |
| `META-INF/pluginIcon.svg`, `icons/asl.svg` | the commissioner's logo and file icon, unchanged |
| `files/AslFileIconProvider.kt` | the icon of an ASL file in tabs and the project view |
| `tools/demo/make-demo.sh`, `tools/demo/README.md` | the demo project and the list of screenshots |
| `docs/images/` | screenshots: `media/` for the listing, the rest for README and description |
| `docs/marketplace/listing.md`, `getting-started.html` | what the commissioner enters on the Marketplace |

## Execution

Subagent-driven, coordinated by the session that holds the plan. A fresh subagent implements each task; reviews run at the points below. Between reviews the coordinator reads each report and runs `./gradlew check` and the private check itself. The ledger is kept in `.superpowers/sdd/` while the plan runs and moved to `.superpowers/ledgers/2026-11-15-aslan-stage4-diagram.md` at the end. Every dispatch points to the Global Constraints, to the research document and to the IDE tools section of the stage 3 plan.

| Task | Model | Reason |
|---|---|---|
| 1 Split editor and the layout library | Opus 5.5 | platform parts that moved in 2026.2, the dependency and its licences |
| 2 Labels | Sonnet 5.5 | pure functions from a given table |
| 3 The model | Sonnet 5.5 | a walk over the document model with given rules; to Opus 5.5 if the alias offsets go wrong |
| 4 Details | Sonnet 5.5 | given rows |
| 5 Layout | Opus 5.5 | the ELK options decide how the diagram reads, against a width limit |
| 6 Scene and painting | Sonnet 5.5 | given items and rules; to Opus 5.5 if hit testing goes wrong |
| 7 The pane | Opus 5.5 | a Swing component with mouse input, hover and IDE actions |
| 8 Controller | Opus 5.5 | threads, read actions and disposal |
| 9 Shared selection | Sonnet 5.5 | given rules on top of the controller |
| 10 In the running IDE | the coordinator, with a Sonnet 5.5 subagent for computer use | observations, then fixes by the model the fix needs |
| 11 Logo and file icon | Sonnet 5.5 | two given files and one extension |
| 12 Screenshots | the coordinator, with the same kind of subagent | |
| 13 README and listing | Opus 5.5 | texts a user reads first |
| 14 Hand-over | the coordinator | |

An implementer that is blocked, or whose work fails a review twice, is replaced by the next model up.

| After task | Covers | Reviewer | What it looks at |
|---|---|---|---|
| 1 | 1 | Opus 5.5, high | No flagged API; Guava is not in the zip; licences and notices are complete; the existing tests are untouched. Then the smoke check in the sandbox IDE. |
| 4 | 2, 3, 4 | Opus 5.5, medium | The model against section 6.5 "Content" and "Details panel"; Review Focus 1 to 3; nothing throws. |
| 6 | 5, 6 | Opus 5.5, high | Both layouts against section 6.5 "Layout" and the pictures of the research document; the catch switch moves nothing; the scene against "Appearance" and the mockup; Review Focus 2 and 4. |
| 9 | 7, 8, 9 | Opus 5.5, high | One selection (decision 5); decision 4 holds on every path into the layouter; everything is disposed with the editor; Review Focus 4 and 5. |
| 13 | 11, 12, 13 | Opus 5.5, medium | Every statement of README and description is true of the code; the Marketplace rules of Part B; no personal information in any image. |
| 14 | the whole branch | Fable 5.2, high | The branch against section 6.5, the Global Constraints and the two anti-goals; what only shows across tasks. |

---

## Part A: The diagram

### Task 1: Split editor and the layout library

**Files:**
- Create: `diagram/AslSplitEditorProvider.kt`, `diagram/DiagramPreviewEditor.kt`, `diagram/DiagramLayout.kt` (`LayoutMode` only, for now), `META-INF/licenses/elk-LICENSE.txt`, `META-INF/licenses/emf-LICENSE.txt`, `tools/demo/make-demo.sh`, `tools/demo/README.md`
- Modify: `build.gradle.kts`, `META-INF/plugin.xml`, `files/AslFiles.kt`, `NOTICE`, `README.md` (third-party list)
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/SplitEditorTest.kt`

**Interfaces:**
- Produces:

```kotlin
// files/AslFiles.kt: a new overload in the existing object AslFiles, beside isAslFile(PsiFile)
fun isAslFile(file: VirtualFile): Boolean      // an ASL name (isAslFileName), and a file type whose language is YAML or JSON

// diagram/DiagramLayout.kt
enum class LayoutMode(val maxLabel: Int) {
    LOOSE(30), COMPACT(20);
    companion object { fun defaultFor(states: Int): LayoutMode = if (states <= 25) LOOSE else COMPACT }
}

// diagram/AslSplitEditorProvider.kt
class AslSplitEditorProvider : TextEditorWithPreviewProvider(DiagramPreviewProvider())
class DiagramPreviewProvider : FileEditorProvider, DumbAware                // editor type "aslan-diagram"; not registered on its own
class DiagramEditorState(val layoutMode: LayoutMode?) : FileEditorState     // null until the layout switch has been used for the file

// diagram/DiagramPreviewEditor.kt
class DiagramPreviewEditor(project: Project, file: VirtualFile) : UserDataHolderBase(), FileEditor {
    var layoutMode: LayoutMode?                // what getState and setState carry
    fun attach(textEditor: TextEditor)         // called once from createSplitEditor
}
```

- [ ] **Step 1: The dependency and its licences.** In `build.gradle.kts`: `implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.12.1") { exclude(group = "com.google.guava") }`. Check with `./gradlew dependencies --configuration runtimeClasspath` that exactly the seven jars of "Open points" resolve, EMF in 2.12.0. Fetch the EPL-2.0 text and the EPL-1.0 text from the two addresses of "Open points" into `META-INF/licenses/`, unchanged. `NOTICE` and the README's third-party list name ELK and EMF with their versions, licences and where their source is (the addresses their POM files and `about.html` pages give), say that they are bundled unmodified, and say that Guava comes from the IDE.
- [ ] **Step 2: Write the tests.**

```kotlin
fun `test an ASL file is accepted and another YAML file is not`()        // AslFiles.isAslFile(VirtualFile) and DiagramPreviewProvider.accept, for .asl.yaml, .asl.json, plain .yaml, .asl.txt
fun `test the split editor wraps the text editor and the diagram pane`() // AslSplitEditorProvider().createEditor(project, file) is a TextEditorWithPreview; its preview is a DiagramPreviewEditor; its layout is SHOW_EDITOR_AND_PREVIEW; dispose it
fun `test the chosen layout survives closing the file`()                 // preview.layoutMode = COMPACT; the split editor's state written with the provider's writeState and read back with readState carries COMPACT; a file never switched carries null
fun `test the size rule`()                                               // LayoutMode.defaultFor(25) is LOOSE, defaultFor(26) is COMPACT
fun `test the layout library runs`()                                     // two ELK nodes and an edge, laid out with the layered algorithm: both nodes have a place and the edge a section
```

The provider is called directly: light tests need not consult file editor providers when a fixture opens a file.

- [ ] **Step 3: Run** the test. Expected: FAIL (classes missing).
- [ ] **Step 4: Implement.** `plugin.xml` registers `<fileEditorProvider id="aslan-split-editor" implementation="…AslSplitEditorProvider"/>`. `createSplitEditor` returns `TextEditorWithPreview(editor, preview, "Aslan Diagram", Layout.SHOW_EDITOR_AND_PREVIEW, false)` after `preview.attach(editor)`. If the third test shows that the preview's state does not pass through `DiagramPreviewProvider`, keep the chosen layout per file in a project-level `PropertiesComponent` entry keyed by the file's URL, and record that in the ledger. For now `DiagramPreviewEditor` (name "Diagram") lays out two nodes on a background thread and shows "Layout ready: 2 states" in a label.

  Two things about the platform are settled here and recorded in the ledger for Task 8. First, on which thread the platform calls `DiagramPreviewProvider.createEditor` and `createSplitEditor`: `TextEditorWithPreviewProvider.createFileEditor` is a suspending function, so read its bytecode with `javap -c`, and log the thread in both methods for the smoke check of Step 8. If either can run off the UI thread, `DiagramPreviewEditor` builds its Swing component on the first call of `getComponent()` instead of in its constructor. Second, the platform calls `setState` after `createSplitEditor`: a stored layout that arrives after the first layout has started causes a second layout, which is accepted.
- [ ] **Step 5: The demo project.** `tools/demo/make-demo.sh` creates `build/demo/film-archive/` afresh with copies of `film-archive-intake.asl.yaml` (as `intake.asl.yaml`), `reel-restoration.asl.yaml` and `reel-check.asl.json` from `src/test/testData/clean/`, and `intake-with-problems.asl.yaml`: the intake file with three planted problems made by fixed `sed` edits (a `Next` that names no state, a Task without `Resource`, an unknown `JitterStrategy`). The `Next` that is changed is one whose old target is also reached by another transition, so no fourth problem ("no transition leads to this state") appears; the implementer confirms once, with a test that is not committed, that the plugin's inspections report exactly three problems in the generated file, and records it in the ledger. `sed` writes to standard output, redirected into the new file (`sed -i` differs between macOS and Linux). The script copies from nowhere else. `tools/demo/README.md` says what the project is for.
- [ ] **Step 6: Run** `./gradlew check verifyPlugin buildPlugin` and the private check. Expected: PASS, all earlier tests included; the verifier compatible with both IDE versions, with no internal or deprecated API; the zip holds the seven jars and both licence texts and no Guava jar, and is between 5 and 7 MB (design, section 5: about 6 MB).
- [ ] **Step 7: Commit** `feat: open ASL files in a split editor, and bundle the layout library`.
- [ ] **Step 8: Smoke check in the sandbox IDE** (coordinator, after the review). `tools/demo/make-demo.sh`, then `./gradlew runIde --args="$PWD/build/demo/film-archive"`. A computer-use subagent opens `intake.asl.yaml` and reports: what the right pane shows, which mode buttons exist at the top right, what each does, and whether the mode survives closing and reopening the file. Expected: "Layout ready: 2 states", three modes, remembered.

---

### Task 2: Labels

**Files:**
- Create: `diagram/DiagramLabels.kt`
- Modify: `spec/data/ChoiceRuleData.kt` (`internal val comparisonOperatorNames: List<String>`, from the private list `comparisons`, which stays private)
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramLabelsTest.kt`

**Interfaces:**
- Produces:

```kotlin
object DiagramLabels {
    data class Label(val text: String, val title: String?)    // text: the whole label; title: a longer form for hover, or null
    fun resource(text: String): String
    fun rule(rule: AslObject, index: Int, language: QueryLanguage): Label
    fun catcher(catcher: AslObject): Label
    fun shorten(text: String, max: Int): String               // by code points; the last one becomes "…" when cut
}
```

Labels are not cut here: the layouter cuts them to the limit of its layout (`LayoutMode.maxLabel`).

**Rules.**

`resource`:
1. A service integration, `arn:<partition>:states:<region>:<account>:<service>:<action>` with an optional pattern suffix (`.sync`, `.sync:2`, `.waitForTaskToken`), also as `aws-sdk:<service>:<action>`: the service's display name, a space, the action without the suffix.
2. An activity, `arn:<partition>:states:<region>:<account>:activity:<name>`: "Activity " and the name.
3. A Lambda function, `arn:<partition>:lambda:<region>:<account>:function:<name>` with an optional alias or version: "Lambda " and the name.
4. Anything else, a placeholder included: the text as written.

Display names: `s3` S3, `dynamodb` DynamoDB, `lambda` Lambda, `sqs` SQS, `sns` SNS, `ecs` ECS, `eks` EKS, `events` and `eventbridge` EventBridge, `states` and `sfn` Step Functions, `glue` Glue, `batch` Batch, `athena` Athena, `bedrock` Bedrock, `codebuild` CodeBuild, `apigateway` API Gateway, `sagemaker` SageMaker, `emr` EMR, `mediaconvert` MediaConvert, `http` HTTP, `ssm` Systems Manager, `secretsmanager` Secrets Manager, `kms` KMS, `ses` and `sesv2` SES, `kinesis` Kinesis, `firehose` Firehose, `textract` Textract, `comprehend` Comprehend, `rekognition` Rekognition, `translate` Translate. Any other service is shown as written.

`rule`:
1. JSONata, `Condition` a string wrapped in `{%` and `%}`: the text between them, trimmed, each run of white space as one space; no title.
2. JSONPath, `Variable` a string without a placeholder and exactly one comparison operator (`comparisonOperatorNames`): variable, operator sign, value. In the text a leading `$.` of the variable is left out; the title is the same with it. Signs: `…Equals` `=`, `…LessThan` `<`, `…GreaterThan` `>`, `…LessThanEquals` `<=`, `…GreaterThanEquals` `>=`, `StringMatches` `matches`. A string value is in double quotes, a number or Boolean as written, the operand of a `…Path` operator as written. `IsNull`, `IsPresent`, `IsNumeric`, `IsString`, `IsBoolean`, `IsTimestamp` with `true` read "is null", "is present", "is numeric", "is string", "is boolean", "is timestamp", and with `false` "is not …".
3. Anything else (`And`, `Or`, `Not`, a placeholder, a rule being typed): the text "Rule <index + 1>"; the title is the text of the rule's `psi` with each run of white space as one space, cut to 200 with `shorten`, or null when that text is blank (decision 15).

`catcher`: `Label("Catch", <the ErrorEquals names joined with ", ">)`, the title null when there are none.

- [ ] **Step 1: Write the tests** (`§` stands for `$`):

```kotlin
fun `test resource labels`() {
    // arn:aws:states:::aws-sdk:s3:listObjectsV2            -> "S3 listObjectsV2"
    // arn:aws:states:::lambda:invoke.waitForTaskToken      -> "Lambda invoke"
    // arn:aws:states:::states:startExecution.sync:2        -> "Step Functions startExecution"
    // arn:aws-cn:states:::sqs:sendMessage                  -> "SQS sendMessage"
    // arn:aws:states:::aws-sdk:frobnicate:doIt             -> "frobnicate doIt"
    // arn:aws:states:eu-central-1:123456789012:activity:scan                     -> "Activity scan"
    // arn:aws:lambda:eu-central-1:123456789012:function:catalog-reader:live      -> "Lambda catalog-reader"
    // arn:aws:lambda:§{Region}:§{Account}:function:scan    -> "Lambda scan"
    // §{ScanFunctionArn}                                   -> "§{ScanFunctionArn}"
}
fun `test a JSONata condition is shown without its delimiters`()   // "{% §states.input.status = 'split' %}" -> "§states.input.status = 'split'"; a condition over three lines is joined with single spaces
fun `test shorten cuts by code points`()                           // shorten(text, 20) has 20 code points and ends in "…"; a shorter text is unchanged; a surrogate pair is not split
fun `test JSONPath rules`() {
    // Variable §.status, StringEquals split         -> Label("status = \"split\"", "§.status = \"split\"")
    // Variable §.count, NumericGreaterThanEquals 10 -> "count >= 10"
    // Variable §.a, StringEqualsPath §.b            -> "a = §.b"
    // Variable §.seal, IsPresent false              -> "seal is not present"
    // Variable §.name, StringMatches "reel-*"       -> "name matches \"reel-*\""
}
fun `test a rule the label cannot say is numbered`()               // And [...] at index 1 -> text "Rule 2", and the title starts with "And:" and has no line break; a rule with a placeholder Variable; an empty rule has the title null
fun `test a catcher is labelled Catch with its error names`()      // title "States.Timeout, States.TaskFailed"; without ErrorEquals the title is null
```

- [ ] **Step 2: Run** the test. Expected: FAIL.
- [ ] **Step 3: Implement.** The ARN is split on `:` by this object itself; `ResourceChecks` judges validity and is not the place for display names.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: label Task resources and Choice rules for the diagram`.

---

### Task 3: The model

**Files:**
- Create: `diagram/DiagramModel.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramBuilderTest.kt`

**Interfaces:**
- Consumes: `DiagramLabels` (Task 2); `AslDocument.allStates()`, `allMachines()`, `AslState.transitions`, `AslStateMachine.state(name)`.
- Produces:

```kotlin
class DiagramModel(val root: DiagramMachine?, val details: Map<String, List<DetailRow>>, val stateCount: Int) {
    val offsets: Map<String, Int>                 // every state and edge ID to its offset
    fun state(id: String): DiagramState?
    fun edge(id: String): DiagramEdge?
    fun stateAt(offset: Int): String?             // the ID of the innermost state whose range holds the offset, or null
}
class DiagramMachine(val id: String, val title: String?, val start: String?, val states: List<DiagramState>, val edges: List<DiagramEdge>)
class DiagramState(val id: String, val name: String, val type: String?, val sub: String, val terminal: Boolean, val offset: Int, val range: TextRange, val machines: List<DiagramMachine>)
class DiagramEdge(val id: String, val kind: TransitionKind, val from: String, val to: String, val label: String?, val title: String?, val offset: Int)
class DetailRow(val label: String, val items: List<DetailItem>)
class DetailItem(val text: String, val prefix: String = "", val kind: Kind = Kind.PLAIN, val offset: Int? = null) { enum class Kind { PLAIN, STATE, VARIABLE } }

object DiagramBuilder {
    fun readable(doc: AslDocument?): Boolean      // decision 6
    fun build(doc: AslDocument): DiagramModel     // details empty until Task 4
}
```

**Rules.**
- A state ID is `s` and the state's index in `AslDocument.allStates()`; a machine ID is `m` and the index in `allMachines()`. An edge ID is its state's ID and `.n` (Next), `.d` (Default), `.c<i>` (Choice rule i) or `.k<i>` (Catcher i). IDs are unique also when two states share a name, and are valid for one model.
- A Parallel state has one machine per branch, titled "Branch 1", "Branch 2"; a Map state has one, without a title. `stateCount` counts nested states too.
- `sub`, the second line of a node: the type as written; for a Task with a `Resource` that is a certain string, the type, " · " and `DiagramLabels.resource`; empty without a type.
- `terminal`: `End` is the Boolean `true`, or the type is Succeed or Fail.
- `start`: the first state the machine's `StartAt` names, else null.
- An edge exists for each transition whose `targetName` names a state of the same machine (decision 8). `NEXT` has no label, `DEFAULT` the label "Default", `CHOICE` the label and title of `DiagramLabels.rule`, `CATCH` those of `DiagramLabels.catcher`.
- A Map or Parallel state without a readable machine (half-typed) has no machines and is drawn like any other state.
- The `range` of a state is the text range of its entry, key and value (`entryPsi`). The model holds no PSI: it is plain data and may be read on any thread.
- The offset of a state is the start of its key. The offset of an edge is the first of these that lies inside the text range of the state's own entry: for `NEXT` and `DEFAULT` the key of that field; for `CHOICE` and `CATCH` the place the rule or catcher is used (`usePsi`), then the key of `Choices` or `Catch`; last, for every kind, the state's key.
- `readable` is false when `doc` or its root is null, the value of `States` is not a mapping, or the file has an error element (in the YAML or JSON file itself; an error inside a JSONata expression does not count).

- [ ] **Step 1: Write the tests.** One small definition in the film archive domain, inline, with a Choice, a Task with a Catcher, a Map with two inner states, a Parallel with two branches, a Succeed and a Fail:

```kotlin
fun `test states and nested machines keep file order`()               // names, types and sub lines; the Map has one machine without a title, the Parallel two titled "Branch 1" and "Branch 2"; stateCount
fun `test start and terminal states are marked`()                     // start of the root and of each nested machine; End: true, Succeed and Fail are terminal
fun `test each kind of transition is an edge`()                       // IDs "s1.c0", "s1.d", "s2.k0", "s0.n"; labels as in Task 2; an inner edge is in its machine's list
fun `test a target that does not resolve gives no edge`()             // a missing state, a placeholder, a Next of another machine's state
fun `test a state may lead to itself and twice to the same state`()   // both edges exist with their own IDs (Review Focus 2)
fun `test two states of the same name get their own IDs`()            // Review Focus 2
fun `test offsets are the lines that create each item`()              // the text at a state's offset starts with its name; at an edge's offset with "Next", "Default" or the rule's first key
fun `test a path of a shared Catch list starts at the state's own line`() // Catch: *onFault used by two states: each edge's offset lies inside its own state; so does a catcher used as - *oneCatcher (Review Focus 3)
fun `test stateAt finds the innermost state`()                        // an offset inside an inner state of the Map gives the inner state; on StartAt and in the top Comment null; beyond the file null
fun `test a half-typed file builds without an exception`()            // a state without Type, a state that is a scalar, an empty States, a Map without ItemProcessor (no machines); readable is false for a syntax error, a root that is a list, and States: §{States}, and true with an error only inside a JSONata expression (Review Focus 1)
fun `test the committed definitions build completely`()               // each file under src/test/testData/clean: one node per state of the document model, one edge per transition whose target resolves, every offset inside the file
```

- [ ] **Step 2: Run** the tests. Expected: FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew test` and the private check. Expected: PASS. **Step 5: Commit** `feat: build the diagram model from the document model`.

---

### Task 4: The details of a state

**Files:**
- Create: `diagram/DiagramDetails.kt`
- Modify: `diagram/DiagramModel.kt` (`build` fills `details`)
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramDetailsTest.kt`

**Interfaces:**
- Consumes: `AslDocument.variables` (`assignments`, `reads`), `JsonataPsi.rangeInHost`, `PathRead.range`.
- Produces: `object DiagramDetails { fun rows(doc: AslDocument, state: AslState): List<DetailRow> }`

**Rows**, in this order; a row without items is left out. An item with an `offset` is a link to that place; "linked to" below means the item's `offset`. An item without one is plain text.

| Label | Items |
|---|---|
| Type | the type as written |
| Resource | the `Resource` of a Task, as written |
| Reads | one `VARIABLE` item per name read in this state (`read.state === state`), in file order, as `$name`, linked to the first read |
| Assigns | one `VARIABLE` item per name assigned in this state, its Choice rules and its Catchers, as `$name`, linked to the first key |
| Retry | one item per retrier: the name of the alias when it is written as one, else its `ErrorEquals` names joined with ", ", else "Retrier n"; linked to where it is used |
| Catch | one `STATE` item per Catcher: prefix the `ErrorEquals` names joined with ", " and " → ", text the target, linked to the target state's key; `PLAIN` without a link when the target does not resolve |
| Next | for a Choice, one `STATE` item per rule target and the `Default`, without repeats; else the `Next` target; each linked to the target state's key, or `PLAIN` without a link when the target does not resolve; a `PLAIN` item "End" for a terminal state |

- [ ] **Step 1: Write the tests.**

```kotlin
fun `test a Task shows type, resource, reads, assigns, retry, catch and next`()  // the labels in order; the mockup's ListReelFolder case in the film archive domain
fun `test reads and assigns are linked to their first place`()                    // the text at the offset starts with "§batch" for a read in an expression and in a JSONPath string, and with the key for an assignment
fun `test a variable is named once`()                                             // read three times, assigned in the state and in a Catcher
fun `test a retrier is named by its alias`()                                      // Retry: [*noRetry, *retryAll] -> "noRetry", "retryAll"; an inline one -> "States.Timeout" (Review Focus 3)
fun `test a Choice lists every target once`()
fun `test a terminal state ends`()                                                // Next row: one item "End"
fun `test rows without content are left out`()                                    // a Pass without anything: Type and Next only
fun `test a target that is missing is plain`()                                    // in the Catch row and in the Next row: kind PLAIN, offset null
```

- [ ] **Step 2: Run** the test. Expected: FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew test` and the private check. Expected: PASS. **Step 5: Commit** `feat: collect the details of a state for the diagram`.

---

### Task 5: Layout

**Files:**
- Modify: `diagram/DiagramLayout.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramLayoutTest.kt`

**Interfaces:**
- Consumes: `DiagramModel` (Task 3), `LayoutMode` (Task 1), `DiagramLabels.shorten`.
- Produces:

```kotlin
fun interface TextMeasure { fun width(text: String, size: Int, bold: Boolean): Double }

class DiagramLayout(val width: Double, val height: Double, val nodes: List<LaidNode>, val edges: List<LaidEdge>)   // absolute coordinates; a parent before its children
class LaidNode(val id: String, val kind: Kind, val bounds: Rectangle2D.Double, val state: DiagramState?, val title: String?,
               val start: Boolean, val collapsed: Boolean, val collapsible: Boolean) { enum class Kind { STATE, GROUP, BRANCH } }
class LaidEdge(val edge: DiagramEdge, val points: List<Point2D.Double>, val label: LaidLabel?)
class LaidLabel(val text: String, val bounds: Rectangle2D.Double)     // the label cut to the layout's limit

object DiagramLayouter {
    fun layout(model: DiagramModel, mode: LayoutMode, collapsed: Set<String>, measure: TextMeasure): DiagramLayout
}
```

**Rules.**
- A state is a node of 190 by 40. A state with machines whose name is not in `collapsed` is a `GROUP` holding its machines' states; each Parallel branch is a `BRANCH` inside it with its title. A collapsed one is a plain `STATE` node with `collapsed` set. A state with machines is `collapsible`; a Map or Parallel state without machines is a plain `STATE`.
- `LaidNode.id` is the state's ID for a `STATE` and a `GROUP`, and the machine's ID for a `BRANCH` (whose `state` is null).
- A model whose `root` is null gives a layout of width and height 0 without nodes.
- **Every edge of the model is laid out**, catch paths included, whatever the catch switch says: the layouter does not know the switch.
- A label is cut to `mode.maxLabel`; its width is `measure` at size 10 plus 8, its height 14.
- Top to bottom, paths in right angles, labels on their paths. The options of the two layouts start from decision 9.
- `layout` may be called from any thread, one call at a time; it takes no read action and touches no PSI.

- [ ] **Step 1: Write the tests** (real ELK; a `TextMeasure` of 6 per character; the models come from `DiagramBuilder` on the small definition of Task 3 and on the files under `src/test/testData/clean/`):

```kotlin
fun `test every state and every path has a place`()                   // both layouts, every committed definition: one LaidNode per state, one LaidEdge per edge with at least two points
fun `test a Map is a group and each Parallel branch a box inside its state`()   // the children's bounds lie inside the parent's
fun `test a collapsed Map is a plain node and its inner states are not laid out`()
fun `test the layout flows downwards`()                               // along NEXT edges the target is lower than the source, apart from edges that lead back; at least two thirds of all edges lead down
fun `test every path runs in right angles`()                          // neighbouring points share x or y, within 0.01
fun `test no two nodes of one machine overlap`()
fun `test a state that leads to itself and two edges between the same states lay out`()   // Review Focus 2
fun `test a label is cut to the layout's limit and lies on its path`() // 30 in the loose layout, 20 in the compact one
fun `test the compact layout of the large definition is at most 2200 wide`()   // film-archive-intake; the loose one is wider than the compact one
fun `test a state reached only through a Catcher keeps its place in the flow`() // it lies below the state whose Catcher leads to it
fun `test the large definition lays out in under two seconds`()
fun `test an empty model and a Map without a machine lay out`()       // root null: no nodes, size 0; the Map is a STATE that is not collapsible
```

The width limit is tested here with the fixed measure and again in Task 7 with the fonts the pane paints with.

- [ ] **Step 2: Run** the test. Expected: FAIL. **Step 3: Implement.** Register the layered algorithm once. Look at the result before settling the options: a test helper may paint a layout into a PNG in the scratchpad, as the spike did; no picture is committed.
- [ ] **Step 4: Run** `./gradlew test` and the private check. Expected: PASS. **Step 5: Commit** `feat: lay out the diagram, loose and compact`.

---

### Task 6: Scene and painting

**Files:**
- Create: `diagram/DiagramScene.kt`, `diagram/DiagramPainter.kt`, `diagram/Viewport.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramSceneTest.kt`, `ViewportTest.kt`

**Interfaces:**
- Consumes: `DiagramLayout` (Task 5).
- Produces:

```kotlin
enum class CatchMode { NORMAL, MUTED, HIDDEN }

sealed interface SceneItem {
    class Box(val id: String, val kind: LaidNode.Kind, val bounds: Rectangle2D.Double, val type: String?, val name: String, val sub: String,
              val tooltip: String?, val selected: Boolean, val linked: Boolean, val start: Boolean, val terminal: Boolean, val collapsed: Boolean) : SceneItem
    class Path(val id: String, val kind: TransitionKind, val points: List<Point2D.Double>, val selected: Boolean, val muted: Boolean) : SceneItem
    class Label(val edgeId: String, val text: String, val bounds: Rectangle2D.Double, val tooltip: String?, val selected: Boolean, val muted: Boolean) : SceneItem
    class Toggle(val stateName: String, val bounds: Rectangle2D.Double, val collapsed: Boolean) : SceneItem
}
sealed interface Hit {
    data class State(val id: String) : Hit
    data class Edge(val id: String) : Hit
    data class Toggle(val stateName: String) : Hit
}
class DiagramScene(val items: List<SceneItem>, val width: Double, val height: Double) {
    fun hit(x: Double, y: Double, tolerance: Double): Hit?            // in diagram coordinates; the panel passes 4 pixels divided by its scale
    fun tooltip(x: Double, y: Double, tolerance: Double): String?     // plain text, lines separated by "\n"
    fun bounds(id: String): Rectangle2D?           // of a state or a path; of the collapsed box for a state or a path hidden inside it
    companion object { fun of(layout: DiagramLayout, selected: String?, catchMode: CatchMode, measure: TextMeasure): DiagramScene }
}
class DiagramColors { companion object { fun current(): DiagramColors } }      // from the editor colour scheme and the IDE's theme
object DiagramPainter { fun paint(g: Graphics2D, scene: DiagramScene, colors: DiagramColors) }

class Viewport(val scale: Double, val tx: Double, val ty: Double) {
    fun zoomAt(factor: Double, x: Double, y: Double): Viewport      // keeps the point under the pointer; scale within 0.2 and 3
    fun reveal(rect: Rectangle2D, pane: Dimension, margin: Int): Viewport   // unchanged when rect is visible, else moved the least
    companion object {
        fun around(rect: Rectangle2D?, size: Dimension2D, pane: Dimension): Viewport   // scale 1, rect in the middle; without a rect, the top of the diagram
        fun fit(size: Dimension2D, pane: Dimension, padding: Int): Viewport            // the whole diagram visible, scale at most 1
    }
}
```

**The scene.**
- Items come in painting order: boxes (a parent before its children), paths, labels, toggles; a selected path and its label come last, so they lie on top.
- `selected` names a state or an edge. A selected edge marks its path and label `selected` and its two end boxes `linked`. A selected state or edge inside a collapsed box marks the box `selected`; what a collapsed box holds is read from its `LaidNode.state.machines`. An ID the layout does not know marks nothing.
- With `CatchMode.MUTED`, catch paths and their labels are `muted`. With `HIDDEN` they have no items at all; nothing else changes place.
- A name or `sub` line too wide for its box is cut with "…" (measured with `measure`). The box's `tooltip` is null when both lines fit; else it is the whole name, followed by "\n" and the whole `sub` line when that one was cut. A label's `tooltip` is the edge's title, or its whole text when the label was cut, else null.
- `Box.id` is `LaidNode.id`. `hit`: a toggle before its box; the innermost `STATE` or `GROUP` box at the point (a `BRANCH` box is never hit, so a click on the empty part of a branch hits the Parallel state around it); a path within `tolerance` of one of its segments, and a label counts as its path; a selected path before the others. Hidden items are never hit.

**The painting**, as in the mockup: a rounded rectangle, filled in a colour that differs from the pane's background, with a bar of the type colour, 4 wide, on its left edge, the name in bold, the `sub` line smaller; a selected box with a highlighted fill and border, a linked one with a highlighted outline; a mark above a start state and on a terminal one; a group with its title line and toggle; paths with arrowheads, catch paths dashed; muted items faint; a selected path thicker, in the accent colour, over its whole length. Type colours, from the structure view icons: Task `#56A8F5`, Choice `#E0A84E`, Pass `#8A8D93`, Wait `#7E8CE0`, Succeed `#3FB8AF`, Fail `#DB5C5C`, Parallel `#C77DBB`, Map `#6AAB73`; catch paths `#DB5C5C`. In a dark theme Pass is `#A5A8AE`, as its icon; the other colours are the same in both. An unknown or missing type has the colour of Pass (decision 15). Everything else follows the editor's colour scheme, light or dark.

- [ ] **Step 1: Write the tests.**

```kotlin
fun `test each node and edge of the layout has its items`()             // one Box per LaidNode, one Path per LaidEdge, a Label where the edge has one, a Toggle per collapsible state
fun `test the selected state is marked, and so are a selected path and its two ends`()
fun `test the selected path lies on top`()                              // its Path and Label are the last items
fun `test catch paths are muted on demand and absent when hidden`()     // and every other item has the same bounds in all three modes
fun `test a selection inside a collapsed box marks the box`()
fun `test a long name is cut and whole on hover`()                      // 80 characters: Box.name ends in "…", Box.tooltip is the whole name; a name that fits has the tooltip null (Review Focus 4)
fun `test texts that look like markup are kept as they are`()           // a state "<html><b>Scan": Box.name is exactly that; a name of 80 characters that starts with "<html>": the tooltip is exactly the name; a condition "<html>x = 1" of 40 characters: Label.text starts with "<html>" and Label.tooltip is exactly the condition (Review Focus 4)
fun `test hit finds the innermost state, a path near its line, and a toggle before its box`()
fun `test a click on the empty part of a branch hits the Parallel state`()   // Hit.State with the Parallel's ID, never a machine ID
fun `test the tolerance of a path is the one passed`()                  // a point 10 from a segment: hit with tolerance 20, not with 4
fun `test bounds of a state inside a collapsed box are the box's`()
fun `test a label counts as its path and a hidden catch path is not hit`()
fun `test the background is not hit`()
fun `test a diagram paints without a window`()                          // every committed definition, both layouts, into a BufferedImage: no exception, and the pixel in the middle of the first state's type bar has the colour of its type
fun `test around centres a state at full size`()                        // ViewportTest
fun `test fit shows the whole diagram and never enlarges it`()
fun `test zoom keeps the point under the pointer and stays within its limits`()
fun `test reveal leaves a visible state alone and moves the least for a hidden one`()
```

- [ ] **Step 2: Run** the tests. Expected: FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew test` and the private check. Expected: PASS. **Step 5: Commit** `feat: paint the diagram and find what lies at a point`.

---

### Task 7: The pane

**Files:**
- Create: `diagram/DiagramPane.kt`, `diagram/DiagramPanel.kt`, `diagram/DiagramDetailsPanel.kt`, `diagram/DiagramPrefs.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramPaneTest.kt`

**Interfaces:**
- Consumes: `DiagramScene`, `DiagramPainter`, `Viewport`, `CatchMode` (Task 6); `DiagramModel.details` (Task 4).
- Produces:

```kotlin
interface DiagramView {
    val component: JComponent
    var onSelect: (id: String?) -> Unit            // a click on a state or a path; null for a click on the background
    var onNavigate: (offset: Int) -> Unit          // a link in the details panel
    var onToggleBox: (stateName: String) -> Unit   // a click on a box's toggle
    var onLayoutMode: (LayoutMode) -> Unit         // the layout switch
    val measure: TextMeasure                       // with the fonts the panel paints with
    fun show(model: DiagramModel, layout: DiagramLayout, mode: LayoutMode, selected: String?)   // selected: a state or edge ID of this model, or null; also clears the out-of-date mark
    fun select(id: String?)                        // a state or an edge of the model shown, or null
    fun setStale()
}
class DiagramPane(parent: Disposable) : DiagramView
object DiagramText { fun literal(text: String): String }   // for Swing components that read HTML: "<html>", the text with &, < and > escaped and "\n" as "<br>", "</html>"
object DiagramPrefs {
    var catchMode: CatchMode             // key aslan.diagram.catchPaths; anything unknown reads as NORMAL
    var detailsCollapsed: Boolean        // key aslan.diagram.detailsCollapsed
}
```

**The pane**, from top to bottom as in the mockup: an action toolbar with the catch switch (Normal, Muted, Hidden), the layout switch (Loose, Compact) and zoom out, zoom in, Fit; the diagram; the legend (the eight type colours and the catch line); the details panel with its header and a control to collapse it.

**Behaviour.**
- `show` marks `selected` as `select` does. The view is set once, at the first `show` that finds the pane with a size, or when the pane first gets a size after a `show`: full size around what is selected then (`Viewport.around`), or the top of the diagram when nothing is. A later `show` keeps zoom and position, unless the layout mode changed: then the selection is brought back into view (`reveal`).
- A click on a state selects it, shows its details and calls `onSelect`. A click on a path selects it and its two ends, shows no details, and calls `onSelect`. A click on a toggle calls `onToggleBox`. A click on the background clears the selection and calls `onSelect(null)`.
- `select` marks the state or the path and reveals it; a state or a path inside a collapsed box marks and reveals the box. For a state the details panel shows its rows under a header with the state's whole name; for a path or null it is empty.
- The catch switch builds a new scene from the same layout, repaints and stores the mode in `DiagramPrefs`. The layout switch shows the mode of the last `show` and calls `onLayoutMode`; it paints nothing itself.
- Wheel and drag pan; the wheel with Ctrl or Cmd zooms at the pointer; the toolbar zooms at the centre and fits.
- Hovering shows `DiagramScene.tooltip`. Collapsing the details panel is stored in `DiagramPrefs`, and the diagram takes the freed space.
- `setStale` shows a mark "Out of date" over the diagram, which stays; `show` removes it. A model with `root` null shows "No state machine to show yet."
- A change of the editor's colour scheme or the IDE's theme repaints with new colours.
- Every text from the file (names, labels, hover texts, details) is shown literally (decision 10). What the painter draws is literal already. A text given to a Swing component that reads HTML (the tool tip, the labels and links of the details panel) always goes through `DiagramText.literal`.
- A details item with an `offset` is a link that calls `onNavigate`; one without is plain text.

- [ ] **Step 1: Write the tests** (no window: the pane gets a size, mouse events are sent to the panel, links are clicked through their components):

```kotlin
fun `test a click on a state selects it and reports it`()                 // onSelect("s3"); the details panel shows its rows
fun `test a click on a path selects the path and shows no details`()
fun `test a click on the background clears the selection`()               // onSelect(null); the details panel is empty
fun `test a click on a toggle reports the box`()
fun `test the first diagram opens at full size around the selected state`()   // show(..., selected = the last state) on the large definition: scale 1, the state's box lies inside the pane; with selected null the top of the diagram
fun `test a pane without a size sets its view when it gets one`()         // show before the pane has a size, then a size: the same view
fun `test a later diagram keeps zoom and position`()
fun `test a selected path is kept by show`()                              // show(..., selected = an edge ID): the path is marked, the details panel is empty
fun `test the compact layout of the large definition is at most 2200 wide with the pane's fonts`()   // DiagramLayouter with pane.measure
fun `test select reveals a state outside the view and leaves a visible one alone`()
fun `test the catch switch moves nothing and is remembered`()             // the layout object is the same; DiagramPrefs.catchMode
fun `test the layout switch reports the mode and shows the current one`()
fun `test collapsing the details panel is remembered`()
fun `test a link in the details reports its offset`()
fun `test literal escapes markup`()                                       // DiagramText.literal("<html><b>Scan\n& more") is "<html>&lt;html&gt;&lt;b&gt;Scan<br>&amp; more</html>"
fun `test texts from the file are shown literally`()                      // a state named "<html>" plus 80 characters: the panel's tool tip over its box and the header of the details panel are DiagramText.literal of the whole name; a catch target of that name in the details likewise (Review Focus 4)
fun `test out of date keeps the diagram and marks it`()
fun `test an empty model says so`()
fun `test the pane paints every committed definition`()                   // both layouts, a light and a dark colour scheme, into a BufferedImage of 600 by 800: no exception
```

- [ ] **Step 2: Run** the test. Expected: FAIL. **Step 3: Implement.** The toolbar's actions are made in code and need no entry in `plugin.xml`.
- [ ] **Step 4: Run** `./gradlew check verifyPlugin` and the private check. Expected: PASS. **Step 5: Commit** `feat: the diagram pane with toolbar, legend and details`.

---

### Task 8: The controller

**Files:**
- Create: `diagram/DiagramController.kt`
- Modify: `diagram/DiagramPreviewEditor.kt` (shows a `DiagramPane`, creates the controller in `attach`, and `layoutMode` reads and writes the controller's choice)
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramControllerTest.kt`

**Interfaces:**
- Consumes: `DiagramView` (Task 7), `DiagramBuilder` (Task 3), `DiagramLayouter` (Task 5).
- Produces:

```kotlin
class DiagramController(project: Project, file: VirtualFile, editor: Editor, view: DiagramView, parent: Disposable) {
    var chosenMode: LayoutMode?                       // null: the size rule decides
    internal fun refresh(): CompletableFuture<*>      // done when the view has the result; tests wait for it
}
```

**Behaviour.**
- On creation, and 300 ms after the last change of the file's document, a refresh: build the model in a non-blocking read action that waits for committed documents (`withDocumentsCommitted(project)`; `commitDocument` itself needs the UI thread), coalesced and expiring with `parent`, and note the document's modification stamp in the same read action; then, outside the read action and on a background thread used for nothing else, lay it out; then hand both to the view on the UI thread (decision 4).
- A result is dropped when the document's modification stamp is no longer the one its model was built from, or the controller is disposed. The refresh that the newer change has scheduled replaces it.
- The controller holds the selection: a state or edge ID of the last model, or null. Every `show` is given it. In this task it stays null; Task 9 fills it.
- The layout mode is `chosenMode`, or `LayoutMode.defaultFor(model.stateCount)` while that is null.
- When `DiagramBuilder.readable` is false: `setStale()`. A file that was never readable gets one `show` of a model with `root` null.
- `onLayoutMode(mode)` sets `chosenMode`; `onToggleBox(name)` adds the name to the collapsed boxes or removes it. Both lay out the last model again, without reading the file, and are subject to the same dropping rule.
- Everything registered is disposed with `parent`.
- The delay is a scheduled future on `AppExecutorUtil.getAppScheduledExecutorService()`, cancelled by the next change; the layout thread is `AppExecutorUtil.createBoundedApplicationPoolExecutor("Aslan diagram layout", 1)`. Check both with `javap -v` for an internal, experimental, obsolete or deprecated mark before using them; if one carries a mark, use a plain `java.util.concurrent` executor that is shut down with `parent`, and record it in the ledger.
- If Task 1 found that the preview editor is created off the UI thread, the pane and the controller are created on the first `getComponent()`.

- [ ] **Step 1: Write the tests**, with a `DiagramView` that records its calls. A test waits for `refresh()` by pumping the event queue (`PlatformTestUtil.waitForFuture`), never with a blocking `get` on the UI thread, which would wait for itself:

```kotlin
fun `test opening shows the diagram`()
fun `test an edit shows a new diagram`()                               // type a new state, refresh: the last model names it
fun `test an unreadable file marks the diagram out of date`()          // break the YAML: setStale, no show; repair it: show (Review Focus 1)
fun `test a file that was never readable shows an empty diagram`()
fun `test a small machine opens loose and a large one compact`()       // 25 and 26 states; the large synthetic definition is compact
fun `test the chosen layout overrides the size rule and stays`()       // onLayoutMode(LOOSE) on the large definition; a later edit keeps LOOSE; DiagramPreviewEditor.layoutMode returns it
fun `test collapsing a box lays out again without reading the file`()  // the model object is the same; the box is a collapsed node
fun `test the layout runs off the UI thread and outside a read action`() // a TextMeasure that records the thread and isReadAccessAllowed at each call (Review Focus 5)
fun `test a burst of edits leads to one layout, and an outdated result is dropped`()   // Review Focus 5
fun `test nothing is shown after disposal`()
```

- [ ] **Step 2: Run** the test. Expected: FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew check verifyPlugin` and the private check. Expected: PASS. **Step 5: Commit** `feat: redraw the diagram after edits`.

---

### Task 9: Shared selection

**Files:**
- Modify: `diagram/DiagramController.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramSelectionTest.kt`

**Interfaces:**
- Consumes: `DiagramModel.stateAt`, `DiagramModel.offsets`; the controller's selection and its dropping rule (Task 8).
- Produces: `internal fun caretMovedNow()` on the controller, for tests.

**Behaviour** (decision 5). The state at the caret is always `model.stateAt(caret offset)` of the last model, so an ID never comes from a newer document than the diagram shows. No PSI is read for the selection.
- A caret move sets, 100 ms later, the selection to the state at the caret (null outside every state) and calls `select`. While the document's modification stamp differs from the last model's, the caret move does nothing: the refresh that is on its way sets the selection.
- A refresh after an edit, and the first one, set the selection to the state at the caret, read on the UI thread when the result is handed over, and pass it to `show`.
- A new layout of the same model (layout switch, box toggle) passes the selection as it is, so a selected path stays selected.
- `onSelect(id)`: the selection becomes `id`. For an ID the caret moves to the offset the last model has for it, clamped to the document, and that line scrolls into view; the caret move this causes changes no selection. An ID the model does not know is ignored. For null the selection is cleared and the caret stays.
- `onNavigate(offset)`: the caret moves there, clamped to the document. This one does select the state at the new place.
- Neither moves the focus out of the diagram.

- [ ] **Step 1: Write the tests.**

```kotlin
fun `test the caret in a state selects it`()                       // also inside an expression of the state, and in an inner state of a Map: the inner one
fun `test the caret outside every state selects nothing`()         // on StartAt, in the top Comment
fun `test a click on a state moves the caret to its key`()
fun `test a click on a path moves the caret to its line and keeps the selection`()   // no select call follows
fun `test a link in the details moves the caret and selects the state there`()
fun `test a new diagram after an edit selects the state at the caret`()   // show is called with that ID, also on opening
fun `test a selected path survives a layout switch and a box toggle, and not an edit`()   // show is called with the edge ID twice, then with the state at the caret
fun `test a caret move during an edit selects nothing until the new diagram`()   // insert a state above the caret and move the caret before the refresh: no select call; the next show carries the state the caret is in, by the new model
fun `test a click on the background clears the selection and leaves the caret`()
fun `test an unknown ID and an offset beyond the file do no harm`()
```

- [ ] **Step 2: Run** the test. Expected: FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew check verifyPlugin` and the private check. Expected: PASS. **Step 5: Commit** `feat: share the selection between editor and diagram`.

---

### Task 10: In the running IDE

No automated test covers how the diagram looks and feels, so this task is the last test of Part A (spec section 8).

- [ ] **Step 1: Start.** `tools/demo/make-demo.sh`, then `./gradlew runIde --args="$PWD/build/demo/film-archive"`.
- [ ] **Step 2: Observe.** A computer-use subagent works through the list and reports, for each point, what it sees: texts, colours, which elements are marked, what moved. It gives no verdict. The coordinator judges each report against section 6.5 and the mockup.
  1. `reel-restoration.asl.yaml` (11 states) opens split and in the loose layout; the diagram flows from top to bottom; nodes show name, type and, for Tasks, the resource label; start and terminal states are marked; the legend is there.
  2. `intake.asl.yaml` (62 states) opens in the compact layout, at full size, with the state at the caret in view. The layout switch changes to loose and back; the choice is still there after the file and the IDE were closed and reopened, and `reel-restoration.asl.yaml` is still loose.
  3. A Choice shows one labelled path per rule and "Default"; catch paths are dashed and red and labelled "Catch"; hovering a cut condition or a cut name shows the whole text.
  4. The Map is a box with its inner states; the Parallel a box with one box per branch; a box collapses and expands.
  5. A click on a state: the caret moves to its key; the details panel shows the rows that apply. Whether the Structure tool window follows is the platform's doing (its option to follow the caret); the report says whether it does with the default settings, and the README words it accordingly. A click in the structure view and a caret move in the editor select the state in the diagram and bring it into view.
  6. A click on a path: the path and its two states are marked, no state is selected, the caret is on the line that creates it. In the compact layout the selected path can be followed over its whole length where paths share a line.
  7. Links in the details panel move the caret.
  8. The catch switch: normal, muted, hidden. No state moves when it is used. The details panel collapses. Both are remembered after the IDE restarts, and so is the editor mode.
  9. Zoom, pan and Fit.
  10. Typing a new state draws it shortly after; a broken line marks the diagram out of date and keeps it; repairing clears the mark. Typing stays fluent in the 1,257-line file.
  11. Switching the IDE's theme (light and dark) recolours the diagram, the toolbar and the details panel.
  12. `reel-check.asl.json` shows its diagram. Editor-only and diagram-only modes work.
- [ ] **Step 3: Fix** what the observations show, each fix with a test where one is possible, and observe that point again. **Step 4: Run** `./gradlew check verifyPlugin` and the private check. **Step 5: Commit** each fix on its own as `fix: <what was wrong>`, with the files it changed staged by path. Without a fix there is no commit.

---

## Part B: Release 1.0, README and Marketplace listing

The rules of JetBrains this part holds to (looked up on 2026-11-15 in the Marketplace documentation, "Best practices for listing" and "Approval guidelines", and in the SDK documentation, "Plugin Icon File"):

- **Description:** English; the first 40 characters are a summary, used on the preview card; plain wording, bullet points for the main benefits, no marketing adjectives; every image and link must work. JetBrains recommends the Media section for images; an embedded image should be 600 to 800 pixels wide and cannot be zoomed, so it must be readable as it is.
- **Screenshots for the Media section:** at least 1200 × 760 pixels, all of one aspect ratio, the default IDE theme, readable text, no desktop background, no browser window, no personal information.
- **Logo:** `META-INF/pluginIcon.svg`, 40 × 40, SVG, ideally under 3 kB, the drawing within the inner 36 × 36; optional `pluginIcon_dark.svg`. Not the template's logo, and nothing that resembles a JetBrains logo.
- **Name:** at most 30 characters, without "Plugin", "IntelliJ" or a trademark of others. "Aslan" complies.
- **Vendor:** a working website and email; the email is `aslan@kaixo.de` (given by the commissioner). **Change notes:** real ones. **Links:** reachable; an open-source licence needs a public link to the source.

### Task 11: Logo and file icon

The commissioner supplies both files in a folder outside the repository: `pluginIcon.svg` (40 × 40, a lion's face with a small state machine and a Choice diamond) and `asl.svg` (16 × 16). They are copied unchanged. There is no dark variant of either; the folder's notes say the logo reads on both backgrounds.

**Files:**
- Create: `META-INF/pluginIcon.svg`, `icons/asl.svg`, `files/AslFileIconProvider.kt`
- Modify: `AslIcons.kt` (`val File`), `META-INF/plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/files/AslFileIconTest.kt`, `src/test/kotlin/io/github/saigyo/aslan/ListingTest.kt`

**Interfaces:**
- Consumes: `AslFiles.isAslFile(file: VirtualFile)` (Task 1).
- Produces: `class AslFileIconProvider : FileIconProvider` with `getIcon(file: VirtualFile, flags: Int, project: Project?): Icon?`, registered as `<fileIconProvider implementation="…"/>`; `AslIcons.File`.

- [ ] **Step 1: Write the tests.**

```kotlin
fun `test an ASL file has the plugin's icon`()          // .asl.yaml, .asl.yml and .asl.json, and a name matching an extra pattern of the settings: AslIcons.File
fun `test another YAML or JSON file keeps its icon`()   // the provider returns null for config.yaml, data.json and notes.asl.txt
fun `test the logo is a small SVG of 40 by 40`()        // ListingTest: META-INF/pluginIcon.svg has width and height 40 and is under 3 kB
```

- [ ] **Step 2: Run** the tests. Expected: FAIL. **Step 3: Implement.** The two SVG files are copied with `cp`, so that they stay byte for byte as supplied; this is the one exception to editing with the IDE tools. The files keep their YAML and JSON file types; the plugin registers no file type. Before using `com.intellij.ide.FileIconProvider` and its extension point `com.intellij.fileIconProvider`, check in the 2026.2.3 jars (`javap -v`) that neither carries an internal, experimental or deprecated mark; this was not checked when the plan was written.
- [ ] **Step 4: Run** `./gradlew check verifyPlugin` and the private check. Expected: PASS. **Step 5: Commit** `feat: plugin logo and an icon for ASL files`.
- [ ] **Step 6: Look.** In the sandbox IDE on the demo project (the computer-use subagent of Task 10 reports): the icon in the editor tab and in the Project tool window, in the light and in the dark theme. If the mane colour `#B86E0E` does not read on the dark background, report it to the commissioner; the folder's notes name that colour as the one to adjust.

---

### Task 12: Screenshots

**Files:**
- Create: `docs/images/media/01-overview.png` to `06-diagram.png`, `docs/images/completion.png`, `jsonata.png`, `variables.png`, `diagram.png`
- Modify: `tools/demo/README.md` (the list below, and how each picture was made)
- Test: `src/test/kotlin/io/github/saigyo/aslan/ListingTest.kt` (the file of Task 11, extended)

**The pictures.** All from the sandbox IDE on the demo project, with the file icon of Task 11 in place, default theme and default font sizes, the IDE window at 1280 × 800 points, the Project tool window closed, no notification balloons.

| File | Shows |
|---|---|
| `media/01-overview.png` | split mode on `intake.asl.yaml`, in the compact layout it opens with: the Structure tool window on the left, the editor with JSONata, the diagram with a Task selected, the details panel open (the mockup's figure 1) |
| `media/02-completion.png` | the completion of field names in a new state, with the quick documentation of the chosen field beside it |
| `media/03-validation.png` | `intake-with-problems.asl.yaml`: an error underlined with its tooltip, and the Problems tool window listing the three planted problems |
| `media/04-jsonata.png` | an expression of twenty lines, coloured, with the completion of functions and their signatures |
| `media/05-variables.png` | the caret on a variable with ten or more usages: assignments and reads in two colours, and Find Usages below, grouped into reads and writes |
| `media/06-diagram.png` | diagram-only mode on `reel-restoration.asl.yaml` in the loose layout: a path selected, catch paths muted, a Map box with its inner states, the details panel collapsed (the mockup's figure 2) |
| `completion.png`, `jsonata.png`, `variables.png`, `diagram.png` | the part of pictures 2, 4, 5 and 1 that carries the point, at most 800 points wide, for the README's sections and the description |

- [ ] **Step 1: Write the test** (plain JUnit, files read from the project directory):

```kotlin
fun `test the media screenshots share one size of at least 1200 by 760`()   // the six files exist; every width and height is the same
fun `test the detail pictures are at most 1600 pixels wide`()               // 800 points on a display of double density
```

- [ ] **Step 2: Run** the test. Expected: FAIL (no files).
- [ ] **Step 3: Take the pictures.** A computer-use subagent brings the IDE into the state of each row; the window alone is captured into a file (on macOS, `screencapture -o -l <window id>`), and the detail pictures are cut from the captures. The subagent reports what each picture shows; the coordinator looks at every file before it is committed and rejects one that shows a path, a name, a notification or anything outside the IDE window. If the capture cannot be made from this session, the commissioner takes the six pictures from the list in `tools/demo/README.md`, and the coordinator cuts the details.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `docs: screenshots of the plugin on the demo project`.

---

### Task 13: README, plugin description and listing

**Files:**
- Modify: `README.md`, `META-INF/plugin.xml` (`<description>`, `<change-notes>`, `<vendor>`), `gradle.properties` (`version = 1.0.0`), `src/test/kotlin/io/github/saigyo/aslan/ListingTest.kt`
- Create: `docs/marketplace/listing.md`, `docs/marketplace/getting-started.html`

**The README**, in this order. It introduces and shows; the reasoning stays in the design document, which it links.

1. **Title and summary:** one sentence on what Aslan is and for whom, the build badge, and `media/01-overview.png`.
2. **What it does:** the four things of the design's section 1 (ASL-aware editing, JSONata as code, variables that can be followed, a live diagram), two or three lines each.
3. **Installation:** from the Marketplace (Settings | Plugins, search "Aslan"), stated as available once published; and from a zip built locally (Install Plugin from Disk).
4. **Getting started:** the four steps below.
5. **Using the plugin**, one subsection per area, each with its picture and the actions and shortcuts a user needs:
   - *Writing a definition:* completion, quick documentation, the structure view, state names (go to, find, rename). `completion.png`.
   - *Checks:* the rule (an error only where AWS rejects the definition, everything else at most a warning), where to switch a check off, what is never reported. `media/03-validation.png`.
   - *JSONata:* where expressions are recognised, highlighting, checks, completion, local variables. `jsonata.png`.
   - *State machine variables:* usage highlighting in two colours, stepping through usages with the shortcuts as today's README gives them in its paragraph on variables, go to declaration, find usages, rename and when it refuses, completion after `$`. `variables.png`.
   - *Diagram:* the three modes, shared selection, paths, the catch switch, the loose and the compact layout, boxes, the details panel, zoom. `diagram.png`.
   - *Placeholders* and *Settings* (file name patterns, inspections, colours).
6. **Limits:** what the plugin does not judge, as today's two closing paragraphs say, and that it makes no network requests.
7. **Requirements**, **Building** (as today), **Design** (with the research document), **Licence** with the third-party list including ELK and EMF.

Every fact of today's README that a user needs stays; counts of probes and the history of the checks move out (the design document has them). The "Status" section goes.

**Getting started** (README, description and `getting-started.html` use the same four steps):
1. Name the definition `*.asl.yaml`, `*.asl.yml` or `*.asl.json`, or add your own pattern under Settings | Languages & Frameworks | Amazon States Language.
2. Open the file. The editor is on the left and the diagram on the right; the buttons at the top right of the editor switch between editor, both, and diagram.
3. Click a state in the diagram or in the Structure tool window to go to it. Place the caret on a variable to see where it is assigned and read.
4. The checks are listed under Settings | Editor | Inspections | Amazon States Language; each can be switched off.

**The description** in `plugin.xml`:
1. First sentence, fixed: "Editor support for AWS Step Functions state machine definitions (Amazon States Language) in YAML and JSON: completion, checks, JSONata, variable navigation and a live diagram."
2. `diagram.png`, then the features as four short groups of bullet points (Editing, JSONata, Variables, Diagram), with `variables.png` and `jsonata.png` after their groups. The Variables group names the shortcuts, as the design's section 6.4 requires and today's description does: Next Highlighted Usage and Previous Highlighted Usage, Ctrl+Option+Down and Ctrl+Option+Up in the default macOS keymap, none in the default Windows and Linux keymaps. Each image is `<img src="https://raw.githubusercontent.com/saigyo/aslan/main/docs/images/<file>" width="700">`.
3. "Getting started": the four steps.
4. One paragraph on the checking rule and on what is not judged; "The plugin makes no network requests."
5. Links to the README and the issue tracker on GitHub; the trademark notice as today.

**Change notes** for 1.0.0: "First release", and one line for each of the four areas.

**The vendor:** `<vendor email="aslan@kaixo.de" url="https://github.com/saigyo/aslan">saigyo</vendor>`.

**`docs/marketplace/listing.md`:** what the commissioner does and enters, in order: make the repository public and merge this branch, so the description's images and links work; the licence (Apache-2.0) and the links to source, issue tracker and documentation (the README); the tags to choose in the upload form; the six media files in their order with a one-line caption each; the text of `getting-started.html` for the "Getting Started" field; and that signing and uploading follow the JetBrains documentation, outside this project.

- [ ] **Step 1: Extend the test.**

```kotlin
fun `test the description opens with an English summary`()                  // its first 40 characters are ASCII letters, digits, spaces and punctuation, and start with "Editor support for AWS Step Functions"
fun `test every image of the description is a file of this repository`()    // each img src starts with https://raw.githubusercontent.com/saigyo/aslan/main/ and the rest is an existing file under docs/images/
fun `test every local link and image of the README exists`()
fun `test the description names the shortcuts`()                             // it contains "Next Highlighted Usage", "Previous Highlighted Usage", "Ctrl+Option+Down" and "Ctrl+Option+Up"
fun `test the vendor has an email and a website`()                           // aslan@kaixo.de and https://github.com/saigyo/aslan
fun `test the version is 1_0_0 and the change notes are not empty`()
```

- [ ] **Step 2: Run** the test. Expected: FAIL.
- [ ] **Step 3: Write** the texts and the listing files. Check each statement against the code or a test before it is written; shortcuts are taken from the platform's keymaps, as in stage 3.
- [ ] **Step 4: Run** `./gradlew check verifyPlugin buildPlugin`. Expected: PASS; the zip in `build/distributions` is named `aslan-1.0.0.zip` and holds `pluginIcon.svg`, the seven ELK and EMF jars, the licence texts, and no Guava jar, and is between 5 and 7 MB.
- [ ] **Step 5: Commit** everything in one commit, `docs: introduce the plugin in README and description, as version 1.0.0` (description, change notes and vendor are all in `plugin.xml`, which cannot be split between two commits here).

---

### Task 14: Hand-over

**Files:**
- Modify: `docs/superpowers/specs/2026-11-13-aslan-design.md`

- [ ] **Step 1: Update the design document.** Section 6.5: the decisions of this plan that stand, each marked as made in the plan of stage 4; the rules of labels, offsets and the details rows in a few lines; and the layout options the two layouts ended up with. Section 8: the demo project and the running-IDE check. Section 9: what the builder chose. Section 10: what Task 10 found and could not settle, and the four rows added on 2026-11-15 brought up to date.
- [ ] **Step 2: Verify.** `./gradlew check verifyPlugin buildPlugin` passes; the private check reports the definition as checked; install the built zip in the sandbox IDE once and open `intake.asl.yaml`.
- [ ] **Step 3: Commit** `docs: describe the diagram and the release in the design`.
- [ ] **Step 4: Final review** of the whole branch, then one fix round.
- [ ] **Step 5: Report** to the commissioner: what was built, what Task 10 found, the pictures to look at, how the icons read in both themes, the steps of `listing.md`, and the checklist for a last look in the running IDE (the twelve points of Task 10). Wait for the decision on pushing and the pull request.
