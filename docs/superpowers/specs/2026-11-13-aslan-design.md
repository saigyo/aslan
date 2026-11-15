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
- **JSONata is its own language in the IDE (decided)**, with its own lexer and parser, injected into YAML and JSON strings. **(my call)** It does not reuse the existing JSONata plugin, because placeholders, the Step Functions functions and variable references need to be part of the language. It uses a distinct language ID and claims no file extension, so both plugins can be installed together. **(my call, made in the plan of stage 2)** The scanner is a port of the tokenizer and the parser of jsonata-js 2.0.6 (`src/parser.js`, MIT), not a JFlex lexer and a Grammar-Kit parser. In the reference, the parser tells the tokenizer whether a `/` starts a regular expression, which a generated lexer cannot reproduce, and some errors are found only after parsing, which a grammar cannot express. A direct port can be compared with the original line by line. The port turns the text into tokens, a node tree and at most one error; the IDE lexer and the IDE parser replay that result, so there is one source of truth for what is valid.
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

**Grammar.** All of JSONata 2.0.6: literals, names including backtick-quoted names, paths, wildcards and the parent operator, predicates, array and object constructors, ranges, all operators, the conditional with and without an else part, variable binding, blocks, function definitions and calls including partial application, the chain operator, ordering and grouping, positional and context variable binding, the transform operator, regular expression literals, comments and function signatures. The two operators added in JSONata 2.1 (`?:` and `??`) are parsed and reported by a check, not by the parser. A `${name}` placeholder is a single token wherever it appears, and it is accepted where the reference demands a variable (after `@`, `#` and `:=`, and as a lambda parameter). **(my call)** An expression that holds `${` outside a string literal is not judged at all, whatever follows: no syntax error and no other check. Deployment tools use placeholder forms the plugin's pattern does not know (`${ssm:/x}`, `${opt:stage, 'dev'}`), and a false error outranks a missed one. The reading of `${` as the context value followed by a grouping expression is therefore not applied; it is rare. A placeholder inside a string literal changes nothing.

**Nesting limits.** **(my call)** The scanner parses at most 60 levels of nesting and the checks after parsing walk at most 120 levels. Beyond a limit an expression gets no error; hand-written JSONata does not nest that deep, and silence is the safe side. A long `? :` else-if chain counts as nesting.

**Conformance.** The official JSONata test suite (MIT) is vendored as test data, **(my call)** as one distilled file of identifier, expression and expected error code, not as the 1,241 files with input data and results that no test reads. Every expression in it that is expected to evaluate must parse without error, and every one expected to fail with a syntax error must produce one. The suite has only 41 syntax error cases, so the real jsonata-js 2.0.6 parser was also run once over 6,168 expressions (the suite's own, some hand-picked quirks, and small mutations of them), and its answers are committed. A test holds the scanner to every one of them, comparing only error or no error. This is the main defence against false syntax errors.

**Named gaps.** Three kinds of error are not reported, because reporting them needs a port of further parts of the reference whose risk of a false error is larger than the benefit: a parent operator `%` that cannot be resolved (S0217), errors inside a function signature (S0401, S0402), and the syntax of a regular expression (the reference hands the pattern to JavaScript, and Java's rules differ). The tests name these gaps.

**Injection.** **(my call)** JSONata is injected only where Step Functions evaluates it: in a state whose query language is known to be JSONata, into the scalar values of fields that take JSONata and into the strings inside free-form data, where the string starts with `{%` and ends with `%}`. It covers only the text between the delimiters. A `{% %}` string in `Comment`, in a JSONPath state, at the top level or in a state whose query language is a placeholder stays plain text. A scalar used by several states through an alias is a site for each of them. Nothing is injected where the host's text cannot be decoded with certainty, such as an anchored or stale scalar; the known case is a scalar whose quote style is changed in place (`'` to `"`), which is injected again after a reload. It works for single-quoted, double-quoted, folded and literal YAML scalars, single-line and multi-line, and for JSON strings. The injected text must have the same token boundaries as the text Step Functions receives. In particular, where YAML joins two lines of a multi-line quoted scalar with a space, the injected text must have a separator there too; the YAML plugin's default mapping does not, so the injector supplies it. Each scalar style has its own tests.

**Editing.** Syntax highlighting with its own colour settings, a faint background on expressions, matching of brackets and quotes, comment toggling, and sensible behaviour of the Enter key inside multi-line expressions.

**Completion and documentation**

- Functions, shown with their signatures. Quick documentation on a function name and parameter hints inside a call.
- `$states` and its fields, limited to those available at that position. **(my call)** Every field of `$states.context` is offered everywhere: which parts of the context object have a value depends on things the file does not always show (a callback pattern, a Map iteration), so the documentation says where each has a value instead.
- JSONata-local variables and function parameters in scope, and the keywords.

**Local variables.** A binding such as `$total := ...` and a function parameter are declarations within their expression. They have go to declaration, find usages, usage highlighting and rename, limited to that expression. A local name hides a state machine variable of the same name.

**(my call)** The scope rule follows JSONata's order of assignments, not the plan's first, simpler rule. A block or a lambda declares what is assigned or bound in it, a lambda also what is bound directly in its body, and a use that stands before the end of the first assignment of that name in the declaring block or lambda, and not inside a nested lambda, refers to the outer scope. The simpler rule let a rename change the meaning of an expression. Stage 3 must use the same rule. A whole path that binds a name with `@` or `#` is a scope of its own: the binding is visible in the later steps of that path and ends with it, as in jsonata-js 2.0.6, where `(items@$sum.name; $sum([1, 2]))` calls the built-in `$sum` (found by the Copilot review of PR 3; the first rule let the binding reach the rest of the block). An assignment `:=` is declared by the nearest block, lambda or file, also inside such a path, as JSONata does for the first step (`([$a := 1]#$i.$; $a)` gives 1). A transform `|…|…|` runs later, like a lambda, so a name used inside it refers to the scope wherever it stands. Names are compared decoded from the host's escapes (`$\u0061` in JSON is `$a`). Remaining differences from JSONata: a name used in a nested lambda or transform refers to the scope even if it runs before the assignment, so navigation links it to the later assignment, and rename refuses (below); an assignment in a later step or a filter of a path that binds with `@` or `#` is treated as visible after it, where JSONata keeps it to that step; an assignment that may not run (in a branch of a condition, an array, an argument) is treated as if it always ran; `$a := $a := 1` is not modelled. **(my call)** A variable can only be renamed to an identifier (letters, digits and underscore, starting with a letter, as AWS requires, and not `states`), because other characters can break the host string. The plugin has its own rename processor and in-place rename handler that write through the document window, because the platform's rename damages YAML hosts, and it refuses a new name the validator would refuse. It also reports a conflict for a name that another local variable visible at an occurrence has, for `states`, and for a name the expression reads without a local declaration (a called built-in function, a state machine variable or an undeclared name). It refuses any rename when the old or the new name is used inside a function or transform that stands before the assignment it refers to, since what that use reads depends on when it runs; a function that calls itself (`$f := function(){ $f() }`) is exempt, because it exists only once assigned. It also refuses any rename when the old or the new name is first assigned where the assignment may not run, that is anywhere but as a statement of its block, lambda or file or as the right side of such an assignment (`(false ? $sum := 0 : 1; $sum([1, 2]))` calls the built-in). Finally it reads the renamed expression and refuses when any occurrence would then refer to another variable than before, as for a name declared later in the same scope (`($a := 1; true ? $a : $b := 2; $exists($b))` renamed from `a` to `b`). These rules are taken over the whole expression, so they refuse some renames that would be safe, for example a name read only in another part of the expression or an outer variable the inner scope never reads, but they never let a rename change the result. Cancelling an in-place rename restores the old name in every occurrence. Typing the host string's own quote inside an expression inserts its escaped form (`''` in a single-quoted YAML scalar, `\"` in a double-quoted one and in JSON), so the host string stays intact.

### 6.4 Stage 3: state machine variables

**Declarations and references.** Each key directly under an `Assign` mapping is an assignment of a variable; in a JSONPath state the key's `.$` suffix is not part of the name. A `$name` in JSONata that is not a local variable, a function parameter, a built-in function or `$states` is a read of the state machine variable `name`.

**Reads in JSONPath states (my call).** In a JSONPath state, a variable is read as `$name` at the start of a path or as an argument of an intrinsic function, where `$.` and `$$.` mean the input and the context object. The plugin recognises these reads by pattern in the fields that JSONPath evaluates (keys ending in `.$`, the `...Path` fields, and `Variable` in Choice rules) and treats them like reads in JSONata. This keeps find usages complete and rename safe in machines that mix both query languages. It does not parse JSONPath.

**Scope and identity (my call).** A variable is identified by its name and its owning scope, not by its name alone. The owning scope of an assignment or read is the outermost `States` object, among the one that contains it and the ones enclosing that, in which the name is assigned. The search does not cross into or out of a Distributed Map. Occurrences with the same name and the same owning scope are the same variable. So a name assigned at the top level is one variable everywhere below it, while two Parallel branches that each assign `count`, with no assignment further out, have two separate variables. A read with no owning scope is unresolved. Highlighting, find usages, rename and the variable checks all work on this identity.

The chain of scopes a read can see ends at the item processor of a Distributed Map: its states read only what their own machine assigns, and what they assign is invisible outside it. An inline Map and a Parallel branch see the machines around them. **A machine that does not say every name assigned in it is open**: a placeholder or a single JSONata string as `Assign`, a placeholder or an unapplied merge key in it or in `States`, or a Map whose `Mode` is a placeholder or unknown. A read that finds no assignment in an open scope is not reported as unassigned, and an assignment in an open scope is not reported as never read, since the answer depends on the part the file does not show. An unknown `Mode` therefore makes the scope open instead of being taken as inline or distributed.

**Variable names (decided).** A name a variable can be renamed to, and a name the plugin calls plain, starts with a letter, continues with letters, digits and `_`, has at most 80 code points, and is not `states`. This rule may be stricter than AWS, never looser. The checks report as errors only the classes of names AWS rejected (section 7).

**When rename refuses (decided).** Rename changes every assignment and read of the variable and stops with a conflict, naming the place, when it cannot keep the meaning of every occurrence: the new name is not a valid name; the new name is assigned, read or declared locally anywhere the variable's region can see it, or is mentioned in a string the model does not know as a read (a built-in function, say); the old name is mentioned in such a string, or in a string with a syntax error; an occurrence is shared with another variable, as through a YAML alias used by states of different scopes; a merged key has the old or the new name; the raw text of an occurrence is not the name, as with an escape; or a machine of the region is open. The refusal is conservative: it refuses some safe renames and never changes the meaning.

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

**Evidence.** The checks were compared with the definition validation of Step Functions (`aws stepfunctions validate-state-machine-definition`) on 2026-11-14, with about 1,400 small definitions for stage 1 229 more for the JSONata checks of stage 2 and 62 more for the variable checks of stage 3 (rounds 11 to 13, section 8). Most hold one condition; some hold a set of candidate field names in one position. Every field of every structure was sent with `null`, with a value of the wrong kind and with a JSONata string. AWS documents that an error from this validation prevents creating or updating the state machine. The recorded answers are committed as test data, and tests hold the checks to them: no error on a definition AWS accepts, and an error on every definition AWS rejects, apart from a short list of gaps that the test names with their reasons. The rules below marked "validation" rest on these answers.

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
| A value of the wrong basic kind, such as text where a number is required, or a number with a fraction where a whole number is required; a whole value written as `5.0` or `1e2` only in a JSON file, since tools that convert YAML differ on it; a mapping or list as the value of a key that ends in `.$` | validation |
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
| The `Resource` of an ItemReader or ResultWriter names its S3 action with a region or an account; a Task may do so, a reader or writer may not | validation |
| The `RoleArn` in the `Credentials` of a Task state is not an IAM role ARN, `arn:partition:iam::account:role/name` | validation |
| A string in a JSONata state that holds `{%` or `%}` but is not wrapped in them as a whole, where JSONata is evaluated | validation |
| `$states.result` or `$states.errorOutput` outside the fields that provide them, or a field of `$states` that is none of `input`, `result`, `errorOutput` and `context`, also with a filter or binding after it (`$states.result[0]`), and `$states.*`. AWS decides by the name `$states`, so a local variable of that name assigned with `:=` does not change the answer. A `$states` that is a parameter of a function definition is not checked: AWS was not asked about it | validation: `result` and `errorOutput` in all 46 places asked, and each of the other forms named here |
| JSONata syntax error | validation: AWS parses every expression with the JSONata 2.0.6 parser and rejected all 40 syntax errors of the official test suite |
| An operator from JSONata 2.1 (`?:`, `??`) | validation |
| A read in a JSONPath string of a variable with no assignment in any visible scope (`PATH_VARIABLE_NOT_ASSIGNED`) | validation, round 11: assigned nowhere, only inside a Map or Parallel branch, only in another branch, only outside a Distributed Map. A read that stands before its assignment in the flow is rejected too but not found, since the order of states is not modelled (section 10) |
| A read in JSONata inside a Distributed Map of a name assigned only around it (`DISTRIBUTED_MAP_OUTER_READ`) | validation, round 11 |
| An `Assign` key, in a state of any query language, that starts with a digit or `_`, holds `.`, `-` or a space, has more than 80 characters, or is `states` (`INVALID_VARIABLE_NAME`) | validation: round 11 for JSONata states (digit, `_`, `-`, `.`, space, 81 characters, `states`), round 12 for JSONPath states (digit, `_`, `-`, `states`, where AWS answers `RESERVED_VARIABLE_NAME` for the last). AWS gives the same answer in both, so dot, space and length are taken to hold in JSONPath states too. A name of 80 characters and names with the Unicode letters tried are accepted |
| An assignment in a nested state machine (Map of either mode, Parallel branch) of a name that a machine around it assigns before the flow enters the Map or Parallel state (`OUTER_VARIABLE_ASSIGNED`) | validation: AWS reports `DUPLICATE_VARIABLE_NAME` at the inner key for the three shadows of round 11 and the one of round 12. The order of the flow decides: accepted are outer assignments only after the state that holds the nested machine, in its own `Assign` and in its Catchers (round 12). Round 13 confirms the paths: a Catcher's `Assign` or a Choice rule's `Assign` whose `Next` reaches the state, a loop back to it and two levels of nesting are rejected, a state's `Assign` whose only path to the state is its Catcher and a Choice rule's `Assign` on another rule's path are accepted. Two branches that assign the same name are accepted. Where the outer flow is not known, nothing is reported |
| A field name or `$` that is evaluated against the input of the expression itself, which does not exist, and `$$` anywhere. The places that inherit the missing input: the top level, brackets and blocks, arrays and ranges, object constructors (keys and values), the arguments of a call whose callee is a `$name` or a function definition (unless an argument is the `?` of a partial application), the body of a function called directly, by a local name or by a higher-order built-in, the operands of every operator, the three parts of a conditional, the value of `:=`, both sides of `~>`, and the first step of a path, also when a filter, sort, group-by or `@` binding follows it. The base of a `#` binding is taken to have an input: AWS was not asked about it | validation: AWS rejected the name in every one of these places it was asked about, in rounds 9 and 10. A `$` or `$$` the expression binds itself is not reported; AWS accepts it |

A value that is a placeholder or a JSONata string never triggers the "wrong basic kind" error. Neither does a plain YAML value that YAML 1.1 and YAML 1.2 read as different types, such as `yes` or `1_000`, because it is not known which YAML version the tools in front of AWS apply. A YAML mapping or list with a tag such as `!Join [...]` or `!If [...]` is not judged either: whatever handles the tag decides the value. The same holds for a CloudFormation function in its long form, a mapping with the single key `Ref` or `Fn::Name` such as `Fn::GetAtt: [Role, Arn]`, in YAML and in JSON: a definition file that a template pulls in with `AWS::Include` can only use this form. The value of `States` is the exception, since a machine may have a single state named `Ref`.

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
| Read in JSONata of a variable with no assignment in any visible scope, apart from the Distributed Map error above (`VARIABLE_NOT_ASSIGNED`) | AWS only warns that the variable is possibly not defined (round 11). Where the read stands before the assignment in the flow, AWS warns too; the plugin does not find that |
| Variable assigned but never read, in no string of the file and not through a placeholder that stands for a whole value (weak warning, `VARIABLE_NEVER_READ`) | harmless |
| An `Assign` key that is no plain variable name, other than the names rejected above, in a state of any query language, such as a name with a combining mark (`UNUSUAL_VARIABLE_NAME`) | rejection unconfirmed: AWS's own identifier rule may accept it |
| Call of a function that is not built in and not defined locally | deploys; AWS itself only warns, and the plugin's list can be behind |
| `$eval` | deploys; AWS itself only warns |

### Never reported

- Placeholders in any position.
- YAML anchors, aliases and merge keys.
- Anything inside free-form data: the contents of `Arguments`, `Output`, `Assign` values, `Parameters`, `Result` and similar fields, apart from JSONata syntax and keys that end in `.$`. AWS checks the parameter names of a service call; the plugin does not, because it has no list of them.
- Inside a JSONata expression: a bare `*` or `**` and paths that start with them (AWS accepts them), the parent operator `%` and a function signature (AWS rejects what cannot be resolved, but the plugin does not port those parts of the parser), an unknown field below `$states.context` (the plugin's list can be behind AWS), and the number and types of the arguments of a call (AWS does not check them either).
- A JSONata expression that holds `${` outside a string literal: a deployment tool replaces that text before AWS sees it.
- Which actions the `Resource` of an ItemReader or ResultWriter may name. In the `Resource` of a Task state and in a `RoleArn`, whether the function, activity, role, region or account exists cannot be known from the file. The services and actions of an AWS SDK integration are not checked either.

## 8. Testing and verification

- **Automated tests** run in the platform's test framework: completion results, expected errors and warnings marked in test files, references, rename, structure view content, injection for each scalar style, and the document model for YAML and JSON including aliases.
- **JSONata conformance** against the vendored official test suite, as in section 6.3.
- **A large synthetic definition** is committed as the main test case. It is written in an invented English domain and must be comparable to a real production file: over 1,000 lines and 40 states; all eight state types; a Map with an inline processor of several states; a Parallel with two branches; retry rules shared through YAML anchors and aliases; placeholders in all four positions; JSONata in every scalar style, including blocks of twenty lines with lambdas, regular expressions, predicates, sorting and nested object construction; a variable with at least fifteen assignments; a Choice state that dispatches on a variable; comments throughout. It must show no errors and no warnings.
- **Small definitions** each plant one problem from section 7 and assert that exactly that problem is reported. A JSON definition and a JSONPath-mode definition are included.
- **(my call)** The build cannot prove that the synthetic definition deploys. It is cross-checked once with the open-source `asl-validator`. The commissioner can also run it through the AWS command line validation.
- **Recorded reference parser.** `src/test/testData/jsonata` holds the distilled suite (`suite.json`) and the answers of the jsonata-js 2.0.6 parser for 6,168 expressions (`reference.json`), as described in section 6.3. To re-record, see `tools/jsonata-reference/README.md` and `src/test/testData/jsonata/README.md`; the recording runs downloaded code, so only on a checkout that has been checked.
- **Recorded AWS answers.** `src/test/testData/aws` holds the definitions sent to the AWS validation and its answers, as described in section 7. They are re-recorded with the AWS command line when the rules of AWS may have changed; `src/test/testData/aws/README.md` describes how. Round 9 holds 185 definitions about JSONata: the syntax errors of the official suite, valid controls, the places where `$states.result` and `$states.errorOutput` exist, and expressions without an input. Round 10 holds 44 more: further places of a name without an input, further shapes of `$states` fields, and text around the delimiters. AWS parsed every expression as the reference parser does, including its quirks about `/`. Round 11 (2026-11-14, 45 definitions) asks about state machine variables: a `$reel` read from JSONPath and from JSONata in every relation to its assignment (none, before, the same state, later, inside or outside an inline or Distributed Map, a Parallel branch, a Catcher, a Choice rule), the names an `Assign` key may have, and an inner machine that assigns an outer name. Round 12 (2026-11-15, 11 definitions) follows up: an outer assignment only after a Map or Parallel state, in its own `Assign` or in one of its Catchers, an outer assignment before an inline Map in a JSONPath state, and the rejected names in JSONPath states. Round 13 (2026-11-15, 6 definitions) asks the finer points of that order: an outer assignment in a Catcher or a Choice rule whose `Next` leads to the Map, a state `Assign` whose only path to the Map is its Catcher, a Choice rule `Assign` on another rule's path, a loop back to the Map and two levels of nesting.
- **A private file check.** The commissioner has a real definition from a closed-source project. It must never be committed, and neither its names nor its comments may appear in the repository. A test that runs only when a local setting points at such files asserts that they show no errors. It is not part of any shared build.
- **In a running IDE.** Each stage ends with the plugin started in a sandbox IDE and exercised on the synthetic definition, since editor behaviour and the diagram cannot be fully covered by automated tests.
- **Compatibility.** The JetBrains plugin verifier runs against 2026.2 and 2026.3.

## 9. Left to the builder

- Package and module structure, and the format of the reference data.
- How checks are grouped into inspections, and their names.
- Icons, exact colours and the wording of messages and descriptions.
- Structure of the diagram's JavaScript, whether it needs a build step, and how it is tested.
- Caching, debounce intervals and other performance measures. A 1,500-line file with several hundred expressions must stay responsive.
- Code folding for multi-line expressions: **(my call)** none in stage 2.

## 10. Known uncertainties

These could not be verified and are handled as stated.

| Uncertainty | Handling |
|---|---|
| Exact naming rules for placeholders | Accept letters, digits, underscore, hyphen, dot, colon and comma between `${` and `}` |
| Whether AWS rejects JSONata syntax errors at deployment or only at run time | Settled on 2026-11-14: at deployment. The validation parses every expression as jsonata-js 2.0.6 does, including its oddities about where a `/` starts a regular expression |
| Whether several warnings in section 7 are in fact deployment errors | They stay warnings until confirmed |
| Whether `Assign` is allowed in nested JSONPath Choice rules | Settled on 2026-11-14 (round 11): AWS rejects it (`SCHEMA_VALIDATION_FAILED` at the nested rule); reported by the stage 1 error `MISPLACED_FIELD`, as a field that only a top-level Choice rule takes |
| A read that stands before the assignment in the flow, in the same state or in a later one (`r11-p-same-state`, `r11-p-later`) | AWS rejects it in a JSONPath string and warns in JSONata. The order of states is not modelled for reads, so the plugin finds neither; the test names both as gaps. An outer assignment is judged in the order of the flow, since there AWS decides by it |
| A whole-string placeholder nested in free-form data, such as `Arguments: {a: ${A}}` | It does not count as a mention of every name, since such a placeholder is almost always an ARN, a name or a literal. So never-read can still be reported for a variable that the deployment tool might read there, and a rename does not refuse because of it. A placeholder that is the whole value of a field that takes an expression or a path does count |
| Whether the IDE's default search finds `name` inside `$name` | The plugin provides its own search for `$name` within the file (`JsonataReferencesSearcher`) |
| Enter-key behaviour inside injected block scalars, and completion there | The platform indents the new line like the block scalar, which is less than the line the caret is on when an expression is indented deeper. `JsonataEnterHandler` therefore breaks the line itself inside a JSONata expression of a YAML file and gives the new line the leading white space of the current line (nothing else changes); a JSON string is left to the platform, since a raw line break would break it. A block scalar that keeps its final line break (`\|` and `>` without `-`) holds no JSONata string and is left alone. Completion is verified in a running IDE during stage 2 |
| Caret-driven usage highlighting of a variable inside an expression | The handler and the explicit action are tested with exact host ranges, but the daemon's caret pass produces nothing in the test fixture; to be confirmed in a running IDE |
| A scalar whose quote style is changed in place | The YAML plugin keeps a stale tree, so the plugin injects nothing in doubt; the expression is injected and checked again after a reload |
| A keystroke with several carets, one inside an expression and one outside | Known limitation: the host's quote is not escaped, and the platform puts both typed characters into the first expression |
| Toggling a comment in a quoted YAML scalar over several lines | The JSONata commenter does nothing there, since the injected text of such a scalar is one line. A line comment on a later line is the YAML plugin's: it puts `#` at the start of the line, which in a quoted scalar becomes part of the value |
| Names of local variables written with escapes, such as `$caf\u00e9` in JSON next to `$café` | Names are compared decoded from the host's escapes, so the two are the same variable. Navigation and highlighting follow them; a rename refuses where the raw text of an occurrence is not the name |
| Arity of calls | The parameter model cannot express variadic and context arguments, so no check of the number of arguments exists (AWS does not check it either) |
| The complete list of fields of the `$states.context` object and of predefined error names | Taken from the AWS documentation during stage 1 and 2 |
