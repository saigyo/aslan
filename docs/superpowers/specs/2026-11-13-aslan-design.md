# Aslan: Amazon States Language support for JetBrains IDEs

Design, 2026-11-13. Status: awaiting approval.

Statements marked **(decided)** come from the person who commissioned the plugin. Statements marked **(my call)** are choices made while writing this design; they can be overruled. Everything else follows from the two.

## 1. Intent

People who write AWS Step Functions state machines as YAML files get nothing from the IDE beyond YAML syntax. A real definition can run past a thousand lines and thirty states. The editor does not know which fields a state takes, and the JSONata expressions inside the file are opaque text: no colouring, no completion, no way to see where a variable is assigned or read.

Aslan makes these files first-class in JetBrains IDEs:

1. The editor understands Amazon States Language (ASL), the way it understands Kubernetes resource files.
2. JSONata expressions inside the file behave like code.
3. State machine variables can be followed between the places they are assigned and the places they are read.
4. A live diagram of the state machine sits beside the editor and is linked to the source.

## 2. The files

**(decided)**

- The main case is YAML with `QueryLanguage: JSONata`. JSON files and JSONPath-mode definitions must work, with less polish.
- Definitions are standalone files inside AWS CDK source trees, not definitions embedded in CloudFormation or SAM templates.
- JSONata appears as strings of the form `{% expression %}`: single-quoted one-liners, folded block scalars (`>-`) of twenty or more lines, and whole field values such as an entire `Arguments` as one expression.
- Files use definition substitution placeholders such as `${OrdersTable}`, which CDK replaces textually at deployment. They appear as a whole value, as a mapping key, inside a longer string, and inside JSONata string literals.
- Files use YAML anchors and aliases, typically to share retry rules.

## 3. Goals, non-goals, anti-goals

### Goals

- Completion, validation and reference documentation for ASL structure in YAML and JSON.
- A structure view that lists states by type, not raw YAML nodes.
- JSONata as a real language inside the file: highlighting, syntax checking, completion and documentation for functions and for the built-in `$states` variable.
- Navigation for state machine variables and for state names: usage highlighting that distinguishes assignments from reads, stepping between occurrences by keyboard, find usages, go to declaration, rename.
- A live diagram beside the editor with shared selection between diagram, editor and structure view.
- Publishable on the JetBrains Marketplace: no proprietary content, licence notices in place. The act of publishing is outside this project.

### Non-goals for this project

- Editing the state machine from the diagram or from the details panel. This is a wanted later project **(decided)**; nothing here should make it harder.
- Definitions embedded in CloudFormation or SAM templates.
- JSONPath as a language: no highlighting, completion or syntax checking of JSONPath expressions and intrinsic functions. JSONPath states get structure support, the diagram, and recognition of variable reads as described in section 6.4 **(my call)**.
- Unquoted placeholders in JSON files, such as `"TimeoutSeconds": ${Timeout}`. The text is not valid JSON until CDK has replaced the placeholder, so the IDE's own JSON parser reports it, and the plugin does not suppress that **(my call)**. Quoted placeholders in JSON and all placeholders in YAML are fully supported.
- Navigation from a placeholder to the CDK code that supplies it.
- Calling AWS (for example the definition validation API). The plugin makes no network requests.
- Evaluating JSONata, a JSONata playground, a JSONata formatter, and completion of data field names such as the fields of `$order`.
- Path-sensitive variable analysis ("may be unassigned on some path"). A natural follow-up, not in this project.
- Treating string values that happen to contain a state name as references to that state.
- Remote development (JetBrains Gateway) support for the diagram.

### Anti-goals

**(decided)** These make the plugin a failure even if everything else works:

1. **A false error.** A file that deploys on AWS must never show an error.
2. **A missed deployment error.** A problem that AWS rejects at deployment and that is detectable from the file should be reported.

Section 7 turns these into a rule for every check.

## 4. Verified facts the design rests on

Researched on 2026-11-13 from primary sources.

### Amazon States Language

Sources: the ASL specification at states-language.net (revision of 22 November 2024) and the Step Functions developer guide.

- Top level: `StartAt` and `States` are required; `Comment`, `Version`, `TimeoutSeconds` and `QueryLanguage` are optional. `QueryLanguage` is `JSONPath` (default) or `JSONata` and may also be set on any state. Nested states inherit from the top level, not from the enclosing Map or Parallel state. A top-level JSONata machine cannot contain JSONPath states.
- State names are unique across the whole machine including nested states, and at most 80 characters.
- Field sets differ by state type and by query language. JSONata states use `Arguments`, `Output`, `Condition` and `Items`. JSONPath states use `InputPath`, `OutputPath`, `Parameters`, `ResultPath`, `ResultSelector`, the `...Path` field variants and the comparison operators in Choice rules. `Assign`, `Retry` and `Catch` exist in both.
- `Assign` may appear on every state except Succeed and Fail, on each Choice rule and on each Catcher. If a Choice rule or Catcher fires, its `Assign` is evaluated instead of the state's own.
- Variable names are Unicode identifiers of at most 80 characters. `states` is reserved.
- Each `States` object is one variable scope. Each Parallel branch and each Map iteration has its own. Inner scopes can read outer variables but must not assign a name that an outer scope assigns. A Distributed Map cannot read outer variables.
- `$states` exists in JSONata states. `$states.input` and `$states.context` are available everywhere. `$states.result` is available only in the top-level `Output` and `Assign` of Task, Map and Parallel states. `$states.errorOutput` is available only in a Catcher's `Output` and `Assign`. Misuse of the last two is rejected at deployment.
- A JSONata string must start with `{%` and end with `%}` with nothing outside. A string not fully wrapped is a literal.
- Step Functions uses JSONata 2.0.6. It adds `$partition`, `$range`, `$hash`, `$random`, `$uuid` and `$parse`, and does not support `$eval`. An expression has no implicit input document, so a top-level `$`, `$$` or bare field name is not allowed.
- Reading a variable that was never assigned fails at run time, not at deployment.
- The AWS validation API publishes these error codes: invalid or missing definition, schema validation failed, invalid resource, missing end state, duplicate state name, invalid state name, missing transition target, duplicate or invalid label, too deeply nested.

### Licences of reference material

- JSONata code and documentation: MIT. Text may be bundled with attribution.
- AWS documentation: CC BY-SA 4.0. Bundling its text would put that text under share-alike terms.
- The ASL specification: a custom licence that allows verbatim copies only.

### IDE platform

- IntelliJ IDEA 2026.3 is current. Release 2026.2 moved the embedded browser and the structure view API into separate bundled plugins and switched the required Java version from 21 to 25.
- Every YAML scalar and every JSON string can host an injected language. For folded and literal block scalars the YAML plugin maps each line separately and keeps the line breaks. For multi-line quoted scalars it drops the line breaks without inserting a separator.
- A YAML key is already a named element that can be renamed and searched. A plugin can add its own read/write classification for keys.
- The YAML and JSON plugins each offer an extension point for a custom structure view that falls back to the default.
- The platform's JSON Schema engine works for YAML too and resolves aliases, but has reported weaknesses in completion for fields that depend on a discriminator such as `Type`.
- The platform's newer Symbol API is still marked experimental.
- The Gradle plugin for IntelliJ Platform (2.20.1) includes lexer and parser generation with JFlex and Grammar-Kit.

### Prior art on the Marketplace

- "Amazon States Language" (open source, Apache-2.0, version 0.1.0): JSON files only.
- "JSONata" (open source, MIT, version 0.1.0): standalone JSONata files and a playground.
- "ASL State Machine Graph" (paid, closed source): a diagram for JSON and YAML.
- AWS Toolkit for JetBrains has no Step Functions editor.

None offers YAML with JSONata support and variable navigation. Both open-source plugins are worth reading before planning.

## 5. Technical decisions

- **Language and build (decided):** Kotlin, Gradle, the official JetBrains plugin tooling, started from the official plugin template.
- **Supported IDEs (decided):** IntelliJ IDEA and other JetBrains IDEs that bundle YAML support, such as WebStorm.
- **Minimum IDE version (my call):** 2026.2, not 2025.3 as first proposed. The 2026.2 release changed the Java version and moved two modules the plugin depends on, and whether one build can span both sides is unverified. The commissioner's IDEs are all on 2026.3. Build with Java 25.
- **Dependencies:** the bundled YAML plugin, the JSON module, the embedded browser module and the structure view module, all required.
- **Identity (my call):** plugin name "Aslan", plugin ID and base package `io.github.saigyo.aslan` (from the repository at github.com/saigyo/aslan), licence Apache-2.0 as already in the repository.
- **File recognition (my call):** a file is an ASL file when its name ends in `.asl.yaml`, `.asl.yml` or `.asl.json`. Further name patterns can be added in settings. The files keep their normal YAML and JSON file types, so all bundled YAML and JSON features keep working.
- **JSON files (my call):** every feature in section 6 works in JSON files, because all of them read the shared document model. "Less polish" means two things only: where a feature needs work specific to the file format, such as inserting quotes and commas on completion or inserting the required fields of a new state, the JSON version may be plainer; and JSON gets fewer editing-behaviour tests than YAML.
- **One model of ASL, no JSON Schema (my call):** the plugin carries its own description of ASL (section 6.1) and drives completion, validation and documentation from it. A JSON Schema would give those three for free, but it cannot express inherited query language, cannot control severity per check, produces poor messages, would report placeholders in typed fields as type errors, and behaves differently across IDE versions. The no-false-errors rule needs control over every reported problem.
- **JSONata is its own language in the IDE (decided)**, with its own lexer and parser, injected into YAML and JSON strings. **(my call)** It does not reuse the existing JSONata plugin, because placeholders, the Step Functions functions and variable references need to be part of the language. It uses a distinct language ID and claims no file extension, so both plugins can be installed together. Lexer with JFlex and parser with Grammar-Kit; hand-writing either is acceptable if generation proves awkward.
- **References (my call):** classic references and a read/write access detector, not the experimental Symbol API.
- **Diagram (decided):** an embedded browser panel beside the editor, like the Markdown preview. **(my call)** Layout by the elkjs library (EPL-2.0, bundled unmodified with its notice), drawing as SVG by the plugin's own JavaScript. All assets ship inside the plugin.
- **Reference texts (my call):** ASL field descriptions are written in the plugin's own words, each with a link to the AWS documentation page. JSONata function descriptions are adapted from the MIT-licensed JSONata documentation with attribution. No AWS documentation text and no ASL specification text is bundled.

## 6. What gets built

Four stages **(decided, in this order)**. Each is planned and delivered separately and leaves a usable plugin.

### 6.1 Shared foundations (built in stage 1)

**ASL reference data.** One bundled data set that describes:

- the top-level fields, the eight state types and the nested structures (Choice rule, Retrier, Catcher, ItemProcessor, ProcessorConfig, ItemReader, ReaderConfig, ItemBatcher, ResultWriter, WriterConfig, Parallel branch);
- for every field: where it is allowed, in which query language, whether it is required, what kind of value it takes (string, integer, number, boolean, one of a fixed list, a state name, a list or mapping of some structure, free-form data, and whether a JSONata string is accepted in its place), a short description and a documentation link;
- the predefined error names (`States.ALL`, `States.Timeout` and the others);
- the JSONata built-in functions and the six Step Functions additions: name, signature, category, description, and whether Step Functions supports it;
- the fields of `$states` and where each is available.

**ASL document model.** A read-only view of an ASL file that is the same for YAML and JSON: the state machine, its states with name, type and fields, nested state machines inside Map and Parallel states, and for each item a link back to its place in the source. YAML aliases and merge keys are resolved, so a retry rule defined once through an anchor is seen wherever it is used. Validation, the structure view, variable resolution and the diagram all read this model. It carries the effective query language of every state.

**Placeholders.** `${name}` is recognised in values, in keys, inside longer strings and inside JSONata. A placeholder is highlighted in its own colour. A field whose value is or contains a placeholder is never type-checked, and a transition whose target is a placeholder is never reported. Placing the caret on a placeholder highlights its other occurrences in the file.

### 6.2 Stage 1: ASL-aware editing

**Completion**

- Field names valid at the caret position, taking the state type and the effective query language into account and leaving out fields already present. Required fields are listed first.
- State types after `Type:`. Choosing one on a new state inserts its required fields.
- Fixed-list values such as `QueryLanguage`, `JitterStrategy`, `Mode` and `ExecutionType`.
- State names after `Next`, `Default` and `StartAt`, limited to the states of the same `States` object.
- Predefined error names under `ErrorEquals`.
- The optimised service integration ARNs after `Resource` on a Task, from a curated list. Any other string is accepted without comment.

**Validation** as specified in section 7.

**Documentation.** Quick documentation and hover on field names, state types and fixed-list values, from the reference data.

**State names.** The key of a state under `States` is its declaration. `StartAt`, `Next` and `Default`, including `Next` in Choice rules and Catchers, are references. Go to declaration, find usages, usage highlighting and rename all work; rename changes the key and every reference.

**Structure view.** States in file order with an icon and label per type. A Map or Parallel state contains its inner states. Under each state: its assigned variables, its Choice rules and its Catchers. Selecting an entry moves the caret.

**Highlighting.** Field names that ASL defines are coloured differently from keys inside free-form data such as `Arguments`. State names are emphasised. The colours appear in the IDE's colour settings.

### 6.3 Stage 2: JSONata

**Grammar.** All of JSONata 2.0.6: literals, names including backtick-quoted names, paths, wildcards and the parent operator, predicates, array and object constructors, ranges, all operators, the conditional with and without an else part, variable binding, blocks, function definitions and calls including partial application, the chain operator, ordering and grouping, positional and context variable binding, the transform operator, regular expression literals, comments and function signatures. The two operators added in JSONata 2.1 (`?:` and `??`) are parsed and reported by a check, not by the parser. A placeholder is a single token wherever it appears. `${` followed by something that is not a plain placeholder name is ordinary JSONata (the context value followed by a grouping expression).

**Conformance.** The official JSONata test suite (MIT) is vendored as test data. Every expression in it that is expected to evaluate must parse without error, and every one expected to fail with a syntax error must produce one. This is the main defence against false syntax errors.

**Injection.** JSONata is injected into every string value in a JSONata-mode state that starts with `{%` and ends with `%}`, covering only the text between the delimiters. It works for single-quoted, double-quoted, folded and literal YAML scalars, single-line and multi-line, and for JSON strings. The injected text must have the same token boundaries as the text Step Functions receives. In particular, where YAML joins two lines of a multi-line quoted scalar with a space, the injected text must have a separator there too; the YAML plugin's default mapping does not, so the injector supplies it. Each scalar style has its own tests.

**Editing.** Syntax highlighting with its own colour settings, a faint background on expressions, matching of brackets and quotes, comment toggling, and sensible behaviour of the Enter key inside multi-line expressions.

**Completion and documentation**

- Functions, shown with their signatures. Quick documentation on a function name and parameter hints inside a call.
- `$states` and its fields, limited to those available at that position, including the fields of the context object.
- JSONata-local variables and function parameters in scope, and the keywords.

**Local variables.** A binding such as `$total := ...` and a function parameter are declarations within their expression. They have go to declaration, usage highlighting and rename, limited to that expression. A local name hides a state machine variable of the same name.

### 6.4 Stage 3: state machine variables

**Declarations and references.** Each key directly under an `Assign` mapping is an assignment of a variable; in a JSONPath state the key's `.$` suffix is not part of the name. A `$name` in JSONata that is not a local variable, a function parameter, a built-in function or `$states` is a read of the state machine variable `name`.

**Reads in JSONPath states (my call).** In a JSONPath state, a variable is read as `$name` at the start of a path or as an argument of an intrinsic function, where `$.` and `$$.` mean the input and the context object. The plugin recognises these reads by pattern in the fields that JSONPath evaluates (keys ending in `.$`, the `...Path` fields, and `Variable` in Choice rules) and treats them like reads in JSONata. This keeps find usages complete and rename safe in machines that mix both query languages. It does not parse JSONPath.

**Scope and identity (my call).** A variable is identified by its name and its owning scope, not by its name alone. The owning scope of an assignment or read is the outermost `States` object, among the one that contains it and the ones enclosing that, in which the name is assigned. The search does not cross into or out of a Distributed Map. Occurrences with the same name and the same owning scope are the same variable. So a name assigned at the top level is one variable everywhere below it, while two Parallel branches that each assign `count`, with no assignment further out, have two separate variables. A read with no owning scope is unresolved. Highlighting, find usages, rename and the variable checks all work on this identity.

**Navigation (decided)**

- Placing the caret on a variable, at an assignment or a read, highlights every assignment and read of that variable. Assignments and reads have different colours, as in Java.
- The IDE's actions for stepping to the next and previous highlighted usage work on these highlights. The plugin's description names the shortcuts.

**(my call)** In addition:

- Go to declaration from a read jumps to the assignment, or offers a list when there are several. Each entry names the state and the place, for example "ReserveStock, second Catcher".
- Find usages lists all assignments and reads.
- Rename changes every assignment and read, and rejects a name that is not a valid identifier.
- Completion after `$` in JSONata offers the variables visible at that position.

### 6.5 Stage 4: diagram

**Mockup.** The file `2026-11-13-aslan-design-mockup.html` next to this document is part of the design. Open it in a browser. Figure 1 shows the structure view, editor and diagram with a state selected; the commissioner approved this arrangement. Figure 2 shows the diagram pane with a path selected, catch paths muted and the details panel collapsed. The mockup fixes arrangement and behaviour; exact colours, icons and spacing are left to the builder. Where the mockup and this text disagree, this text wins.

**Appearance (decided, from the mockup).** The diagram flows from top to bottom. A state is a rounded rectangle with a coloured bar on its left edge for the type, the state name in bold, and a smaller second line with the type and, for a Task, the resource label. A selected state has a highlighted fill and border. Transitions are solid lines with arrowheads, drawn with right-angle bends, and their labels sit on the line. Catch paths are dashed and red. A legend under the diagram names the type colours and the catch line. The details panel is a two-column table of labels and values.

**Arrangement (decided).** ASL files open in an editor with three modes: editor only, split, diagram only. In split mode the diagram is on the right. Below the diagram is a details panel for the selected state, which can be collapsed so the diagram uses the whole pane.

**Content**

- A node per state, showing its name, its type by colour and label, and for a Task a short label derived from the resource ARN, such as "S3 listObjectsV2".
- A line per transition: `Next`, each Choice rule (labelled with its condition, shortened, full text on hover), `Default`, and each Catcher (dashed, labelled "Catch"). The start state and terminal states are marked.
- **(my call)** A Map state is a box containing its inner states; a Parallel state is a box containing one box per branch. Boxes can be collapsed.

**Interaction**

- **(decided)** Selection is shared. Clicking a node moves the caret to that state. Moving the caret in the editor or selecting in the structure view selects the node and brings it into view.
- **(decided)** Catch paths have a three-way switch: normal colour, muted, hidden.
- **(decided)** Any path can be selected. This highlights the path and the two states it connects.
- **(my call)** Selecting a path also moves the caret to the line that creates it.
- **(my call)** Zoom, pan and fit to window. The diagram follows the IDE's light or dark theme.
- **(my call)** The diagram redraws shortly after typing stops. While the file cannot be read as a state machine, the last good diagram stays, marked as out of date.
- **(my call)** The catch switch, the panel's collapsed state and the editor mode are remembered.

**Details panel (decided: read-only).** For the selected state: type, resource, variables read, variables assigned, retry rules, catchers with their targets, and the next state. State and variable names are links into the source.

**Without an embedded browser.** If the IDE runtime has none, the diagram pane shows a short explanation and the editor works as usual.

## 7. Checking policy

The rule that follows from the anti-goals:

- A problem is an **error** only if AWS rejects the definition at deployment, or if the text is not valid JSONata and so cannot be evaluated at all.
- Anything that deploys but is probably a mistake is a **warning** at most.
- Every check has its own entry in the IDE's inspection settings, so it can be switched off or changed in severity without affecting the others.

**Evidence.** The checks of stage 1 were compared with the definition validation of Step Functions (`aws stepfunctions validate-state-machine-definition`) on 2026-11-14, with about 1,400 small definitions. Most hold one condition; some hold a set of candidate field names in one position. Every field of every structure was sent with `null`, with a value of the wrong kind and with a JSONata string. AWS documents that an error from this validation prevents creating or updating the state machine. The recorded answers are committed as test data, and two tests hold the checks to them: no error on a definition AWS accepts, and an error on every definition AWS rejects, apart from a short list of gaps that the test names with their reasons. The rules below marked "validation" rest on these answers.

### Errors

| Problem | Basis |
|---|---|
| File is not a mapping, or `StartAt` or `States` is missing | validation |
| State without `Type`, or with an unknown type | validation |
| Required field missing: `Resource` on Task, `Choices` on Choice (or empty), `Branches` on Parallel (or empty), `ItemProcessor` on Map, `ErrorEquals` in a Retrier or Catcher, `Next` in a Catcher or Choice rule, `Condition` in a JSONata Choice rule, a time field on Wait, `RoleArn` in `Credentials` | validation |
| A Task that calls a service integration has neither `Parameters` nor `Arguments`, even when the call takes no parameters | validation |
| A state that needs a transition has neither `Next` nor `End` | validation |
| `StartAt`, `Next` or `Default` names a state that does not exist in the same `States` object, or is empty | validation |
| No terminal state in a `States` object | validation |
| A state that no transition names | validation |
| Duplicate state name anywhere in the machine; name longer than 80 characters | validation |
| A JSONPath-only field in a JSONata state, or a JSONata-only field in a JSONPath state | validation, for every such field |
| A JSONPath state in a machine whose top level is JSONata | validation |
| A value of the wrong basic kind, such as text where a number is required, or a number with a fraction where a whole number is required; `5.0` only in a JSON file, since tools that convert YAML differ on it | validation |
| `null` in an optional field with a fixed kind of value, and in `Assign`, `Items`, `Credentials` and the `Parameters` or `Arguments` of a Task, ItemReader or ResultWriter; AWS accepts it in the paths, in `Error` of a Fail state and in `CSVDelimiter` | validation |
| Field name that ASL does not define, outside free-form data | validation; the plugin's field names were compared with AWS in every position |
| Field that ASL defines, used on a state type or structure that does not take it, such as `Next` on a Succeed state | validation |
| A value that is not one of the values a field takes, such as an unknown `JitterStrategy`, or a `Version` other than the string `"1.0"` | validation; the plugin's lists match the ones AWS reports |
| Value outside the range AWS applies, such as a negative `MaxAttempts`, a `BackoffRate` below 1 or a `TimeoutSeconds` above 99999999 | validation |
| Two fields together that exclude each other, such as two time fields on a Wait state, or `Next` together with `End`, whatever its value | validation |
| A JSONata string in a field that does not evaluate JSONata, such as `MaxAttempts`; not in `Comment` or `ItemsPointer` | validation, for every field |
| `States.ALL` combined with other names, or not in the last Retrier or Catcher | validation |
| An empty `ErrorEquals`, `And`, `Or` or `CSVHeaders` list | validation |
| A JSONPath Choice rule that is neither one combination nor a `Variable` with exactly one operator | validation |
| A path that does not start with `$`; a `ResultPath` that is not a reference path; a key ending in `.$` whose value is neither a path nor an intrinsic function | validation |
| A timestamp that is not an RFC 3339 date and time that exists, with a time zone | validation |
| A Map `Label` that is empty, longer than 40 characters, or holds whitespace or a forbidden character; a label used twice | validation |
| A Map setting that lacks a field it needs, such as distributed mode without `ExecutionType` or a CSV reader without `CSVHeaderLocation` | validation |
| A field that does not apply where it is used, such as `CSVDelimiter` for a JSON input, or `Credentials` with an activity | validation |
| The `Resource` of a Task state is not a service integration, a Lambda ARN or an activity ARN, or is an AWS SDK integration of the wrong form | validation |
| The `RoleArn` in the `Credentials` of a Task state is not an IAM role ARN, `arn:partition:iam::account:role/name` | validation |
| A string in a JSONata state that holds `{%` or `%}` but is not wrapped in them as a whole, where JSONata is evaluated | validation |
| `$states.result` or `$states.errorOutput` outside the fields that provide them | documented as rejected |
| JSONata syntax error | cannot be evaluated |

A value that is a placeholder or a JSONata string never triggers the "wrong basic kind" error. Neither does a plain YAML value that YAML 1.1 and YAML 1.2 read as different types, such as `yes` or `1_000`, because it is not known which YAML version the tools in front of AWS apply.

### Warnings

| Problem | Why not an error |
|---|---|
| The `Resource` of a Task state is written as a service integration (`arn:aws:states:::…`) that is not in the plugin's list | AWS rejects an integration it does not recognise, but it adds integrations over time and not every region has each, so the list can be behind |
| State that only unreached states lead to | deploys |
| `End: false` without `Next` | deploys as long as another state ends the machine |
| An `ItemBatcher` without a limit, or a `ResultWriter` with neither `Resource` nor `WriterConfig` | deploys |
| `HeartbeatSeconds` not smaller than `TimeoutSeconds` | deploys |
| A name in `ErrorEquals` that starts with `States.` but is not predefined | deploys |
| Choice state without `Default` (weak warning) | fails only at run time, and only if no rule matches |
| Read of a variable with no assignment in any visible scope | fails at run time |
| Variable assigned but never read (weak warning) | harmless |
| Assignment to `states`, to an invalid name, or in an inner scope to a name an outer scope assigns | rejection unconfirmed |
| Call of a function that is not built in and not defined locally | fails at run time |
| `$eval`, or an operator from JSONata 2.1 | fails; time of failure unconfirmed |
| Top-level `$`, `$$` or bare field name in an expression | rejection unconfirmed |

### Never reported

- Placeholders in any position.
- YAML anchors, aliases and merge keys.
- Anything inside free-form data: the contents of `Arguments`, `Output`, `Assign` values, `Parameters`, `Result` and similar fields, apart from JSONata syntax and keys that end in `.$`. AWS checks the parameter names of a service call; the plugin does not, because it has no list of them.
- The `Resource` of an ItemReader or ResultWriter, beyond being a string. In the `Resource` of a Task state and in a `RoleArn`, whether the function, activity, role, region or account exists cannot be known from the file. The services and actions of an AWS SDK integration are not checked either.

## 8. Testing and verification

- **Automated tests** run in the platform's test framework: completion results, expected errors and warnings marked in test files, references, rename, structure view content, injection for each scalar style, and the document model for YAML and JSON including aliases.
- **JSONata conformance** against the vendored official test suite, as in section 6.3.
- **A large synthetic definition** is committed as the main test case. It is written in an invented English domain and must be comparable to a real production file: over 1,000 lines and 40 states; all eight state types; a Map with an inline processor of several states; a Parallel with two branches; retry rules shared through YAML anchors and aliases; placeholders in all four positions; JSONata in every scalar style, including blocks of twenty lines with lambdas, regular expressions, predicates, sorting and nested object construction; a variable with at least fifteen assignments; a Choice state that dispatches on a variable; comments throughout. It must show no errors and no warnings.
- **Small definitions** each plant one problem from section 7 and assert that exactly that problem is reported. A JSON definition and a JSONPath-mode definition are included.
- **(my call)** The build cannot prove that the synthetic definition deploys. It is cross-checked once with the open-source `asl-validator`. The commissioner can also run it through the AWS command line validation.
- **Recorded AWS answers.** `src/test/testData/aws` holds the definitions sent to the AWS validation and its answers, as described in section 7. They are re-recorded with the AWS command line when the rules of AWS may have changed.
- **A private file check.** The commissioner has a real definition from a closed-source project. It must never be committed, and neither its names nor its comments may appear in the repository. A test that runs only when a local setting points at such files asserts that they show no errors. It is not part of any shared build.
- **In a running IDE.** Each stage ends with the plugin started in a sandbox IDE and exercised on the synthetic definition, since editor behaviour and the diagram cannot be fully covered by automated tests.
- **Compatibility.** The JetBrains plugin verifier runs against 2026.2 and 2026.3.

## 9. Left to the builder

- Package and module structure, and the format of the reference data.
- How checks are grouped into inspections, and their names.
- Icons, exact colours and the wording of messages and descriptions.
- Structure of the diagram's JavaScript, whether it needs a build step, and how it is tested.
- Caching, debounce intervals and other performance measures. A 1,500-line file with several hundred expressions must stay responsive.
- Whether code folding for multi-line expressions is worth adding in stage 2.

## 10. Known uncertainties

These could not be verified and are handled as stated.

| Uncertainty | Handling |
|---|---|
| Exact naming rules for placeholders | Accept letters, digits, underscore, hyphen, dot, colon and comma between `${` and `}` |
| Whether AWS rejects JSONata syntax errors at deployment or only at run time | Reported as errors either way, since the expression cannot run |
| Whether several warnings in section 7 are in fact deployment errors | They stay warnings until confirmed |
| Whether `Assign` is allowed in nested JSONPath Choice rules | Not reported |
| Whether the IDE's default search finds `name` inside `$name` | The plugin provides its own search within the file |
| Completion and Enter-key behaviour inside injected block scalars | Verified in a running IDE during stage 2 |
| The complete list of fields of the `$states.context` object and of predefined error names | Taken from the AWS documentation during stage 1 and 2 |
