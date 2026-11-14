# Aslan Stage 2: JSONata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** JSONata expressions inside ASL files behave like code: they are parsed exactly as Step Functions parses them, coloured, checked, completed and documented, and local variables can be followed and renamed.

**Architecture:** One scanner, a port of the tokenizer and parser of jsonata-js 2.0.6, turns expression text into tokens, a node tree and at most one syntax error. The IDE lexer and the IDE parser are thin adapters that replay its result, so there is one source of truth for what is valid. A new part of the document model lists the strings Step Functions evaluates as JSONata; the injector injects the language exactly there. Problems in expressions are found by the existing checker and shown by the existing one-inspection-per-problem mechanism, on a range inside the host string.

**Tech Stack:** as in stage 1 (Kotlin 2.4, Java 25, IntelliJ Platform Gradle Plugin 2.20.1, IntelliJ Platform 2026.2, JUnit 4 platform tests). No new build plugins and no new dependencies. Python 3 and Node.js are used by two one-off recording scripts, never by the build.

**Spec:** `docs/superpowers/specs/2026-11-13-aslan-design.md`, sections 4, 5, 6.1 (the JSONata function and `$states` reference data, moved here from stage 1), 6.3, 7, 8, 9 and 10. Stage 3 (state machine variables) and stage 4 (diagram) have their own plans.

**Branch:** `stage2/jsonata`, created from `origin/main` after pull request #2 is merged. The checks of that pull request are a precondition.

## Decisions made in this plan

These are choices of this plan, not of the commissioner. Each can be overruled before execution starts. Task 16 writes the ones that stand into the design document.

1. **A port instead of generated code.** The spec names JFlex and Grammar-Kit and allows hand-writing "if generation proves awkward". It is awkward here: in the reference, the parser tells the tokenizer whether a `/` starts a regular expression, and it rejects some trees only after parsing. A separate generated lexer cannot reproduce the first, and a grammar cannot express the second. A direct port of `src/parser.js` (MIT) can, and it can be compared with the original line by line.
2. **Two recorded sources of truth.** The vendored test suite has only 41 syntax error cases. In addition, the real jsonata-js 2.0.6 parser is run once over about 6,000 expressions (the suite's own, plus small mutations of them), and its answers are committed. A test requires the scanner to agree. AWS is asked once about some 175 small definitions, as in pull request #2.
3. **Syntax errors are an inspection.** The IDE would show parser errors by itself, with no way to switch them off. They are hidden, and the inspection "JSONata syntax error" reports them instead. If the scanner is ever wrong about a valid expression, the user can switch that one inspection off.
4. **Three kinds of syntax error are not reported:** a parent operator `%` that cannot be resolved (S0217) and two errors inside a function signature (S0401, S0402). Reporting them needs a port of two further parts of the reference whose risk of a false error is larger than the benefit. The conformance test names them as gaps.
5. **An expression with a placeholder outside a string literal is not judged.** `${Limit}` may be replaced by any text, so neither syntax nor any other check is applied to such an expression. A placeholder inside a string literal changes nothing.
6. **JSONata is injected only where Step Functions evaluates it:** in a state whose query language is known to be JSONata, in a field that evaluates JSONata or inside free-form data, in a string that starts with `{%` and ends with `%}`. A `{% %}` string in `Comment`, in a JSONPath state, at the top level, or in a state whose query language is a placeholder stays plain text.
7. **The suite is vendored as one distilled file** (identifier, expression, expected error code), not as 1,241 files of 5 MB with input data and results the tests never read.
8. **No code folding** for multi-line expressions (spec section 9 leaves it open).
9. **Severity follows AWS, within what was asked.** The five expression checks of Task 14 start with the level the spec gives them. Task 15 raises a check to an error only for the cases AWS was shown and rejected, and lowers it to a warning where AWS accepts what the spec calls an error. A call of an unknown function stays a warning whatever AWS answers, for the reason an unknown service integration does: the plugin's list can be behind AWS.
10. **The syntax of a regular expression is not checked.** The reference hands the pattern to JavaScript, and Java's rules differ; checking with Java would give false errors.
11. **Every field of `$states.context` is offered everywhere.** The spec asks for the fields available at the position. Which parts of the context object have a value depends on things the file does not always show (a callback pattern, a Map iteration), so completion offers all of them and the documentation says where each has a value.
12. **A local variable can only be renamed to an identifier** (letters, digits and underscore, not starting with a digit, and not `states`), although JSONata allows more. Other characters can break the host string, and stage 3 needs the same rule.

## Global Constraints

- Everything in the Global Constraints of the stage 1 plan still holds: plugin ID and base package `io.github.saigyo.aslan`, minimum IDE version 2026.2, Java 25, no network requests, no JSON Schema, no Symbol API, the invented film archive domain for all committed test data, and the private definition that is never committed, copied from or named.
- The JSONata language has the ID `AslJsonata` and claims no file extension.
- A problem is an **error** only if AWS rejects the definition, or if the reference parser cannot parse the expression. Everything else is a warning at most. Section 7 of the spec is the list.
- **No false error** outranks every other requirement. Where a rule is uncertain, the plugin stays silent.
- Text ported from jsonata-js and descriptions adapted from the JSONata documentation are MIT-licensed. The licence text is bundled and `NOTICE` names them. Descriptions of Step Functions additions and of `$states` are written in the plugin's own words with a link to the AWS page; no AWS text is copied.
- Tests assert problem identifiers, elements, ranges and structure, never message text. Message wording, icons and exact colours are the implementer's choice.
- Stage files by explicit path. Never `git add -A` or `git add .`: the working tree can hold the commissioner's own uncommitted changes to test files.
- Every commit message ends with `Co-Authored-By: <the Claude model that made the commit> <noreply@anthropic.com>`.
- Pushing, opening a pull request and merging are the commissioner's decisions. AWS calls and the Node.js recording are run by the commissioner from scripts this plan prepares.
- After each task: `./gradlew test` passes, and `./gradlew test -Paslan.privateFixtures="$PWD"` reports the private definition as checked with no errors.

## Review Focus

Inputs the spec implies but does not spell out. Each has a test in the task named.

1. **A half-typed expression** (`{% $states. %}`, an unclosed bracket or string, `{% %}`): no exception, at most one syntax finding, and the tree built so far is kept so that completion works. Tasks 2, 4 and 11.
2. **Multi-line quoted YAML scalars and escapes** (`''` in single quotes, `\"` and an escaped line break in double quotes, `\n` in JSON): the injected text has the tokens Step Functions sees, and a finding or a rename lands on the right characters of the host, and typing a quote does not end the host string. Tasks 6, 7, 8 and 10.
3. **A `{% %}` string where JSONata is not evaluated** (`Comment`, JSONPath state, top level, query language given by a placeholder, unclosed or with a trailing line break): no injection and no finding. Task 6.
4. **One expression used by several states** through a YAML anchor, where the states offer different `$states` fields: AWS sees a copy in each state, so the expression is reported when it is wrong in any use, and completion offers only what every use provides. Tasks 6, 11 and 14.
5. **Placeholders in expressions:** outside a string literal the expression is not judged; inside a string literal it is judged and the placeholder is coloured. Tasks 2, 7 and 9.

## File Structure

Paths are under `src/main/kotlin/io/github/saigyo/aslan/` unless they start with another directory.

| File | Responsibility |
|---|---|
| `jsonata/JsonataLanguage.kt` | `JsonataLanguage`, `JsonataFileType` |
| `jsonata/parser/JsonataTokens.kt`, `JsonataElements.kt` | token types and node types |
| `jsonata/parser/JsonataScanner.kt` | port of the reference tokenizer and parser: text to tokens, tree and error |
| `jsonata/parser/JsonataTreeChecks.kt` | port of the reference's post-parse error checks |
| `jsonata/parser/JsonataLexer.kt`, `JsonataParser.kt`, `JsonataParserDefinition.kt` | adapters that replay a scan for the IDE |
| `jsonata/psi/*.kt` | `JsonataFile`, the few PSI classes with behaviour, `JsonataPsi`, `JsonataScopes` |
| `jsonata/inject/JsonataInjector.kt` | injection into YAML scalars and JSON strings |
| `jsonata/highlight/*.kt` | syntax highlighter, annotator, colour settings page, error filter |
| `jsonata/edit/*.kt` | brace matcher, quote handler, commenter, Enter handling |
| `jsonata/refs/*.kt` | local variables: reference, usages, rename |
| `jsonata/completion/JsonataCompletionContributor.kt` | completion inside expressions |
| `jsonata/docs/*.kt` | quick documentation and parameter info |
| `spec/data/JsonataFunctions.kt`, `spec/data/StatesVariable.kt` | reference data |
| `model/JsonataSites.kt` | the strings of a definition that Step Functions evaluates as JSONata |
| `check/JsonataChecks.kt` | findings inside expressions |
| `tools/jsonata-reference/distill.py`, `record.mjs` | one-off recording scripts |
| `src/test/testData/jsonata/suite.json`, `reference.json`, `LICENSE`, `README.md` | vendored suite and recorded reference answers |

## Execution

The plan is executed subagent-driven: the session that holds the plan (Claude Fable 5.2) coordinates, a fresh subagent implements each task, and reviews run at the six points below. This replaces the review after every single task that the subagent-driven skill prescribes. Between reviews the coordinator reads each report and runs `./gradlew test` and the private check itself before the next task starts.

**Order.** Tasks run in their numbered order, with one exception: Task 13 runs as soon as Task 3 is done and `reference.json` has arrived. The earlier the scanner is held to the reference, the less is built on a tree that still changes. If the file is not there after Task 3, the work goes on with Task 4 and Task 13 runs when the file arrives, at the latest in its numbered place.

**Who implements what.** Each dispatch names its model explicitly and tells the implementer that model's name for the `Co-Authored-By` line.

| Task | Model | Reason |
|---|---|---|
| 1 Recording scripts | Sonnet 5.5 | scripts and probe definitions from complete lists |
| 2 The scanner | Opus 5.5 | a faithful port of some 800 lines, where every deviation is a false or a missed error |
| 3 Errors after parsing | Opus 5.5 | has to decide which parts of `processAST` its throw sites need |
| 4 Language plumbing and PSI | Sonnet 5.5 | adapters with given interfaces |
| 5 Reference data | Sonnet 5.5 | data from named sources |
| 6 Sites and injection | Opus 5.5 | offsets in every scalar style, and a new part of the model |
| 7 Syntax errors as findings | Sonnet 5.5 | wiring into the existing checker; an error in a clean or the private definition goes to Opus 5.5 for diagnosis |
| 8 Local variables | Opus 5.5 | scope rule, search and rename inside injected text |
| 9 Highlighting | Sonnet 5.5 | given keys and rules |
| 10 Editing behaviour | Sonnet 5.5 | small platform extensions |
| 11 Completion | Sonnet 5.5 | given rules |
| 12 Documentation and parameter hints | Sonnet 5.5 | given rules |
| 13 Hold the scanner to the reference | Opus 5.5 | each disagreement has to be traced into `parser.js` |
| 14 The expression checks | Opus 5.5 | the rule for what has an input is subtle |
| 15 The answers of AWS | the coordinator (Fable 5.2) | the levels are decisions against the commissioner's two anti-goals, with stop-and-report cases |
| 16 Hand-over | Sonnet 5.5 for the texts (Steps 1 and 3); the coordinator for Steps 2, 4 and 5 | |

An implementer that is blocked, or whose work fails a review twice, is replaced by the next model up (Sonnet, Opus, Fable).

**Reviews.** Every task is covered by exactly one of the first five reviews. A reviewer gets the diff since the previous review, the task texts and the spec, and reports findings; a finding is fixed by an implementer of the task's model, and the same reviewer checks the fix.

| After task | Covers | Reviewer | What it looks at |
|---|---|---|---|
| 1 | 1 | Opus 5.5, medium effort | Every AWS probe is valid apart from the one thing it asks about, and the groups are complete; `record.mjs` does what the plan says. It runs before the commands go to the commissioner, because a wrong probe costs a second run. |
| 3 | 2, 3 (and 13 if it was pulled forward) | Opus 5.5, high effort | The port beside `parser.js`: the `advance` calls and their second argument, the binding powers, the listed differences and no others. Token types and the shape of the tree, which the conformance test does not compare. |
| 7 | 4 to 7 | Opus 5.5, medium effort | Review Focus 2, 3 and 5. Ranges in the host for every scalar style. The changes to `AslFinding` and `AslChecker` leave the findings of stage 1 as they were. The data of Task 5 against its sources, with no AWS text copied. |
| 12 | 8 to 12 | Opus 5.5, medium effort | The scope rule against the semantics of JSONata. A rename cannot damage the host string. Review Focus 1 and 4 for completion. |
| 15 | 14 and 15 (and 13 if it ran in its numbered place) | Opus 5.5, medium effort | The rule for names without an input. Every level of Task 15 rests on probes named in the ledger, and nothing is an error for a kind of case no probe covers. For Task 13: the gap list holds error codes only, and each change of the scanner matches `parser.js`. |
| 16 | the whole branch | Fable 5.2, high effort | The branch against the spec and the two anti-goals, and what only shows across tasks. It runs before the report of Task 16, Step 5. |

---

### Task 1: Recording scripts and hand-over

Prepares the two recordings that need the commissioner. Nothing later waits for them until Task 13 (reference) and Task 15 (AWS).

**Files:**
- Create: `tools/jsonata-reference/distill.py`, `tools/jsonata-reference/record.mjs`, `tools/jsonata-reference/README.md`
- Create: `src/test/testData/jsonata/suite.json`, `src/test/testData/jsonata/LICENSE`, `src/test/testData/jsonata/README.md`
- Create, not committed (`.superpowers/` is git-ignored): `.superpowers/jsonata-2.0.6/` (checkout), `.superpowers/aws-probes/round-9/make_probes9.py`, `run.sh`, the probe files

**Interfaces:**
- Produces: `suite.json`, an array of `{"id": "<group>/<file>#<index>", "expr": "<text>", "code": "<code>" | null}`, one entry per test case, in the order of sorted file names. `code` is the case's `code`, or its `error.code`, or null.
- Produces later, through the commissioner: `src/test/testData/jsonata/reference.json` and `.superpowers/aws-probes/round-9/aws-results.tsv`.

- [ ] **Step 1: Check out the reference**

```bash
git clone --depth 1 --branch v2.0.6 https://github.com/jsonata-js/jsonata.git .superpowers/jsonata-2.0.6
git -C .superpowers/jsonata-2.0.6 rev-parse HEAD
```
Expected: `6f1fc886aa1e3e6bdbfffcdd80e63ede1d7d6b6b`.

- [ ] **Step 2: Write `distill.py <checkout>`**

It reads `test/test-suite/groups/*/*.json` (a file holds one case or a list of cases; `expr-file` names a file in the same directory) and writes two files: `src/test/testData/jsonata/suite.json` as described above, and `.superpowers/jsonata-corpus.json`, an array of strings for the recorder. The corpus holds every distinct suite expression, the quirk list below, and, for every distinct suite expression of at most 300 characters, three mutants made with `random.Random(206)`: one character deleted, one character from ``()[]{}.,;:?/*%|&=<>!~@#^$"'`-+ `` inserted, and two neighbouring characters swapped, each at a random position. Mutants equal to their original are dropped; the list is deduplicated and sorted.

Quirk list: `[/a/]`, `(/a/)`, `-/a/`, `{"a": /a/}`, `$match("a", /a/)`, `$x[] / 2`, `$x^(a) / 2`, `function($x){$x} / 2`, `$f(?) / 2`, `$x ~> |a|{}| / 2`, `1 / 2 / 3`, `(1) / 2`, `[1] / 2`, `{"a":1} / 2`, `$x.a / 2`, `and`, `or.and.in`, `a and and`, `$a ?: $b`, `$a ?? $b`, `$f(??)`, `${Bucket}`, `${a:b}`, `a"b`, `a$b`, `$$`, `$`, `//`, `/a/x`, `/[^]/`, `/a{/`, `/a{2,1}/`, `/(/`, `1 +`, `1 =`, `$states.`, `(`, `[1,`, the empty string and a single space.

Run with `python3 -I tools/jsonata-reference/distill.py .superpowers/jsonata-2.0.6`.
Expected: `suite.json` has 1,625 entries, 41 of them with a code that starts with `S`.

- [ ] **Step 3: Write `record.mjs <checkout>`**

It loads `<checkout>/src/parser.js`, calls the parser on every string of `.superpowers/jsonata-corpus.json` and writes `src/test/testData/jsonata/reference.json`: an array of `{"expr": "<text>", "code": "<code>" | null}`, where `code` is the `code` of the thrown error, or `"EXCEPTION"` for an error without one (JavaScript rejecting a regular expression). One entry per line, so that the file diffs well. Do not run it: it executes downloaded code, which is the commissioner's call.

- [ ] **Step 4: Copy the licence and write the READMEs**

`src/test/testData/jsonata/LICENSE` is a verbatim copy of the checkout's `LICENSE`. The README beside it names the source (repository, tag `v2.0.6`, the commit above), says what the two JSON files hold and how to re-record them.

- [ ] **Step 5: Write `make_probes9.py` and generate the AWS round**

Copy `run.sh` from `.superpowers/aws-probes/round-8/`. The generator writes one JSON definition per probe next to it. File names must not differ only in letter case; the generator asserts that. Unless a group says otherwise, a probe is a JSONata machine with one Pass state whose `Output` is `{% EXPR %}`. All names are from the film archive domain or neutral.

| Group | Probes |
|---|---|
| `r9-s-` syntax | the 40 suite entries with an `S` code and an inline expression, named by code and number |
| `r9-q-` quirks | `[/a/]`, `(/a/)`, `-/a/`, `$states.input.a[] / 2`, `$states.input.a^(b) / 2`, `function($x){$x} / 2`, `$match("abc", /b/)`, `"a" ~> /a/` |
| `r9-v-` valid | one per construct, all reading only `$states.input`: a path; a predicate `items[price > 10].name`; an index; `items^(>price).name`; `items{category: $sum(price)}`; `*`; `**.id`; `items#$i.{"n": $i, "v": name}`; `items@$it.{"v": $it.name}`; `[1..3]`; an object with every literal kind; `($x := 1; $x + 2)`; `function($x){ $x * 2 }(3)`; the same with `λ`; `$map([1,2], function($v, $i){ $v + $i })`; a conditional with and without else; `name ~> $uppercase() ~> $trim()`; a transform `$states.input ~> \|items\|{"seen": true}, ["tmp"]\|`; `$match("abc", /b+/i)`; a chain of `in`, `and`, `or`, `!=`, `>=`, unary minus and `4.5e1`; `"a" & 'b' & $string(1)`; `$substring(?, 0, 2)("hello")`; a backtick name; `items[].name`; `orders.items.%.id`; `function($a, $b)<nn:n>{ $a + $b }(1, 2)`; `1 /* note */ + 2`; `$states.context.Execution.Id` |
| `r9-p-` policy | `$eval("1")`; `$states.input.a ?? 1`; `$states.input.a ?: 1`; `$`; `$$`; `foo`; `foo.bar`; `$.a`; `*`; `%`; `$states.input.items[price > 1]`; `$states.input.items.$`; `$states.input.items[$$.a]`; a field name with no input in each place that inherits the context: `(foo)`, `[foo]`, `{"a": foo}`, `$string(foo)`, `function($v){ foo }(1)`, `$map($states.input.items, function($v){ foo })`, `foo ? 1 : 2`, `-foo`, `foo & "x"`, `foo.bar[baz > 1]`; and with an input in each place that starts one: `$states.input.{"a": b}`, `$states.input.items^(price)`, `$states.input.items{category: price}`, `$states.input.items.$string(name)`, `$states.input ~> \|items\|{"a": b}\|`; `$nope(1)`; `($f := function($x){$x}; $f(1))`; `$states.nope`; `$states.context.Nope`; `$states := 1`; `($states := 1; 2)`; `$x`; `$x := 1`; `$uuid()`; `$partition([1,2,3], 2)`; `$range(1, 3, 1)`; `$hash("a", "SHA-256")`; `$random(1)`; `$parse("{}")`; `$now()`; `$millis()`; `$sum(1, 2, 3)`; `$substring()`; `$uppercase(1)` |
| `r9-r-` `$states` fields | `$states.result` and `$states.errorOutput`, each in: Task `Arguments`, `Output`, `Assign`, `TimeoutSeconds`, a Catcher's `Output` and `Assign`; Pass `Output` and `Assign`; Choice rule `Condition`, `Output` and `Assign`; the Choice state's own `Output` and `Assign`; Map `Items`, `ItemSelector`, `Output` and `Assign`; Parallel `Arguments`, `Output` and `Assign`; Wait `Seconds`; Succeed `Output`; Fail `Cause`. 46 probes, each otherwise valid |
| `r9-w-` layout | a line break before and after the expression; `{%1%}`; `{% %}`; `{%  1  %}`; `{% /* note */ 1 %}`; `{% 1 /* open %}` |

Expected: about 175 files.

- [ ] **Step 6: Commit and hand over**

```bash
git add tools/jsonata-reference src/test/testData/jsonata/suite.json src/test/testData/jsonata/LICENSE src/test/testData/jsonata/README.md
git commit -m "test: vendor the JSONata 2.0.6 test suite and add the recording scripts"
```
After the review of this task (see Execution), give the commissioner both commands with absolute paths: `sh <repo>/.superpowers/aws-probes/round-9/run.sh` and `node <repo>/tools/jsonata-reference/record.mjs <repo>/.superpowers/jsonata-2.0.6`. Go on with Task 2 without waiting.

---

### Task 2: The scanner

**Files:**
- Create: `jsonata/JsonataLanguage.kt`, `jsonata/parser/JsonataTokens.kt`, `jsonata/parser/JsonataElements.kt`, `jsonata/parser/JsonataScanner.kt`
- Create: `src/main/resources/META-INF/licenses/jsonata-LICENSE.txt`; modify `NOTICE`
- Test: `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataScannerTest.kt`, `JsonataConformanceTest.kt`

**Interfaces:**
- Produces:

```kotlin
object JsonataLanguage : Language("AslJsonata")
object JsonataFileType : LanguageFileType(JsonataLanguage)      // name "AslJsonata", default extension ""

object JsonataScanner { fun scan(text: CharSequence): JsonataScan }
class JsonataScan(val tokens: List<ScanToken>, val root: ScanNode?, val error: ScanError?)
class ScanToken(val type: IElementType, val start: Int, val end: Int)
class ScanNode(val type: IElementType, val start: Int, val end: Int, val children: List<ScanNode>)
class ScanError(val code: String, val start: Int, val end: Int)   // the offending token; empty at the end of the text
```

- `JsonataTokens`: `WHITE_SPACE` (the platform's `TokenType.WHITE_SPACE`), `COMMENT`, `NUMBER`, `STRING`, `NAME`, `QUOTED_NAME`, `VARIABLE` (`$name`, also `$` and `$$`), `REGEX`, `PLACEHOLDER`, `TRUE`, `FALSE`, `NULL`, `AND`, `OR`, `IN`, `DOT`, `DOT_DOT`, `LBRACKET`, `RBRACKET`, `LBRACE`, `RBRACE`, `LPAREN`, `RPAREN`, `COMMA`, `SEMICOLON`, `COLON`, `ASSIGN`, `QUESTION`, `ELVIS` (`?:`), `COALESCE` (`??`), `PLUS`, `MINUS`, `STAR`, `STAR_STAR`, `SLASH`, `PERCENT`, `PIPE`, `EQ`, `NE`, `LT`, `LE`, `GT`, `GE`, `CARET`, `CHAIN` (`~>`), `AMP`, `AT`, `HASH`, `BANG`, `TILDE`, `BAD_STRING`, `BAD_QUOTED_NAME`, `BAD_COMMENT`, `BAD_REGEX`. Token sets `COMMENTS` (only `COMMENT`) and `STRINGS`.
- `JsonataElements`: `FILE`, `BLOCK`, `ARRAY`, `RANGE`, `OBJECT`, `PAIR`, `NEGATION`, `BINARY`, `PATH` (`lhs . rhs`), `ASSIGNMENT` (first child is the `VARIABLE_REF` assigned), `CONDITIONAL`, `CALL` (first child is the callee, the others the arguments), `PARTIAL_ARGUMENT`, `LAMBDA` (children: `PARAMETER`s, an optional `SIGNATURE`, the body last), `PARAMETER`, `SIGNATURE`, `FILTER` (`e[p]` and `e[]`), `GROUP_BY`, `ORDER_BY`, `SORT_TERM`, `FOCUS_BINDING` and `INDEX_BINDING` (last child is the `VARIABLE_REF` bound), `TRANSFORM`, and the single-token nodes `VARIABLE_REF`, `CONTEXT_REF` (`$`, `$$`), `FIELD` (a name, a backtick name, or `and`, `or`, `in` used as a name), `LITERAL`, `REGEX_LITERAL`, `WILDCARD`, `DESCENDANTS`, `PARENT`, `PLACEHOLDER_REF`.

- [ ] **Step 1: Write the conformance test**

```kotlin
class JsonataConformanceTest : AslTestCase() {
    private val gaps = mapOf(
        "S0217" to "the parent operator is not resolved", "S0401" to "function signatures are not validated",
        "S0402" to "function signatures are not validated",
        "S0209" to "Task 3", "S0210" to "Task 3", "S0213" to "Task 3", "S0215" to "Task 3", "S0216" to "Task 3",
    )
    fun `test every suite expression that is expected to parse scans without an error`()   // code null or not starting with "S"
    fun `test every suite syntax error is found, apart from the named gaps`()
    fun `test every named gap is still a gap`()
}
```
Each test collects the identifiers that disagree and asserts that the list is empty, so one run shows them all. Only "an error or none" is compared, never the code or the position.

- [ ] **Step 2: Write the scanner test**

`kinds(text)` is the list of the token types of a scan without whitespace; `nodes(text)` the node types of its tree in depth-first order.

```kotlin
fun `test the tokens cover the text without a gap`()        // for every suite expression: first start 0, each start = previous end, last end = length
fun `test a slash is a regular expression only where the reference reads one`() {
    assertEquals(listOf(REGEX), kinds("/ab+/i").filter { it == REGEX || it == SLASH })
    assertEquals(listOf(SLASH), kinds("\$x / 2").filter { it == REGEX || it == SLASH })
    assertEquals(listOf(REGEX), kinds("\$match(\$x, /a\\/b/)").filter { it == REGEX || it == SLASH })
    assertEquals("S0211", JsonataScanner.scan("[/a/]").error?.code)      // a quirk of the reference: no regex directly after a prefix bracket
}
fun `test keywords are names where a value is expected`()    // kinds("and.or") is NAME, DOT, NAME and its nodes hold two FIELDs; kinds("a and b") holds AND
fun `test a placeholder is one token`()                      // "\${Bucket}" -> PLACEHOLDER; "\${\"a\": 1}" -> VARIABLE, LBRACE, ...
fun `test the operators of JSONata 2_1 are tokens and no syntax error`()   // "\$a ?: \$b" and "\$a ?? \$b": ELVIS / COALESCE, error == null
fun `test the tree built before an error is kept`()          // "\$sum(\$states.input." -> an error, and the root has a CALL containing a PATH
fun `test a missing operand is an error at the end`()        // "1 +", "1 =", "\$states.", "(", "" and " ": S0207, with an empty range at the end of the text
fun `test text after a complete expression is an error`()    // "Account.Order[0].Product;" and "1 2": S0201 at the first extra token
fun `test the syntax of a regular expression is not checked`()   // "/[^]/", "/a{/", "/a{2,1}/", "/(/": no error
fun `test the shape of a tree`()                             // dump of ScanNode types for "(\$t := 1; \$f(\$t, ?).a[0])"
```
Lexical errors with their codes: `"abc` S0101, `10e1000` S0102, `"\y"` S0103, `"\u12"` S0104, `` `abc `` S0105, `/* abc` S0106, `//` S0301, `/abc` S0302.

- [ ] **Step 3: Run both tests**

Run: `./gradlew test --tests '*JsonataScannerTest' --tests '*JsonataConformanceTest'`
Expected: FAIL, the scanner does not exist.

- [ ] **Step 4: Port `src/parser.js` as `JsonataScanner`**

Port the tokenizer (lines 62 to 333), the parser up to the object transformer (lines 338 to 865) and the entry block that reports text after a complete expression as S0201 (lines 1355 to 1370, without its S0217 check) of `.superpowers/jsonata-2.0.6/src/parser.js`. Keep its structure: a `next(prefix)` function that the parser calls, a symbol table with binding powers, `nud` and `led` functions, and the same `advance` calls with the same arguments, because the second argument of `advance` decides whether a `/` is read as a regular expression. Do not port `processAST` (Task 3) or the recover mode. Differences from the reference, and nothing else:

- Every character belongs to a token: whitespace and comments are recorded as tokens.
- The end of the text used where a value is expected is the error S0207, with an empty range at the end. In the reference, `processAST` raises it; the scanner raises it when the parser takes the end as an operand, which finds the same expressions.
- A regular expression is scanned as the reference scans it (to the closing `/` outside brackets, then the flags `i` and `m`), but the pattern is not compiled: no error for a pattern JavaScript or Java would reject.
- A token with a lexical error that is not unterminated (a bad escape, a number out of range) keeps its normal type; the error's range is the token.
- The first error ends parsing. Nodes under construction are closed at the last token consumed and kept. The rest of the text is tokenized with every `/` read as `SLASH`.
- `and`, `or`, `in` get their operator token type when the parser uses them as an operator and `NAME` when it uses them as a name.
- `${` followed by one or more of the characters letter, digit, `_`, `-`, `.`, `:`, `,` and then `}` is one `PLACEHOLDER` token and parses like a literal. Use the pattern of `tree/Placeholders.kt`.
- `?:` and `??` are the tokens `ELVIS` and `COALESCE`, infix operators with binding power 40 that give a `BINARY` node.
- An unterminated string, backtick name, comment or regular expression is a `BAD_…` token that runs to the end of the text.

Add the licence copy and a paragraph to `NOTICE` that says the scanner is ported from jsonata-js, © IBM Corp. 2016, under the MIT licence bundled at that path.

- [ ] **Step 5: Run the tests**

Run: the command of Step 3. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/github/saigyo/aslan/jsonata src/main/resources/META-INF/licenses NOTICE src/test/kotlin/io/github/saigyo/aslan/jsonata
git commit -m "feat: scanner for JSONata 2.0.6, ported from the reference parser"
```

---

### Task 3: Errors the reference finds after parsing

**Files:**
- Create: `jsonata/parser/JsonataTreeChecks.kt`
- Modify: `jsonata/parser/JsonataScanner.kt` (call it), `JsonataConformanceTest.kt` (remove the five "Task 3" gaps)

**Interfaces:**
- Consumes: `ScanNode`, `ScanError`.
- Produces: `internal object JsonataTreeChecks { fun check(root: ScanNode, text: CharSequence): ScanError? }`. `JsonataScanner.scan` returns its result as `error` when parsing itself found none.

- [ ] **Step 1: Remove the five gaps from the conformance test and add cases**

```kotlin
fun `test errors found after parsing`() {
    for ((expr, code) in listOf(
        "[1,2,3]{\"num\": \$}[true]" to "S0209", "[1,2,3]{\"num\": \$}{\"num\": \$}" to "S0210", "\$.7" to "S0213",
        "Account.Order[1]@\$o.Product" to "S0215", "Account.Order^(>OrderID)@\$o.Product" to "S0216",
    )) assertEquals(expr, code, JsonataScanner.scan(expr).error?.code)
    for (expr in listOf("Account.Order{\"a\": 1}[0]", "\$.\"7\"", "Account.Order@\$o[1].Product", "(7).a"))
        assertNull(expr, JsonataScanner.scan(expr).error)
}
```

- [ ] **Step 2: Run** `./gradlew test --tests '*JsonataConformanceTest' --tests '*JsonataScannerTest'`. Expected: FAIL on the five codes.

- [ ] **Step 3: Port the error checks of `processAST`**

`processAST` (lines 998 to 1352 of `parser.js`) rewrites the tree into paths and steps and throws while doing so. Port it as a function that computes, for every node, only what its throw sites read: whether the result is a path, the kind of its last step, and whether that step or the path already has a group, a predicate or stage, or is a sort. Raise S0209, S0210, S0213, S0215 and S0216 exactly where the reference does. Leave out everything about the parent operator (`seekParent`, `pushAncestry`, `resolveAncestry` in lines 901 to 997, and their calls: S0217), the tail-call rewriting, and the default case, whose S0207 the scanner already raises.

- [ ] **Step 4: Run** the command of Step 2. Expected: PASS, with only S0217, S0401 and S0402 left as gaps.

- [ ] **Step 5: Commit** `jsonata/parser` and the tests: `feat: report the errors JSONata finds after parsing`.

---

### Task 4: Language plumbing and PSI

**Files:**
- Create: `jsonata/parser/JsonataLexer.kt`, `JsonataParser.kt`, `JsonataParserDefinition.kt`, `jsonata/psi/JsonataFile.kt`, `JsonataElement.kt` (with the PSI classes below)
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataTestCase.kt`, `JsonataPsiTest.kt`

**Interfaces:**
- Consumes: `JsonataScanner.scan`.
- Produces:

```kotlin
class JsonataLexer : LexerBase()                       // replays scan.tokens; state 0 only at offset 0
class JsonataFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, JsonataLanguage)
open class JsonataElement(node: ASTNode) : ASTWrapperPsiElement(node)
class JsonataVariableRef(node: ASTNode) : JsonataElement(node) { val variableName: String }     // without the "$"
class JsonataCall(node: ASTNode) : JsonataElement(node) { val callee: PsiElement?; val arguments: List<PsiElement> }
class JsonataLambda(node: ASTNode) : JsonataElement(node) { val parameters: List<JsonataVariableRef>; val body: PsiElement? }
class JsonataPath(node: ASTNode) : JsonataElement(node) { val lhs: PsiElement?; val rhs: PsiElement? }

abstract class JsonataTestCase : AslTestCase() {
    fun jsonata(text: String): JsonataFile                  // a standalone file of JsonataFileType; "<caret>" is honoured
    fun machineText(state: String): String
    // a JSONata machine with the single state "Work", whose body is [state] (YAML, indented by the helper), and a second state "Failed" of type Fail for catchers
    fun hostText(expression: String, field: String = "Output"): String
    // machineText of a Task state (lambda:invoke, Arguments with a FunctionName, End) in which [field] is a ">-" block scalar holding "{% expression %}";
    // for field "Arguments" the block scalar is the value of Arguments
    fun hosted(expression: String, field: String = "Output"): PsiFile     // configures hostText as test.asl.yaml
}
```
A `PARAMETER` node is a `JsonataVariableRef` too. Every other node type is a plain `JsonataElement`.

- [ ] **Step 1: Write `JsonataPsiTest`**

```kotlin
fun `test the tree of an expression`()               // DebugUtil.psiToString of jsonata("(\$t := 1; \$f(\$t, ?).a[0])") against the expected dump
fun `test one error element where the scanner reports an error`()   // jsonata("1 +"): exactly one PsiErrorElement; jsonata("1 + 2"): none
fun `test the psi of every suite expression has an error exactly when the scan has`()
fun `test accessors`()                               // callee and arguments of "\$sum(1, 2)"; parameters and body of "function(\$a, \$b){ \$a }"; lhs and rhs of "a.b"
fun `test an empty and a blank file`()               // jsonata(""), jsonata("  "): no exception, one error element
```

- [ ] **Step 2: Run** `./gradlew test --tests '*JsonataPsiTest'`. Expected: FAIL.

- [ ] **Step 3: Implement the adapters**

`JsonataParser` scans `builder.originalText` and replays the tree: for a node, advance the builder to the node's start, mark, replay the children, advance to the node's end, and complete the marker with the node's type. For an error, advance to its start, call `builder.error`, and consume the remaining tokens. `JsonataParserDefinition` creates the lexer, the parser, `JsonataFile`, and the PSI class for each node type. Register `fileType` (no extensions) and `lang.parserDefinition` for `AslJsonata` in `plugin.xml`.

- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.

- [ ] **Step 5: Commit** `feat: JSONata as a language of its own in the IDE`.

---

### Task 5: Reference data for functions and `$states`

**Files:**
- Create: `spec/data/JsonataFunctions.kt`, `spec/data/StatesVariable.kt`
- Modify: `spec/AslSpec.kt`, `spec/SpecModel.kt`, `NOTICE`
- Test: `src/test/kotlin/io/github/saigyo/aslan/spec/JsonataSpecTest.kt`

**Interfaces:**
- Produces:

```kotlin
class JsonataFunction(val name: String, val parameters: List<JsonataParameter>, val category: String, val description: String,
                      val url: String, val stepFunctionsOnly: Boolean = false, val supported: Boolean = true)
class JsonataParameter(val name: String, val optional: Boolean = false)
enum class StatesField(val text: String) { INPUT("input"), RESULT("result"), ERROR_OUTPUT("errorOutput"), CONTEXT("context") }
class ContextField(val name: String, val description: String, val children: List<ContextField> = emptyList())

// on AslSpec
val jsonataFunctions: List<JsonataFunction>
fun jsonataFunction(name: String): JsonataFunction?          // name without the "$"
fun statesFieldDoc(field: StatesField): NamedDoc
val contextFields: List<ContextField>                        // the fields of $states.context
```

- [ ] **Step 1: Write the test**

```kotlin
fun `test the functions are those of JSONata 2_0_6 and of Step Functions`() {
    val jsonata = ("sum count max min average string substring substringBefore substringAfter lowercase uppercase length trim pad match " +
        "contains replace split join formatNumber formatBase formatInteger parseInteger number floor ceil round abs sqrt power random boolean " +
        "not map zip filter single reduce sift keys lookup append exists spread merge reverse each error assert type sort shuffle distinct " +
        "base64encode base64decode encodeUrlComponent encodeUrl decodeUrlComponent decodeUrl eval toMillis fromMillis clone now millis").split(' ')
    val added = listOf("partition", "range", "hash", "uuid", "parse")
    assertEquals((jsonata + added).sorted(), AslSpec.jsonataFunctions.map { it.name }.sorted())
    assertEquals(added.sorted(), AslSpec.jsonataFunctions.filter { it.stepFunctionsOnly }.map { it.name }.sorted())
    assertEquals(listOf("eval"), AslSpec.jsonataFunctions.filter { !it.supported }.map { it.name })
}
fun `test every function has a category, a description and a link`()
fun `test the context object`()      // contextFields holds Execution, State, StateMachine, Task and Map; Execution holds Id, Input, Name, RoleArn and StartTime
```

- [ ] **Step 2: Run** `./gradlew test --tests '*JsonataSpecTest'`. Expected: FAIL.

- [ ] **Step 3: Write the data**

Names and parameters of the JSONata functions come from `.superpowers/jsonata-2.0.6/docs/*-functions.md` (the signature line of each function; a parameter in square brackets is optional). Descriptions are shortened from those files, one or two sentences each; the link is the function's anchor on docs.jsonata.org. `$random` gets a note that Step Functions accepts an optional seed. The five additions, the note on `$eval`, the `$states` fields and the context object are described in own words from two AWS pages, read with a web fetch: `https://docs.aws.amazon.com/step-functions/latest/dg/transforming-data.html` and `https://docs.aws.amazon.com/step-functions/latest/dg/input-output-contextobject.html`. Take the complete list of context fields from the second page. Add to `NOTICE` that the function descriptions are adapted from the JSONata documentation under the MIT licence.

- [ ] **Step 4: Run** the test. Expected: PASS. **Step 5: Commit** `feat: reference data for JSONata functions and $states`.

---

### Task 6: Where JSONata is evaluated, and injection

**Files:**
- Create: `model/JsonataSites.kt`, `jsonata/inject/JsonataInjector.kt`, `jsonata/psi/JsonataPsi.kt`
- Modify: `model/AslDocument.kt` (lazy property), `plugin.xml` (`multiHostInjector`)
- Test: `src/test/kotlin/io/github/saigyo/aslan/model/JsonataSitesTest.kt`, `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataInjectionTest.kt`

**Interfaces:**
- Consumes: `AslDocument`, `AslState`, `AslSpec.structure`, `FieldSpec.jsonata`, `StatesField`.
- Produces:

```kotlin
/** One use of a string that Step Functions evaluates as JSONata. */
class JsonataSite(val scalar: AslScalar, val state: AslState, val provides: Set<StatesField>)
class JsonataSites { fun at(host: PsiElement): List<JsonataSite>; val all: List<JsonataSite> }   // keyed by scalar.psi
// in AslDocument, a member: val jsonataSites: JsonataSites by lazy { ... }

object JsonataPsi {
    fun injectedFile(host: PsiElement): JsonataFile?        // the file injected into a YAML scalar or JSON string
    fun sites(file: PsiFile): List<JsonataSite>             // of the host of an injected file; empty for any other file
    fun rangeInHost(file: JsonataFile, range: TextRange): Pair<PsiElement, TextRange>?   // the host and the range relative to the host's start
}
```

**Rule for sites.** For every state with `queryLanguageKnown`, query language JSONata, a mapping as value and a known type, walk its entries with the state's structure from `AslSpec`, entries from merge keys included. A field whose mode does not allow JSONata is skipped. Under a free-form field, every string scalar at any depth is a candidate. For another field with `jsonata = true`, its scalar value is a candidate. A field whose kind is a structure, or a list of structures, is walked with that structure; the states of a nested machine are not, they are states of their own. A candidate is a site when it is a certain string (not `ambiguous`, not `dynamic`) that starts with `{%` and ends with `%}`. `provides` always holds `INPUT` and `CONTEXT`; `RESULT` is added under the state's own `Output` or `Assign` when the state is a Task, Map or Parallel; `ERROR_OUTPUT` is added under the `Output` or `Assign` of an entry of the state's `Catch`.

**Rule for places.** Inside the host, the expression runs from two characters after the first `{%` at or after the start of the first content range to the last `%}`. A JSON string, a single-line YAML scalar and a YAML block scalar get one place for that range. A multi-line quoted YAML scalar gets one place per line, the intersection of each of `YAMLScalarImpl.getContentRanges()` with that range, empty ones left out; every place but the last gets the suffix `" "`. A line of a double-quoted scalar that ends with an unescaped backslash is the exception: its place ends before the backslash and gets no suffix. This is needed because the YAML plugin joins the lines of a quoted scalar without a separator.

- [ ] **Step 1: Write `JsonataSitesTest`**

```kotlin
fun `test where an expression is a site`()
// sites: Arguments value nested in a list, Output, Assign value, Condition, TimeoutSeconds, Items, a Catcher's Output, a field reached through a merge key
// no sites: Comment of a state, a JSONPath state, a top-level field, a state under "QueryLanguage: \${Lang}", "{% 1" , a block scalar "|" that ends with a line break,
//           a plain string, a tagged scalar "!Sub '{% 1 %}'", a state whose Type is a placeholder
fun `test what a site provides`()
// Task Output and Assign: input, context, result.  Task Arguments: input, context.  Pass Output: input, context.
// Catcher Output and Assign: input, context, errorOutput.  Choice rule Condition and Output: input, context.  Map Output: with result.
fun `test an anchored expression used by two states has two sites at one element`()
```

- [ ] **Step 2: Write `JsonataInjectionTest`**

`injected(hostText)` returns the text of the JSONata file at `<caret>`, or null; `tokens(hostText)` the texts of its tokens without whitespace. Block scalars are compared by tokens, because only the token boundaries must match what Step Functions receives.

```kotlin
fun `test each scalar style`() {
    assertEquals(" \$states.input.a & 'x' ", injected("Output: '{% <caret>\$states.input.a & ''x'' %}'"))
    assertEquals(" \$states.input.a & \"x\" ", injected("Output: \"{% <caret>\$states.input.a & \\\"x\\\" %}\""))
    val words = listOf("\$states", ".", "input", ".", "a", "and", "true")
    assertEquals(words, tokens("Output: >-\n  {% <caret>\$states.input.a\n  and true %}"))       // folded
    assertEquals(words, tokens("Output: |-\n  {% <caret>\$states.input.a\n  and true %}"))       // literal
    assertEquals(words, tokens("Output: '{% <caret>\$states.input.a\n  and true %}'"))            // quoted, two lines: "a" and "and" stay apart
    assertEquals(words, tokens("Output: \"{% <caret>\$states.input.a\n  and true %}\""))
    assertEquals(listOf("'abcd'"), tokens("Output: \"{% <caret>'ab\\\n  cd' %}\""))              // escaped line break: nothing in between, and no backslash
}
fun `test a json string`()                // "{% $states.input.a & \"x\" %}" and "{% 1 +\n 2 %}" with the JSON escape \n: the text holds a line break
fun `test nothing is injected where JSONata is not evaluated`()     // the "no sites" cases of JsonataSitesTest: injected(...) is null
fun `test the injected tokens are the tokens of the value`()
// for every site of every file under src/test/testData/clean: the non-whitespace tokens (type and text) of the injected file
// equal those of JsonataScanner.scan(scalar.text.substring(2, scalar.text.length - 2))
```
The indentation of the host snippets is the test's concern; each replaces the `Output` entry of the Task state that `hostText` builds.

- [ ] **Step 3: Run** both tests. Expected: FAIL.

- [ ] **Step 4: Implement** `JsonataSites` by the first rule and `JsonataInjector : MultiHostInjector` (for `YAMLScalar` and `JsonStringLiteral`) by the second. The injector asks `AslModel.of(file)?.jsonataSites?.at(host)` and injects nothing when that is empty or null.

- [ ] **Step 5: Run** `./gradlew test`. Expected: PASS. **Step 6: Commit** `feat: inject JSONata where Step Functions evaluates it`.

---

### Task 7: Syntax errors as findings

**Files:**
- Create: `check/JsonataChecks.kt`, `jsonata/highlight/JsonataErrorFilter.kt`, `src/main/resources/inspectionDescriptions/AslJsonataSyntaxError.html`
- Modify: `check/AslFinding.kt`, `check/AslProblem.kt`, `check/AslChecker.kt`, `check/AslInspections.kt`, `plugin.xml`, `src/test/kotlin/io/github/saigyo/aslan/check/CheckTestCase.kt`, `src/test/kotlin/io/github/saigyo/aslan/fixtures/PrivateFilesTest.kt`
- Test: `src/test/kotlin/io/github/saigyo/aslan/check/JsonataChecksTest.kt`

**Interfaces:**
- Consumes: `JsonataPsi`, `JsonataSites`.
- Produces: `data class AslFinding(val problem: AslProblem, val element: PsiElement, val message: String, val range: TextRange? = null)`, where `range` is relative to the start of `element`, lies inside it and is never empty; `AslProblem.JSONATA_SYNTAX_ERROR(ERROR)`; `internal object JsonataChecks { fun check(doc: AslDocument, out: MutableList<AslFinding>) }`; in `CheckTestCase`, `fun marked(text: String, name: String = "test.asl.yaml"): List<Pair<AslProblem, String>>`, each finding as its problem and the text of its range, or of its element when it has none.

- [ ] **Step 1: Write the test**

```kotlin
fun `test a syntax error is marked inside the string`() =
    assertEquals(listOf(JSONATA_SYNTAX_ERROR to ")"), marked(edit("'{% \$states.input.ok %}'" to "'{% \$states.input.ok) %}'")))
fun `test an error at the end marks the last token`()            // "{% 1 + %}" marks "+"
fun `test an error on a later line of a block scalar`()          // the range text is the offending token on that line
fun `test an error on the second line of a quoted scalar`()      // single-quoted and double-quoted, two lines: the range text is the offending token
fun `test an error after an escaped quote`()                     // '{% ''a'' ) %}' and "{% \"a\" ) %}" and the JSON form: the range text is ")"
fun `test an error in a json file`()
fun `test an empty expression is a syntax error`()               // '{% %}' marks the space between the delimiters, '{%%}' marks "%}"
fun `test an expression with a placeholder outside a string is not judged`()    // '{% \${Limit} + %}' gives nothing
fun `test a placeholder inside a string changes nothing`()       // '{% "\${Bucket}" + %}' is reported
fun `test the operators of JSONata 2_1 are no syntax error`()
fun `test nothing is shown twice`()
// myFixture.doHighlighting(ERROR) on a file with one syntax error, all inspections on: exactly one highlight
fun `test the large clean definition is checked in reasonable time`()    // AslChecker.findings on film-archive-intake.asl.yaml twice; the second call returns the cached list
```
`InspectionsTest."test clean definitions show nothing"` now covers expressions as well, without a change. `PrivateFilesTest` does too; change it to report the line of the range's start when a finding has a range.

- [ ] **Step 2: Run** `./gradlew test --tests '*JsonataChecksTest'`. Expected: FAIL.

- [ ] **Step 3: Implement**

`JsonataChecks.check` takes each host element of `doc.jsonataSites` once, gets its injected file and skips it when it holds a `PLACEHOLDER` token. It reports each `PsiErrorElement` as `JSONATA_SYNTAX_ERROR` on the host, with the range of the token at the error, or of the last token before it at the end of the expression. For an expression without a token the range is the text between the delimiters, or the closing `%}` when there is none. `AslChecker.compute` calls it after the other checks; `distinctBy` and the sort take the range into account. `AslProblemInspection` passes the range to the problem descriptor. `JsonataErrorFilter : HighlightErrorFilter` returns false for every error element of a `JsonataFile`. Register the filter and the inspection as the existing ones are registered (a subclass of `AslProblemInspection` in `AslInspections.all()`, level ERROR, group "Amazon States Language", no `language` attribute, since it runs on the host file; `InspectionsTest` checks all of this) and write its description, which says how to switch it off and that an expression with a placeholder outside a string is not checked.

- [ ] **Step 4: Run** `./gradlew test` and the private check. Expected: PASS, and the private definition shows no errors. An error in a clean definition is a bug in the scanner or the injector: compare with `parser.js` and fix it; do not change the definition. An error in the private definition is examined the same way. If the expression really is one the reference rejects, stop and tell the commissioner the problem and the line number, nothing else from the file.

- [ ] **Step 5: Commit** `feat: report JSONata syntax errors`.

---

### Task 8: Local variables

**Files:**
- Create: `jsonata/psi/JsonataScopes.kt`, `jsonata/refs/JsonataVariableReference.kt`, `JsonataReferencesSearcher.kt`, `JsonataFindUsagesProvider.kt`, `JsonataReadWriteAccessDetector.kt`, `JsonataRefactoringSupport.kt`, `JsonataNamesValidator.kt`
- Modify: the `JsonataVariableRef` class, `plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataVariablesTest.kt`

**Interfaces:**
- Produces:

```kotlin
object JsonataScopes {
    fun declarations(variable: JsonataVariableRef): List<JsonataVariableRef>   // in file order; empty when the name is not declared locally
    fun isDeclaration(variable: JsonataVariableRef): Boolean                   // a parameter, the target of ":=", or bound by "@" or "#"
    fun visibleNames(at: PsiElement): List<String>                             // local names visible at an element, innermost scope first
    fun occurrences(variable: JsonataVariableRef): List<JsonataVariableRef>    // every declaration and use of the same local variable, in file order
}
```
`JsonataVariableRef` becomes a `PsiNameIdentifierOwner` whose use scope is its file. The first declaration is the element others resolve to; every other occurrence, later assignments included, has a reference to it.

**Scope rule.** A scope is a `LAMBDA`, a `BLOCK`, or the file. A lambda declares its parameters. A block or the file declares every name that is the target of `:=`, or bound by `@` or `#`, inside it and not inside a nested block or lambda. A name refers to the nearest enclosing scope that declares it, wherever in that scope the declaration stands. A name that no scope declares is not local: it is a function, `$states`, or a state machine variable of stage 3, and has no reference.

- [ ] **Step 1: Write the test** (standalone files through `jsonata`, host files through `hosted`)

```kotlin
fun `test a use resolves to the assignment`()             // "(\$total := 1; \$to<caret>tal + 2)" -> the first "$total"
fun `test a parameter hides an outer variable`()          // "(\$x := 1; function(\$x){ \$<caret>x }(2))" -> the parameter
fun `test a variable of an inner block is not visible outside`()   // "((\$y := 1); \$<caret>y)" -> no reference
fun `test a function may call itself`()                   // "(\$f := function(\$n){ \$n = 0 ? 0 : \$<caret>f(\$n - 1) }; \$f(3))"
fun `test names that are not local have no reference`()   // $states, $sum, $order
fun `test focus and index variables`()                    // "items@\$it#\$i.{\"v\": \$<caret>it.name}"
fun `test assignments are writes and uses are reads`()    // "(\$n := 1; \$n := \$n + 1; \$n)": Write, Write, Read, Read
fun `test usages are highlighted in the host file`()      // highlightedUsages() in a hosted block scalar: every occurrence of "$total"
fun `test find usages lists every occurrence`()           // myFixture.findUsages on the declaration of "(\$n := 1; \$n := \$n + 1; \$n)": three usages
fun `test rename changes every occurrence`()              // in a block scalar, a single-quoted scalar and a two-line double-quoted scalar; myFixture.renameElementAtCaret("sum2")
fun `test rename rejects a name that is no identifier`()  // "", "a b", "a.b", "a+b", "a'b", "1a", "states"
```

- [ ] **Step 2: Run** `./gradlew test --tests '*JsonataVariablesTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Register `lang.findUsagesProvider`, `readWriteAccessDetector`, `lang.refactoringSupport` (in-place rename) and `lang.namesValidator` for `AslJsonata`, and `JsonataReferencesSearcher` as `referencesSearch`. The searcher is needed because the platform's own search looks for the name as a whole word, and `total` inside `$total` is not one: for a local `JsonataVariableRef` it supplies the references of `JsonataScopes.occurrences`, as `refs/AslReferencesSearcher.kt` does for state names. A valid new name is a letter or underscore followed by letters, digits and underscores, and is not `states`.

- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: navigate and rename local JSONata variables`.

---

### Task 9: Highlighting

**Files:**
- Create: `jsonata/highlight/JsonataHighlighting.kt`, `JsonataSyntaxHighlighter.kt`, `JsonataAnnotator.kt`, `JsonataColorSettingsPage.kt`
- Modify: `plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataHighlightingTest.kt`

**Interfaces:**
- Consumes: `JsonataLexer`, `JsonataScopes`, `AslSpec.jsonataFunction`, `AslHighlighting.PLACEHOLDER`, `Placeholders.ranges`.
- Produces: `object JsonataHighlighting` with the keys `KEYWORD`, `NUMBER`, `STRING`, `REGEX`, `COMMENT`, `OPERATOR`, `PARENTHESES`, `BRACKETS`, `BRACES`, `SEPARATOR`, `VARIABLE`, `FIELD`, `FUNCTION`, `STATES`, each with an external name that starts with `ASL_JSONATA_` and a fallback from `DefaultLanguageHighlighterColors`.

- [ ] **Step 1: Write the test**

```kotlin
fun `test token colours`()            // JsonataSyntaxHighlighter().getTokenHighlights for NUMBER, STRING, REGEX, COMMENT, AND, TRUE, VARIABLE, NAME, PLUS, LPAREN, LBRACKET, LBRACE, COMMA
fun `test semantic colours in a host file`()
// in hosted("\$sum(\$states.input.prices) & function(\$v){ \$v } & 'in \${Bucket}'"):
// "$sum" FUNCTION, "$states" STATES, "function" KEYWORD, "${Bucket}" AslHighlighting.PLACEHOLDER, "$v" neither FUNCTION nor STATES
fun `test a local variable named like a function is not coloured as one`()   // "(\$sum := 1; \$sum)"
fun `test the colour page lists every key`()
fun `test the caret on a placeholder inside an expression highlights its other occurrences`()
// a file with "\${Bucket}" as a Resource value and inside a string literal of an expression; caret on the second: highlightedUsages() holds both
```

- [ ] **Step 2: Run** the test. Expected: FAIL.

- [ ] **Step 3: Implement.** The annotator colours a `VARIABLE_REF` that names a known function and has no local declaration, `$states` likewise, the name `function` or `λ` that heads a `LAMBDA`, and each placeholder inside a `STRING` token. A `PLACEHOLDER` token gets `AslHighlighting.PLACEHOLDER` from the syntax highlighter. Register `lang.syntaxHighlighterFactory`, `annotator` and a second `colorSettingsPage`. If the placeholder test fails, `highlight/PlaceholderUsagesHandlerFactory.kt` is being asked with the injected file: make it go to the host file and editor in that case. The faint background of an expression is the platform's colour for injected fragments and needs no code; Task 16 looks at it in a running IDE.

- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: highlight JSONata`.

---

### Task 10: Editing behaviour

**Files:**
- Create: `jsonata/edit/JsonataBraceMatcher.kt`, `JsonataQuoteHandler.kt`, `JsonataCommenter.kt`
- Modify: `plugin.xml`
- Test: `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataEditingTest.kt`

- [ ] **Step 1: Write the test** (all in host files, caret inside a `>-` block scalar unless stated)

```kotlin
fun `test typing an opening bracket adds the closing one`()     // "(", "[", "{"
fun `test typing a closing bracket before one types over it`()
fun `test typing a double quote in a block scalar adds the closing one`()
fun `test a quote that is the host string's own is not paired`()
// typing ' in a single-quoted YAML scalar and " in a double-quoted YAML scalar and in a JSON string inserts one character;
// typing " in the single-quoted and ' in the double-quoted one inserts the pair
fun `test toggling a comment wraps the line in a block comment and removes it again`()   // IdeActions.ACTION_COMMENT_LINE
fun `test Enter inside a multi-line expression keeps the indentation of the block scalar`()
// after Enter at the end of the first of two expression lines, the new line starts with the indentation of that line,
// and the file still has the same sites as before
```

- [ ] **Step 2: Run** the test. Expected: FAIL for brackets, quotes and comments. Note whether the Enter test passes.

- [ ] **Step 3: Implement** a `PairedBraceMatcher` for the three bracket pairs, a `SimpleTokenSetQuoteHandler` for `STRING` and `BAD_STRING` that does not treat a quote as an opening one when it is the quote character of the host string (the character before the `{%` in the host document, which the injected document, a `DocumentWindow`, leads to), and a `Commenter` with the block comment `/*` and `*/` and no line comment. If the Enter test failed in Step 2, add `JsonataEnterHandler : EnterHandlerDelegateAdapter`, registered as `enterHandlerDelegate`, that gives the new line the indentation of the current host line when the caret is inside a JSONata fragment in a YAML block scalar. If it passed, add nothing and keep the test.

- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: brackets, quotes, comments and Enter in JSONata`.

---

### Task 11: Completion

**Files:**
- Create: `jsonata/completion/JsonataCompletionContributor.kt`
- Modify: `plugin.xml` (`completion.contributor` for `AslJsonata`)
- Test: `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataCompletionTest.kt`

**Interfaces:**
- Consumes: `AslSpec.jsonataFunctions`, `AslSpec.contextFields`, `StatesField`, `JsonataPsi.sites`, `JsonataScopes.visibleNames`, `JsonataPath`, `machineText` and `hostText` of `JsonataTestCase`.

**Rules.** The prefix is the text of the token at the caret up to the caret, with its `$`. After `$`: every supported function as `$name` with its parameter list as tail text, `$states`, and the visible local names. Choosing a function inserts `()` and puts the caret between the brackets, or behind them for a function without parameters. As the right side of a path whose left side is `$states`: the fields every site of the expression provides, or all four when there is no site. Below `$states.context`: the context fields of that level. Elsewhere, when the prefix has no `$` and the caret does not follow a `.`: `and`, `or`, `in`, `true`, `false`, `null`, `function`. Nothing is offered for data fields such as those of `$states.input`.

- [ ] **Step 1: Write the test** (`complete` is the helper of `AslTestCase` and takes a text: `hostText(...)` for a field of the Task state, `machineText(...)` for a Catcher)

```kotlin
fun `test functions and states after a dollar sign`()      // contains $sum, $states, $uuid, $partition; lacks $eval
fun `test local names`()                                   // "(\$total := 1; \$t<caret>)" contains $total, $trim, $toMillis
fun `test the fields of states depend on the place`() {
    // Task Output: [context, input, result]; Task Arguments: [context, input]; a Catcher's Output: [context, errorOutput, input];
    // an anchored expression used in a Task Output and, through an alias, in a Pass Output: [context, input]
}
fun `test the context object`()                            // "\$states.context.<caret>" contains Execution, State, StateMachine, Task, Map;
                                                           // "\$states.context.Execution.<caret>" contains Id, Input, Name, StartTime
fun `test keywords`()                                      // "\$states.input.a a<caret>" contains and; "t<caret>" contains true
fun `test choosing a function inserts its brackets`()      // "\$uui<caret>" -> "\$uuid()<caret>"; "\$substringB<caret>" -> "\$substringBefore(<caret>)"
fun `test nothing for data fields`()                       // "\$states.input.<caret>" is empty
fun `test completion in a half-typed expression`()         // "\$sum(\$states.<caret>" in Task Output still gives [context, input, result]
```

- [ ] **Step 2: Run.** Expected: FAIL. **Step 3: Implement** by the rules. **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: completion inside JSONata`.

---

### Task 12: Documentation and parameter hints

**Files:**
- Create: `jsonata/docs/JsonataDocumentationProvider.kt`, `JsonataParameterInfoHandler.kt`
- Modify: `plugin.xml` (`lang.documentationProvider` and `codeInsight.parameterInfo` for `AslJsonata`), `docs/AslDocumentation.kt` if its HTML helpers need to be shared
- Test: `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataDocumentationTest.kt`

**Interfaces:**
- Consumes: `AslSpec.jsonataFunction`, `AslSpec.statesFieldDoc`, `AslSpec.contextFields`, `JsonataScopes.declarations`, `JsonataCall`.

- [ ] **Step 1: Write the test** (follow `AslDocumentationTest` for how documentation is requested)

```kotlin
fun `test a function`()                 // on "$substring": holds the name, each parameter name and the link to docs.jsonata.org
fun `test a Step Functions function and eval`()   // "$uuid": says it is a Step Functions addition; "$eval": says Step Functions does not support it
fun `test states and its fields`()      // on "$states", on "result" in "$states.result", on "Execution" in "$states.context.Execution"
fun `test nothing for a local variable named like a function`()
fun `test parameter info`()
// in jsonata("\$substring(\"abc\", <caret>)"): the handler finds the call, offers the function $substring, and the current parameter index is 1;
// in jsonata("\$substring(\$join([\"a\", \"b\"], \",\"), <caret>)") it is 1 as well
fun `test no parameter info for a function that is not built in`()
```

- [ ] **Step 2: Run.** Expected: FAIL. **Step 3: Implement.** The documentation shows the call form (`$substring(str, start[, length])`), the description, the note for an addition or for `$eval`, and the link. The parameter info shows the same call form with the current parameter emphasised; the index is the number of the call's own argument separators before the caret, not counting commas inside nested calls, arrays or objects. **Step 4: Run** `./gradlew test`. Expected: PASS. **Step 5: Commit** `feat: documentation and parameter hints for JSONata`.

---

### Task 13: Hold the scanner to the reference parser

Needs `src/test/testData/jsonata/reference.json` from the commissioner (Task 1, Step 6). It runs as soon as that file is there and Task 3 is done (see Execution). If the file is still missing when every task before this one is done, ask for it and wait; this is one of the two places where the plan depends on the commissioner.

**Files:**
- Create: `src/test/testData/jsonata/reference.json` (recorded), `src/test/kotlin/io/github/saigyo/aslan/jsonata/JsonataReferenceTest.kt`
- Modify: `jsonata/parser/JsonataScanner.kt`, `JsonataTreeChecks.kt` as the test demands

- [ ] **Step 1: Write the test**

```kotlin
class JsonataReferenceTest : AslTestCase() {
    /** Reference error codes the scanner does not report, each with the reason. */
    private val gaps = mapOf("S0217" to "the parent operator is not resolved", "S0401" to "function signatures are not validated",
        "S0402" to "function signatures are not validated", "EXCEPTION" to "the syntax of a regular expression is JavaScript's and is not checked")
    fun `test no error where the reference parses`()
    // every entry with code null: scan(expr).error == null
    fun `test an error where the reference fails, apart from the gaps`()
    // every entry with a code not in gaps: scan(expr).error != null
}
```
Both tests leave out an entry whose scan holds a `PLACEHOLDER`, `ELVIS` or `COALESCE` token, since the plugin reads those differently on purpose. Each test lists every disagreeing expression with the reference's code. A third test requires that every `EXCEPTION` entry holds a `REGEX` token, so that no other kind of failure hides behind that gap.

- [ ] **Step 2: Run** `./gradlew test --tests '*JsonataReferenceTest'` and read the disagreements.

- [ ] **Step 3: Fix the scanner** until the first test passes with no exception at all: an error on an expression the reference parses is a false error. For the second test, fix what is a deviation of the port. A disagreement that cannot be removed without the parts left out on purpose is added to `gaps` under its code with a reason; no single expression is exempted.

- [ ] **Step 4: Run** `./gradlew test` and the private check. Expected: PASS. **Step 5: Commit** `test: hold the JSONata scanner to the recorded answers of the reference parser`.

---

### Task 14: The expression checks

**Files:**
- Modify: `check/JsonataChecks.kt`, `check/AslProblem.kt`, `check/AslInspections.kt`, `plugin.xml`; create five inspection descriptions
- Test: `src/test/kotlin/io/github/saigyo/aslan/check/JsonataChecksTest.kt`

**Interfaces:**
- Consumes: `JsonataSites`, `JsonataScopes`, `AslSpec.jsonataFunction`, the PSI of Task 4.
- Produces in `AslProblem`, with the levels the spec gives them: `STATES_FIELD_UNAVAILABLE(ERROR)`, `UNKNOWN_JSONATA_FUNCTION(WARNING)`, `UNSUPPORTED_JSONATA_FUNCTION(WARNING)`, `NEWER_JSONATA_OPERATOR(WARNING)`, `JSONATA_WITHOUT_INPUT(WARNING)`.

**The checks.** None applies to an expression with a placeholder outside a string.

| Problem | Reported on |
|---|---|
| `STATES_FIELD_UNAVAILABLE` | `result` or `errorOutput` as the right side of a path whose left side is `$states` (not a local variable), when any site of the expression does not provide that field. AWS sees a copy of an anchored expression in every state that uses it, so one wrong use is enough; the message names the state |
| `UNKNOWN_JSONATA_FUNCTION` | the callee of a call when it is a `$name` that is neither declared locally nor a known function |
| `UNSUPPORTED_JSONATA_FUNCTION` | `$eval`, when it is not declared locally |
| `NEWER_JSONATA_OPERATOR` | a `?:` or `??` token |
| `JSONATA_WITHOUT_INPUT` | `$`, a field name, `*`, `**` or `%` that is evaluated against the input of the expression itself, and `$$` anywhere, since it always names that input. The root has no input, and everything inherits that except: the right side of a path, the predicate of a filter, the pairs of a group-by, the terms of an order-by and the three parts of a transform, which each have one. A lambda body, a block, a call's arguments, the items of an array, the pairs of an object constructor and the left side of a path inherit |

- [ ] **Step 1: Write the tests**, with the element text each finding marks:

```kotlin
fun `test result and errorOutput outside their fields`()     // "$states.result" in Arguments marks "result"; in Output of a Task: nothing; "$states.errorOutput" in a Catcher's Output: nothing
fun `test an anchored expression is reported when one of its uses is wrong`()
// "{% $states.result %}" anchored in a Task Output and used through an alias in a Pass Output: one finding at the anchored scalar;
// used in two Task Outputs: nothing
fun `test a call of an unknown function`()                   // "$nope(1)" marks "$nope"; "(\$f := function(\$x){\$x}; \$f(1))": nothing; "$uuid()": nothing
fun `test eval`()
fun `test the operators of JSONata 2_1`()
fun `test names without an input`()
// reported: "foo", "$", "$$", "foo.bar" (only "foo"), "*", "%", "function(\$v){ foo }(1)" (foo), "\$states.input.items[\$\$.a]" (only "$$")
// nothing: "$states.input.items[price > 1]", "$states.input.items.$", "$states.input.{\"a\": b}", "$states.input.items^(price)", "$states.input ~> |items|{\"a\": b}|"
fun `test nothing in an expression with a placeholder outside a string`()
```

- [ ] **Step 2: Run** `./gradlew test --tests '*JsonataChecksTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the checks in `JsonataChecks` and the five inspections with their descriptions, registered like the one of Task 7.
- [ ] **Step 4: Run** `./gradlew test` and the private check. Expected: PASS. A warning in a clean definition means the check or the definition is wrong; decide by the JSONata documentation, and do not weaken a check to make a definition pass.
- [ ] **Step 5: Commit** `feat: checks inside JSONata expressions`.

---

### Task 15: The answers of AWS

Needs `.superpowers/aws-probes/round-9/aws-results.tsv` from the commissioner. If it is not there, ask for it and wait.

**Files:**
- Modify: `src/test/testData/aws/verdicts.json`, `src/test/testData/aws/README.md`, `src/test/kotlin/io/github/saigyo/aslan/check/AwsVerdictsTest.kt`
- Modify as the answers demand: `check/AslProblem.kt`, `check/JsonataChecks.kt`, `plugin.xml`, the inspection descriptions, `JsonataChecksTest.kt`
- Modify: `docs/superpowers/specs/2026-11-13-aslan-design.md` (sections 7 and 10)

- [ ] **Step 1: Record the answers**

Append one line per probe to `verdicts.json` as text, in the format of its existing lines (`"name": {"aws":"OK"|"FAIL","diagnostics":[...],"definition":{...}},`), and add a sentence on round 9 to the README. Run `./gradlew test --tests '*AwsVerdictsTest'` and keep the two lists it prints: definitions AWS accepts that show an error, and definitions AWS rejects that show none.

- [ ] **Step 2: Settle each disagreement by these rules**, and write each result into the ledger with the probes it rests on.

- **A valid control that AWS rejects** (group `r9-v-`, or the valid ones of `r9-q-` and `r9-w-`): AWS does not parse like the reference in that case. Stop and report it; it is no gap.
- **An accepted probe that shows a syntax error** in `r9-q-`, `r9-v-` or `r9-w-`: the same, stop and report.
- **Syntax errors that AWS accepts** (group `r9-s-` and the quirks): the finding stays an error, because the expression cannot run. `AwsVerdictsTest` gets a second named list, `acceptedButBroken`, for exactly these probes, with that reason. If AWS accepts all of them, its validation does not parse expressions, and the quirks of the reference are then not confirmed by AWS. Say so in the hand-over and propose a second run that executes the quirk expressions with `aws stepfunctions test-state`; do not prepare it unasked.
- **Levels.** `UNKNOWN_JSONATA_FUNCTION` stays a warning. Each of the other four checks becomes an error only if AWS rejects every probe the check reports and accepts every probe it does not report; the spec basis is then "validation". If AWS rejects only some kinds of case and one rule describes them, the error covers those kinds and the rest stays a warning under a second problem; when no rule is visible, the whole check is a warning. A kind of case that no probe covers is never an error. If AWS accepts what a check reports as an error, the check becomes a warning.
- **`$states.nope`.** If AWS rejects an unknown field of `$states` and accepts the four known ones, `STATES_FIELD_UNAVAILABLE` also reports a name that is none of the four.
- **Anything else AWS rejects** (for example a wrong number of arguments) is not implemented in this stage. It becomes a named gap in `AwsVerdictsTest` with its reason and is listed in the hand-over.

- [ ] **Step 3: Apply** what Step 2 settled: levels in `AslProblem` and `plugin.xml`, changed rules with their tests, the gap lists of `AwsVerdictsTest`.
- [ ] **Step 4: Update the spec.** In section 7, move each row to the table (errors or warnings) Step 2 found and set its basis; in section 10, replace the uncertainty about JSONata syntax errors at deployment with the answer.
- [ ] **Step 5: Run** `./gradlew test` and the private check. Expected: PASS. **Step 6: Commit** `fix: hold the JSONata checks to the answers of AWS`.

---

### Task 16: Hand-over

**Files:**
- Modify: `plugin.xml` (`<description>`), `README.md`, `docs/superpowers/specs/2026-11-13-aslan-design.md`

- [ ] **Step 1: Update the texts.** The plugin description and the README gain the JSONata features. The design document gets the decisions of this plan that stand: in section 5 the ported scanner in place of JFlex and Grammar-Kit; in section 6.3 the distilled suite, the recorded reference answers, the gaps, the placeholder rule, the rule for where JSONata is injected, the context fields in completion and the rule for new variable names; in section 8 the two new recordings; in section 9 "no folding"; in section 10 what Task 10 found about Enter and what Task 8 does about searching `$name`.
- [ ] **Step 2: Verify.** `./gradlew check verifyPlugin` passes; `./gradlew test -Paslan.privateFixtures="$PWD"` reports the private definition as checked.
- [ ] **Step 3: Commit** `docs: describe JSONata support`.
- [ ] **Step 4: Hand the running-IDE checklist to the commissioner.** With `./gradlew runIde` and `src/test/testData/clean/film-archive-intake.asl.yaml` open: expressions have a faint background and their own colours in the light and the dark theme (if a default theme shows no background, report it; an own colour for it is a follow-up); brackets match; a comment toggles; Enter and completion behave inside a twenty-line block scalar; typing a quote inside a single-quoted and a double-quoted host string does not break the YAML; parameter hints appear inside a call; a local variable renames in place; the colour settings show the JSONata page; the inspection settings list the six new inspections.
- [ ] **Step 5: Report** what Task 15 settled (each level and its evidence, each new gap, whether AWS confirmed the quirks of the reference), and wait for the commissioner's decision on pushing and the pull request.
