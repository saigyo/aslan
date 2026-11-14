# Aslan Stage 3: State Machine Variables Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** State machine variables can be followed between the places they are assigned and the places they are read: highlighted with distinct colours for assignments and reads, stepped through, found, renamed, completed and checked.

**Architecture:** A new part of the document model, `AslVariables`, lists every assignment (a key under an `Assign` mapping) and every read (a `$name` in JSONata that is no local variable, or a `$name` in a string JSONPath evaluates), and gives each one its identity: the name and the owning `States` object. Everything else reads that model: references from reads to assignments, an own usage-highlighting handler, a find-usages handler, a rename processor that writes every occurrence into the host document in one pass, completion, and five new checks in the existing one-inspection-per-problem mechanism.

**Tech Stack:** as in stage 2 (Kotlin 2.4, Java 25, IntelliJ Platform Gradle Plugin 2.20.1, IntelliJ Platform 2026.2, JUnit 4 platform tests). No new dependencies. Python 3 for the one-off AWS probe generator, never in the build.

**Spec:** `docs/superpowers/specs/2026-11-13-aslan-design.md`, sections 4 (variable names, scopes, Distributed Map), 6.4, 7 (the variable rows of the warnings table), 8 and 10. Stage 4 (diagram) has its own plan; its details panel will read `AslVariables`.

**Branch:** `stage3/variables`, created from `origin/main` after pull request #3 is merged. Its checks are a precondition.

## Decisions made in this plan

These are choices of this plan, not of the commissioner. Each can be overruled before execution starts. Task 10 writes the ones that stand into the design document.

1. **AWS is asked before any variable check becomes an error.** The recorded answers already show a difference the spec does not make: a read of a variable that is assigned nowhere is a deployment error in a JSONPath field (`UNDEFINED_VARIABLE`, probes `r3-AI-dollar-word-*` and `r3-AC-parameters-dollar-word`) but only a warning in JSONata (`POSSIBLY_UNDEFINED_VARIABLE`, probes `r9-p-variable` and `r2-B-role-arn`). Round 11 (Task 1) asks how AWS decides "defined" across scopes, and about names AWS may reject. Task 9 sets the levels from the answers.
2. **An assigned name wins over a built-in function.** `$sum` is a read of the variable `sum` when an assignment of `sum` is visible from the expression's state (JSONata binds variables in front of the built-ins); otherwise it is the built-in. A callee `$name(…)` without a visible assignment stays a function call: stage 2's unknown-function warning covers it, and no unassigned-read finding is added on top.
3. **The order of states is not modelled.** A read resolves to an assignment in its scope wherever that assignment stands in the flow. "May be unassigned on some path" is a non-goal of the spec. If AWS rejects a read whose only assignment comes later in the flow, that is a named gap.
4. **The Distributed Map boundary** is a Map whose `ItemProcessor.ProcessorConfig.Mode` is the string `DISTRIBUTED`. A `Mode` that is a placeholder, tagged or of the wrong kind leaves the boundary unknown, and no variable finding is reported for reads or assignments in that Map's processor.
5. **Go to declaration from a read lists the assignments** with their place ("ReserveStock", "ReserveStock, second Choice rule", "ReserveStock, third Catcher"), through an own `GotoDeclarationHandler`. The reference itself resolves to the first assignment in file order, so rename and find usages started on a read reach the variable.
6. **Rename goes through the dialog, not in place.** One rename edits YAML or JSON keys, injected expressions and JSONPath strings. It writes every occurrence as a range of the host document in one pass, as stage 2's local rename does, and refuses (as a conflict) whenever it cannot prove the result reads the same (Task 6).
7. **A new variable name** is a letter or `_`, followed by letters, digits and `_` (`Character.isLetter` and `Character.isDigit`, so Unicode letters count), at most 80 code points, not `states`, and `$name` must scan as one JSONata variable token. The `INVALID_VARIABLE_NAME` check uses the same rule unless the AWS answers of Task 9 call for another.
8. **Usage highlighting is the plugin's own handler**, built from the model, in the host editor and in an injected editor, as for placeholders in stage 1. The platform's identifier highlighting cannot connect a YAML key with a `$name` inside an injected fragment.
9. **"Assigned but never read" stays silent when in doubt:** it is not reported for a name whose `$name` appears in any string of the file outside the reads the model knows (an expression with a syntax error, a string in a state of unknown type), nor in a file whose variable scopes are open (decision 4, Task 3 `isOpen`).
10. **No quick documentation for state variables, and no completion in JSONPath strings.** The spec asks for neither.

## Global Constraints

- Everything in the Global Constraints of the stage 1 and stage 2 plans still holds: plugin ID and base package `io.github.saigyo.aslan`, minimum IDE version 2026.2, Java 25, no network requests, no JSON Schema, no Symbol API, the invented film archive domain for all committed test data, and the private definition that is never committed, copied from or named.
- A problem is an **error** only if AWS rejects the definition. Everything else is a warning at most. **No false error** outranks every other requirement; where a rule is uncertain, the plugin stays silent.
- A variable is identified by its name and its owning scope (spec 6.4, "Scope and identity"); highlighting, find usages, rename, completion and the checks all use `AslVariables` and nothing else.
- The JSONata scope rule of stage 2 decides what is local (`JsonataScopes`). Stage 3 adds nothing to it.
- Tests assert problem identifiers, elements, ranges and structure, never message text. The place texts of decision 5 are fixed copy and are asserted.
- Stage files by explicit path. Never `git add -A` or `git add .`. The commissioner may have uncommitted changes to test files, `src/test/testData/clean/film-archive-intake.asl.yaml` in particular; never commit them.
- Every commit message ends with `Co-Authored-By: <the Claude model that made the commit> <noreply@anthropic.com>`.
- Pushing, opening a pull request and merging are the commissioner's decisions. AWS calls are run by the commissioner from scripts this plan prepares, given with absolute paths.
- After each task: `./gradlew test` passes, and `./gradlew test -Paslan.privateFixtures="$PWD"` reports the private definition as checked with no errors.

## IDE tools

IntelliJ has this project open and is connected through its MCP server. Its tools are listed to a subagent by name only; load each one with ToolSearch (`select:<name>`) before the first call. Paths are relative to the project root. The IDE has the main checkout open, so the plan runs there, not in a separate worktree.

- `mcp__idea__generate_psi_tree`: the real PSI of a YAML, JSON or JSONata snippet. Use it before writing code that walks a tree, instead of guessing the element types or writing a scratch test.
- `mcp__idea__get_file_problems`: the compile errors of a Kotlin file after an edit, without a Gradle run. `./gradlew test` still decides.
- `mcp__idea__get_symbol_info`, `mcp__idea__search_symbol`: the declaration and documentation of a symbol at a position, and the project's own symbols by name. A platform class used in the project resolves to its compiled declaration. `search_symbol` does not find platform classes, so for a platform class not used yet, `javap` on the jar in the Gradle cache is still the way.

Edit files with the Edit and Write tools, not with shell or Python scripts, so every change shows as an edit.

**Escapes.** The Edit and Write tools turn a backslash followed by `u` and four hex digits in their input into the character it stands for, without a warning, and an Edit whose only change is such an escape fails with "old_string and new_string are exactly the same". Tests of this stage depend on escapes (Review Focus 3); a lost one leaves a test that checks the plain name and still passes. So:
- In Kotlin, never type that sequence: build it as `"\\" + "u0024"` (or `"${'\\'}u0024"`), as the stage 2 tests do. The same holds for the YAML and JSON text inside test strings.
- In a file that must hold the sequence literally (test data, Markdown), write that one change with a script that builds the backslash as `chr(92)`; that is the one exception to the rule above.
- After writing, confirm each escape with `grep -n 'u0024' <file>` or `od -c`, and say in the report that you did.

## Review Focus

Inputs the spec implies but does not spell out. Each has a test in the task named.

1. **YAML anchors and aliases across scopes.** An `Assign` mapping or an expression shared through an alias by states in two Parallel branches is an occurrence of each branch's variable: highlighting and find usages show it for both, and a rename refuses. Tasks 3 and 6.
2. **Definitions that do not say every name.** An `Assign` written as a placeholder, as one JSONata string, or with a placeholder or unapplied merge key; a `States` that is open; a Map whose `Mode` is a placeholder. No unassigned-read and no never-read finding where the answer depends on the unknown part. Task 8.
3. **Escapes in the host text.** `\u0024total` or `$\u0074otal` in a JSON string, a key written `"\u0074otal"`, an escape in a double-quoted YAML scalar: the names compare decoded, navigation works, and a rename refuses where the raw text of an occurrence is not the name. Tasks 3 and 6.
4. **A name that is also local or built in.** `$sum` assigned with `:=` inside an expression is local there and no read of the state variable `sum`; a state variable `sum` turns `$sum` into a read where it is visible; completion offers what a use would read. Tasks 3 and 7.
5. **Half-typed files.** An `Assign` key being typed, an expression with a syntax error that mentions `$fault`, an `Assign:` with no value. No exception; rename refuses when an unread mention exists; never-read stays silent. Tasks 3, 6 and 8.

## File Structure

Paths are under `src/main/kotlin/io/github/saigyo/aslan/` unless they start with another directory.

| File | Responsibility |
|---|---|
| `model/JsonPathReads.kt` | the strings JSONPath evaluates, and the `$name` reads in them |
| `model/AslVariables.kt` | assignments, reads, scopes and identity; `AslDocument.variables` |
| `refs/AslVariableReferences.kt` | the references of JSONata and JSONPath reads, and the JSONPath reference provider |
| `refs/AslVariableNavigation.kt` | go to declaration with place texts, find usages handler, read/write access detector, searcher part |
| `highlight/VariableUsagesHandlerFactory.kt` | usage highlighting of a variable in host and injected editors |
| `refs/AslVariableRename.kt` | rename processor, dialog, name rule, conflicts, the host-document writer |
| `jsonata/completion/JsonataCompletionContributor.kt` | state variables after `$` |
| `check/VariableChecks.kt` | the five variable checks |
| `src/test/kotlin/io/github/saigyo/aslan/variables/*Test.kt` | the tests of this stage, with a `VariableTestCase` base |

## Execution

Subagent-driven, coordinated by the session that holds the plan (Opus 5.5). A fresh subagent implements each task; reviews run at the five points below. Between reviews the coordinator reads each report and runs `./gradlew test` and the private check itself before the next task starts. The ledger is kept in `.superpowers/sdd/` while the plan runs and moved to `.superpowers/ledgers/2026-11-14-aslan-stage3-variables.md` at the end. Every implementer and reviewer dispatch points to the Global Constraints and the IDE tools sections; a dispatch that touches PSI names `generate_psi_tree` for the step where it helps.

**Order.** Tasks run in their numbered order. Task 9 needs the answers of round 11; if they have not arrived when Task 8 is done, the coordinator asks for them and waits.

| Task | Model | Reason |
|---|---|---|
| 1 AWS round 11 | Sonnet 5.5 | probe definitions from a complete list |
| 2 Reads in JSONPath strings | Sonnet 5.5 | a small scanner with given rules, and a collector modelled on `JsonataSites` |
| 3 The variable model | Opus 5.5 | scopes, aliases, injected files and open definitions in one place |
| 4 References and navigation | Opus 5.5 | several platform extension points that have to agree on one target |
| 5 Usage highlighting | Sonnet 5.5 | modelled on `PlaceholderUsagesHandlerFactory`; to Opus 5.5 if ranges in injected editors go wrong |
| 6 Rename | Opus 5.5 | one write across host keys, injected text and JSONPath strings, and the conflict rules |
| 7 Completion | Sonnet 5.5 | given rules |
| 8 Variable checks | Opus 5.5 | the open-definition rules decide between a missed and a false finding |
| 9 The answers of AWS | the coordinator (Opus 5.5) | levels are decisions against the two anti-goals |
| 10 Hand-over | Sonnet 5.5 for the texts; the coordinator for verification and the report | |

An implementer that is blocked, or whose work fails a review twice, is replaced by the next model up.

| After task | Covers | Reviewer | What it looks at |
|---|---|---|---|
| 1 | 1 | Opus 5.5, medium | Each probe is valid apart from the one thing it asks; the groups cover every scope relation of decision 1. Runs before the command goes to the commissioner. |
| 3 | 2, 3 | Opus 5.5, high | Identity against spec 6.4 for nested, sibling and distributed machines; aliases; what counts as a read (Review Focus 4); open definitions. |
| 6 | 4, 5, 6 | Opus 5.5, high | One target for references, find usages and rename; host ranges in every scalar style; every conflict rule of Task 6 has a test, and no rename can change what the definition reads. |
| 9 | 7, 8, 9 | Opus 5.5, medium | Each level rests on probes named in the ledger; nothing is an error for a kind of case no probe covers; the clean files and the private definition show nothing. |
| 10 | the whole branch | Fable 5.2, high (Opus 5.5, high, if Fable is unavailable) | The branch against spec 6.4 and the two anti-goals; what only shows across tasks. |

---

### Task 1: AWS round 11

Asks AWS how it decides whether a variable is defined, and about assignments it may reject. Nothing waits for the answers until Task 9.

**Files:**
- Create, not committed (`.superpowers/` is git-ignored): `.superpowers/aws-probes/round-11/make_probes11.py`, `run.sh` (copied from `round-10/`), the probe files, `INDEX.tsv`

- [ ] **Step 1: Write `make_probes11.py`.** It writes one JSON definition per probe and an `INDEX.tsv` of name and one-line purpose. File names must not differ only in letter case; it asserts that. Unless a probe says otherwise, a probe is otherwise valid: every state reachable, a terminal state, real Task resources (`arn:aws:states:::lambda:invoke` with `FunctionName`), names from the film archive domain. "Read" below means `"InputPath": "$reel"` in a JSONPath machine (group `r11-p-`) and `"Output": "{% $reel %}"` in a JSONata machine (group `r11-j-`); each scenario exists in both groups, with the same suffix.

| Suffix | Scenario |
|---|---|
| `none` | `reel` is assigned nowhere |
| `before` | assigned in the Assign of the previous state |
| `same-state` | assigned in the Assign of the reading state itself |
| `later` | assigned only in a state after the reading one |
| `inline-map-inner` | assigned only in a state inside an inline Map's processor, read after the Map |
| `inline-map-outer` | assigned before an inline Map, read inside its processor |
| `distributed-outer` | assigned before a Distributed Map, read inside its processor |
| `parallel-sibling` | assigned in branch 1 of a Parallel, read in branch 2 |
| `parallel-after` | assigned only inside branch 1, read after the Parallel |
| `catcher` | assigned in a Catcher's Assign, read in the Catcher's target |
| `choice-rule` | assigned in a Choice rule's Assign, read in the rule's `Next` |
| `jsonpath-dollar-key` (`r11-p-` only) | assigned as `"reel.$": "$.title"`, read in the next state |
| `intrinsic` (`r11-p-` only) | `"Parameters": {"FunctionName": "fn", "Payload.$": "States.Format('{}', $reel)"}`, `reel` assigned nowhere |
| `intrinsic-literal` (`r11-p-` only) | `States.Format('$reel')`, assigned nowhere |
| `path-field` (`r11-p-` only) | `"InputPath": "$reel.title"`, assigned nowhere |
| `choice-variable` (`r11-p-` only) | `"Variable": "$reel"` in a Choice rule, assigned nowhere |
| `builtin-name` (`r11-j-` only) | `sum` assigned before, read as `{% $sum %}` |

Group `r11-a-` (assignments, a JSONata machine with two Pass states where the first assigns, the second reads `{% $NAME %}` unless stated):

| Suffix | Assignment |
|---|---|
| `states` | key `states` |
| `digit-first` | `1reel` |
| `hyphen` | `reel-count` |
| `dot` | `reel.count` (JSONata machine, so `.` is no suffix) |
| `space` | `reel count` |
| `unicode-1`, `unicode-2` | `bobine_nº`; `ÉtatBobine` |
| `underscore` | `_reel` |
| `len-80`, `len-81` | `r` repeated 80 and 81 times |
| `inline-map-shadow` | `reel` assigned at the top level and again inside an inline Map's processor |
| `parallel-shadow` | `reel` assigned at the top level and again in a Parallel branch |
| `distributed-shadow` | `reel` assigned at the top level and again inside a Distributed Map's processor |
| `siblings` | `reel` assigned in both branches of a Parallel, nowhere outside |
| `nested-choice` | a JSONPath machine whose Choice rule `And` holds a sub-rule with `Assign` |

Expected: 43 files (16 `r11-p-`, 12 `r11-j-`, 15 `r11-a-`).

- [ ] **Step 2: Commit nothing; hand over.** After the review of this task, give the commissioner `sh <absolute repo path>/.superpowers/aws-probes/round-11/run.sh`. It writes `aws-results.tsv` beside the probes. Go on with Task 2 without waiting.

---

### Task 2: Reads in JSONPath strings

**Files:**
- Create: `model/JsonPathReads.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/variables/JsonPathReadsTest.kt`, `VariableTestCase.kt`

**Interfaces:**
- Produces:

```kotlin
/** A `$name` read in a JSONPath string: [name] without `$`; [start] and [end] in the decoded text, `$` included. */
data class PathName(val name: String, val start: Int, val end: Int)

object JsonPathReads {
    /** The variable reads in [text], a path or an intrinsic function call. */
    fun names(text: String): List<PathName>
    /** The strings of [doc] that JSONPath evaluates (see Step 3), with the state each belongs to; one entry per use through an alias. */
    fun sites(doc: AslDocument): List<Pair<AslScalar, AslState>>
}
```

- [ ] **Step 1: Write the tests.** `VariableTestCase` extends `AslTestCase` and has `fun machine(states: String, top: String = ""): String`, a JSONPath or JSONata machine (`top` holds `QueryLanguage` and other top-level lines) whose `States` are [states] indented under it.

```kotlin
fun `test a path that starts with a variable`()     // names("$reel") == [PathName("reel", 0, 5)]; "$reel.title[0]" -> reel 0..5; "$bobine_nº.x" -> "bobine_nº"
fun `test paths that read no variable`()             // "$", "$$", "$.reel", "$$.Execution.Id", "$[0]", "reel", "" -> []
fun `test intrinsic arguments`()                     // "States.Format('{} $no', $a, States.ArrayGetItem($b, 0))" -> a, b with their offsets
fun `test quoted text in an intrinsic is no read`()  // "States.Format('it\\'s $no', $c)" -> c only
fun `test the strings JSONPath evaluates`()          // sites() of a JSONPath machine: "Payload.$" in Parameters, "x.$" in ResultSelector,
                                                     // ItemSelector and Assign, InputPath, OutputPath, ItemsPath, a Choice rule's Variable,
                                                     // StringEqualsPath; not ResultPath, not a plain "Payload" value, not a JSONata state's strings
fun `test an aliased path is a site for each state`()
```

- [ ] **Step 2: Run** `./gradlew test --tests '*JsonPathReadsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** `names`: text starting with `$` and a name character gives one read of the longest run of name characters (`Character.isLetterOrDigit` or `_`). Text starting with `States.` is an intrinsic: walk it, skipping single-quoted literals (`\'` and `\\` are escapes inside them); right after `(` or `,`, and after spaces, a `$` with a name character starts a read. Any other text reads nothing. `sites`: in every state whose query language is known to be JSONPath, the certain string values (not ambiguous, not dynamic) of (a) every entry whose key ends in `.$` inside the free-form fields, at any depth, (b) every field of the state and of its nested structures (Choice rules with their `And`, `Or` and `Not` sub-rules, Catchers, ItemReader, ItemBatcher and the others) whose name ends in `Path`, except `ResultPath`, and (c) `Variable` in a Choice rule. Walk the structures with `AslSpec` as `JsonataSites.Collector` does.
- [ ] **Step 4: Run** the test. Expected: PASS. **Step 5: Commit** `feat: find variable reads in JSONPath strings`.

---

### Task 3: The variable model

**Files:**
- Create: `model/AslVariables.kt`
- Modify: `model/AslDocument.kt` (`val variables: AslVariables by lazy { AslVariables(this) }`)
- Test: `src/test/kotlin/io/github/saigyo/aslan/variables/AslVariablesTest.kt`

**Interfaces:**
- Consumes: `JsonPathReads` (Task 2), `JsonataSites`, `JsonataPsi.injectedFile`, `JsonataScopes`, `AslSpec.jsonataFunction`.
- Produces:

```kotlin
enum class AssignPlace { STATE, CHOICE_RULE, CATCHER }

class AslAssignment(val name: String, val entry: AslEntry, val state: AslState, val place: AssignPlace, val index: Int) {
    /** "ReserveStock", "ReserveStock, second Choice rule", "ReserveStock, third Catcher"; ordinal words up to "tenth", then "11th" */
    val placeText: String
}

sealed interface AslRead { val name: String; val state: AslState }
class JsonataRead(override val name: String, override val state: AslState, val variable: JsonataVariableRef, val site: JsonataSite) : AslRead
/** [range] is relative to `scalar.psi` and covers `$` and the name in the host text. */
class PathRead(override val name: String, override val state: AslState, val scalar: AslScalar, val range: TextRange) : AslRead

/** A state machine variable: its name and its owning `States` object (spec 6.4). */
data class AslVariable(val name: String, val scope: AslStateMachine)

class AslVariables internal constructor(doc: AslDocument) {
    val assignments: List<AslAssignment>                      // file order, one per use through an alias or merge
    val reads: List<AslRead>                                  // file order, one per site
    fun variableOf(assignment: AslAssignment): AslVariable
    fun variableOf(read: AslRead): AslVariable?               // null: no owning scope
    fun assignmentsOf(variable: AslVariable): List<AslAssignment>
    fun readsOf(variable: AslVariable): List<AslRead>
    fun assignmentsAt(entryPsi: PsiElement): List<AslAssignment>
    fun readsAt(variable: JsonataVariableRef): List<JsonataRead>
    fun pathReadsAt(host: PsiElement): List<PathRead>
    fun visibleNames(state: AslState): Set<String>            // assigned in the state's machine or one its reads can reach
    fun isOpen(state: AslState): Boolean                      // the file does not say every name a read in [state] can reach
}
```

**Rules.**
- *Assignments:* each key directly under an `Assign` mapping of a state, of a Choice rule (top-level rules only, as stage 1's `TOP_RULE_ONLY`) or of a Catcher. In a JSONPath state a key's `.$` suffix is not part of the name. Unapplied merge keys and keys with a placeholder are no assignments; they make the machine open.
- *Reads:* in each `JsonataSite`, every `JsonataVariableRef` of the injected file with a `JsonataScopes.localName`, no local declaration, a name other than `states`, and not a built-in function name unless that name is in `visibleNames(site.state)` (decision 2). In each `JsonPathReads.sites` string, each `PathName`; its host range comes from the host's `LiteralTextEscaper` (`getOffsetInHost`), so escapes are mapped.
- *Reach:* the chain of a machine is the machine, then the machine of its owner state, and so on outward, ending after a machine owned by a Distributed Map (decision 4). The owning scope of an assignment or read in state S is the outermost machine in the chain of S's machine in which the name is assigned; a read with none has no variable.
- *Open:* a machine is open when it is `AslStateMachine.open`, when an `Assign` in it is not a mapping or holds a placeholder or unapplied merge key, or when it is owned by a Map whose `Mode` is unknown. `isOpen(state)` is true when any machine in the chain of the state's machine is open.

- [ ] **Step 1: Write the tests** (YAML unless the name says JSON; each builds a small definition with `machine`).

```kotlin
fun `test assignments in a state, a Choice rule and a Catcher`()    // names, places, index, placeText "Check", "Check, second Choice rule", "Load, first Catcher"
fun `test the dollar suffix of a JSONPath key is not part of the name`()
fun `test JSONata reads`()                                           // "$reel" and "$reel.title" are reads; "$states", "$", "$$", a local "$x" are not
fun `test a built-in function is no read unless the name is assigned`()   // "$sum([1])" alone: no read; with "sum" assigned before: a read
fun `test JSONPath reads with host ranges`()                         // ranges cover "$reel" in the host text, also in a JSON file with "\\u0024reel"
fun `test a name assigned at the top level is one variable below it`()
fun `test two Parallel branches have separate variables`()
fun `test an inner assignment of an outer name joins the outer variable`()
fun `test a Distributed Map does not reach outward`()                // read inside: no variable; same name assigned inside and outside: two variables
fun `test a read with no assignment in reach has no variable`()
fun `test an Assign shared through an alias belongs to each branch`()     // assignmentsAt(key) has two entries with different variables
fun `test open definitions`()                                        // Assign: ${Vars}; Assign: '{% {} %}'; a key ${K}; Mode: ${Mode}; States with a placeholder key
fun `test a JSON definition`()
fun `test half-typed definitions do not throw`()                     // "Assign:", "Assign:\n  re", an expression "{% $reel + %}"
```

- [ ] **Step 2: Run** `./gradlew test --tests '*AslVariablesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** with one walk over `doc.allStates()` for the assignments and the reads, then the identities, cached in the instance. Getting the injected files from `AslModel` code must not happen while the model is built: `variables` is lazy and is first used by callers after `AslModel.of` returned.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: model the state machine variables of a definition`.

---

### Task 4: References and navigation

**Files:**
- Create: `refs/AslVariableReferences.kt`, `refs/AslVariableNavigation.kt`
- Modify: the `JsonataVariableRef` class (`getReference`), `refs/AslReferenceContributor.kt`, `refs/AslReferencesSearcher.kt`, `plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/variables/VariableNavigationTest.kt`

**Interfaces:**
- Consumes: `AslVariables` (Task 3).
- Produces:

```kotlin
/** A read of a state machine variable. resolve() is the entry of the first assignment in file order; multiResolve() every one. */
class AslVariableReadReference(element: PsiElement, range: TextRange) : PsiPolyVariantReferenceBase<PsiElement>
internal object AslVariableTargets {
    fun variablesAt(element: PsiElement): List<AslVariable>          // an assignment entry, a JSONata read, a host scalar with path reads (at an offset)
    fun assignmentEntries(variable: AslVariable): List<PsiElement>   // distinct entry elements, file order
    fun readReferences(variable: AslVariable): List<PsiReference>
}
```

- [ ] **Step 1: Write the tests.**

```kotlin
fun `test a JSONata read refers to the assignments`()     // reference on "$reel" in an Output: resolve() is the first "reel" entry; multiResolve has all three
fun `test a JSONPath read refers to the assignments`()    // the reference range on "InputPath: $reel.title" is "$reel"; two reads in one intrinsic give two references
fun `test go to declaration names each place`()           // the GotoDeclarationHandler's targets for "$reel" navigate to the keys, and their presentations' location strings are the placeTexts
fun `test find usages lists assignments and reads`()      // myFixture.findUsages on one assignment entry: every other assignment and every read; ReadWriteAccessDetector: assignments Write, reads Read
fun `test a local JSONata variable is not affected`()     // "(\$reel := 1; \$reel)" with a state variable "reel": no AslVariableReadReference, stage 2's reference stays
fun `test reads of separate variables do not mix`()       // two Parallel branches: find usages from branch 1's assignment lists nothing of branch 2
```

- [ ] **Step 2: Run** the test. Expected: FAIL.
- [ ] **Step 3: Implement.** `JsonataVariableRef.getReference()` returns stage 2's local reference when there is one, else an `AslVariableReadReference` over the whole `$name` when `AslVariables.readsAt` has a read with a variable. `AslReferenceContributor` gets a second provider for path reads. `isReferenceTo` holds for every assignment entry of the read's variables. Register a `gotoDeclarationHandler` (whose targets are `FakePsiElement`s with an `ItemPresentation` and the key entry as navigation element), a `findUsagesHandlerFactory` (primary elements: every assignment entry of the variable), a `readWriteAccessDetector`, and extend `AslReferencesSearcher.referencesTo` for assignment entries.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: go to and find the assignments and reads of a variable`.

---

### Task 5: Usage highlighting

**Files:**
- Create: `highlight/VariableUsagesHandlerFactory.kt`
- Modify: `plugin.xml` (`highlightUsagesHandlerFactory`, before the placeholder one), `src/test/kotlin/io/github/saigyo/aslan/AslTestCase.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/variables/VariableHighlightingTest.kt`

**Interfaces:**
- Consumes: `AslVariableTargets.variablesAt`, `AslVariables`.
- Produces: in `AslTestCase`, `protected fun highlightedAccesses(): List<Pair<String, Boolean>>`: each highlighted text with `true` for a write, sorted by offset (a write uses `EditorColors.WRITE_SEARCH_RESULT_ATTRIBUTES`).

- [ ] **Step 1: Write the tests.** The caret on an assignment key, on a JSONata read in a block scalar, a single-quoted and a JSON string, and on a JSONPath read gives the same list: every assignment key's name as a write, every read's `$name` as a read.

```kotlin
fun `test the caret on an assignment highlights writes and reads`()
fun `test the caret on a JSONata read highlights the same`()
fun `test the caret on a JSONPath read highlights the same`()
fun `test only the variable under the caret`()            // a second variable and a second branch's same name stay unmarked
fun `test a local JSONata variable keeps stage 2's highlighting`()
```

- [ ] **Step 2: Run** the test. Expected: FAIL.
- [ ] **Step 3: Implement** after `PlaceholderUsagesHandlerFactory`: work in the host editor; ranges of keys are the name part of the key text (without quotes and `.$`), ranges of JSONata reads are their host ranges (`JsonataPsi.rangeInHost`), ranges of path reads are `PathRead.range` shifted by the host's offset. Fill `myWriteUsages` and `myReadUsages`.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: highlight the assignments and reads of a variable`.

---

### Task 6: Rename

**Files:**
- Create: `refs/AslVariableRename.kt`
- Modify: `plugin.xml` (`renamePsiElementProcessor order="first"`, `renameInputValidator`)
- Test: `src/test/kotlin/io/github/saigyo/aslan/variables/VariableRenameTest.kt`

**Interfaces:**
- Consumes: `AslVariableTargets`, `AslVariables`, `NameWriter`, `JsonataElementFactory.variableToken`.
- Produces: `internal fun variableNameProblem(name: String): String?` (decision 7), used by Task 8.

**Conflicts** (each a `UnresolvableCollisionUsageInfo`, each with its own test). Region: the variable's scope and every machine its chain reaches or that reaches it.
1. Another variable, assignment or read, of the new name in the region.
2. A local JSONata variable of the new name declared in an expression that reads the variable.
3. `$newName` anywhere in an expression of the region (a built-in function that the rename would turn into a read, decision 2).
4. An occurrence that belongs to more than one variable (Review Focus 1).
5. An occurrence whose raw host text is not the old name (Review Focus 3).
6. A `$oldName` followed by no name character in any string of the file except `Comment` values, that is no known read (Review Focus 5).

**Writing.** One command: every range (assignment key names, JSONata read names after `$`, path read names after `$`) as a range of the host document, replaced from the end backwards, then one commit. A plain YAML key gets `NameWriter.yamlValue(project, newName + suffix)` for its whole text, so a name that does not read back plain is quoted; other keys get the name part replaced.

- [ ] **Step 1: Write the tests.**

```kotlin
fun `test rename from an assignment changes every occurrence`()   // YAML: three assignments (state, Choice rule, Catcher), reads in Output, Arguments and a JSONPath state; expected text in full
fun `test rename from a JSONata read and from a JSONPath read`()
fun `test rename keeps the dollar suffix of a JSONPath key`()
fun `test rename in a JSON definition`()
fun `test rename rejects a name that breaks the rule`()          // "", "1a", "a-b", "a b", "states", 81 characters; accepts "_a", "bobine_nº"
fun `test each conflict`()                                       // conflicts 1 to 6, each with assertRenameConflict as in JsonataVariablesTest
fun `test a rename leaves another branch's variable alone`()
```

- [ ] **Step 2: Run** the test. Expected: FAIL.
- [ ] **Step 3: Implement.** The dialog validates with `variableNameProblem`, as `AslStateRenameDialog` does for states. `canProcessElement` accepts assignment entries and the targets of Task 4.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: rename state machine variables`.

---

### Task 7: Completion

**Files:**
- Modify: `jsonata/completion/JsonataCompletionContributor.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataCompletionTest.kt`

- [ ] **Step 1: Write the tests.**

```kotlin
fun `test state variables after a dollar sign`()          // "reel" assigned before: "$reel" offered with type text; a variable of another branch is not
fun `test a local variable hides a state variable`()      // offered once, as the local one
fun `test a state variable hides a built-in function`()   // "sum" assigned: "$sum" offered once, as the variable, and choosing it inserts no brackets
fun `test an expression used by two states offers what both see`()
```

- [ ] **Step 2: Run** the test. Expected: FAIL.
- [ ] **Step 3: Implement** in `addVariables`: the names in `visibleNames` of every site of the original file (intersection), less the visible locals; function items whose name is among them are left out.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: complete state machine variables`.

---

### Task 8: The variable checks

**Files:**
- Create: `check/VariableChecks.kt`, five inspection descriptions under `src/main/resources/inspectionDescriptions/`
- Modify: `check/AslProblem.kt`, `check/AslInspections.kt`, `check/AslChecker.kt`, `plugin.xml`, `src/test/kotlin/io/github/saigyo/aslan/check/AwsVerdictsTest.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/check/VariableChecksTest.kt`

**Problems**, with their levels until Task 9:
- `PATH_VARIABLE_NOT_ASSIGNED` (ERROR): a JSONPath read of a name assigned nowhere in the definition, in a file with no open machine. Basis: `r3-AI-dollar-word-*`, `r3-AC-parameters-dollar-word`.
- `VARIABLE_NOT_ASSIGNED` (WARNING): any other read without a variable, unless `isOpen(read.state)`, unless the JSONata expression is not judged (`JsonataPsi.judged`), and unless it is the callee of a call (decision 2).
- `VARIABLE_NEVER_READ` (WEAK_WARNING): an assignment whose variable has no read, unless decision 9 applies.
- `INVALID_VARIABLE_NAME` (WARNING): an assignment whose name fails `variableNameProblem`.
- `OUTER_VARIABLE_ASSIGNED` (WARNING): an assignment in a machine other than its variable's scope.

- [ ] **Step 1: Write the tests** (`marked` as in `JsonataChecksTest`), one per problem with a planted case, one per exception (open `Assign`, placeholder `Mode`, unjudged expression, callee, a mention in an expression with a syntax error), and: the clean files under `src/test/testData/clean/` show none of the five problems.
- [ ] **Step 2: Run** the test. Expected: FAIL.
- [ ] **Step 3: Implement.** Findings on a JSONata read are reported on its host with the range of `JsonataPsi.rangeInHost`, on a path read on its scalar with `PathRead.range`, on an assignment on its key. In `AwsVerdictsTest`, remove the five gaps "variables are checked in a later stage"; the tests then require an error on those probes. If a clean file shows a finding, treat it as a false finding first; change a clean file only when the finding is right, and never commit the commissioner's uncommitted changes to it.
- [ ] **Step 4: Run** `./gradlew test` and the private check. Expected: PASS. **Step 5: Commit** `feat: check the assignments and reads of variables`.

---

### Task 9: The answers of AWS

Needs `.superpowers/aws-probes/round-11/aws-results.tsv`.

**Files:**
- Modify: `src/test/testData/aws/verdicts.json` (appended as text, as `append_round10.py` did), `src/test/testData/aws/README.md`, `AwsVerdictsTest.kt`, and as the answers demand `AslProblem.kt`, `VariableChecks.kt`, `plugin.xml`, `VariableChecksTest.kt`

- [ ] **Step 1: Record the answers**, and add a test `the variable checks agree with AWS probe by probe` in the form of stage 2's: `PATH_VARIABLE_NOT_ASSIGNED` is reported exactly on the `r11-` probes AWS rejects with `UNDEFINED_VARIABLE`, and `VARIABLE_NOT_ASSIGNED` on those it accepts with `POSSIBLY_UNDEFINED_VARIABLE`, apart from named gaps.
- [ ] **Step 2: Settle each disagreement**, each in the ledger with its probes:
  - AWS rejects a read the model resolves (for example `r11-p-later` or `r11-p-same-state`): a named gap (decision 3), not an error.
  - AWS accepts or only warns about a read the model leaves without a variable: the model's reach is wrong for that relation. Stop and report; do not change the scope rule unasked.
  - `PATH_VARIABLE_NOT_ASSIGNED` grows to the relations where AWS rejects every probe and the model leaves the read without a variable, for example `parallel-sibling`; a relation no probe covers stays a warning.
  - `INVALID_VARIABLE_NAME` and `OUTER_VARIABLE_ASSIGNED` become errors for the names and relations AWS rejects, and only those; `variableNameProblem` follows what AWS accepts (the rename rule may stay stricter than AWS, never looser).
  - `r11-a-nested-choice` settles the row on nested JSONPath Choice rules in spec section 10.
- [ ] **Step 3: Apply** the levels and rules, with tests. **Step 4: Run** `./gradlew test` and the private check. **Step 5: Commit** `fix: hold the variable checks to the answers of AWS round 11`.

---

### Task 10: Hand-over

**Files:**
- Modify: `plugin.xml` (`<description>`), `README.md`, `docs/superpowers/specs/2026-11-13-aslan-design.md`

- [ ] **Step 1: Update the texts.** The plugin description and the README gain the variable features and name the actions Next Highlighted Usage and Previous Highlighted Usage with their default shortcuts, taken from the platform's default and macOS keymaps (`GotoNextElementUnderCaretUsage`, `GotoPrevElementUnderCaretUsage`). The design document gets the decisions of this plan that stand (in 6.4), the levels of Task 9 with their basis (in 7), round 11 (in 8), and in section 10 the answer on nested Choice rules. It also fixes two leftovers of stage 2 if pull request #3 did not: in 6.3 the example of a decoded name must read `$\u0061` in JSON is `$a`, and the row on escaped local names in section 10 now says names are compared decoded.
- [ ] **Step 2: Verify.** `./gradlew check verifyPlugin` passes; the private check reports the definition as checked.
- [ ] **Step 3: Commit** `docs: describe state machine variables`.
- [ ] **Step 4: Hand the running-IDE checklist to the commissioner.** With `./gradlew runIde` and `film-archive-intake.asl.yaml`: the caret on `rejection` (19 assignments) marks assignments and reads in two colours, in the YAML key and inside a twenty-line expression; Next and Previous Highlighted Usage step through them; Go to Declaration from a read lists the places; Find Usages groups reads and writes; Rename from a read changes keys and expressions and the file shows no new problem; completion after `$` offers the variables; the inspection settings list the five new inspections.
- [ ] **Step 5: Report** what Task 9 settled and wait for the commissioner's decision on pushing and the pull request.
