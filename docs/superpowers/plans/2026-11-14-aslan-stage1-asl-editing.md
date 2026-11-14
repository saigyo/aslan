# Aslan Stage 1: ASL-Aware Editing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A JetBrains IDE plugin that recognises Amazon States Language files in YAML and JSON and gives them completion, validation, documentation, state-name navigation, a structure view and semantic highlighting.

**Architecture:** One bundled description of ASL (`spec`) and one format-independent read model of a file (`tree`, then `model`) feed every feature. YAML and JSON PSI are touched only in the `tree` adapters and in thin feature entry points. Checks are a pure function from the model to findings; inspections only surface them.

**Tech Stack:** Kotlin 2.4, Java 25, Gradle with IntelliJ Platform Gradle Plugin 2.20.1, IntelliJ Platform 2026.2, bundled YAML and JSON plugins, JUnit 4 platform tests (`BasePlatformTestCase`).

**Spec:** `docs/superpowers/specs/2026-11-13-aslan-design.md` (sections 4, 5, 6.1, 6.2, 7, 8) and its mockup `docs/superpowers/specs/2026-11-13-aslan-design-mockup.html`. This plan covers stage 1 only. Stages 2 to 4 (JSONata, variables, diagram) get their own plans. One deviation from spec section 6.1: the reference data for JSONata functions and for the fields of `$states` is built in stage 2, where it is first used.

## Global Constraints

- Plugin name `Aslan`; plugin ID and base package `io.github.saigyo.aslan`; licence Apache-2.0.
- Minimum IDE version 2026.2 (`since-build` 262), no upper bound. Build with Java 25.
- Required plugin dependencies in this stage: `org.jetbrains.plugins.yaml`, `com.intellij.modules.json`, `intellij.structureView.plugin`.
- A file is an ASL file when its name ends in `.asl.yaml`, `.asl.yml` or `.asl.json`, or matches a pattern added in settings. Files keep their YAML and JSON file types. The plugin contributes nothing to any other file.
- No JSON Schema, no Symbol API, no network requests.
- A problem is an **error** only if AWS documents that it rejects the definition at deployment. Everything else is a **warning** at most. Placeholders (`${name}`), YAML anchors, aliases and merge keys, and anything inside free-form data are never reported.
- Descriptions of ASL fields are written in the plugin's own words with a link to the AWS documentation page. No text is copied from the AWS documentation or from the ASL specification.
- All committed test data uses an invented English domain (a film archive that takes in batches of reels). A private definition from a closed-source project lies git-excluded in the repository root. Never commit it, never copy names or comments from it, never name it in committed files.
- The repository is hosted at github.com/saigyo/aslan. A GitHub Actions workflow builds and tests every push to `main` and every pull request. Every test must pass on the runner as well as locally, so no test may depend on files outside the repository. Pushing is always the human partner's decision.
- Every commit message ends with a `Co-Authored-By` trailer that names the Claude model that made the commit, in the form `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`. Use your own model's name: an implementer running on a different model writes that model's name, not the one in this example. The commit commands in this plan carry the trailer as a second `-m` argument with the placeholder `<your model name>`; replace the placeholder. A commit without the trailer is not finished: amend it before moving on.
- Message wording, icons and exact colours are the implementer's choice. Tests assert problem identifiers, elements and structure, never message text.

## Review Focus

Inputs the spec implies but does not spell out. Each has a test in the task named.

1. **A half-typed file** (key without value, wrong indentation, empty file, root that is a list or a scalar): no exception anywhere, and whatever can be read still works. Task 3 and Task 5.
2. **Cyclic or self-referencing YAML aliases and merge keys**: the tree stops instead of recursing forever. Task 3.
3. **A non-ASL YAML or JSON file** such as `docker-compose.yaml` or `package.json`: no completion items, no findings, no highlighting, default structure view. Task 2, re-asserted in Tasks 7, 10, 14 and 15.
4. **Placeholders in structural positions** (as `Type`, as a transition target, as a state name key, as a whole state): no finding, and reachability is not judged for that state machine. Task 7 and Task 8.
5. **A multi-document YAML file** (`---` separators): only the first document is read, nothing fails. Task 3.

## File Structure

All paths are under `src/main/kotlin/io/github/saigyo/aslan/` unless they start with `src/` or are build files.

| Path | Responsibility |
|---|---|
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `src/main/resources/META-INF/plugin.xml` | Build and plugin descriptor |
| `.github/workflows/build.yml` | CI: test, build and compatibility check on pushes to `main` and on pull requests |
| `README.md`, `NOTICE` | Project introduction, status, build instructions; licence notice |
| `files/AslFiles.kt` | Decides whether a file is an ASL file |
| `settings/AslSettings.kt`, `settings/AslConfigurable.kt` | Extra file name patterns, settings page |
| `tree/AslNode.kt` | Format-independent value tree types |
| `tree/YamlTree.kt`, `tree/JsonTree.kt`, `tree/AslTree.kt` | Build the tree from YAML or JSON PSI |
| `tree/Placeholders.kt` | Finds `${name}` placeholders in text |
| `spec/SpecModel.kt` | Types of the ASL reference data |
| `spec/AslSpec.kt` | Lookup API over the data |
| `spec/data/*.kt` | The data: one file per group of structures |
| `model/AslDocument.kt` | State machines, states, transitions |
| `model/SchemaIndex.kt` | Which ASL structure and field each tree object and key is |
| `model/AslModel.kt` | Builds and caches `AslDocument` per file |
| `check/AslProblem.kt`, `check/AslFinding.kt` | Catalogue of problems, finding type |
| `check/AslChecker.kt` | Runs all checks, cached per file |
| `check/StructureChecks.kt`, `check/TransitionChecks.kt`, `check/WarningChecks.kt` | The checks |
| `check/AslInspections.kt` | One thin inspection per problem, so each check has its own switch and severity |
| `completion/AslCompletionContributor.kt`, `completion/CompletionContext.kt`, `completion/StateTemplates.kt` | Completion |
| `docs/AslDocumentation.kt`, `docs/AslDocumentationProvider.kt` | Quick documentation |
| `refs/AslStateReference.kt`, `refs/AslReferenceContributor.kt`, `refs/AslReferencesSearcher.kt` | State-name references and search |
| `structure/AslStructureViewFactories.kt`, `structure/AslStructureViewModel.kt`, `structure/AslStructureElements.kt` | Structure view |
| `highlight/AslHighlighting.kt`, `highlight/AslAnnotator.kt`, `highlight/AslColorSettingsPage.kt`, `highlight/PlaceholderUsagesHandlerFactory.kt` | Semantic highlighting, placeholders |
| `AslIcons.kt`, `src/main/resources/icons/*.svg` | Icons |
| `src/test/kotlin/io/github/saigyo/aslan/AslTestCase.kt` | Test base class |
| `src/test/testData/clean/*.asl.*` | Definitions that must show no findings |

---

### Task 1: Build scaffold, plugin descriptor, CI workflow and README

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `src/main/resources/META-INF/plugin.xml`, `.github/workflows/build.yml`, `README.md`
- Modify: `.gitignore`
- Test: `src/test/kotlin/io/github/saigyo/aslan/AslTestCase.kt`, `src/test/kotlin/io/github/saigyo/aslan/PluginLoadsTest.kt`

**Interfaces:**
- Produces: `abstract class AslTestCase : BasePlatformTestCase()` with `fun yaml(text: String, name: String = "test.asl.yaml"): PsiFile` and `fun json(text: String, name: String = "test.asl.json"): PsiFile`. Both call `myFixture.configureByText(name, text.trimIndent())`. `getTestDataPath()` returns `"src/test/testData"`.

- [ ] **Step 1: Copy the build skeleton from the official template**

Clone `https://github.com/JetBrains/intellij-platform-plugin-template` (branch `main`) into a temporary directory outside the repository. Copy `gradlew`, `gradlew.bat`, `gradle/wrapper/`, `settings.gradle.kts`, `build.gradle.kts` and `gradle.properties`. Do not copy its sources, workflows, changelog or README.

- [ ] **Step 2: Set the project values**

- `settings.gradle.kts`: `rootProject.name = "aslan"`; `org.jetbrains.intellij.platform.settings` version `2.20.1`; remove the changelog plugin.
- `gradle.properties`: `group = io.github.saigyo.aslan`, `version = 0.1.0`; remove `pluginRepositoryUrl`.
- `build.gradle.kts`: the template's file is short and has none of the following blocks, so add them. Remove the changelog plugin. Add `kotlin { jvmToolchain(25) }`. In `dependencies { intellijPlatform { ... } }` use `intellijIdea("2026.2.3")`, `bundledPlugins("org.jetbrains.plugins.yaml", "com.intellij.modules.json", "intellij.structureView.plugin")`, `testFramework(TestFrameworkType.Platform)`, `pluginVerifier()`; remove `testBundledPlugins(...)`. Add `intellijPlatform { pluginConfiguration { ideaVersion { sinceBuild = "262" } } }` without an `untilBuild`. Add `intellijPlatform { pluginVerification { ides { ... } } }` listing IntelliJ IDEA `2026.2.3` and `2026.3`. In `tasks.test` pass the Gradle property `aslan.privateFixtures` through as a system property of the same name (empty string when unset).
- The structure view plugin ID is `intellij.structureView.plugin` in 2026.2; 2026.3 renames it to `com.intellij.structureView` and keeps the old ID as an alias, so the 2026.2 ID works on both. If Gradle cannot resolve a bundled plugin ID, run `./gradlew printBundledPlugins` and use the ID it lists. For DSL details follow https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html.
- `.gitignore`: add `.gradle/`, `build/`, `.intellijPlatform/`, `.kotlin/`, `.idea/`, `/private-fixtures/`. Keep the existing `.superpowers/` line.

- [ ] **Step 3: Write `plugin.xml`**

`<id>io.github.saigyo.aslan</id>`, `<name>Aslan</name>`, `<vendor url="https://github.com/saigyo/aslan">saigyo</vendor>`, a two-sentence description saying it supports Amazon States Language definitions for AWS Step Functions in YAML and JSON, and `<depends>` on `com.intellij.modules.platform`, `org.jetbrains.plugins.yaml`, `com.intellij.modules.json`, `intellij.structureView.plugin`. An empty `<extensions defaultExtensionNs="com.intellij">` block that later tasks fill.

- [ ] **Step 4: Write the test base class and the failing smoke test**

```kotlin
class PluginLoadsTest : AslTestCase() {
    fun `test plugin is loaded`() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.saigyo.aslan"))
        assertNotNull(plugin)
        assertEquals("Aslan", plugin!!.name)
    }
}
```

- [ ] **Step 5: Run the build**

Run: `./gradlew test --tests "io.github.saigyo.aslan.PluginLoadsTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed. Then run `./gradlew buildPlugin` and expect `build/distributions/aslan-0.1.0.zip`.

- [ ] **Step 6: Write the CI workflow `.github/workflows/build.yml`**

```yaml
name: Build

on:
  push:
    branches: [main]
  pull_request:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  test:
    name: Test and build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: zulu
          java-version: 25
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew check buildPlugin
      - uses: actions/upload-artifact@v7
        if: failure()
        with:
          name: test-reports
          path: build/reports/tests
      - uses: actions/upload-artifact@v7
        with:
          name: plugin-distribution
          path: build/distributions/*.zip

  verify:
    name: Verify IDE compatibility
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: zulu
          java-version: 25
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew verifyPlugin
      - uses: actions/upload-artifact@v7
        if: failure()
        with:
          name: plugin-verifier-reports
          path: build/reports/pluginVerifier
```

The workflow runs on every push to `main` and on every pull request. It needs no secrets. The private file test finds nothing on the runner and passes. If the verify job later fails for lack of disk space, add the `jlumbroso/free-disk-space@v2.0.0` step that the official template uses before checkout.

- [ ] **Step 7: Check the workflow file**

Run: `python3 -c 'import yaml,sys; d=yaml.safe_load(open(".github/workflows/build.yml")); print(sorted(d["jobs"]))'`
Expected: `['test', 'verify']`. If `actionlint` is installed, also run `actionlint .github/workflows/build.yml` and expect no output. The first real run happens when the branch is pushed; pushing is the human partner's decision (see Task 16).

- [ ] **Step 8: Write the first `README.md`**

Sections, in this order:

1. Title `Aslan` and one sentence: editor support for Amazon States Language (AWS Step Functions) definitions in JetBrains IDEs.
2. The build badge: `[![Build](https://github.com/saigyo/aslan/actions/workflows/build.yml/badge.svg)](https://github.com/saigyo/aslan/actions/workflows/build.yml)`.
3. **Status**: in development, not yet published; stage 1 of 4 (ASL-aware editing) in progress; the other stages are JSONata support, variable navigation and a live diagram.
4. **Requirements**: IntelliJ IDEA or another JetBrains IDE with YAML support, version 2026.2 or newer; Java 25 to build.
5. **Building**: `./gradlew test` (tests), `./gradlew buildPlugin` (plugin zip in `build/distributions`), `./gradlew runIde` (sandbox IDE with the plugin), `./gradlew verifyPlugin` (compatibility check).
6. **Design**: links to the design document and the plans under `docs/superpowers/`.
7. **Licence**: Apache-2.0.

Task 16 adds the feature list once the features exist. Do not describe features that are not built yet as available.

- [ ] **Step 9: Commit**

```bash
git add .gitignore .github README.md settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle src
git commit -m "build: scaffold the Aslan plugin project with CI workflow and README" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 2: ASL file recognition and settings

**Files:**
- Create: `files/AslFiles.kt`, `settings/AslSettings.kt`, `settings/AslConfigurable.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/files/AslFilesTest.kt`

**Interfaces:**
- Produces:
  - `object AslFiles { fun isAslFileName(name: String): Boolean; fun isAslFile(file: PsiFile): Boolean }`. `isAslFile` uses `file.originalFile.name` and also requires the file's language to be YAML or JSON.
  - `class AslSettings : PersistentStateComponent<AslSettings.State>, ModificationTracker` as an application service, with `var extraPatterns: List<String>` and `companion object { fun getInstance(): AslSettings }`. State name `AslanSettings`, storage `aslan.xml`. Setting `extraPatterns` increments the modification count.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AslFilesTest : AslTestCase() {
    override fun tearDown() { try { AslSettings.getInstance().extraPatterns = emptyList() } finally { super.tearDown() } }

    fun `test built-in suffixes`() {
        assertTrue(AslFiles.isAslFileName("intake.asl.yaml"))
        assertTrue(AslFiles.isAslFileName("intake.asl.yml"))
        assertTrue(AslFiles.isAslFileName("intake.asl.json"))
        assertTrue(AslFiles.isAslFileName("INTAKE.ASL.YAML"))
        assertFalse(AslFiles.isAslFileName("intake.yaml"))
        assertFalse(AslFiles.isAslFileName("asl.yaml"))
        assertFalse(AslFiles.isAslFileName("intake.asl.txt"))
    }
    fun `test extra patterns from settings`() {
        AslSettings.getInstance().extraPatterns = listOf("*.statemachine.yaml", "workflow-?.json")
        assertTrue(AslFiles.isAslFileName("intake.statemachine.yaml"))
        assertTrue(AslFiles.isAslFileName("workflow-a.json"))
        assertFalse(AslFiles.isAslFileName("workflow-ab.json"))
    }
    fun `test changing patterns advances the modification count`() {
        val before = AslSettings.getInstance().modificationCount
        AslSettings.getInstance().extraPatterns = listOf("*.sm.yaml")
        assertTrue(AslSettings.getInstance().modificationCount > before)
    }
    fun `test psi files`() {
        assertTrue(AslFiles.isAslFile(yaml("StartAt: A")))
        assertFalse(AslFiles.isAslFile(yaml("services: {}", "docker-compose.yaml")))
        assertFalse(AslFiles.isAslFile(myFixture.configureByText("notes.asl.txt", "StartAt: A")))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.files.AslFilesTest"`
Expected: compilation fails, `AslFiles` unresolved.

- [ ] **Step 3: Implement `AslFiles` and `AslSettings`**

Suffix matching is case-insensitive. An extra pattern is a glob over the whole file name with `*` and `?` only (`com.intellij.util.PatternUtil.fromMask`). Blank patterns are ignored. Register the service with `<applicationService serviceImplementation="...AslSettings"/>`.

- [ ] **Step 4: Implement `AslConfigurable`**

Register as `<applicationConfigurable parentId="language" instance="...AslConfigurable" id="io.github.saigyo.aslan.settings" displayName="Amazon States Language"/>`. One multi-line text area labelled "Additional file name patterns", one pattern per line, with a comment line giving `*.statemachine.yaml` as example. On apply, store the non-blank trimmed lines and restart the daemon code analyzer of every open project.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.files.AslFilesTest"`
Expected: 4 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src && git commit -m "feat: recognise ASL files by name, with extra patterns in settings" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 3: Format-independent tree and placeholders

**Files:**
- Create: `tree/AslNode.kt`, `tree/YamlTree.kt`, `tree/JsonTree.kt`, `tree/AslTree.kt`, `tree/Placeholders.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/tree/AslTreeTest.kt`, `src/test/kotlin/io/github/saigyo/aslan/tree/PlaceholdersTest.kt`

**Interfaces:**
- Produces:

```kotlin
sealed interface AslNode {
    val psi: PsiElement      // where the content is written; for an alias, the anchored value
    val usePsi: PsiElement   // where it is used; the alias element for an alias, else same as psi
}
class AslObject(override val psi: PsiElement, override val usePsi: PsiElement, val entries: List<AslEntry>) : AslNode {
    operator fun get(key: String): AslEntry?          // first entry with that key
    val anchorPsi: PsiElement                         // first entry's keyPsi, or psi when there are no entries
}
class AslEntry(val key: String, val keyPsi: PsiElement, val entryPsi: PsiElement, val value: AslNode, val viaMerge: Boolean)
class AslArray(override val psi: PsiElement, override val usePsi: PsiElement, val items: List<AslNode>) : AslNode
class AslScalar(override val psi: PsiElement, override val usePsi: PsiElement, val type: ScalarType, val text: String,
                val ambiguous: Boolean = false) : AslNode   // ambiguous: YAML 1.1 and YAML 1.2 type this plain scalar differently
class AslUnresolved(override val psi: PsiElement, override val usePsi: PsiElement) : AslNode   // an alias that does not resolve, or resolves into itself
enum class ScalarType { STRING, NUMBER, BOOLEAN, NULL }

object AslTree { fun root(file: PsiFile): AslNode? }   // null for an empty file or an unsupported language

object Placeholders {
    fun ranges(text: String): List<IntRange>           // each `${...}` occurrence
    fun contains(text: String): Boolean
}
```

- `entryPsi` is the `YAMLKeyValue` or `JsonProperty`; `keyPsi` is its key element. `AslScalar.text` is the decoded value (`YAMLScalar.getTextValue()`, unescaped JSON string, raw text for JSON numbers and literals).
- An entry always has a value. A key with nothing after it (`Next:` in YAML, an unfinished JSON property) gets a `NULL` scalar whose `psi` and `usePsi` are the `keyPsi`. This is why a problem with an empty value ends up reported on the key.
- YAML scalar typing: a quoted or block scalar is always `STRING`. A plain scalar is `NULL` for empty, `~`, `null`, `Null`, `NULL`; `BOOLEAN` for `true`/`false` in the three YAML 1.2 capitalisations; `NUMBER` if it matches a YAML 1.2 core integer or float (including `0x`, `0o`, `.inf`, `.nan` forms); else `STRING`.
- `ambiguous` is true for a plain scalar that YAML 1.1 and YAML 1.2 type differently. It is not known which YAML version the tools in front of AWS apply, so no check may judge the kind of such a value. That covers the YAML 1.1 booleans (`y`, `n`, `yes`, `no`, `on`, `off` in their capitalisations), numbers with `_` separators, sexagesimal numbers such as `1:30`, octal with a leading `0` such as `010`, binary `0b...`, and the YAML 1.2 octal form `0o...`.
- A block scalar with clip chomping (`>` or `|` without `-` or `+`) that ends at the end of the file with no final line break has no trailing line break in real YAML, but `getTextValue()` appends one. Remove it in that case.
- A placeholder is `${` + one or more of letters, digits, `_`, `-`, `.`, `:`, `,` + `}`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AslTreeTest : AslTestCase() {
    private fun root(text: String) = AslTree.root(yaml(text)) as AslObject
    private fun AslNode?.scalar() = this as AslScalar

    fun `test yaml mapping sequence and scalar types`() {
        val r = root("""
            Comment: intake
            TimeoutSeconds: 900
            Enabled: true
            Nothing:
            Quoted: '900'
            Yes: yes
            List: [a, 2]
        """)
        assertEquals(listOf("Comment", "TimeoutSeconds", "Enabled", "Nothing", "Quoted", "Yes", "List"), r.entries.map { it.key })
        assertEquals(ScalarType.STRING, r["Comment"]!!.value.scalar().type)
        assertEquals(ScalarType.NUMBER, r["TimeoutSeconds"]!!.value.scalar().type)
        assertEquals(ScalarType.BOOLEAN, r["Enabled"]!!.value.scalar().type)
        assertEquals(ScalarType.NULL, r["Nothing"]!!.value.scalar().type)
        assertEquals(ScalarType.STRING, r["Quoted"]!!.value.scalar().type)
        assertEquals(ScalarType.STRING, r["Yes"]!!.value.scalar().type)
        assertEquals(2, (r["List"]!!.value as AslArray).items.size)
        assertEquals("Comment", r["Comment"]!!.keyPsi.text)
    }
    fun `test folded scalar text is the decoded value`() {
        val r = root("""
            A: >-
              {% ${'$'}x
              + 1 %}
            B: >
              {% 1 %}
            C: 1
        """)
        assertEquals("{% \$x + 1 %}", r["A"]!!.value.scalar().text)
        assertEquals("{% 1 %}\n", r["B"]!!.value.scalar().text)
    }
    fun `test alias resolves to the anchored value and keeps the use site`() {
        val r = root("""
            First:
              - &noRetry
                MaxAttempts: 0
            Second: [*noRetry]
        """)
        val defined = (r["First"]!!.value as AslArray).items.single() as AslObject
        val used = (r["Second"]!!.value as AslArray).items.single() as AslObject
        assertEquals("0", used["MaxAttempts"]!!.value.scalar().text)
        assertSame(defined.psi, used.psi)
        assertEquals("*noRetry", used.usePsi.text)
    }
    fun `test merge key contributes entries marked viaMerge`() {
        val r = root("""
            Base: &base
              IntervalSeconds: 2
            Derived:
              <<: *base
              MaxAttempts: 3
        """)
        val d = r["Derived"]!!.value as AslObject
        assertEquals(setOf("IntervalSeconds", "MaxAttempts"), d.entries.map { it.key }.toSet())
        assertTrue(d["IntervalSeconds"]!!.viaMerge)
        assertFalse(d["MaxAttempts"]!!.viaMerge)
        assertNull(d["<<"])
    }
    fun `test self referencing alias does not recurse forever`() {
        val r = root("""
            Loop: &loop
              Inner: *loop
        """)
        assertNotNull(r["Loop"])   // reaching this line is the assertion
        assertTrue((r["Loop"]!!.value as AslObject)["Inner"]!!.value is AslUnresolved)
    }
    fun `test alias without an anchor is unresolved not null`() =
        assertTrue(root("Retry: *missing")["Retry"]!!.value is AslUnresolved)
    fun `test empty value is a null scalar at the key`() {
        val e = root("Next:\nEnd: true")["Next"]!!
        assertEquals(ScalarType.NULL, e.value.scalar().type)
        assertSame(e.keyPsi, e.value.psi)
    }
    fun `test scalars typed differently by yaml 1_1 and 1_2 are ambiguous`() {
        val r = root("A: yes\nB: Off\nC: 1_000\nD: 010\nE: 0o14\nF: 1:30\nG: true\nH: 12\nI: 'yes'\nJ: text")
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), r.entries.filter { it.value.scalar().ambiguous }.map { it.key })
    }
    fun `test clipped block scalar at end of file without line break`() {
        val r = AslTree.root(myFixture.configureByText("test.asl.yaml", "A: >\n  {% 1 %}")) as AslObject
        assertEquals("{% 1 %}", r["A"]!!.value.scalar().text)
    }
    fun `test incomplete and odd files do not throw`() {
        assertNull(AslTree.root(yaml("")))
        assertTrue(AslTree.root(yaml("- a\n- b")) is AslArray)
        assertTrue(AslTree.root(yaml("just text")) is AslScalar)
        AslTree.root(yaml("StartAt:\nStates:\n  A:\n    Type"))          // must not throw
        AslTree.root(yaml("StartAt: A\n   States: oops\n Type: x"))        // must not throw
    }
    fun `test only the first yaml document is read`() {
        val r = root("StartAt: A\n---\nStartAt: B")
        assertEquals("A", r["StartAt"]!!.value.scalar().text)
    }
    fun `test json objects arrays and literals`() {
        val r = AslTree.root(json("""{"StartAt": "A", "TimeoutSeconds": 900, "Flag": true, "None": null, "L": [1]}""")) as AslObject
        assertEquals("A", r["StartAt"]!!.value.scalar().text)
        assertEquals(ScalarType.STRING, r["StartAt"]!!.value.scalar().type)
        assertEquals(ScalarType.NUMBER, r["TimeoutSeconds"]!!.value.scalar().type)
        assertEquals(ScalarType.BOOLEAN, r["Flag"]!!.value.scalar().type)
        assertEquals(ScalarType.NULL, r["None"]!!.value.scalar().type)
        assertEquals("StartAt", r["StartAt"]!!.key)
    }
}

class PlaceholdersTest : TestCase() {
    fun `test ranges`() {
        assertEquals(listOf(0..13), Placeholders.ranges("\${OrdersTable}"))
        assertEquals(listOf(4..9, 11..16), Placeholders.ranges("arn:\${A.b}/\${c-d}"))
        assertEquals(emptyList<IntRange>(), Placeholders.ranges("\${} \${a b} \$x {y} \${\"k\": 1}"))
        assertTrue(Placeholders.contains("x \${Stage} y"))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.tree.*"`
Expected: compilation fails, `AslTree` unresolved.

- [ ] **Step 3: Implement the tree types, `YamlTree`, `JsonTree`, `AslTree` and `Placeholders`**

YAML: the root is the top-level value of the first `YAMLDocument`. An alias resolves through `alias.reference.resolve()` (a `YAMLAnchor`) and that anchor's `markedValue`. A `<<` entry whose value is an alias to a mapping, or a list of such aliases, is replaced by that mapping's entries with `viaMerge = true`; an explicit key in the mapping wins over a merged one. Guard alias and merge resolution with a set of anchors currently being resolved; on re-entry yield an `AslUnresolved` at the alias. An alias that resolves to nothing is an `AslUnresolved` too. JSON: `JsonObject`, `JsonArray`, `JsonStringLiteral`, `JsonNumberLiteral`, `JsonBooleanLiteral`, `JsonNullLiteral`; anything else is a `STRING` scalar of its text.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.tree.*"`
Expected: 13 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat: format-independent value tree over YAML and JSON" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 4: ASL reference data

**Files:**
- Create: `spec/SpecModel.kt`, `spec/AslSpec.kt`, `spec/data/StateMachineData.kt`, `spec/data/StateData.kt`, `spec/data/ChoiceRuleData.kt`, `spec/data/ErrorHandlingData.kt`, `spec/data/MapData.kt`, `spec/data/ErrorNames.kt`, `spec/data/ResourceArns.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/spec/AslSpecTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class QueryLanguage(val text: String) { JSONPATH("JSONPath"), JSONATA("JSONata") }
enum class Mode { BOTH, JSONATA, JSONPATH }   // fun allows(ql: QueryLanguage): Boolean
enum class StructureId { STATE_MACHINE,
    STATE,   // a state whose type is missing, unknown or a placeholder: only the fields every state has
    TASK, PASS, CHOICE, WAIT, SUCCEED, FAIL, PARALLEL, MAP,
    CHOICE_RULE, RETRIER, CATCHER, BRANCH, ITEM_PROCESSOR, PROCESSOR_CONFIG,
    ITEM_READER, READER_CONFIG, ITEM_BATCHER, RESULT_WRITER, WRITER_CONFIG }

sealed interface ValueKind {
    data object Text : ValueKind;  data object Integer : ValueKind;  data object Number : ValueKind;  data object Bool : ValueKind
    data class OneOf(val values: List<NamedDoc>) : ValueKind
    data object StateName : ValueKind      // a transition target
    data object StatesMap : ValueKind      // mapping of state name to state
    data object ErrorNames : ValueKind     // list of error name strings
    data object ResourceArn : ValueKind
    data object FreeForm : ValueKind       // any data; never checked, never descended into
    data class Struct(val id: StructureId) : ValueKind
    data class ListOf(val element: ValueKind) : ValueKind
}
data class NamedDoc(val name: String, val description: String, val docUrl: String? = null)
data class FieldSpec(val name: String, val kind: ValueKind, val mode: Mode = Mode.BOTH, val required: Boolean = false,
    val jsonata: Boolean = false,          // AWS documents that this field accepts a `{% %}` string in a JSONata state
    val nullable: Boolean = false, val min: Double? = null, val max: Double? = null,
    val deprecated: Boolean = false, val description: String, val docUrl: String)
data class StructureSpec(val id: StructureId, val fields: List<FieldSpec>,
    val oneOfRequired: List<Set<String>> = emptyList(),   // at least one field of each set must be present
    val exclusive: List<Set<String>> = emptyList()) {     // at most one field of each set may be present
    fun field(name: String): FieldSpec?
    fun fieldsFor(ql: QueryLanguage): List<FieldSpec>
}
data class StateTypeSpec(val name: String, val structure: StructureId, val needsTransition: Boolean, val terminal: Boolean,
    val description: String, val docUrl: String)

object AslSpec {
    fun structure(id: StructureId): StructureSpec
    val stateTypes: List<StateTypeSpec>              // in the order Task, Pass, Choice, Wait, Succeed, Fail, Parallel, Map
    fun stateType(name: String): StateTypeSpec?
    val errorNames: List<NamedDoc>
    val resourceArns: List<NamedDoc>
    fun definesFieldAnywhere(name: String): Boolean
}
```

**The data.** Sources to work from, in this order: the ASL specification https://states-language.net/spec.html, then the Step Functions developer guide pages under https://docs.aws.amazon.com/step-functions/latest/dg/ (`statemachine-structure`, `state-task`, `state-pass`, `state-choice`, `state-wait`, `state-succeed`, `state-fail`, `state-parallel`, `state-map-inline`, `state-map-distributed`, `input-output-itemreader`, `input-output-resultwriter`, `concepts-error-handling`, `workflow-variables`, `transforming-data`, `integrate-optimized`). Where the sources disagree on whether a field is allowed, required or bound to one query language, take the more permissive reading: a wrongly strict entry produces a false error, a wrongly lenient one only a missed finding. Known cases: the specification requires `Resource` in a ResultWriter but the guide documents a ResultWriter with only `WriterConfig`, so it is optional; the guide labels `Assign` on Pass "JSONata only" and `ItemSelector` on a Distributed Map "JSONPath only", and both are allowed in both. Every `docUrl` points at the developer guide page for that structure. Field modes (`P` = JSONPath only, `A` = JSONata only, none = both); `*` = required; `j` = `jsonata = true`; `n` = nullable:

| Structure | Fields |
|---|---|
| STATE_MACHINE | `StartAt*` StateName, `States*` StatesMap, `Comment` Text, `Version` Text, `TimeoutSeconds` Integer min 1, `QueryLanguage` OneOf(JSONPath, JSONata) |
| every state, and STATE | `Type*` OneOf(the eight types), `Comment` Text, `QueryLanguage`. The STATE structure has exactly these three. |
| Task, Parallel, Map, Pass, Wait | `Next` StateName, `End` Bool; exclusive {Next, End} |
| every state except Succeed, Fail | `Assign` FreeForm |
| every state except Fail | `Output` FreeForm A, `InputPath` Text P n, `OutputPath` Text P n |
| Task, Parallel, Map, Pass | `Parameters` FreeForm P, `ResultPath` Text P n |
| Task, Parallel, Map | `ResultSelector` FreeForm P, `Retry` ListOf(Struct RETRIER), `Catch` ListOf(Struct CATCHER) |
| TASK | `Resource*` ResourceArn, `Arguments` FreeForm A, `TimeoutSeconds` Integer j min 1, `HeartbeatSeconds` Integer j min 1, `TimeoutSecondsPath` Text P, `HeartbeatSecondsPath` Text P, `Credentials` FreeForm; exclusive {TimeoutSeconds, TimeoutSecondsPath}, {HeartbeatSeconds, HeartbeatSecondsPath} |
| PASS | `Result` FreeForm P |
| CHOICE | `Choices*` ListOf(Struct CHOICE_RULE), `Default` StateName |
| WAIT | `Seconds` Integer j min 0, `Timestamp` Text j, `SecondsPath` Text P, `TimestampPath` Text P; oneOfRequired and exclusive {Seconds, Timestamp, SecondsPath, TimestampPath} |
| FAIL | `Error` Text j, `Cause` Text j, `ErrorPath` Text P, `CausePath` Text P; exclusive {Error, ErrorPath}, {Cause, CausePath} |
| PARALLEL | `Branches*` ListOf(Struct BRANCH), `Arguments` FreeForm A |
| MAP | `ItemProcessor` Struct ITEM_PROCESSOR, `Iterator` Struct ITEM_PROCESSOR deprecated, `Items` FreeForm A, `ItemsPath` Text P, `ItemSelector` FreeForm, `ItemReader` Struct, `ItemBatcher` Struct, `ResultWriter` Struct, `MaxConcurrency` Integer j min 0, `MaxConcurrencyPath` Text P, `ToleratedFailurePercentage` Number j min 0 max 100, `ToleratedFailurePercentagePath` Text P, `ToleratedFailureCount` Integer j min 0, `ToleratedFailureCountPath` Text P, `Label` Text; `Parameters` is deprecated here; oneOfRequired and exclusive {ItemProcessor, Iterator}; exclusive for each field and its `Path` twin |
| CHOICE_RULE | `Next` StateName, `Comment` Text, `Assign` FreeForm, `Condition` Bool A j, `Output` FreeForm A; P only: `Variable` Text, `And` and `Or` ListOf(Struct CHOICE_RULE), `Not` Struct CHOICE_RULE, and the 39 comparison operators. Operator kinds: names ending in `Path` are Text; `String...` and `Timestamp...` are Text; `Numeric...` are Number; `BooleanEquals` and the six `Is...` are Bool; `StringMatches` is Text |
| RETRIER | `ErrorEquals*` ErrorNames, `IntervalSeconds` Integer min 1, `MaxAttempts` Integer min 0, `BackoffRate` Number min 1, `MaxDelaySeconds` Integer min 1, `JitterStrategy` OneOf(FULL, NONE), `Comment` Text |
| CATCHER | `ErrorEquals*` ErrorNames, `Next*` StateName, `Assign` FreeForm, `Output` FreeForm A, `ResultPath` Text P n, `Comment` Text |
| BRANCH | `StartAt*` StateName, `States*` StatesMap, `Comment` Text |
| ITEM_PROCESSOR | as BRANCH plus `ProcessorConfig` Struct |
| PROCESSOR_CONFIG | `Mode` OneOf(INLINE, DISTRIBUTED), `ExecutionType` OneOf(STANDARD, EXPRESS) |
| ITEM_READER | `Resource*` ResourceArn, `Arguments` FreeForm A, `Parameters` FreeForm P, `ReaderConfig` Struct |
| READER_CONFIG | `InputType` OneOf(CSV, JSON, JSONL, PARQUET, MANIFEST), `CSVHeaderLocation` OneOf(FIRST_ROW, GIVEN), `CSVHeaders` ListOf(Text), `CSVDelimiter` Text, `MaxItems` Integer j min 0, `MaxItemsPath` Text P, `ItemsPointer` Text, `Transformation` Text, `ManifestType` Text |
| ITEM_BATCHER | `MaxItemsPerBatch` Integer j min 1, `MaxItemsPerBatchPath` Text P, `MaxInputBytesPerBatch` Integer j min 1, `MaxInputBytesPerBatchPath` Text P, `BatchInput` FreeForm |
| RESULT_WRITER | `Resource` ResourceArn, `Arguments` FreeForm A, `Parameters` FreeForm P, `WriterConfig` Struct |
| WRITER_CONFIG | `Transformation` OneOf(NONE, COMPACT, FLATTEN), `OutputType` OneOf(JSON, JSONL) |

State types: Task, Pass, Wait, Parallel, Map need a transition; Succeed and Fail are terminal; Choice is neither.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AslSpecTest : TestCase() {
    private fun field(s: StructureId, name: String) = AslSpec.structure(s).field(name) ?: error("$s.$name missing")

    fun `test eight state types in order`() {
        assertEquals(listOf("Task", "Pass", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map"), AslSpec.stateTypes.map { it.name })
        assertTrue(AslSpec.stateType("Task")!!.needsTransition)
        assertTrue(AslSpec.stateType("Fail")!!.terminal)
        assertFalse(AslSpec.stateType("Choice")!!.needsTransition)
        assertNull(AslSpec.stateType("task"))
    }
    fun `test task fields and modes`() {
        assertTrue(field(TASK, "Resource").required)
        assertEquals(Mode.JSONATA, field(TASK, "Arguments").mode)
        assertEquals(Mode.JSONPATH, field(TASK, "Parameters").mode)
        assertEquals(Mode.JSONPATH, field(TASK, "ResultPath").mode)
        assertTrue(field(TASK, "ResultPath").nullable)
        assertEquals(Mode.BOTH, field(TASK, "Assign").mode)
        assertEquals(ValueKind.FreeForm, field(TASK, "Arguments").kind)
        assertTrue(field(TASK, "TimeoutSeconds").jsonata)
        assertEquals(ValueKind.ListOf(ValueKind.Struct(RETRIER)), field(TASK, "Retry").kind)
    }
    fun `test fields absent where the specification forbids them`() {
        assertNull(AslSpec.structure(SUCCEED).field("Next"))
        assertNull(AslSpec.structure(SUCCEED).field("Assign"))
        assertNull(AslSpec.structure(FAIL).field("Output"))
        assertNull(AslSpec.structure(CHOICE).field("Next"))
        assertNull(AslSpec.structure(PASS).field("Retry"))
        assertNull(AslSpec.structure(WAIT).field("Parameters"))
    }
    fun `test fieldsFor filters by query language`() {
        val jsonata = AslSpec.structure(TASK).fieldsFor(QueryLanguage.JSONATA).map { it.name }
        assertTrue("Arguments" in jsonata && "Assign" in jsonata)
        assertFalse("Parameters" in jsonata || "InputPath" in jsonata)
    }
    fun `test choice rule`() {
        assertEquals(Mode.JSONATA, field(CHOICE_RULE, "Condition").mode)
        assertEquals(Mode.JSONPATH, field(CHOICE_RULE, "Variable").mode)
        assertEquals(39, AslSpec.structure(CHOICE_RULE).fields.count { it.mode == Mode.JSONPATH && it.name !in setOf("Variable", "And", "Or", "Not") })
        assertEquals(ValueKind.Number, field(CHOICE_RULE, "NumericLessThan").kind)
        assertEquals(ValueKind.Text, field(CHOICE_RULE, "NumericLessThanPath").kind)
        assertEquals(ValueKind.Bool, field(CHOICE_RULE, "IsPresent").kind)
    }
    fun `test ranges groups and enumerations`() {
        assertEquals(0.0, field(RETRIER, "MaxAttempts").min)
        assertEquals(1.0, field(RETRIER, "BackoffRate").min)
        assertEquals(100.0, field(MAP, "ToleratedFailurePercentage").max)
        assertEquals(listOf("FULL", "NONE"), (field(RETRIER, "JitterStrategy").kind as ValueKind.OneOf).values.map { it.name })
        assertTrue(setOf("Seconds", "Timestamp", "SecondsPath", "TimestampPath") in AslSpec.structure(WAIT).oneOfRequired)
        assertTrue(setOf("Next", "End") in AslSpec.structure(TASK).exclusive)
        assertTrue(setOf("ItemProcessor", "Iterator") in AslSpec.structure(MAP).oneOfRequired)
    }
    fun `test every field has a description and a documentation link`() {
        for (id in StructureId.entries) for (f in AslSpec.structure(id).fields) {
            assertTrue("$id.${f.name} description", f.description.length >= 20)
            assertTrue("$id.${f.name} docUrl", f.docUrl.startsWith("https://docs.aws.amazon.com/step-functions/"))
        }
    }
    fun `test error names and resources`() {
        val errors = AslSpec.errorNames.map { it.name }
        assertTrue(errors.containsAll(listOf("States.ALL", "States.Timeout", "States.TaskFailed", "States.Permissions",
            "States.HeartbeatTimeout", "States.NoChoiceMatched", "States.QueryEvaluationError", "States.BranchFailed")))
        val arns = AslSpec.resourceArns.map { it.name }
        assertTrue(arns.containsAll(listOf("arn:aws:states:::lambda:invoke", "arn:aws:states:::lambda:invoke.waitForTaskToken",
            "arn:aws:states:::dynamodb:putItem", "arn:aws:states:::sns:publish", "arn:aws:states:::events:putEvents",
            "arn:aws:states:::states:startExecution.sync:2")))
    }
    fun `test permissive readings where the sources disagree`() {
        assertFalse(field(RESULT_WRITER, "Resource").required)
        assertEquals(Mode.BOTH, field(PASS, "Assign").mode)
        assertEquals(Mode.BOTH, field(MAP, "ItemSelector").mode)
    }
    fun `test generic state structure`() =
        assertEquals(setOf("Type", "Comment", "QueryLanguage"), AslSpec.structure(STATE).fields.map { it.name }.toSet())
    fun `test definesFieldAnywhere`() {
        assertTrue(AslSpec.definesFieldAnywhere("Next"))
        assertTrue(AslSpec.definesFieldAnywhere("WriterConfig"))
        assertFalse(AslSpec.definesFieldAnywhere("Retries"))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.spec.AslSpecTest"`
Expected: compilation fails, `AslSpec` unresolved.

- [ ] **Step 3: Implement `SpecModel.kt` and `AslSpec.kt`, then enter the data**

Enter the table above, then read the listed sources page by page and add or correct fields the table misses. Write each description yourself in one or two sentences: what the field does and what value it takes. `ErrorNames.kt` holds every predefined `States.*` error name from the error handling page. `ResourceArns.kt` holds every optimised integration from the `integrate-optimized` page and its sub-pages, with each supported pattern suffix (none, `.sync`, `.sync:2`, `.waitForTaskToken`) as its own entry.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.spec.AslSpecTest"`
Expected: 11 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat: bundled reference data for Amazon States Language" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 5: Typed document model and schema index

**Files:**
- Create: `model/AslDocument.kt`, `model/SchemaIndex.kt`, `model/AslModel.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/model/AslModelTest.kt`

**Interfaces:**
- Consumes: `AslTree.root`, `AslObject`, `AslEntry`, `AslScalar`, `AslSpec`, `AslFiles.isAslFile`, `AslSettings` (as cache dependency).
- Produces:

```kotlin
class AslDocument(val file: PsiFile, val rootNode: AslNode?, val root: AslStateMachine?, val schema: SchemaIndex) {
    fun allMachines(): List<AslStateMachine>        // root first, then nested, depth first in file order
    fun allStates(): List<AslState>
    fun stateAt(element: PsiElement): AslState?     // innermost state whose entry contains the element
}
class AslStateMachine(val obj: AslObject, val owner: AslState?, val startAt: AslEntry?, val statesEntry: AslEntry?, val states: List<AslState>) {
    fun state(name: String): AslState?              // first with that name
    val open: Boolean                               // see the rules below
}
class AslState(val name: String, val entry: AslEntry, val obj: AslObject?, val machine: AslStateMachine,
               val typeName: String?, val type: StateTypeSpec?, val queryLanguage: QueryLanguage, val queryLanguageKnown: Boolean,
               val children: List<AslStateMachine>, val transitions: List<AslTransition>)
enum class TransitionKind { NEXT, CHOICE, DEFAULT, CATCH }
class AslTransition(val kind: TransitionKind, val from: AslState, val target: AslScalar?, val owner: AslObject, val index: Int) {
    val targetName: String?                         // target?.text when it is a STRING
}
class SchemaIndex {
    fun info(objectPsi: PsiElement): ObjectInfo?    // keyed by AslObject.psi
    fun field(keyPsi: PsiElement): FieldInfo?       // keyed by AslEntry.keyPsi; null for unknown keys and inside free-form data
    fun stateNameKey(keyPsi: PsiElement): AslState? // non-null when the key declares a state
    fun isFreeForm(element: PsiElement): Boolean    // inside the value of a FreeForm field
}
data class ObjectInfo(val structure: StructureSpec, val queryLanguage: QueryLanguage, val state: AslState?)
data class FieldInfo(val spec: FieldSpec, val owner: ObjectInfo, val entry: AslEntry)

object AslModel { fun of(file: PsiFile): AslDocument? }   // null when the file is not an ASL file
```

- Rules: the effective query language of a state is its own `QueryLanguage` if that is `JSONPath` or `JSONata`, otherwise the top-level `QueryLanguage` of the file, otherwise `JSONPATH`. Nested states never inherit from the enclosing Map or Parallel state. A Map's child machine is the value of `ItemProcessor`, or of `Iterator` if `ItemProcessor` is absent. A Parallel's child machines are the items of `Branches`. Transition order: the state's `Next`, then each Choice rule's `Next` (index = rule position, owner = the rule), then `Default`, then each Catcher's `Next` (index = catcher position, owner = the catcher). Nested `And`/`Or`/`Not` rules produce no transitions. Transitions are read whatever the state's type is, known or not. A top-level Choice rule or a Catcher without `Next` still yields a transition, with `target == null`. A state whose value is not a mapping has `obj == null`.
- `AslState.queryLanguageKnown` is false when the state's own `QueryLanguage`, or the top-level one it would inherit, contains a placeholder; `queryLanguage` then still holds the fallback value. `AslStateMachine.open` is true when the machine cannot be fully read: its `States` value or any state name key contains a placeholder. Checks use both to stay silent where the file does not say enough.
- `AslModel.of` is cached with `CachedValuesManager.getCachedValue(file)` depending on `PsiModificationTracker.MODIFICATION_COUNT` and `AslSettings.getInstance()`. `root` is null when the file's root is not a mapping; `rootNode` still holds what was read.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AslModelTest : AslTestCase() {
    private val nested = """
        QueryLanguage: JSONata
        StartAt: Sort
        States:
          Sort:
            Type: Choice
            Choices:
              - Condition: '{% ${'$'}a %}'
                Next: Each
              - Condition: '{% ${'$'}b %}'
                Next: Both
            Default: Done
          Each:
            Type: Map
            QueryLanguage: JSONPath
            ItemProcessor:
              StartAt: One
              States:
                One:
                  Type: Task
                  Resource: arn:aws:states:::lambda:invoke
                  Arguments:
                    Next: notAField
                  Catch:
                    - ErrorEquals: [States.ALL]
                      Next: OneFailed
                  End: true
                OneFailed:
                  Type: Fail
            Next: Done
          Both:
            Type: Parallel
            Branches:
              - StartAt: Left
                States:
                  Left: { Type: Succeed }
              - StartAt: Right
                States:
                  Right: { Type: Succeed }
            End: true
          Done:
            Type: Succeed
    """
    private fun doc(text: String = nested) = AslModel.of(yaml(text))!!

    fun `test states machines and nesting`() {
        val d = doc()
        assertEquals(listOf("Sort", "Each", "Both", "Done"), d.root!!.states.map { it.name })
        assertEquals(listOf("Sort", "Each", "One", "OneFailed", "Both", "Left", "Right", "Done"), d.allStates().map { it.name })
        assertEquals(4, d.allMachines().size)
        assertEquals(2, d.root!!.state("Both")!!.children.size)
        assertEquals("Each", d.root!!.state("Each")!!.children.single().owner!!.name)
        assertEquals("Map", d.root!!.state("Each")!!.type!!.name)
    }
    fun `test transitions in order with kinds`() {
        val sort = doc().root!!.state("Sort")!!
        assertEquals(listOf(CHOICE to "Each", CHOICE to "Both", DEFAULT to "Done"), sort.transitions.map { it.kind to it.targetName })
        assertEquals(listOf(0, 1, 0), sort.transitions.map { it.index })
        val one = doc().allStates().first { it.name == "One" }
        assertEquals(listOf(CATCH to "OneFailed"), one.transitions.map { it.kind to it.targetName })
    }
    fun `test effective query language comes from the top level not the enclosing state`() {
        val d = doc()
        assertEquals(QueryLanguage.JSONPATH, d.root!!.state("Each")!!.queryLanguage)
        assertEquals(QueryLanguage.JSONATA, d.allStates().first { it.name == "One" }.queryLanguage)
        assertEquals(QueryLanguage.JSONPATH, doc("StartAt: A\nStates:\n  A:\n    Type: Succeed").root!!.state("A")!!.queryLanguage)
    }
    fun `test schema index knows structures fields and free-form data`() {
        val d = doc()
        val one = d.allStates().first { it.name == "One" }
        assertEquals(StructureId.TASK, d.schema.info(one.obj!!.psi)!!.structure.id)
        assertEquals("Resource", d.schema.field(one.obj!!["Resource"]!!.keyPsi)!!.spec.name)
        val arguments = one.obj!!["Arguments"]!!.value as AslObject
        assertNull(d.schema.field(arguments["Next"]!!.keyPsi))
        assertTrue(d.schema.isFreeForm(arguments["Next"]!!.keyPsi))
        val catcher = (one.obj!!["Catch"]!!.value as AslArray).items.single() as AslObject
        assertEquals(StructureId.CATCHER, d.schema.info(catcher.psi)!!.structure.id)
        assertSame(one, d.schema.stateNameKey(one.entry.keyPsi))
        assertNull(d.schema.field(one.entry.keyPsi))
        assertEquals(StructureId.STATE_MACHINE, d.schema.info(d.root!!.obj.psi)!!.structure.id)
    }
    fun `test stateAt returns the innermost state`() {
        val d = doc()
        val one = d.allStates().first { it.name == "One" }
        assertSame(one, d.stateAt(one.obj!!["Resource"]!!.keyPsi))
        assertNull(d.stateAt(d.root!!.startAt!!.keyPsi))
    }
    fun `test json files give the same model`() {
        val d = AslModel.of(json("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "Next": "B"}, "B": {"Type": "Succeed"}}}"""))!!
        assertEquals(listOf("A", "B"), d.root!!.states.map { it.name })
        assertEquals("B", d.root!!.state("A")!!.transitions.single().targetName)
    }
    fun `test not an asl file and broken files`() {
        assertNull(AslModel.of(yaml("StartAt: A", "plain.yaml")))
        assertNull(AslModel.of(yaml("- a"))!!.root)
        assertNotNull(AslModel.of(yaml(""))!!)
        val partial = AslModel.of(yaml("StartAt: A\nStates:\n  A:\n  B: text\n  C:\n    Type: Bogus"))!!
        assertEquals(listOf("A", "B", "C"), partial.root!!.states.map { it.name })
        assertNull(partial.root!!.state("A")!!.obj)
        assertNull(partial.root!!.state("C")!!.type)
        assertEquals("Bogus", partial.root!!.state("C")!!.typeName)
        assertEquals(StructureId.STATE, partial.schema.info(partial.root!!.state("C")!!.obj!!.psi)!!.structure.id)
    }
    fun `test placeholders make the model say what it cannot know`() {
        val d = doc("QueryLanguage: \${Language}\nStartAt: A\nStates:\n  A:\n    Type: Succeed\n  \${Extra}:\n    Type: Succeed")
        assertFalse(d.root!!.state("A")!!.queryLanguageKnown)
        assertTrue(d.root!!.open)
        val plain = doc("StartAt: A\nStates:\n  A:\n    Type: Succeed")
        assertTrue(plain.root!!.state("A")!!.queryLanguageKnown)
        assertFalse(plain.root!!.open)
    }
    fun `test a rule or catcher without Next yields a transition without target`() {
        val d = doc("StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: x\n    Catch:\n      - ErrorEquals: [States.ALL]\n    End: true")
        assertNull(d.root!!.state("A")!!.transitions.single().target)
    }
    fun `test model is cached until the file changes`() {
        val file = yaml("StartAt: A\nStates:\n  A:\n    Type: Succeed")
        assertSame(AslModel.of(file), AslModel.of(file))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.model.AslModelTest"`
Expected: compilation fails, `AslModel` unresolved.

- [ ] **Step 3: Implement the model, the schema index and the cache**

The schema index is filled in one walk that follows the specification data: start at the root object with `STATE_MACHINE`; for each entry look up its `FieldSpec` among the fields of the structure (regardless of query language, so that wrong-language fields are still known fields); descend into `Struct`, `ListOf(Struct)` and `StatesMap` values; mark the value of a `FreeForm` field as free-form and do not descend. A state object gets the structure of its type; a state whose type is missing, unknown or a placeholder gets the generic `STATE` structure, so that completion and documentation still work there.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.model.AslModelTest"`
Expected: 10 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat: typed state machine model with schema index" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 6: Synthetic test definitions

**Files:**
- Create: `src/test/testData/clean/film-archive-intake.asl.yaml`, `src/test/testData/clean/reel-check.asl.json`, `src/test/testData/clean/legacy-loan.asl.yaml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/fixtures/FixtureShapeTest.kt`

**Interfaces:**
- Consumes: `AslModel.of`, `AslTree`, `Placeholders`.
- Produces: the three files above. Later tasks assert that they produce no findings.

**Content of `film-archive-intake.asl.yaml`.** A workflow in which a film archive takes in a batch of reels: a manifest file arrives in a bucket, the workflow takes a lock, splits and checks the manifest, compares it with an earlier batch of the same ID, reads a shelf index in pages, classifies each reel, validates reels in a Map, writes a catalog transaction, seals the batch, publishes events, cleans up, and has rejection, incident-reporting and "outcome unknown" paths. It must be a definition that would deploy and run. Requirements, all enforced by the shape test or by review:

- `QueryLanguage: JSONata` at the top level; over 1,000 lines; at least 40 states; all eight state types.
- One Map state with an inline `ItemProcessor` of at least two states; one Parallel state with two branches.
- Retry rules defined once with YAML anchors and reused through aliases in at least ten states, both as `Retry: [*a, *b]` and as list items.
- Placeholders in all four positions: as a whole value, as a mapping key, inside a longer plain string, and inside a JSONata string literal.
- JSONata in single-quoted, double-quoted, folded (`>-`) and literal (`|-`) scalars. At least six expressions of ten lines or more, and at least one of twenty. Between them they use: blocks with `:=` bindings, `function(...)` definitions, regular expression literals, predicates, `$sort` with a comparator, `$map`/`$filter`/`$reduce`, nested object and array construction, the conditional with and without an else part, `in`, `&`, and `$states.input`, `$states.result`, `$states.errorOutput`, `$states.context`. Only functions that Step Functions supports. No `?:` or `??`.
- A whole `Arguments` value written as one JSONata expression in at least two states.
- One variable assigned in at least fifteen places (states, Choice rules and Catchers) and read in a few; a first Pass state that initialises every variable.
- One Choice state that dispatches on a variable to at least six targets.
- An explanatory English comment above most states.
- Every state reachable, every Choice with a `Default`, no unknown fields.

`reel-check.asl.json`: a JSONata definition in JSON with about eight states, with quoted placeholders. `legacy-loan.asl.yaml`: a JSONPath-mode definition with about eight states using `Parameters`, `ResultPath`, `InputPath`, a Choice with `Variable` and comparison operators including `And`, and an intrinsic function.

- [ ] **Step 1: Write the failing shape test**

```kotlin
class FixtureShapeTest : AslTestCase() {
    private fun load(name: String) = myFixture.configureByFile("clean/$name")
    private fun objects(n: AslNode?): Sequence<AslObject> = when (n) {
        is AslObject -> sequenceOf(n) + n.entries.asSequence().flatMap { objects(it.value) }
        is AslArray -> n.items.asSequence().flatMap { objects(it) }
        else -> emptySequence()
    }

    fun `test intake definition is as large and varied as required`() {
        val file = load("film-archive-intake.asl.yaml")
        val text = file.text
        val doc = AslModel.of(file)!!
        assertTrue(text.lines().size > 1000)
        assertTrue(doc.allStates().size >= 40)
        assertEquals(setOf("Task", "Pass", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map"), doc.allStates().mapNotNull { it.typeName }.toSet())
        assertTrue(doc.allStates().any { it.typeName == "Map" && it.children.single().states.size >= 2 })
        assertTrue(doc.allStates().any { it.typeName == "Parallel" && it.children.size == 2 })
        assertTrue(Regex("""\*\w+""").findAll(text).count() >= 20)
        assertTrue(Regex("""^\s+\$\{\w+}:""", RegexOption.MULTILINE).containsMatchIn(text))        // placeholder as key
        assertTrue(Regex(""""[^"\n]*\$\{\w+}[^"\n]*"""").containsMatchIn(text))                     // inside a JSONata string
        assertTrue(listOf(": >-", ": |-", ": '{%", ": \"{%").all { it in text })
        val assignments = objects(doc.rootNode).flatMap { it.entries }.filter { it.key == "Assign" }
            .flatMap { (it.value as? AslObject)?.entries.orEmpty() }.groupingBy { it.key }.eachCount()
        assertTrue(assignments.values.max() >= 15)                                                  // one variable assigned in many places
        assertTrue(doc.root!!.states.maxOf { it.transitions.size } >= 6)                            // the dispatching Choice
        assertTrue(text.lines().count { it.trimStart().startsWith("#") } >= 30)
    }
    fun `test json and jsonpath definitions load`() {
        assertTrue(AslModel.of(load("reel-check.asl.json"))!!.allStates().size >= 6)
        val legacy = AslModel.of(load("legacy-loan.asl.yaml"))!!
        assertTrue(legacy.allStates().all { it.queryLanguage == QueryLanguage.JSONPATH })
    }
    fun `test no name from outside the invented domain`() {
        for (name in listOf("film-archive-intake.asl.yaml", "reel-check.asl.json", "legacy-loan.asl.yaml"))
            assertFalse(Regex("[äöüßÄÖÜ]").containsMatchIn(load(name).text))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.fixtures.FixtureShapeTest"`
Expected: FAIL, file `clean/film-archive-intake.asl.yaml` not found.

- [ ] **Step 3: Write the three definitions**

Write the definitions from scratch in the film archive domain. Do not open or consult the private definition in the repository root.

- [ ] **Step 4: Run the shape test**

Run: `./gradlew test --tests "io.github.saigyo.aslan.fixtures.FixtureShapeTest"`
Expected: 3 tests passed.

- [ ] **Step 5: Cross-check with an independent validator**

Run the community validator on each definition. It checks the structure independently of this plugin and, according to its documentation, also parses the JSONata expressions:

```bash
npx --yes asl-validator@4 --no-arn-check --yaml-path src/test/testData/clean/film-archive-intake.asl.yaml
npx --yes asl-validator@4 --no-arn-check --yaml-path src/test/testData/clean/legacy-loan.asl.yaml
npx --yes asl-validator@4 --no-arn-check --json-path src/test/testData/clean/reel-check.asl.json
```

Expected: the validator reports each definition as valid. Fix the definitions until all three pass. If the validator rejects a placeholder used as a mapping key or stumbles over a YAML feature, record exactly what it said in the task report and judge that point by the ASL specification instead. If Node is unavailable, say so in the task report and continue; do not skip silently.

- [ ] **Step 6: Commit**

```bash
git add src/test && git commit -m "test: synthetic film archive definitions as main test data" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 7: Problem catalogue, structure errors and inspections

**Files:**
- Create: `check/AslProblem.kt`, `check/AslFinding.kt`, `check/AslChecker.kt`, `check/StructureChecks.kt`, `check/AslInspections.kt`, and one description file `src/main/resources/inspectionDescriptions/<shortName>.html` for every `AslProblem`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/check/CheckTestCase.kt`, `StructureChecksTest.kt`, `InspectionsTest.kt`

**Interfaces:**
- Consumes: `AslDocument`, `SchemaIndex`, `AslSpec`, `Placeholders`.
- Produces:

```kotlin
enum class AslProblem(val level: HighlightDisplayLevel) {
    // errors: an AWS source documents that the definition is rejected
    ROOT_NOT_MAPPING(ERROR), MISSING_REQUIRED_FIELD(ERROR), EMPTY_CHOICES(ERROR), UNKNOWN_STATE_TYPE(ERROR),
    MISSING_TRANSITION(ERROR), WRONG_QUERY_LANGUAGE_FIELD(ERROR), JSONPATH_STATE_IN_JSONATA_MACHINE(ERROR),
    WRONG_VALUE_KIND(ERROR), UNCLOSED_JSONATA_DELIMITER(ERROR),
    MISSING_TRANSITION_TARGET(ERROR), NO_TERMINAL_STATE(ERROR), DUPLICATE_STATE_NAME(ERROR), STATE_NAME_TOO_LONG(ERROR),
    // warnings: deploys, or rejection not confirmed
    UNKNOWN_FIELD(WARNING), MISPLACED_FIELD(WARNING), VALUE_OUT_OF_RANGE(WARNING), UNKNOWN_ENUM_VALUE(WARNING),
    EXCLUSIVE_FIELDS(WARNING), JSONATA_IN_STATIC_FIELD(WARNING), STATES_ALL_PLACEMENT(WARNING),
    HEARTBEAT_NOT_BELOW_TIMEOUT(WARNING), UNREACHABLE_STATE(WARNING),
    CHOICE_WITHOUT_DEFAULT(WEAK_WARNING);

    val shortName: String    // "Asl" + the name in upper camel case, for example "AslUnknownStateType"
}
data class AslFinding(val problem: AslProblem, val element: PsiElement, val message: String)
object AslChecker { fun findings(file: PsiFile): List<AslFinding> }   // empty for non-ASL files; cached like AslModel; no two findings with the same problem and element

open class AslProblemInspection(val problem: AslProblem) : LocalInspectionTool()   // getShortName() returns problem.shortName
object AslInspections { fun all(): List<AslProblemInspection> }                    // exactly one per AslProblem
```

- **One inspection per problem.** The design requires that every check can be switched off or changed in severity on its own, so that a single wrong finding can be silenced without losing the others. Each problem therefore has its own inspection: a one-line subclass `class <shortName>Inspection : AslProblemInspection(AslProblem.X)`, all in `AslInspections.kt`.
- Test base: `abstract class CheckTestCase : AslTestCase()` with `val BASE: String` (below), `fun found(text: String, name: String = "test.asl.yaml"): List<Pair<AslProblem, String>>` returning `problem to element.text` for `AslChecker.findings(myFixture.configureByText(name, text))`, and `fun edit(vararg replacements: Pair<String, String>): String` applying literal replacements to `BASE` and failing if a search string is absent.

`BASE`, which must produce no findings in this and all later tasks (escape each `$` when embedding it or a table row below in a Kotlin string):

```yaml
QueryLanguage: JSONata
StartAt: Inspect
States:
  Inspect:
    Type: Task
    Resource: arn:aws:states:::lambda:invoke
    Arguments:
      FunctionName: ${InspectFn}
    Retry:
      - ErrorEquals: [States.ALL]
        MaxAttempts: 2
    Catch:
      - ErrorEquals: [States.ALL]
        Next: Rejected
    Next: Decide
  Decide:
    Type: Choice
    Choices:
      - Condition: '{% $states.input.ok %}'
        Next: Accepted
    Default: Rejected
  Accepted:
    Type: Succeed
  Rejected:
    Type: Fail
```

**Where a finding is reported.** A missing field or a state-level problem is reported on the key of the entry whose value is the object concerned; for the root and for list items (rules, retriers, catchers, branches) on `AslObject.anchorPsi`. A problem with a field is reported on its key. A problem with a value is reported on the value's `psi`, which is the key when the value is empty. Findings inside an aliased object are reported at its definition. `AslChecker.findings` returns findings sorted by the start offset of their element, then by the order of `AslProblem`.

**Rules of this task** (all in `StructureChecks.kt`):

| Problem | Condition |
|---|---|
| ROOT_NOT_MAPPING | the file has content and its root is not a mapping; reported on the root |
| MISSING_REQUIRED_FIELD | a `required` field of the object's structure is absent (this covers a state without `Type` through the `STATE` structure), or no field of a `oneOfRequired` set is present; also a JSONata Choice rule without `Condition`, and a top-level Choice rule without `Next` |
| EMPTY_CHOICES | `Choices` is an empty list; reported on the key |
| UNKNOWN_STATE_TYPE | `Type` is a string that names no state type; reported on the value |
| MISSING_TRANSITION | a state whose type needs a transition has neither `Next` nor `End: true` |
| WRONG_QUERY_LANGUAGE_FIELD | a field of the structure whose mode excludes the state's effective query language |
| JSONPATH_STATE_IN_JSONATA_MACHINE | top-level `QueryLanguage` is `JSONata` and a state sets `QueryLanguage: JSONPath`; reported on that value |
| WRONG_VALUE_KIND | the value's basic kind does not fit the field: a mapping where `Struct`, `StatesMap` is not expected or the reverse; a list where `ListOf`, `ErrorNames` is not expected or the reverse; for scalar kinds, `Text`, `StateName`, `ResourceArn`, `OneOf` need a `STRING`, `Integer` and `Number` need a `NUMBER`, `Bool` needs a `BOOLEAN`; a `NULL` only fits a `nullable` field. Integer versus fractional numbers are not distinguished. A state whose value is not a mapping is also this problem, reported on the value's `psi`. |
| UNCLOSED_JSONATA_DELIMITER | in a JSONata state, any string scalar, including inside free-form data, whose text starts with `{%` and does not end with `%}` |

A state having both `Next` and `End: true` is not an error: no AWS source documents its rejection, so Task 9 reports it as a warning.

**Exemptions.** They apply to every rule in Tasks 7 to 9 and exist to keep deployable files free of errors:

- A value whose text contains a placeholder is never checked for kind, range or membership in a fixed list.
- A scalar with `ambiguous == true` and an `AslUnresolved` node are never checked for kind, range or membership.
- In a JSONata state, and in a state with `queryLanguageKnown == false`, a string that starts with `{%` is never `WRONG_VALUE_KIND`, whatever the field. If it is not closed, the only finding is `UNCLOSED_JSONATA_DELIMITER`.
- `FreeForm` values are never checked for kind.
- A field reported as `WRONG_QUERY_LANGUAGE_FIELD` gets no further checks on its value.
- A state whose `Type` contains a placeholder, or whose whole value is a placeholder, is skipped entirely.
- A state with `queryLanguageKnown == false` gets no `WRONG_QUERY_LANGUAGE_FIELD`, no `JSONPATH_STATE_IN_JSONATA_MACHINE` and no `UNCLOSED_JSONATA_DELIMITER`.
- `End` whose value contains a placeholder or is ambiguous counts as possibly true: no `MISSING_TRANSITION`.
- Entries with `viaMerge` are checked at their definition only.
- A key that is present counts as present even when its value is empty. `End: false` counts as absent.

- [ ] **Step 1: Write the failing check tests**

`fun test BASE is clean` asserts `found(BASE)` is empty. Then one test per row. Each asserts the complete result list, and each must still hold when the checks of Tasks 8 and 9 exist:

| Test name | Change to `BASE` | Expected `found(...)` |
|---|---|---|
| root that is a list | whole text `- a` | `[ROOT_NOT_MAPPING to "- a"]` |
| missing StartAt | remove `StartAt: Inspect\n` | `[MISSING_REQUIRED_FIELD to "QueryLanguage"]` |
| missing Type | remove `    Type: Task\n` | `[MISSING_REQUIRED_FIELD to "Inspect"]` |
| unknown type | `Type: Task` → `Type: Tsak` | `[UNKNOWN_STATE_TYPE to "Tsak"]` |
| missing Resource | remove the `Resource` line | `[MISSING_REQUIRED_FIELD to "Inspect"]` |
| empty Choices | replace the `Choices` block with `Choices: []`, and `Default: Rejected` → `Default: Accepted` | `[EMPTY_CHOICES to "Choices"]` |
| rule without Condition | remove the `Condition` line, write `- Next: Accepted` | `[MISSING_REQUIRED_FIELD to "Next"]` |
| catcher without Next | remove `        Next: Rejected\n` under `Catch` | `[MISSING_REQUIRED_FIELD to "ErrorEquals"]` |
| no transition | remove `    Next: Decide\n` | `[MISSING_TRANSITION to "Inspect"]` |
| End false counts as absent | add `    End: false` to `Inspect` | `[]` |
| placeholder End | `Accepted` becomes `Type: Pass` with `End: ${Last}` | `[]` |
| ambiguous End | `Accepted` becomes `Type: Pass` with `End: yes` | `[]` |
| JSONPath field in JSONata state | `Arguments:` → `Parameters:` | `[WRONG_QUERY_LANGUAGE_FIELD to "Parameters"]` |
| JSONata fields in a JSONPath machine | remove the `QueryLanguage` line | `[WRONG_QUERY_LANGUAGE_FIELD to "Arguments", WRONG_QUERY_LANGUAGE_FIELD to "Condition"]` (a JSONPath rule is not checked for `Variable` or an operator) |
| placeholder query language | `QueryLanguage: JSONata` → `QueryLanguage: ${Language}` | `[]` |
| JSONPath state in JSONata machine | add `    QueryLanguage: JSONPath` to `Accepted` | `[JSONPATH_STATE_IN_JSONATA_MACHINE to "JSONPath"]` |
| text for a number | `MaxAttempts: 2` → `MaxAttempts: many` | `[WRONG_VALUE_KIND to "many"]` |
| quoted number | `MaxAttempts: 2` → `MaxAttempts: '2'` | `[WRONG_VALUE_KIND to "'2'"]` |
| mapping for a list | `Retry:` block → `Retry: {MaxAttempts: 2}` | `[WRONG_VALUE_KIND to "{MaxAttempts: 2}"]` |
| empty value | `Next: Decide` → `Next:` | `[WRONG_VALUE_KIND to "Next"]` |
| placeholder for a number | `MaxAttempts: 2` → `MaxAttempts: ${Attempts}` | `[]` |
| number written the YAML 1.1 way | `MaxAttempts: 2` → `MaxAttempts: 1_0` | `[]` |
| unresolved alias | `Retry:` block → `Retry: *missing` | `[]` |
| JSONata where allowed | add `    TimeoutSeconds: '{% $t %}'` to `Inspect` | `[]` |
| unclosed delimiter | `'{% $states.input.ok %}'` → `'{% $states.input.ok'` | `[UNCLOSED_JSONATA_DELIMITER to "'{% \$states.input.ok'"]` |
| folded scalar that keeps its line break | `Condition: '{% $states.input.ok %}'` → `Condition: >\n          {% $states.input.ok %}` | one finding, `UNCLOSED_JSONATA_DELIMITER` |
| unclosed delimiter inside free-form data | `FunctionName: ${InspectFn}` → `FunctionName: '{% $x'` | one finding, `UNCLOSED_JSONATA_DELIMITER` |
| placeholder as Type | `Type: Succeed` → `Type: ${Kind}` | `[]` |
| placeholder as whole state | `Accepted:\n    Type: Succeed` → `Accepted: ${AcceptedState}` | `[]` |
| unknown keys in free-form data | add `      Next: 5\n      Whatever: [1]` under `Arguments:` | `[]` |
| wrong kind in an aliased retrier is reported once | in `Inspect`, the retrier becomes `- &r` with `ErrorEquals: [States.ALL]` and `MaxAttempts: many`; `Accepted` becomes `Type: Task` with `Resource: x`, `Retry: [*r]`, `End: true` | `[WRONG_VALUE_KIND to "many"]` |
| JSON file | `found(json, "test.asl.json")` with the JSON form of `BASE` and `"Type": "Tsak"` | `[UNKNOWN_STATE_TYPE to "\"Tsak\""]` |
| not an ASL file | `found(edit("Type: Task" to "Type: Tsak"), "plain.yaml")` | `[]` |
| half-typed file | `found("StartAt:\nStates:\n  A:\n    Type")` and `found("States:\n  A:\n  - x")` | no exception; the result is not asserted |

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.check.StructureChecksTest"`
Expected: compilation fails, `AslChecker` unresolved.

- [ ] **Step 3: Implement the catalogue, the checker and the structure checks**

`AslChecker.findings` runs each check file's `fun check(doc: AslDocument, out: MutableList<AslFinding>)`, removes duplicates by problem and element, and sorts. The transition and warning checks are added in Tasks 8 and 9.

- [ ] **Step 4: Run the check tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.check.StructureChecksTest"`
Expected: all passed.

- [ ] **Step 5: Write the failing inspection tests**

```kotlin
class InspectionsTest : CheckTestCase() {
    private fun highlights(text: String, name: String = "test.asl.yaml",
                           tools: List<LocalInspectionTool> = AslInspections.all()): List<Pair<HighlightSeverity, String>> {
        myFixture.configureByText(name, text)
        myFixture.enableInspections(*tools.toTypedArray())
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { it.severity to it.text }
    }
    fun `test an error finding surfaces as an error on its element`() =
        assertEquals(listOf(HighlightSeverity.ERROR to "Tsak"), highlights(edit("Type: Task" to "Type: Tsak")))
    fun `test a finding in a json file surfaces too`() =
        assertEquals(listOf(HighlightSeverity.ERROR to "\"Tsak\""),
            highlights("""{"StartAt": "A", "States": {"A": {"Type": "Tsak"}}}""", "test.asl.json"))
    fun `test one inspection per problem with its level and a description`() {
        val ours = LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.pluginDescriptor.pluginId.idString == "io.github.saigyo.aslan" }
        val names = AslProblem.entries.map { it.shortName }.toSet()
        assertEquals(AslProblem.entries.size, names.size)
        assertEquals(names, ours.map { it.shortName }.toSet())
        assertEquals(names, AslInspections.all().map { it.shortName }.toSet())
        for (p in AslProblem.entries) {
            val ep = ours.single { it.shortName == p.shortName }
            assertEquals(p.name, p.level, ep.defaultLevel)
            assertNull(p.name, ep.language)
            assertEquals(p.name, "Amazon States Language", ep.groupDisplayName)
            assertNotNull(p.name, javaClass.getResource("/inspectionDescriptions/${p.shortName}.html"))
        }
    }
    fun `test switching one inspection off silences only its problem`() {
        val text = edit("Type: Succeed" to "Type: Sucked", "MaxAttempts: 2" to "MaxAttempts: many")
        assertEquals(listOf("many", "Sucked"), highlights(text).map { it.second })
        val withoutType = AslInspections.all().filter { it.problem != AslProblem.UNKNOWN_STATE_TYPE }
        assertEquals(listOf("many"), highlights(text, tools = withoutType).map { it.second })
    }
    fun `test nothing in other files`() =
        assertEquals(emptyList<Any>(), highlights(edit("Type: Task" to "Type: Tsak"), "plain.yaml"))
    fun `test clean definitions show nothing`() {
        for (name in listOf("film-archive-intake.asl.yaml", "reel-check.asl.json", "legacy-loan.asl.yaml")) {
            myFixture.configureByFile("clean/$name")
            myFixture.enableInspections(*AslInspections.all().toTypedArray())
            assertEquals(name, emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { "${it.severity}: ${it.description} at ${it.text}" })
        }
    }
}
```

Call `myFixture.disableInspections(...)` or use a fresh fixture state as needed so that the second `highlights` call in the switching test really runs without the removed inspection.

- [ ] **Step 6: Implement the inspections**

`AslProblemInspection.checkFile` takes `AslChecker.findings(file)`, keeps the findings of its problem and registers each on its element with the generic problem highlight type. Register every subclass in `plugin.xml` as a `<localInspection>` with `shortName`, `implementationClass`, a display name, `groupName="Amazon States Language"`, `enabledByDefault="true"` and `level` (`ERROR`, `WARNING` or `WEAK WARNING`, the last one with a space). Do not set a `language` attribute: the inspections must run on YAML and on JSON files. Write one description HTML file per problem: what it reports, and why it is an error or only a warning.

- [ ] **Step 7: Run all tests so far**

Run: `./gradlew test`
Expected: all passed. If a clean definition shows a finding, decide whether the definition or the rule is wrong by reading the specification, and fix that one.

- [ ] **Step 8: Commit**

```bash
git add src && git commit -m "feat: structure checks reported as errors" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 8: Transition and state-name errors

**Files:**
- Create: `check/TransitionChecks.kt`
- Modify: `check/AslChecker.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/check/TransitionChecksTest.kt`

**Interfaces:**
- Consumes: `AslDocument`, `AslStateMachine` (including `open`), `AslState`, `AslTransition`, `CheckTestCase`.
- Produces: `internal fun AslStateMachine.hasUnknownFlow(): Boolean` in `TransitionChecks.kt`, used by Task 9. It is true when the file does not say enough to judge which states can be reached:
  - the machine is `open`;
  - `StartAt` is absent, contains a placeholder, or names no state of this machine;
  - any transition has no target, a target with a placeholder, or a target that names no state of this machine;
  - any state whose type needs a transition has neither `Next` nor `End: true`, or has an `End` that contains a placeholder or is ambiguous.

**Rules:**

| Problem | Condition | Reported on |
|---|---|---|
| MISSING_TRANSITION_TARGET | `StartAt` of a machine, or a transition target, is a string without a placeholder that names no state in the same machine; skipped when the machine is `open` | the value |
| NO_TERMINAL_STATE | a machine has at least one state and none is of a terminal type or has `End: true`; skipped when the machine is `open`, when any state's type is missing, unknown or a placeholder, and when any state has an `End` that contains a placeholder or is ambiguous | the `States` key |
| DUPLICATE_STATE_NAME | a state name occurs more than once in the whole file, at any nesting level | every state key with that name |
| STATE_NAME_TOO_LONG | a state name has more than 80 Unicode code points | the state key |

A state name key that contains a placeholder is exempt from the last two rules.

- [ ] **Step 1: Write the failing tests**

One test per row, asserting the complete result of `found(...)`. Each must still hold when the checks of Task 9 exist:

| Test name | Change to `BASE` | Expected |
|---|---|---|
| Next to a missing state | `Next: Decide` → `Next: Decid` | `[MISSING_TRANSITION_TARGET to "Decid"]` |
| StartAt to a missing state | `StartAt: Inspect` → `StartAt: Nowhere` | `[MISSING_TRANSITION_TARGET to "Nowhere"]` |
| Default to a missing state | `Default: Rejected` → `Default: Nowhere` | `[MISSING_TRANSITION_TARGET to "Nowhere"]` |
| catcher Next to a missing state | catcher `Next: Rejected` → `Next: Nowhere` | `[MISSING_TRANSITION_TARGET to "Nowhere"]` |
| target in another machine | `Accepted` becomes `Type: Map` with `End: true` and an `ItemProcessor` with `StartAt: Inner` and two states: `Inner` (`Type: Pass`, `Next: Rejected`) and `InnerDone` (`Type: Succeed`) | `[MISSING_TRANSITION_TARGET to "Rejected"]` |
| placeholder target | `Next: Decide` → `Next: ${NextState}` | `[]` |
| placeholder state name | add a state `${Extra}:` of type Succeed, and `Default: Rejected` → `Default: Elsewhere` | `[]` |
| no terminal state | `Accepted` and `Rejected` become `Type: Pass` with `Next: Inspect` | `[NO_TERMINAL_STATE to "States"]` |
| duplicate across nesting levels | `Accepted` becomes `Type: Map` with `End: true` and an `ItemProcessor` with `StartAt: Rejected` and one state `Rejected` of type Succeed | `[DUPLICATE_STATE_NAME to "Rejected", DUPLICATE_STATE_NAME to "Rejected"]` |
| name of 81 characters | rename `Accepted` to `A` repeated 81 times, in the key and in `Next` | `[STATE_NAME_TOO_LONG to <the name>]` |
| name of 80 characters | the same with 80 | `[]` |
| name of 80 code points outside the basic plane | the same with `𝔸` (U+1D538) repeated 80 times | `[]` |

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.check.TransitionChecksTest"`
Expected: FAIL, expected findings missing.

- [ ] **Step 3: Implement `TransitionChecks.check(doc, out)` and call it from `AslChecker`**

- [ ] **Step 4: Run the tests**

Run: `./gradlew test`
Expected: all passed, including `InspectionsTest` for the clean definitions.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat: transition and state name checks" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 9: Warning checks

**Files:**
- Create: `check/WarningChecks.kt`
- Modify: `check/AslChecker.kt`, `src/test/kotlin/io/github/saigyo/aslan/check/InspectionsTest.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/check/WarningChecksTest.kt`

**Interfaces:**
- Consumes: `hasUnknownFlow()`, `SchemaIndex`, `AslSpec.definesFieldAnywhere`, `CheckTestCase`. The exemptions of Task 7 apply.

**Rules:**

| Problem | Condition | Reported on |
|---|---|---|
| UNKNOWN_FIELD | a key in an object with an `ObjectInfo` whose structure is not `STATE`, not in that structure, with `definesFieldAnywhere(key)` false, and not containing a placeholder | the key |
| MISPLACED_FIELD | the same, with `definesFieldAnywhere(key)` true | the key |
| VALUE_OUT_OF_RANGE | a `NUMBER` value below the field's `min` or above its `max` | the value |
| UNKNOWN_ENUM_VALUE | a `STRING` value of a `OneOf` field other than `Type` that is not in the list | the value |
| EXCLUSIVE_FIELDS | more than one field of an `exclusive` set is present; this includes `Next` with `End: true`, while `End: false` counts as absent | each of those keys after the first |
| JSONATA_IN_STATIC_FIELD | in a JSONata state, a string that starts with `{%` as the value of a field with a scalar kind and `jsonata == false`, so it is not evaluated there | the value |
| STATES_ALL_PLACEMENT | `States.ALL` shares an `ErrorEquals` list with other names, or is in a retrier or catcher that is not the last of its list | the `States.ALL` scalar |
| HEARTBEAT_NOT_BELOW_TIMEOUT | a Task has numeric `HeartbeatSeconds` and `TimeoutSeconds` with heartbeat ≥ timeout | the `HeartbeatSeconds` value |
| UNREACHABLE_STATE | a state cannot be reached from `StartAt` through the transitions of its machine; skipped for a machine where `hasUnknownFlow()` is true | the state key |
| CHOICE_WITHOUT_DEFAULT | a Choice state has no `Default` | the state key |

- [ ] **Step 1: Write the failing tests**

| Test name | Change to `BASE` | Expected |
|---|---|---|
| unknown field | add `    Retries: 3` to `Inspect` | `[UNKNOWN_FIELD to "Retries"]` |
| field on the wrong state type | add `    Next: Rejected` to `Accepted` | `[MISPLACED_FIELD to "Next"]` |
| placeholder key in a structure | add `    ${Extra}: 1` to `Inspect` | `[]` |
| negative MaxAttempts | `MaxAttempts: 2` → `MaxAttempts: -1` | `[VALUE_OUT_OF_RANGE to "-1"]` |
| BackoffRate below one | add `        BackoffRate: 0.5` to the retrier | `[VALUE_OUT_OF_RANGE to "0.5"]` |
| unknown JitterStrategy | add `        JitterStrategy: SOME` to the retrier | `[UNKNOWN_ENUM_VALUE to "SOME"]` |
| two time fields on Wait | `Accepted` becomes `Type: Wait`, `Seconds: 5`, `Timestamp: '2030-01-01T00:00:00Z'`, `End: true` | `[EXCLUSIVE_FIELDS to "Timestamp"]` |
| Next and End | add `    End: true` after `    Next: Decide` | `[EXCLUSIVE_FIELDS to "End"]` |
| JSONata in a field that does not evaluate it | `MaxAttempts: 2` → `MaxAttempts: '{% 2 %}'` | `[JSONATA_IN_STATIC_FIELD to "'{% 2 %}'"]` |
| States.ALL with another name | retrier `ErrorEquals: [States.ALL]` → `[States.ALL, States.Timeout]` | `[STATES_ALL_PLACEMENT to "States.ALL"]` |
| States.ALL retrier not last | add a second retrier `- ErrorEquals: [States.Timeout]` after the first | `[STATES_ALL_PLACEMENT to "States.ALL"]` |
| heartbeat not below timeout | add `    TimeoutSeconds: 30` and `    HeartbeatSeconds: 30` to `Inspect`, in this order | `[HEARTBEAT_NOT_BELOW_TIMEOUT to "30"]` |
| unreachable state | add a state `Orphan` of type Succeed | `[UNREACHABLE_STATE to "Orphan"]` |
| no reachability verdict with a placeholder target | `Next: Decide` → `Next: ${NextState}` | `[]` |
| no reachability verdict with a missing target | `Next: Decide` → `Next: Decid` | `[MISSING_TRANSITION_TARGET to "Decid"]` |
| no reachability verdict with a missing transition | remove `    Next: Decide\n` | `[MISSING_TRANSITION to "Inspect"]` |
| no unknown-field warnings while the type is unknown | `Type: Task` → `Type: Tsak` | `[UNKNOWN_STATE_TYPE to "Tsak"]` |
| Choice without Default | remove `    Default: Rejected\n` (the catcher still reaches `Rejected`) | `[CHOICE_WITHOUT_DEFAULT to "Decide"]` |

Add two tests to `InspectionsTest` that use its `highlights` helper: the unknown field change gives `[HighlightSeverity.WARNING to "Retries"]`, and the Choice without Default change gives `[HighlightSeverity.WEAK_WARNING to "Decide"]`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.check.WarningChecksTest"`
Expected: FAIL, expected findings missing.

- [ ] **Step 3: Implement `WarningChecks.check(doc, out)` and call it from `AslChecker`**

- [ ] **Step 4: Go through the specification for further checkable rules**

Read https://states-language.net/spec.html section by section and the developer guide pages listed in Task 4. For every rule that can be checked from the file and that no existing problem covers: if an AWS source documents that the definition is rejected at deployment, add it with level `ERROR`; otherwise with level `WARNING`. The same test applies to the existing warnings: promote one to `ERROR` only when you can cite the AWS page that documents the rejection. Each added rule needs an `AslProblem` entry, its inspection subclass, registration and description, the rule, and a test row. List the added and promoted rules, with their source, in the commit message.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: all passed, including the clean definitions and the test that every problem has exactly one registered inspection.

- [ ] **Step 6: Commit**

```bash
git add src && git commit -m "feat: warning checks for probable mistakes" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 10: Completion of field names and fixed values

**Files:**
- Create: `completion/AslCompletionContributor.kt`, `completion/CompletionContext.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`, `src/test/kotlin/io/github/saigyo/aslan/AslTestCase.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/completion/FieldCompletionTest.kt`

**Interfaces:**
- Consumes: `AslModel.of`, `SchemaIndex`, `AslSpec`.
- Produces:

```kotlin
sealed interface CompletionContext {
    data class Key(val owner: ObjectInfo, val present: Set<String>) : CompletionContext        // the caret is where a field name goes
    data class Value(val field: FieldInfo, val state: AslState?) : CompletionContext           // the caret is in the value of a known field
}
object CompletionContexts { fun at(parameters: CompletionParameters): CompletionContext? }     // null outside ASL files and inside free-form data
```

- [ ] **Step 1: Write the failing tests**

`<caret>` marks the caret. First add this helper to `AslTestCase`: `protected fun complete(text: String, name: String = "test.asl.yaml"): List<String>`, which calls `myFixture.configureByText(name, text.trimIndent())`, then `myFixture.completeBasic()`, and returns `myFixture.lookupElementStrings.orEmpty()`. The list is empty when a single match was inserted directly.

```kotlin
class FieldCompletionTest : AslTestCase() {
    private val head = "QueryLanguage: JSONata\nStartAt: A\nStates:\n  A:\n    Type: Task\n"

    fun `test keys of a JSONata task without those already present`() {
        val items = complete(head + "    Resource: x\n    <caret>")
        assertTrue(items.containsAll(listOf("Arguments", "Assign", "Output", "Retry", "Catch", "Next", "End", "TimeoutSeconds", "Comment")))
        assertFalse(items.any { it in setOf("Type", "Resource", "Parameters", "ResultPath", "InputPath", "Choices") })
    }
    fun `test required fields come first`() =
        assertEquals("Resource", complete(head + "    <caret>").first())
    fun `test prefix filters`() =
        assertEquals(listOf("Arguments", "Assign"), complete(head + "    A<caret>").sorted())
    fun `test JSONPath task offers path fields`() {
        val items = complete("StartAt: A\nStates:\n  A:\n    Type: Task\n    <caret>")
        assertTrue(items.containsAll(listOf("Parameters", "ResultPath", "InputPath")))
        assertFalse("Arguments" in items)
    }
    fun `test top level keys`() =
        assertEquals(listOf("Comment", "States", "TimeoutSeconds", "Version"), complete("QueryLanguage: JSONata\nStartAt: A\n<caret>").sorted())
    fun `test keys inside a retrier and a catcher`() {
        assertTrue(complete(head + "    Retry:\n      - ErrorEquals: [States.ALL]\n        <caret>").containsAll(listOf("MaxAttempts", "BackoffRate", "JitterStrategy")))
        assertTrue("Next" in complete(head + "    Catch:\n      - ErrorEquals: [States.ALL]\n        <caret>"))
    }
    fun `test first key of a new state offers Type`() =
        assertEquals("Type", complete("StartAt: A\nStates:\n  A:\n    <caret>").first())
    fun `test nothing inside free-form data`() =
        assertEquals(emptyList<String>(), complete(head + "    Arguments:\n      <caret>").filter { it in setOf("Next", "Type", "Retry") })
    fun `test fixed values`() {
        assertEquals(listOf("JSONPath", "JSONata"), complete("QueryLanguage: <caret>").sorted())
        assertEquals(listOf("FULL", "NONE"), complete(head + "    Retry:\n      - JitterStrategy: <caret>").sorted())
        assertEquals(listOf("false", "true"), complete(head + "    End: <caret>").sorted())
    }
    fun `test inserting a scalar field adds colon and space`() {
        complete(head + "    Resource: x\n    Comm<caret>")
        myFixture.checkResult(head + "    Resource: x\n    Comment: <caret>")
    }
    fun `test inserting a list field starts the first item`() {
        complete(head + "    Resource: x\n    Retr<caret>")
        myFixture.checkResult(head + "    Resource: x\n    Retry:\n      - <caret>")
    }
    fun `test json keys`() {
        val items = complete("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "<caret>"}}}""", "test.asl.json")
        assertTrue(items.containsAll(listOf("Next", "End", "Result", "Comment")))
        assertFalse("Resource" in items)
    }
    fun `test nothing in other files`() =
        assertFalse("StartAt" in complete("<caret>", "plain.yaml"))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.completion.FieldCompletionTest"`
Expected: FAIL, lookup lists empty.

- [ ] **Step 3: Find out what the PSI looks like at the caret**

For the caret shapes in the tests (new line among sibling keys, first line under an empty key, partly typed key, value position, JSON key in quotes), print `DebugUtil.psiToString(parameters.position.containingFile, true)` from a scratch test. Completion runs on a copy of the file with a dummy identifier inserted at the caret, so the caret element is often a plain scalar or an error element, not a key. Record the shapes as comments in `CompletionContext.kt`.

- [ ] **Step 4: Implement `CompletionContexts.at` and the contributor**

Build the model for the completion copy (`AslModel.of(parameters.position.containingFile)`; the name check uses the original file). Register one contributor for language `yaml` and one for `JSON`, both the same class and both with `order="first"`. Whenever `CompletionContexts.at` returns a context, add the items and call `result.stopHere()`: the YAML plugin otherwise adds word completion and the keys of sibling list items, which do not belong in a structural position. When there is no context (free-form data, other files), add nothing and do not stop the other contributors. A key item shows the field's value kind as type text and is bold when required; required fields sort first, then alphabetical. In a state that has no valid `Type` yet, offer `Type`, `Comment` and `QueryLanguage` only. Insert handler for YAML: scalar kinds insert `Name: `; `Struct`, `StatesMap` and `FreeForm` insert `Name:` and a new line one level deeper; `ListOf` and `ErrorNames` insert `Name:`, a new line one level deeper and `- `. For JSON insert the name only. Values: `OneOf` gives its values with their descriptions; `Bool` gives `true` and `false`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.completion.FieldCompletionTest"`
Expected: 13 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src && git commit -m "feat: completion for ASL field names and fixed values" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 11: Completion of state types, state names, error names and resources

**Files:**
- Create: `completion/StateTemplates.kt`
- Modify: `completion/AslCompletionContributor.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/completion/ValueCompletionTest.kt`

**Interfaces:**
- Consumes: `CompletionContext.Value`, `AslSpec.stateTypes`, `AslSpec.errorNames`, `AslSpec.resourceArns`, `AslStateMachine.states`.
- Produces: `object StateTemplates { fun yamlBody(type: String, queryLanguage: QueryLanguage): List<String> }`, the lines inserted after `Type: <type>` for a new YAML state, relative to the indentation of `Type`, with `<caret>` in exactly one line or none:

| Type | Lines |
|---|---|
| Task | `Resource: <caret>`, `Next: ` |
| Pass | `Next: <caret>` |
| Wait | `Seconds: <caret>`, `Next: ` |
| Choice, JSONata | `Choices:`, `  - Condition: '{% <caret> %}'`, `    Next: ` |
| Choice, JSONPath | `Choices:`, `  - Variable: <caret>`, `    Next: ` |
| Parallel | `Branches:`, `  - StartAt: <caret>`, `    States:`, `Next: ` |
| Map | `ItemProcessor:`, `  StartAt: <caret>`, `  States:`, `Next: ` |
| Succeed, Fail | none |

- [ ] **Step 1: Write the failing tests**

```kotlin
class ValueCompletionTest : AslTestCase() {
    private val two = "QueryLanguage: JSONata\nStartAt: First\nStates:\n  First:\n    Type: Pass\n    Next: Second\n  Second:\n    Type: Succeed\n"

    fun `test state types`() =
        assertEquals(listOf("Task", "Pass", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map"), complete(two + "  Third:\n    Type: <caret>"))
    fun `test choosing a type on a new state inserts its required fields`() {
        myFixture.configureByText("test.asl.yaml", two + "  Third:\n    Type: Tas<caret>")
        myFixture.completeBasic()
        myFixture.checkResult(two + "  Third:\n    Type: Task\n    Resource: <caret>\n    Next: ")
    }
    fun `test choosing a type on a state with other fields inserts only the type`() {
        myFixture.configureByText("test.asl.yaml", two + "  Third:\n    Type: Tas<caret>\n    Comment: x")
        myFixture.completeBasic()
        myFixture.checkResult(two + "  Third:\n    Type: Task<caret>\n    Comment: x")
    }
    fun `test choice template follows the query language`() {
        assertTrue(StateTemplates.yamlBody("Choice", QueryLanguage.JSONATA).any { "Condition" in it })
        assertTrue(StateTemplates.yamlBody("Choice", QueryLanguage.JSONPATH).any { "Variable" in it })
        assertEquals(emptyList<String>(), StateTemplates.yamlBody("Succeed", QueryLanguage.JSONATA))
    }
    fun `test state names of the same machine for Next Default and StartAt`() {
        assertEquals(listOf("First", "Second"), complete(two.replace("Next: Second", "Next: <caret>")).sorted())
        assertEquals(listOf("First", "Second"), complete(two.replace("StartAt: First", "StartAt: <caret>")).sorted())
    }
    fun `test state names do not leak across machines`() {
        val text = two + "  Each:\n    Type: Map\n    ItemProcessor:\n      StartAt: <caret>\n      States:\n        Inner:\n          Type: Succeed\n    End: true"
        assertEquals(listOf("Inner"), complete(text))
    }
    fun `test predefined error names`() {
        val items = complete(two.replace("    Next: Second", "    Next: Second\n    Catch:\n      - ErrorEquals:\n          - <caret>").replace("Type: Pass", "Type: Task\n    Resource: x"))
        assertTrue(items.containsAll(listOf("States.ALL", "States.Timeout", "States.TaskFailed")))
    }
    fun `test resource arns`() {
        val items = complete(two + "  Third:\n    Type: Task\n    Resource: <caret>")
        assertTrue(items.containsAll(listOf("arn:aws:states:::lambda:invoke", "arn:aws:states:::dynamodb:putItem")))
    }
    fun `test json state names`() =
        assertEquals(listOf("A", "B"), complete("""{"StartAt": "<caret>", "States": {"A": {"Type": "Succeed"}, "B": {"Type": "Succeed"}}}""", "test.asl.json").sorted())
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.completion.ValueCompletionTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the value completions and `StateTemplates`**

State types appear in specification order (use a fixed lookup priority, not alphabetical sorting) with their description as tail text. The template is inserted only in YAML and only when the state mapping has no key other than `Type`. State names come from the machine the value belongs to: for `StartAt` the machine that has the entry, otherwise the machine of the enclosing state. Resource ARNs match on any part of the ARN, so typing `dynamo` finds them.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.completion.*"`
Expected: all passed.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat: completion for state types, state names, error names and resources" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 12: Quick documentation

**Files:**
- Create: `docs/AslDocumentation.kt`, `docs/AslDocumentationProvider.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/docs/AslDocumentationTest.kt`

**Interfaces:**
- Consumes: `AslModel.of`, `SchemaIndex.field`, `AslSpec`.
- Produces: `object AslDocumentation { fun html(element: PsiElement): String? }`. `element` is any PSI element at or inside a key or a scalar value. Returns null outside ASL files, inside free-form data, and for elements that are neither a known field name, a state type value, nor a fixed-list value.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AslDocumentationTest : AslTestCase() {
    private fun docAtCaret(text: String, name: String = "test.asl.yaml"): String? {
        myFixture.configureByText(name, text.trimIndent())
        return AslDocumentation.html(myFixture.file.findElementAt(myFixture.caretOffset)!!)
    }
    private val head = "QueryLanguage: JSONata\nStartAt: A\nStates:\n  A:\n    Type: Task\n"

    fun `test field name`() {
        val html = docAtCaret(head + "    Argu<caret>ments: {}")!!
        assertTrue("Arguments" in html)
        assertTrue("JSONata" in html)                                            // says which query language it belongs to
        assertTrue(AslSpec.structure(StructureId.TASK).field("Arguments")!!.description in html)
        assertTrue("https://docs.aws.amazon.com/step-functions/" in html)
    }
    fun `test required field says so`() =
        assertTrue("required" in docAtCaret(head + "    Reso<caret>urce: x")!!.lowercase())
    fun `test state type value`() =
        assertTrue(AslSpec.stateType("Task")!!.description in docAtCaret("StartAt: A\nStates:\n  A:\n    Type: Ta<caret>sk")!!)
    fun `test fixed value`() =
        assertTrue("FULL" in docAtCaret(head + "    Retry:\n      - JitterStrategy: FU<caret>LL")!!)
    fun `test same field name documents the structure it is in`() =
        assertTrue(AslSpec.structure(StructureId.CATCHER).field("Next")!!.description in docAtCaret(head + "    Catch:\n      - ErrorEquals: [States.ALL]\n        Ne<caret>xt: A")!!)
    fun `test nothing for free-form keys state names and other files`() {
        assertNull(docAtCaret(head + "    Arguments:\n      Ne<caret>xt: 1"))
        assertNull(docAtCaret("StartAt: A\nStates:\n  <caret>A:\n    Type: Succeed"))
        assertNull(docAtCaret("Ty<caret>pe: Task", "plain.yaml"))
    }
    fun `test json`() =
        assertTrue("Resource" in docAtCaret("""{"StartAt": "A", "States": {"A": {"Type": "Task", "Reso<caret>urce": "x"}}}""", "test.asl.json")!!)
    fun `test the provider answers for the element at the caret`() {
        myFixture.configureByText("test.asl.yaml", head + "    Reso<caret>urce: x")
        val context = myFixture.file.findElementAt(myFixture.caretOffset)
        val provider = AslDocumentationProvider()
        val element = provider.getCustomDocumentationElement(myFixture.editor, myFixture.file, context, myFixture.caretOffset)
        assertNotNull(element)
        assertTrue("Resource" in provider.generateDoc(element, context)!!)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.docs.AslDocumentationTest"`
Expected: compilation fails, `AslDocumentation` unresolved.

- [ ] **Step 3: Implement `AslDocumentation.html`**

Layout with `DocumentationMarkup`: a definition line with the name and value kind; a line of facts (required or optional, query language if not both, value range, deprecated); the description; a link "AWS documentation" to `docUrl`.

- [ ] **Step 4: Implement and register the provider**

An `AbstractDocumentationProvider` registered as `lang.documentationProvider` for `yaml` and for `JSON`, both with `order="first"`. `getCustomDocumentationElement` returns the key or value element at the caret when `AslDocumentation.html` is non-null for it; `generateDoc` and `generateHoverDoc` return that HTML. If the plugin verifier in Task 16 reports this extension point as deprecated for build 262, replace the wiring with a `PsiDocumentationTargetProvider` and adapt the last test; `AslDocumentation` stays unchanged.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.docs.AslDocumentationTest"`
Expected: 8 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src && git commit -m "feat: quick documentation for ASL fields, state types and values" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 13: State names: references, usages and rename

**Files:**
- Create: `refs/AslStateReference.kt`, `refs/AslReferenceContributor.kt`, `refs/AslReferencesSearcher.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`, `src/test/kotlin/io/github/saigyo/aslan/AslTestCase.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/refs/StateReferenceTest.kt`

**Interfaces:**
- Consumes: `AslModel.of`, `SchemaIndex.field`, `SchemaIndex.stateNameKey`, `AslStateMachine.state`, `AslTransition`.
- Produces:
  - `class AslStateReference(element: PsiElement, range: TextRange) : PsiPolyVariantReferenceBase<PsiElement>`, on the `YAMLScalar` or `JsonStringLiteral` that is the value of a `StateName` field. It resolves to the `entryPsi` (`YAMLKeyValue` or `JsonProperty`) of every state with that name in the machine the value belongs to. `getVariants()` returns an empty array.
  - `class AslReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>`: when the search target is the `entryPsi` of a state, it feeds the references of all transitions and `StartAt` values that name it, read from the model. Stage 3 extends this class for variables.

- [ ] **Step 1: Write the failing tests**

Add this helper to `AslTestCase`: `protected fun highlightedUsages(): List<String>`. It calls `HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)`, then returns the document text of every highlighter in `myFixture.editor.markupModel.allHighlighters`, sorted by start offset. Do not use `myFixture.testHighlightUsages()` without arguments: it reconfigures the fixture with an empty file list and fails.

```kotlin
class StateReferenceTest : AslTestCase() {
    private val text = """
        StartAt: Inspect
        States:
          Inspect:
            Type: Task
            Resource: x
            Catch:
              - ErrorEquals: [States.ALL]
                Next: Rejected
            Next: Decide
          Decide:
            Type: Choice
            Choices:
              - Variable: $.ok
                BooleanEquals: true
                Next: Accepted
            Default: Rejected
          Accepted:
            Type: Succeed
          Rejected:
            Type: Fail
    """.trimIndent()
    private fun at(marker: String, replacement: String): PsiFile =
        myFixture.configureByText("test.asl.yaml", text.replaceFirst(marker, replacement))

    fun `test Next resolves to the state key`() {
        at("Next: Decide", "Next: Dec<caret>ide")
        val target = myFixture.elementAtCaret
        assertTrue(target is YAMLKeyValue)
        assertEquals("Decide", (target as YAMLKeyValue).keyText)
    }
    fun `test StartAt Default and catcher Next resolve`() {
        for ((marker, replacement, name) in listOf(
            Triple("StartAt: Inspect", "StartAt: Insp<caret>ect", "Inspect"),
            Triple("Default: Rejected", "Default: Rej<caret>ected", "Rejected"),
            Triple("Next: Rejected", "Next: Rej<caret>ected", "Rejected"))) {
            at(marker, replacement)
            assertEquals(name, (myFixture.elementAtCaret as YAMLKeyValue).keyText)
        }
    }
    fun `test unknown target does not resolve`() {
        at("Next: Decide", "Next: No<caret>where")
        assertNull(myFixture.getReferenceAtCaretPosition()!!.resolve())
    }
    fun `test no reference inside free-form data or in other files`() {
        myFixture.configureByText("test.asl.yaml", "StartAt: A\nStates:\n  A:\n    Type: Pass\n    Output:\n      Next: <caret>A\n    End: true")
        assertNull(myFixture.getReferenceAtCaretPosition())
        myFixture.configureByText("plain.yaml", "StartAt: <caret>A\nStates:\n  A:\n    Type: Succeed")
        assertFalse(myFixture.getReferenceAtCaretPosition() is AslStateReference)
    }
    fun `test find usages of a state`() {
        at("  Rejected:", "  Rej<caret>ected:")
        val usages = myFixture.findUsages(myFixture.elementAtCaret)
        assertEquals(listOf("Rejected", "Rejected"), usages.map { it.element!!.text })
    }
    fun `test usages are highlighted from a reference`() {
        at("Default: Rejected", "Default: Rej<caret>ected")
        assertEquals(listOf("Rejected", "Rejected", "Rejected"), highlightedUsages())
    }
    fun `test rename from the declaration changes key and references`() {
        at("  Rejected:", "  Rej<caret>ected:")
        myFixture.renameElementAtCaret("Refused")
        val result = myFixture.file.text
        assertEquals(0, Regex("Rejected").findAll(result).count())
        assertEquals(3, Regex("Refused").findAll(result).count())
    }
    fun `test rename from a reference`() {
        at("Next: Decide", "Next: Dec<caret>ide")
        myFixture.renameElementAtCaret("Sort")
        assertTrue("StartAt: Inspect" in myFixture.file.text && "  Sort:" in myFixture.file.text && "Next: Sort" in myFixture.file.text)
    }
    fun `test json`() {
        myFixture.configureByText("test.asl.json", """{"StartAt": "<caret>A", "States": {"A": {"Type": "Pass", "Next": "B"}, "B": {"Type": "Succeed"}}}""")
        assertEquals("A", (myFixture.elementAtCaret as JsonProperty).name)
        myFixture.renameElementAtCaret("First")
        assertTrue(""""StartAt": "First"""" in myFixture.file.text && """"First": {""" in myFixture.file.text)
    }
    fun `test a state name used in a nested machine is a different state`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: Each
            States:
              Each:
                Type: Map
                ItemProcessor:
                  StartAt: Do<caret>ne
                  States:
                    Done: { Type: Succeed }
                Next: Finish
              Finish: { Type: Succeed }
        """.trimIndent())
        val target = myFixture.elementAtCaret as YAMLKeyValue
        assertEquals("Done", target.keyText)
        assertEquals(1, myFixture.findUsages(target).size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.refs.StateReferenceTest"`
Expected: FAIL, no reference at caret.

- [ ] **Step 3: Implement the reference, the contributor and the searcher**

Register `psi.referenceContributor` for `yaml` and for `JSON` and `referencesSearch` for the searcher. The reference range covers the scalar's text without quotes. `handleElementRename` goes through `ElementManipulators`. The search restricts itself to the target's file.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.refs.StateReferenceTest"`
Expected: 10 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat: navigation, usages and rename for state names" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 14: Structure view

**Files:**
- Create: `AslIcons.kt`, `src/main/resources/icons/state-task.svg`, `state-pass.svg`, `state-choice.svg`, `state-wait.svg`, `state-succeed.svg`, `state-fail.svg`, `state-parallel.svg`, `state-map.svg`, `assign.svg`, `rule.svg`, `catch.svg`, `branch.svg`, `structure/AslStructureViewFactories.kt`, `structure/AslStructureViewModel.kt`, `structure/AslStructureElements.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/structure/AslStructureViewTest.kt`

**Interfaces:**
- Consumes: `AslModel.of`, `AslDocument`, `AslState`, `AslTransition`.
- Produces: `object AslIcons { fun forStateType(typeName: String?): Icon; val Assign: Icon; val Rule: Icon; val Catch: Icon; val Branch: Icon }`. Stage 4 reuses the type colours.

**Tree shape.** The root is the file. Its children are the states of the root machine in file order. A state node shows the state name with the type as location text. Its children, in this order:

1. For a Map: the states of its child machine. For a Parallel: one node `Branch 1`, `Branch 2`, ... per branch, each containing that branch's states.
2. One node `Assign <name>` per key of the state's own `Assign`.
3. One node `Rule <n>` per Choice rule, numbered from 1, with location text `→ <Next>`.
4. One node `Catch <error names joined by ", ">` per Catcher, with location text `→ <Next>`.

Each node navigates to its key or list item in the source. Icons are 16×16 SVG with a dark variant; type colours as in the mockup: Task `#56A8F5`, Choice `#E0A84E`, Pass `#8A8D93`, Map `#6AAB73`, Parallel `#C77DBB`, Fail `#DB5C5C`; choose distinct colours for Wait and Succeed.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AslStructureViewTest : AslTestCase() {
    private fun tree(): String {
        var result = ""
        myFixture.testStructureView { component ->
            PlatformTestUtil.expandAll(component.tree)
            result = PlatformTestUtil.print(component.tree, false)
        }
        return result
    }
    fun `test states with nested machines assignments rules and catchers`() {
        yaml("""
            QueryLanguage: JSONata
            StartAt: Load
            States:
              Load:
                Type: Task
                Resource: x
                Assign:
                  batch: 1
                  hasSeal: false
                Catch:
                  - ErrorEquals: [States.Timeout, States.TaskFailed]
                    Next: Failed
                Next: Sort
              Sort:
                Type: Choice
                Choices:
                  - Condition: '{% ${'$'}hasSeal %}'
                    Next: Each
                Default: Both
              Each:
                Type: Map
                ItemProcessor:
                  StartAt: One
                  States:
                    One: { Type: Succeed }
                Next: Both
              Both:
                Type: Parallel
                Branches:
                  - StartAt: Left
                    States:
                      Left: { Type: Succeed }
                  - StartAt: Right
                    States:
                      Right: { Type: Succeed }
                End: true
              Failed:
                Type: Fail
        """)
        assertEquals("""
            -test.asl.yaml
             -Load
              Assign batch
              Assign hasSeal
              Catch States.Timeout, States.TaskFailed
             -Sort
              Rule 1
             -Each
              One
             -Both
              -Branch 1
               Left
              -Branch 2
               Right
             Failed
        """.trimIndent(), tree().trim())
    }
    fun `test state node presentation`() {
        yaml("StartAt: A\nStates:\n  A:\n    Type: Wait\n    Seconds: 1\n    End: true")
        myFixture.testStructureView { component ->
            val root = component.treeModel.root
            val state = root.children.single() as StructureViewTreeElement
            assertEquals("A", state.presentation.presentableText)
            assertEquals("Wait", state.presentation.locationString)
            assertSame(AslIcons.forStateType("Wait"), state.presentation.getIcon(false))
        }
    }
    fun `test json`() {
        json("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "Next": "B"}, "B": {"Type": "Succeed"}}}""")
        assertEquals("-test.asl.json\n A\n B", tree().trim())
    }
    fun `test other yaml files keep the default structure view`() {
        yaml("services:\n  web:\n    image: x", "docker-compose.yaml")
        assertTrue("services" in tree())
    }
    fun `test a file that is not a state machine yet keeps the default structure view`() {
        yaml("- a\n- b")
        assertNotNull(tree())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.structure.AslStructureViewTest"`
Expected: FAIL, tree shows raw YAML nodes.

- [ ] **Step 3: Implement the icons, tree elements, model and the two factories**

`AslYamlStructureViewFactory : YAMLCustomStructureViewFactory` registered at `com.intellij.yaml.customStructureViewFactory`; `AslJsonStructureViewFactory : JsonCustomStructureViewFactory` registered at `com.intellij.json.customStructureViewFactory` with `order="first"`. Both return null unless `AslModel.of(file)?.root` is non-null, so other files and unreadable files keep the default view. The builder is a `TreeBasedStructureViewBuilder`; the model allows alphabetical sorting.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.structure.AslStructureViewTest"`
Expected: 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat: structure view of states by type" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 15: Semantic highlighting and placeholders

**Files:**
- Create: `highlight/AslHighlighting.kt`, `highlight/AslAnnotator.kt`, `highlight/AslColorSettingsPage.kt`, `highlight/PlaceholderUsagesHandlerFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/highlight/AslHighlightingTest.kt`

**Interfaces:**
- Consumes: `AslModel.of`, `SchemaIndex`, `Placeholders.ranges`, and `AslTestCase.highlightedUsages()` from Task 13.
- Produces:

```kotlin
object AslHighlighting {
    val FIELD: TextAttributesKey            // "ASL_FIELD", falls back to DefaultLanguageHighlighterColors.KEYWORD
    val STATE_NAME: TextAttributesKey       // "ASL_STATE_NAME", falls back to DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
    val STATE_REFERENCE: TextAttributesKey  // "ASL_STATE_REFERENCE", falls back to DefaultLanguageHighlighterColors.FUNCTION_CALL
    val PLACEHOLDER: TextAttributesKey      // "ASL_PLACEHOLDER", falls back to DefaultLanguageHighlighterColors.METADATA
}
```

Stage 2 adds JSONata keys to the same colour settings page.

- [ ] **Step 1: Write the failing tests**

```kotlin
class AslHighlightingTest : AslTestCase() {
    private fun marks(text: String, name: String = "test.asl.yaml"): List<Pair<String, String>> {
        myFixture.configureByText(name, text.trimIndent())
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey?.externalName?.startsWith("ASL_") == true }
            .sortedBy { it.startOffset }
            .map { it.forcedTextAttributesKey.externalName to it.text }
    }
    private val text = """
        StartAt: Load
        States:
          Load:
            Type: Task
            Resource: ${'$'}{LoadFn}
            Arguments:
              Type: free
              ${'$'}{ShelfTable}:
                Prefix: batches/${'$'}{Stage}/in
            Next: Done
          Done:
            Type: Succeed
    """
    fun `test fields state names references and placeholders`() {
        assertEquals(listOf(
            "ASL_FIELD" to "StartAt", "ASL_STATE_REFERENCE" to "Load", "ASL_FIELD" to "States",
            "ASL_STATE_NAME" to "Load", "ASL_FIELD" to "Type", "ASL_FIELD" to "Resource", "ASL_PLACEHOLDER" to "\${LoadFn}",
            "ASL_FIELD" to "Arguments", "ASL_PLACEHOLDER" to "\${ShelfTable}", "ASL_PLACEHOLDER" to "\${Stage}",
            "ASL_FIELD" to "Next", "ASL_STATE_REFERENCE" to "Done",
            "ASL_STATE_NAME" to "Done", "ASL_FIELD" to "Type"), marks(text))
    }
    fun `test json`() {
        val m = marks("""{"StartAt": "A", "States": {"A": {"Type": "Task", "Resource": "${'$'}{Fn}", "End": true}}}""", "test.asl.json")
        assertTrue(m.containsAll(listOf("ASL_FIELD" to "\"StartAt\"", "ASL_STATE_NAME" to "\"A\"", "ASL_PLACEHOLDER" to "\${Fn}")))
    }
    fun `test nothing in other files`() =
        assertEquals(emptyList<Any>(), marks("Type: Task\nResource: ${'$'}{Fn}", "plain.yaml"))
    fun `test caret on a placeholder highlights its other occurrences`() {
        myFixture.configureByText("test.asl.yaml", "StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: \${F<caret>n}\n    Arguments:\n      a: \${Fn}\n      b: x-\${Fn}-y\n      c: \${Other}\n    End: true")
        assertEquals(listOf("\${Fn}", "\${Fn}", "\${Fn}"), highlightedUsages())
    }
    fun `test colour settings page lists the four keys`() {
        val page = AslColorSettingsPage()
        assertEquals(setOf("ASL_FIELD", "ASL_STATE_NAME", "ASL_STATE_REFERENCE", "ASL_PLACEHOLDER"), page.attributeDescriptors.map { it.key.externalName }.toSet())
        assertTrue(page.demoText.isNotBlank())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.github.saigyo.aslan.highlight.AslHighlightingTest"`
Expected: compilation fails, `AslColorSettingsPage` unresolved.

- [ ] **Step 3: Implement the annotator**

One `Annotator` registered for `yaml` and for `JSON`. For a key element: `FIELD` when `schema.field(key)` is non-null, `STATE_NAME` when `schema.stateNameKey(key)` is non-null. For a scalar that is the value of a `StateName` field: `STATE_REFERENCE`. For every key and every scalar in the file, including inside free-form data: `PLACEHOLDER` on each `Placeholders.ranges` occurrence. All annotations are silent information annotations with `textAttributes(key)`.

- [ ] **Step 4: Implement the colour settings page and the placeholder usage handler**

Register the page with `colorSettingsPage`, display name "Amazon States Language", demo text a short definition in the film archive domain with tags for the four keys. Register `highlightUsagesHandlerFactory` for the placeholder handler: when the caret is inside a placeholder in an ASL file, the targets are all occurrences of exactly that placeholder text in the file.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests "io.github.saigyo.aslan.highlight.AslHighlightingTest"`
Expected: 5 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src && git commit -m "feat: semantic highlighting and placeholder support" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

---

### Task 16: Private file check, compatibility and hand-over

**Files:**
- Create: `NOTICE`
- Modify: `README.md`, `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/fixtures/PrivateFilesTest.kt`

**Interfaces:**
- Consumes: `AslFiles.isAslFileName`, `AslChecker.findings`, the system property `aslan.privateFixtures` from Task 1.

- [ ] **Step 1: Write the private file test**

```kotlin
class PrivateFilesTest : AslTestCase() {
    fun `test private definitions show no errors`() {
        val dirs = listOfNotNull(System.getProperty("aslan.privateFixtures")?.takeIf { it.isNotBlank() }, "private-fixtures").map(::File)
        val files = dirs.filter { it.isDirectory }.flatMap { it.listFiles()!!.toList() }.filter { it.isFile && AslFiles.isAslFileName(it.name) }
        if (files.isEmpty()) { println("PrivateFilesTest: no private definitions present, nothing checked"); return }
        for (f in files) {
            val psi = myFixture.configureByText(f.name, f.readText())
            val errors = AslChecker.findings(psi).filter { it.problem.level == HighlightDisplayLevel.ERROR }
            assertEquals(f.name, emptyList<String>(), errors.map { "${it.problem} at line ${psi.viewProvider.document!!.getLineNumber(it.element.textOffset) + 1}" })
        }
    }
}
```

Only the direct children of each directory are read. The failure message gives the problem and line number and never file content.

- [ ] **Step 2: Run it against the private definition**

Run: `./gradlew test --tests "io.github.saigyo.aslan.fixtures.PrivateFilesTest" -Paslan.privateFixtures="$PWD"`
Expected: PASS. The test output says so when no private definition was found; report that in the task report. On a failure, find out from the specification whether the rule is wrong. If it is, fix the rule and add a test row with an invented example to the task that owns the rule. Do not copy anything from the private file into a test.

- [ ] **Step 3: Run the whole suite and the plugin verifier**

Run: `./gradlew test verifyPlugin buildPlugin`
Expected: `BUILD SUCCESSFUL`; the verifier reports the plugin as compatible with 2026.2.3 and 2026.3, with no "deprecated API" finding for the documentation provider. If it reports one, apply the fallback named in Task 12.

- [ ] **Step 4: Complete `README.md`, write `NOTICE` and the plugin description**

`README.md`: after the badge, add a **Features** section listing what stage 1 provides (completion, validation with the error and warning policy in two sentences, quick documentation, state-name navigation and rename, structure view, highlighting, placeholders), and a **Recognised files** section (the three name suffixes and where to add patterns in settings). Add to **Building** how to run the private file check (`-Paslan.privateFixtures=<directory>`, or a git-ignored `private-fixtures/` directory). Update **Status** to say stage 1 is complete. `NOTICE`: the plugin's name and licence, and a line that the field descriptions are original and link to the AWS documentation. Update the `<description>` in `plugin.xml` to list the stage 1 features.

- [ ] **Step 5: Commit**

```bash
git add README.md NOTICE src && git commit -m "docs: README, notice and plugin description for stage 1" -m "Co-Authored-By: <your model name> <noreply@anthropic.com>"
```

- [ ] **Step 6: Confirm the CI run**

Ask the human partner before pushing. Once the branch is pushed or a pull request is open, run `gh run list --workflow build.yml --limit 1` and expect the latest run to have conclusion `success`. On a failure, read the log with `gh run view --log-failed`, fix the cause, and repeat. A failure that appears only on the runner (for example a test that depends on the local machine) is a defect to fix, not to skip.

- [ ] **Step 7: Hand the manual check to the human partner**

Automated tests cannot cover editor behaviour fully. Report that the stage is ready for a manual check and give this list. Start with `./gradlew runIde`, open `src/test/testData/clean/film-archive-intake.asl.yaml` in the sandbox IDE, then:

1. The file shows no errors or warnings. ASL field names, state names and placeholders are coloured.
2. On a new line in a Task state, completion offers the missing fields; choosing `Retry` starts a list item.
3. Under `States`, typing a new state name, `Type: ` and choosing `Task` inserts `Resource` and `Next`.
4. Hovering a field name shows its documentation with a working link.
5. Ctrl-click on a `Next` target jumps to the state; Find Usages on a state key lists its transitions; renaming a state updates every transition.
6. The Structure tool window lists states by type, with nested states under the Map and Parallel states.
7. Changing `Type: Task` to `Type: Tsak` shows an error; removing a state that others point to shows errors at those transitions.
8. Opening any ordinary YAML file shows none of the above.
9. The private definition shows no errors.
