# Aslan: Amazon States Language support for JetBrains IDEs

Design, 2026-11-13. Status: awaiting approval.

Revised 2026-11-15, before stage 4: the diagram is drawn by the plugin itself, not in an embedded browser, and it has two layouts. The reasons and the spike behind this are in `../research/2026-11-15-diagram-rendering.md`. Sections 4, 5, 6.5, 8, 9 and 10 changed.

Revised 2026-11-15, after stage 4: sections 6.5, 8, 9 and 10 describe the diagram and release 1.0 as built and as checked in a running IDE, and sections 6.2 and 6.4 correct two statements about documentation and find usages.

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

### Diagram layout

Researched and tried in a spike on 2026-11-15.

- Eclipse ELK is a Java library (the first design planned to use its JavaScript form, elkjs): version 0.12.1 on Maven Central, EPL-2.0. Its layered algorithm handles nested boxes, right-angle paths and labels on paths. It needs the Eclipse EMF libraries (2.12.0, EPL-1.0) and Guava 15 or newer.
- It lays out the large synthetic definition (62 states, 112 transitions) in 20 to 200 ms, runs inside IntelliJ IDEA 2026.2.3 under the plugin's own class loader, and the plugin verifier reports the plugin with these jars compatible with 2026.2.3 and 2026.3.
- The IDE ships Guava itself (33.5.0 in 2026.2.3, 33.7.1 in 2026.3), and a plugin that does not carry Guava gets the IDE's.
- With default options the large definition is laid out about 5,000 pixels wide and 3,900 high. With compact placement, shared lines for paths into the same state and shorter labels it is about 2,000 wide.
- Left out of the layout, catch paths no longer hold the states they lead to in place: a state reached only through a Catcher drifts to the top.
- The IDE's own Diagrams framework needs the paid features of the IDE.

## 5. Technical decisions

- **Language and build (decided):** Kotlin, Gradle, the official JetBrains plugin tooling, started from the official plugin template.
- **Supported IDEs (decided):** IntelliJ IDEA and other JetBrains IDEs that bundle YAML support, such as WebStorm.
- **Minimum IDE version (my call):** 2026.2, not 2025.3 as first proposed. The 2026.2 release changed the Java version and moved two modules the plugin depends on, and whether one build can span both sides is unverified. The commissioner's IDEs are all on 2026.3. Build with Java 25.
- **Dependencies:** the bundled YAML plugin, the JSON module and the structure view module, all required. The embedded browser is not used (revised 2026-11-15).
- **Identity (my call):** plugin name "Aslan", plugin ID and base package `io.github.saigyo.aslan` (from the repository at github.com/saigyo/aslan), licence Apache-2.0 as already in the repository.
- **File recognition (my call):** a file is an ASL file when its name ends in `.asl.yaml`, `.asl.yml` or `.asl.json`. Further name patterns can be added in settings. The files keep their normal YAML and JSON file types, so all bundled YAML and JSON features keep working.
- **JSON files (my call):** every feature in section 6 works in JSON files, because all of them read the shared document model. "Less polish" means two things only: where a feature needs work specific to the file format, such as inserting quotes and commas on completion or inserting the required fields of a new state, the JSON version may be plainer; and JSON gets fewer editing-behaviour tests than YAML.
- **One model of ASL, no JSON Schema (my call):** the plugin carries its own description of ASL (section 6.1) and drives completion, validation and documentation from it. A JSON Schema would give those three for free, but it cannot express inherited query language, cannot control severity per check, produces poor messages, would report placeholders in typed fields as type errors, and behaves differently across IDE versions. The no-false-errors rule needs control over every reported problem.
- **JSONata is its own language in the IDE (decided)**, with its own lexer and parser, injected into YAML and JSON strings. **(my call)** It does not reuse the existing JSONata plugin, because placeholders, the Step Functions functions and variable references need to be part of the language. It uses a distinct language ID and claims no file extension, so both plugins can be installed together. **(my call, made in the plan of stage 2)** The scanner is a port of the tokenizer and the parser of jsonata-js 2.0.6 (`src/parser.js`, MIT), not a JFlex lexer and a Grammar-Kit parser. In the reference, the parser tells the tokenizer whether a `/` starts a regular expression, which a generated lexer cannot reproduce, and some errors are found only after parsing, which a grammar cannot express. A direct port can be compared with the original line by line. The port turns the text into tokens, a node tree and at most one error; the IDE lexer and the IDE parser replay that result, so there is one source of truth for what is valid.
- **References (my call):** classic references and a read/write access detector, not the experimental Symbol API.
- **Diagram (decided, revised 2026-11-15):** a panel beside the editor, like the Markdown preview, drawn by the plugin itself with the IDE's own UI toolkit (Swing). The first design named an embedded browser with a JavaScript layout library. A spike showed that the native route works; it keeps the plugin in one language and under its automated tests, and it needs no browser runtime. **(my call)** Layout by Eclipse ELK 0.12.1 as a Java library (EPL-2.0), bundled unmodified with its notice, together with the Eclipse EMF libraries it needs (2.12.0, EPL-1.0). Guava, which ELK needs as well, is taken from the IDE; should a later IDE no longer ship it, the plugin bundles its own copy. The plugin grows from about 0.8 MB to about 6 MB.
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

**Documentation.** Quick documentation and hover on field names, state types and fixed-list values, from the reference data. It is given on the name in the text, not on the entries of the completion list, which show only their kind (seen while taking the screenshots of stage 4).

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

**Reads in JSONPath states (my call).** In a JSONPath state, a variable is read as `$name` at the start of a path or as an argument of an intrinsic function, where `$.` and `$$.` mean the input and the context object. The plugin recognises these reads by pattern in the fields that JSONPath evaluates (keys ending in `.$`, the `...Path` fields, and `Variable` in Choice rules; not the `Result` of a Pass state, which AWS does not evaluate, so a `$name` there is data) and treats them like reads in JSONata. This keeps find usages complete and rename safe in machines that mix both query languages. It does not parse JSONPath.

**Scope and identity (my call).** A variable is identified by its name and its owning scope, not by its name alone. The owning scope of an assignment or read is the outermost `States` object, among the one that contains it and the ones enclosing that, in which the name is assigned. The search does not cross into or out of a Distributed Map. Occurrences with the same name and the same owning scope are the same variable. So a name assigned at the top level is one variable everywhere below it, while two Parallel branches that each assign `count`, with no assignment further out, have two separate variables. A read with no owning scope is unresolved. Highlighting, find usages, rename and the variable checks all work on this identity.

The chain of scopes a read can see ends at the item processor of a Distributed Map: its states read only what their own machine assigns, and what they assign is invisible outside it. An inline Map and a Parallel branch see the machines around them. **A machine that does not say every name assigned in it is open**: a placeholder or a single JSONata string as `Assign`, a placeholder or an unapplied merge key in it or in `States`, or a Map whose `Mode` is a placeholder or unknown. A read that finds no assignment in an open scope is not reported as unassigned, and no assignment is reported as never read while any machine of the file is open, since the answer depends on the part the file does not show. An unknown `Mode` therefore makes the scope open instead of being taken as inline or distributed.

**Variable names (decided).** A name a variable can be renamed to, and a name the plugin calls plain, starts with a letter, continues with letters, digits and `_`, has at most 80 code points, and is not `states`. This rule may be stricter than AWS, never looser. The checks report as errors only the classes of names AWS rejected (section 7).

**When rename refuses (decided).** Rename changes every assignment and read of the variable and stops with a conflict, naming the place, when it cannot keep the meaning of every occurrence: the new name is not a valid name; the new name is assigned, read or declared locally anywhere the variable's region can see it, or is mentioned in a string the model does not know as a read (a built-in function, say); the old name is mentioned in such a string, or in a string with a syntax error; an occurrence is shared with another variable, as through a YAML alias used by states of different scopes; a merged key has the old or the new name; the raw text of an occurrence is not the name, as with an escape; or a machine of the region is open. The refusal is conservative: it refuses some safe renames and never changes the meaning.

**Navigation (decided)**

- Placing the caret on a variable, at an assignment or a read, highlights every assignment and read of that variable. Assignments and reads have different colours, as in Java.
- The IDE's actions for stepping to the next and previous highlighted usage work on these highlights. The plugin's description names the shortcuts.

**(my call)** In addition:

- Go to declaration from a read jumps to the assignment, or offers a list when there are several. Each entry names the state and the place, for example "ReserveStock, second Catcher".
- Find usages lists the reads as usages and shows the assignments as the variable's declarations, the places go to declaration leads to (corrected after stage 4; this text first said it lists both alike).
- Rename changes every assignment and read, and rejects a name that is not a valid identifier.
- Completion after `$` in JSONata offers the variables visible at that position.

### 6.5 Stage 4: diagram

Statements marked **(my call, made in the plan of stage 4)** are decisions of that plan that stood. Statements marked **(my call, made while building stage 4)** were decided during the build, where the plan left a gap or turned out wrong; the reason is given with each.

**Mockup.** The file `2026-11-13-aslan-design-mockup.html` next to this document is part of the design. Open it in a browser. Figure 1 shows the structure view, editor and diagram with a state selected; the commissioner approved this arrangement. Figure 2 shows the diagram pane with a path selected, catch paths muted and the details panel collapsed. The mockup fixes arrangement and behaviour; exact colours, icons and spacing are left to the builder. Where the mockup and this text disagree, this text wins. The mockup is a web page, but the diagram is a native panel (section 5), the mockup does not show the layout switch added on 2026-11-15, and its switches are rows of options where the plugin has drop-downs (below).

**Appearance (decided, from the mockup).** The diagram flows from top to bottom. A state is a rounded rectangle with a coloured bar on its left edge for the type, the state name in bold, and a smaller second line with the type and, for a Task, the resource label. A selected state has a highlighted fill and border. Transitions are solid lines with arrowheads, drawn with right-angle bends, and their labels sit on the line. Catch paths are dashed and red. A legend under the diagram names the type colours and the catch line. The details panel is a two-column table of labels and values.

**Arrangement (decided).** ASL files open in an editor with three modes: editor only, split, diagram only. In split mode the diagram is on the right. Below the diagram is a details panel for the selected state, which can be collapsed so the diagram uses the whole pane. A toolbar above the diagram holds the catch switch, the layout switch and the zoom controls.

**(my call, made in the plan of stage 4)** Every ASL file opens in the split editor, first in split mode. The three modes are the platform's own (`TextEditorWithPreview`): its buttons sit at the top right of the editor, and it remembers the mode chosen last. The toolbar, the legend and the details panel are IDE components inside the diagram pane: an action toolbar above the diagram, and a legend strip and the collapsible details panel below it.

**(my call, made while building stage 4)** The two switches are drop-downs that show their caption and current value, such as "Catch: Muted" and "Layout: Compact", followed by zoom out, zoom in and Fit. So the whole toolbar fits the right pane of the default split, about 390 pixels wide; switches as rows of options, as in the mockup, needed about 480. Until the first diagram arrives, the layout drop-down reads "Layout" and is disabled.

**Content**

- A node per state, showing its name, its type by colour and label, and for a Task a short label derived from the resource ARN, such as "S3 listObjectsV2".
- A line per transition: `Next`, each Choice rule (labelled with its condition, shortened, full text on hover), `Default`, and each Catcher (dashed, labelled "Catch"). The start state and terminal states are marked.
- **(my call)** A Map state is a box containing its inner states; a Parallel state is a box containing one box per branch. Boxes can be collapsed.
- **(my call, made in the plan of stage 4)** A transition whose target cannot be resolved is not drawn; the details panel still names it. A state of unknown or missing type is drawn in the grey of Pass. **(my call, made while building stage 4)** So is a state that is not a mapping. Neither marks the diagram out of date: the checks report them, and the diagram follows the file.

**Labels and places (my call, made in the plan of stage 4, with the details settled while building it)**

- The second line of a Task's box names the service and action of an integration ("S3 listObjectsV2", "Step Functions startExecution"), "Lambda" and the function's name, or "Activity" and the activity's name; any other resource is shown as written. A name or second line that does not fit its box is cut with an ellipsis, and hovering the box shows it whole.
- A JSONata Choice rule is labelled with its expression, without the delimiters and with runs of white space made one space. A JSONPath rule with a `Variable` and one operator is labelled in short form, such as `status = "split"` or `seal is present`, without the leading `$.`, and hovering shows the variable's whole path. Any other rule (`And`, `Or`, `Not`, a placeholder, a rule being typed) is labelled "Rule n", and hovering shows the rule as the file has it. A `Default` path is labelled "Default"; a catch path is labelled "Catch", and hovering it names its errors.
- Labels are cut at 30 characters in the loose layout and at 20 in the compact one; hovering a cut label shows it whole.
- **(my call, made while building stage 4)** A hover text is wrapped at about 80 characters, at a space where it can be and inside a longer word where not, and cut at 600 characters with an ellipsis. A JSONata condition of several hundred characters had been one line of the tool tip, wider than the screen.
- Clicking a state puts the caret at the start of the state's name. Clicking a path puts it where the path is created: the `Next` or `Default` key, the Choice rule or the Catcher, or the `Choices` or `Catch` key when the list is shared through a YAML alias; failing all of these, the state's name. The caret selects the innermost state whose entry holds it.

**Layout (decided, 2026-11-15).** The diagram has two layouts and a switch between them.

- *Loose* gives every path its own line and room for longer labels. It suits small machines.
- *Compact* places the states closer and cuts labels shorter. It is needed for large machines. **(revised on 2026-11-17 by the commissioner)** Every path has its own line here too; until then the paths into one state shared a line, which made the layout narrow but let many paths run over each other in a large machine. With network-simplex placement of the states it keeps a large machine to a width that fits a screen: the large synthetic definition is about 4,900 pixels wide in the loose layout and about 2,500 in the compact one (`2026-11-17-compact-own-lines-design.md`).
- **(my call)** A file opens in the loose layout when its machine has at most 25 states, nested ones included, and in the compact layout otherwise. Once the switch is used, the choice is remembered for that file. **(my call, made while building stage 4)** It is kept in the project's properties under the file's URL, not in the editor state the IDE keeps for a file, as the plan of stage 4 had it: overriding the editor provider's reading of that state is reported as deprecated for 2026.3, and its replacement does not exist in 2026.2. So the choice is kept as long as the project's workspace file is, not only while the file is among the recent files, and it is lost when the file is renamed or moved.
- **(my call; limit revised on 2026-11-17 by the commissioner)** The compact layout of the large synthetic definition is at most 2,600 pixels wide at full size (2,200 while paths shared lines); it is 2,539, and with the pane's own fonts on macOS 2,423. The layout options behind the two layouts are left to the builder; they ended as follows.

**Layout options (my call, made while building stage 4).** The plan of stage 4 started both layouts from the options of the spike; the tests and the painted pictures changed them to these:

- In both layouts, on the whole diagram, on every Map or Parallel box and on every branch: ELK's layered algorithm, direction down, right-angle paths, 54 pixels between layers, and depth-first cycle breaking. ELK's default, greedy cycle breaking turned some forward `Next` paths upwards; depth first turns only paths that close a loop, so the flow runs down. The price is height: the large synthetic definition is about 5,300 pixels high instead of the spike's 3,850, which was accepted because the width limit still holds.
- Padding of 14 pixels at the sides and 18 at the bottom; at the top, 14 for the whole diagram, 46 for a Map or Parallel box and 38 for a branch. This keeps the start mark of the first inner state and the end marks clear of a box's texts and border.
- A Parallel box keeps its branches side by side in file order: its parts are not laid out as separate components, and the order of nodes and edges in the file is kept.
- Every label lies on its path.
- In both layouts (until 2026-11-17 in the loose one only, while the compact one had ports): ELK's feedback edges. **(my call, made while building the diagram improvements of 2026-11-17)** A path that closes a loop leaves its state at the bottom, runs around the side and enters its target at the top. Without this option ELK attached such a path to the top of its state and the bottom of its target, so a path seemed to leave where paths come in (4 of the 112 paths of the large synthetic definition). The large synthetic definition is about 4% wider for it.
- In both layouts (until 2026-11-17 in the loose one only): a second pass against paths of one state that cross. **(my call, made while building the diagram improvements of 2026-11-17, at the commissioner's request)** With paths that close a loop leaving at the bottom, ELK let two paths of one state cross below it (4 pairs in the large synthetic definition), where swapping their exits would have avoided it. Where the first pass has such pairs, the path of each pair that leaves further left gets a fixed exit on the right side of its state, and the machine is laid out again; that pass is a new layout and can cross elsewhere, so the one with fewer such pairs is kept. With the greedy switch below, the large synthetic definition has 3 such pairs after the first pass and 1 after the second; 3 paths leave at a side, and all crossings drop from 80 to 43. More passes were tried and did not settle: each moved the crossings elsewhere. No ELK option removed these crossings while keeping paths out of the top. The commissioner accepted paths that leave at a side; only the top is reserved for paths that come in. **(my call, made while building the compact layout of 2026-11-17)** The second pass is kept only when it is also no wider than the first: in the compact layout of the large synthetic definition it would halve the 4 pairs but add 383 pixels of width, and the compact layout is there for its width, so its first pass stays.
- In both layouts: ELK's greedy switch of neighbouring nodes on every graph, one-sided; by default it runs only on graphs of up to 40 nodes. **(my call, made while building the diagram improvements of 2026-11-17)** In the compact layout it removes the 6 pairs of crossing paths of one state that the large synthetic definition had, and the layout is narrower. In the loose layout it keeps the label of a path that leaves at a side from being placed beyond another path, which the path then crossed twice on a long detour (up to 974 pixels past its two states).
- In both layouts: 17 pixels between a node and a path that runs across between two layers (ELK's default is 10). **(my call, made while building the diagram improvements of 2026-11-17)** The painter draws the end mark in the 14 pixels below an end state and the start mark in the 12 above the start state; ELK knows nothing of them, and a path that ran along the bottom of an end state passed through its mark, as if connected to it. ELK ignores node margins when it routes, so the spacing is the means. The large synthetic definition is about 70 pixels higher for it.
- In the compact layout only: network-simplex placement of the states and the file's order of nodes and edges kept. **(revised on 2026-11-17)** The ports that let the paths into one state share a line, and the paths out of it leave at one point, are gone; every path has its own line, as in the loose layout.
- A state's box is 190 by 40 pixels; a collapsed Map or Parallel box has the same size.

Measured at 6 pixels per character with nothing collapsed, the large synthetic definition is 4,923 by 5,472 pixels in the loose layout and 2,539 by 5,452 in the compact one.

**Interaction**

- **(decided)** Selection is shared. Clicking a node moves the caret to that state. Moving the caret in the editor or selecting in the structure view selects the node and brings it into view.
- **(my call, made in the plan of stage 4)** There is one selection, held by the controller: a state, a path, or nothing. Moving the caret selects the state the caret is in. A click in the diagram selects that state or path and moves the caret, without the caret move changing the selection back; the keyboard focus stays in the diagram. A click on the diagram's background selects nothing and leaves the caret where it is. A selected path stays selected until the next caret move or edit; switching the layout or collapsing a box keeps it.
- **(decided)** Catch paths have a three-way switch: normal colour, muted, hidden. **(decided, 2026-11-15)** The switch never changes the layout: a hidden catch path keeps its place and is only not drawn, so no state moves when the switch is used.
- **(decided)** Any path can be selected. This highlights the path and the two states it connects. The selected path is drawn over its whole length on top of the others, so it can be followed where paths share a line.
- **(my call)** Selecting a path also moves the caret to the line that creates it.
- **(my call)** Zoom, pan and fit to window. The diagram follows the IDE's light or dark theme. **(my call, 2026-11-15)** A diagram opens at full size with the selected state in view, not fitted to the pane: a large machine fitted to a pane cannot be read. **(my call, made while building stage 4)** The selected state is the one at the caret. When the caret is in no state, as when a file opens with the caret at its start, the diagram opens at its top, centred when it is narrower than the pane and at its left edge when it is wider, moved just enough to show the start state.
- **(my call, made while building the diagram improvements of 2026-11-17)** Zoom in and zoom out also run on the keys of the actions Expand All and Collapse All in the active keymap, Cmd+Plus and Cmd+Minus on macOS and Ctrl+Plus and Ctrl+Minus in the default keymap, as in the IDE's image viewer. They are registered on the diagram and work while it has the keyboard focus, which a click into the diagram gives it; in the text editor the same keys keep folding code.
- **(my call, made while building the diagram improvements of 2026-11-17)** A pinch on the trackpad over the diagram zooms it around the pointer while the fingers move, within the limits of the zoom buttons (0.2 to 3). The IDE delivers a pinch to the nearest enclosing component that implements the platform's `ZoomableViewport`; the diagram lies in such a container, which applies the sum of the magnification to the view the diagram had when the pinch began, so the zoom does not drift.
- **(my call, made while building stage 4)** After a box is collapsed or expanded, that box is brought into view, since the new layout moves everything around it.
- **(my call, made while building stage 4)** A later diagram keeps zoom and position, and a layout switch with a state selected brings it into view; a view that would show no state of the new diagram, as after a layout switch with nothing selected or an edit that makes the diagram much smaller, is placed like a first view.
- **(my call)** The diagram redraws shortly after typing stops. While the file cannot be read as a state machine, the last good diagram stays, marked as out of date. **(my call, made in the plan of stage 4)** The model is built in a read action and laid out on a thread of the diagram's own, never on the UI thread and never while the read lock is held; a result older than the one last shown is dropped. The file cannot be read when it is a JSON file with a syntax error, when it has no top level that is a mapping, or when `States` is missing or not a mapping. Problems the checks report, such as a missing target or an unknown type, do not stop the diagram. **(my call, made while building stage 4)** A broken line in a YAML file does not mark the diagram, unlike the plan's rule for any syntax error: the platform's YAML parser puts no syntax errors into the tree, so the diagram shows what its tolerant reading of the file gives (section 10). A file that was never readable shows "No state machine to show yet." without the mark. **(my call, made while building stage 4)** A read or a layout that fails, which is a fault of the plugin and not of the file, also marks the diagram out of date; the failure was only logged before, so the old diagram stayed unmarked. An `Error` counts as a failure too, such as the missing class a later IDE without Guava would give. The first failure in an editor is logged as an error, later ones as warnings, so the IDE does not report an error at every pause in typing; no message holds text of the file.
- **(my call, made while building stage 4)** A layout may take ten seconds. One that takes longer is told to stop and given up, the diagram is marked out of date, and later layouts run on a fresh thread. At most two given-up layouts may still be running for one editor: while two are, no new layout starts, and once one of them has ended the diagram is redrawn without a further edit. ELK asks whether to stop only between its phases, hence the bound. A layout is told to stop by a flag of its own that ELK's progress monitor reads, set when the layout is given up and when the editor closes; its thread is not interrupted, since an interrupted thread can fail to load a class from the plugin's jars. The limit was added because one test run stalled for fifteen minutes for a reason never found (section 10), and a stalled layout would otherwise freeze the file's diagram.
- **(my call)** The catch switch, the panel's collapsed state and the editor mode are remembered, and for each file the layout chosen with the switch. **(my call, made in the plan of stage 4)** The editor mode is remembered by the platform, the catch switch and the collapsed state of the details panel for the whole IDE, and the chosen layout for each file as described under Layout. Which boxes are collapsed is kept only while the file is open.
- **(my call, made in the plan of stage 4)** Texts from the file are never read as HTML. Swing renders a text that starts with `<html>` as markup, so names, labels and hover texts are always shown literally.

**Details panel (decided: read-only).** For the selected state: type, resource, variables read, variables assigned, retry rules, catchers with their targets, and the next state. State and variable names are links into the source.

**(my call, made while building stage 4)** The header names the selected state. The rows, each left out when it has nothing to show, are:

- Type, and Resource as written in the file;
- Reads and Assigns: each variable once, as `$name`;
- Retry: each retrier by the name of its YAML alias, else by its error names, else as "Retrier n";
- Catch: each Catcher as its errors, an arrow and its target;
- Next: the target, every target of a Choice state's rules and `Default`, or "End" for a state that ends the machine.

Variables, retriers and target states are links into the source. The type, the resource and "End" are plain text: they belong to the selected state, where the caret already is. The plan of stage 4 had made every item with a place in the file a link. A target that names no state is plain text too.

**(my call, made while building the diagram improvements of 2026-11-17)** For a selected path, which showed nothing before, the header names its two states as `from → to`, and the rows, each left out when it has nothing to show, are:

- From and To: the two states, as links into the source; following one moves the caret there and so selects that state;
- Condition: for a Choice rule its whole condition, not cut as on the path (the hover text when there is one, else the label), wrapped to the panel's width; "Default" for a `Default` path;
- Errors: for a catch path the names in its `ErrorEquals`. The commissioner's request named no row for catch paths; this one was added by the builder.

The condition and the errors are shown in text areas, which read no markup and wrap at spaces. The table of the panel takes the panel's width, so its rows wrap instead of scrolling sideways, as long as its widest item that cannot wrap, such as a long resource, fits; else the table is as wide as that item and the panel scrolls sideways, as before. A Choice rule's condition is carried uncut in the model for this; the hover text on the path stays cut at 200 characters.

**Building blocks (my call, made in the plan of stage 4).** Model, layout, scene and painting are four separate steps. A builder turns the document model into plain data; the layouter gives every state and path a place; the scene turns the layout and the selection into the items to paint and answers what lies at a point; the painter draws the scene. What is painted and what a click hits are decided in the scene, so both are tested without a window.

**No browser.** The diagram needs no embedded browser and is available wherever the plugin runs (revised 2026-11-15).

**Release 1.0 (my call, made in the plan of stage 4).** Version 1.0.0, with the commissioner's logo and file icon, and change notes. The file icon comes from an icon provider, not from a file type, since ASL files keep their YAML and JSON file types (section 5). The Marketplace description carries three screenshots as links to images in the repository on GitHub; they work once the repository is public and this branch is on `main`, and the commissioner makes the repository public before publication. Six full-window screenshots are prepared in addition for the Media section of the listing, whose text is in `docs/marketplace/listing.md`. The screenshots come from the sandbox IDE on a demo project that holds copies of the synthetic definitions and nothing else, in the default theme, with the Project tool window hidden (section 8). Signing and uploading stay outside this project. **(my call, made while building stage 4)** The build copies the repository's `LICENSE` and `NOTICE` into `META-INF/` of the plugin's jar, as the Apache License asks of a distribution; the source tree holds them once, at its root.

## 7. Checking policy

The rule that follows from the anti-goals:

- A problem is an **error** only if AWS rejects the definition at deployment, or if the text is not valid JSONata and so cannot be evaluated at all.
- Anything that deploys but is probably a mistake is a **warning** at most.
- Every check has its own entry in the IDE's inspection settings, so it can be switched off or changed in severity without affecting the others.

**Evidence.** The checks were compared with the definition validation of Step Functions (`aws stepfunctions validate-state-machine-definition`) on 2026-11-14, with about 1,400 small definitions for stage 1, 229 more for the JSONata checks of stage 2 and 62 more for the variable checks of stage 3 (rounds 11 to 13, section 8). Most hold one condition; some hold a set of candidate field names in one position. Every field of every structure was sent with `null`, with a value of the wrong kind and with a JSONata string. AWS documents that an error from this validation prevents creating or updating the state machine. The recorded answers are committed as test data, and tests hold the checks to them: no error on a definition AWS accepts, and an error on every definition AWS rejects, apart from a short list of gaps that the test names with their reasons. The rules below marked "validation" rest on these answers.

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
| A read in JSONata inside a Distributed Map of a name assigned only around it, where that assignment comes before the flow enters the Map, by the same paths as for `OUTER_VARIABLE_ASSIGNED` below (`DISTRIBUTED_MAP_OUTER_READ`) | validation, round 11. A name assigned around the Map only after it, in its own `Assign` or in its Catchers (with or without a loop back to the Map, unlike for `OUTER_VARIABLE_ASSIGNED` since round 14), or where the outer flow is not known, gets the warning `VARIABLE_NOT_ASSIGNED` instead: no probe covers those relations |
| An `Assign` key, in a state of any query language, that starts with a digit or `_`, holds `.`, `-` or a space, has more than 80 characters, or is `states` (`INVALID_VARIABLE_NAME`) | validation: round 11 for JSONata states (digit, `_`, `-`, `.`, space, 81 characters, `states`), round 12 for JSONPath states (digit, `_`, `-`, `states`, where AWS answers `RESERVED_VARIABLE_NAME` for the last). AWS gives the same answer in both, so dot, space and length are taken to hold in JSONPath states too. A name of 80 characters and names with the Unicode letters tried are accepted |
| An assignment in a nested state machine (Map of either mode, Parallel branch) of a name that a machine around it assigns before the flow enters the Map or Parallel state (`OUTER_VARIABLE_ASSIGNED`) | validation: AWS reports `DUPLICATE_VARIABLE_NAME` at the inner key for the three shadows of round 11 and the one of round 12. The order of the flow decides: accepted are outer assignments only after the state that holds the nested machine, in its own `Assign` and in its Catchers (round 12). Round 13 confirms the paths: a Catcher's `Assign` or a Choice rule's `Assign` whose `Next` reaches the state, a loop back to it and two levels of nesting are rejected, a state's `Assign` whose only path to the state is its Catcher and a Choice rule's `Assign` on another rule's path are accepted. Round 14 settles the state itself: its own `Assign` and a Catcher's `Assign` count as soon as the flow leads from them back to the state, by the same rule (its own `Next` or `Default`, the Catcher's `Next`), the state itself as a target included; all four probes, for a Map and a Parallel state, are rejected. Without a path back they still do not count. Two branches that assign the same name are accepted. A state's own `Assign` is followed along its `Next` and `Default` only, not its Choice rules: AWS documents that a Choice state's own `Assign` applies only when no rule matches, and no probe covers it. Where the outer flow is not known, nothing is reported |
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
- **Recorded AWS answers.** `src/test/testData/aws` holds the definitions sent to the AWS validation and its answers, as described in section 7. They are re-recorded with the AWS command line when the rules of AWS may have changed; `src/test/testData/aws/README.md` describes how. Round 9 holds 185 definitions about JSONata: the syntax errors of the official suite, valid controls, the places where `$states.result` and `$states.errorOutput` exist, and expressions without an input. Round 10 holds 44 more: further places of a name without an input, further shapes of `$states` fields, and text around the delimiters. AWS parsed every expression as the reference parser does, including its quirks about `/`. Round 11 (2026-11-14, 45 definitions) asks about state machine variables: a `$reel` read from JSONPath and from JSONata in every relation to its assignment (none, before, the same state, later, inside or outside an inline or Distributed Map, a Parallel branch, a Catcher, a Choice rule), the names an `Assign` key may have, and an inner machine that assigns an outer name. Round 12 (2026-11-15, 11 definitions) follows up: an outer assignment only after a Map or Parallel state, in its own `Assign` or in one of its Catchers, an outer assignment before an inline Map in a JSONPath state, and the rejected names in JSONPath states. Round 13 (2026-11-15, 6 definitions) asks the finer points of that order: an outer assignment in a Catcher or a Choice rule whose `Next` leads to the Map, a state `Assign` whose only path to the Map is its Catcher, a Choice rule `Assign` on another rule's path, a loop back to the Map and two levels of nesting. Round 14 (2026-11-15, 4 definitions) asks whether an assignment in the Map or Parallel state itself counts when the flow leads back to the state: its own `Assign` with a Choice back to it or itself as its `Next`, a Catcher's `Assign` with a Pass back to the Map, and a Parallel state's own `Assign` with a Choice back to it. AWS rejects all four.
- **A private file check.** The commissioner has a real definition from a closed-source project. It must never be committed, and neither its names nor its comments may appear in the repository. A test that runs only when a local setting points at such files asserts that they show no errors. It is not part of any shared build.
- **The diagram.** The layout runs in the automated tests on the synthetic definitions: every state and every path has a place, paths run in right angles, the states of one machine do not overlap, the catch switch moves nothing, and the compact layout keeps its width limit. What the panel shows for a file and what a click at a point selects are tested without a window; the look is checked in a running IDE. **(made while building stage 4)** The tests also hold that the marks of a state stay clear of its box's texts and border, that a path leading back does not end where paths leave, that the width limit holds with the pane's own fonts, and that the large definition lays out in under two seconds, taken as the fastest of three runs after a warm-up. The controller's tests cover the delay, dropped results, the out-of-date mark, the shared selection and the time limit, a layout that fails with an exception or an `Error`, and a layout that ends when its flag is set, without any thread being interrupted. The scene's tests hold that a long hover text is wrapped and cut. `ListingTest` holds the README, the plugin description and the pictures to their rules: every local link and image exists, the images of the description are files of this repository, and the pictures have the sizes the listing needs; it also finds the repository's `LICENSE` and `NOTICE` on the class path under `META-INF/`, where the build puts them for the jar.
- **The demo project.** `tools/demo/make-demo.sh` creates `build/demo/film-archive/`, a project with copies of the synthetic definitions and nothing else: the large definition, two smaller ones (one in JSON) and a copy of the large one with three planted problems. The sandbox IDE is opened on this project only, never on the repository, so the screenshots show nothing but the invented domain. `tools/demo/README.md` gives the conditions of the screenshots (default light theme, a window of 1280 by 800 points, the Project tool window closed, no notification balloon), the capture scripts in `tools/demo/capture/`, and what each of the ten pictures in `docs/images/` shows.
- **In a running IDE.** Each stage ends with the plugin started in a sandbox IDE and exercised on the synthetic definition, since editor behaviour and the diagram cannot be fully covered by automated tests. For stage 4 the check ran on the demo project in three parts: the small definition in the light theme (layout, marks, labels, hover, clicks on states, paths and the background, the structure view, the details links, the catch switch, collapsing, zoom, pan and the file icons); the large definition and the JSON one (the first view, the layout switch, the Parallel box, a selected path among shared lines, the focus after a click, the three modes); and, after a restart, what is remembered, typing, a broken YAML line, a broken JSON file and the dark theme. Its findings changed the toolbar and the view after a box toggle (section 6.5), and what it could not settle is in section 10.
- **Compatibility.** The JetBrains plugin verifier runs against 2026.2 and 2026.3.
- **Time limits (made while building stage 4).** The Gradle test task stops after 30 minutes and each of the two GitHub jobs after 45, so a test that stalls cannot hold the build for GitHub's six hours.

## 9. Left to the builder

- Package and module structure, and the format of the reference data.
- How checks are grouped into inspections, and their names.
- Icons, exact colours and the wording of messages and descriptions.
- Structure of the diagram panel and its painting, and the layout options of the two layouts. **(my call)** The options of stage 4 are listed in section 6.5.
- Caching, debounce intervals and other performance measures. A 1,500-line file with several hundred expressions must stay responsive.
- Code folding for multi-line expressions: **(my call)** none in stage 2.

**(my call)** What stage 4 chose:

- The diagram redraws 300 ms after the last change, and a caret move selects its state 100 ms after the caret stops.
- The layout runs on one thread of the diagram's own, a plain Java thread pool with one daemon thread that ends after 30 seconds without work, not on the platform's bounded pool, which 2026.3 marks as obsolete. The time limit is ten seconds; a given-up layout's warning in the log names the file and holds no text of it.
- A click is resolved in this order: the toggle of a box, a state, a path with the selected one first, then the innermost open box.
- Zoom runs from 0.2 to 3 times full size in steps of 1.25. Fit may go below 0.2 for a large machine, which is then too small to read.
- The type colours are the builder's; a state of unknown or missing type takes the colour of Pass, which is lighter in the dark theme.
- The details panel's label column is as wide as its widest label, so the values move a little between states.

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
| A placeholder outside a string literal inside an expression, such as `{% ${Expr} %}` | The expression is not judged by the other checks, but the placeholder does not count as a mention of every name either: counting it would silence never-read and block a rename in most files that use placeholders. A variable the replaced text might read can therefore still be reported as never read |
| Whether the IDE's default search finds `name` inside `$name` | The plugin provides its own search for `$name` within the file (`JsonataReferencesSearcher`) |
| Enter-key behaviour inside injected block scalars, and completion there | The platform indents the new line like the block scalar, which is less than the line the caret is on when an expression is indented deeper. `JsonataEnterHandler` therefore breaks the line itself inside a JSONata expression of a YAML file and gives the new line the leading white space of the current line (nothing else changes); a JSON string is left to the platform, since a raw line break would break it. A block scalar that keeps its final line break (`\|` and `>` without `-`) holds no JSONata string and is left alone. Completion is verified in a running IDE during stage 2 |
| Caret-driven usage highlighting of a variable inside an expression | The handler and the explicit action are tested with exact host ranges, but the daemon's caret pass produces nothing in the test fixture; to be confirmed in a running IDE |
| Caret-driven usage highlighting that shows nothing, or another variable's usages, until the file is edited or reopened | Settled on 2026-11-16: a fault of the platform in 2026.2 and 2026.3. Its identifier highlighting (`IdentifierHighlightingComputer`, `IdentifierHighlightingManagerImpl`) stores each result under the host element at the caret, which inside an expression is a whole line of a YAML block or a whole string, and reuses it for any caret in that range until the document changes or the editor closes. A caret that rested on a spot without a variable leaves every variable on that line unhighlighted, and two variables on one line share one result. A plugin's handlers cannot change that range. Reproduced without the plugin, with JavaScript injected into a YAML block; reported to JetBrains as [IJPL-261920](https://youtrack.jetbrains.com/issue/IJPL-261920). Highlight Usages in File is not affected and is named in the README's limits as the workaround |
| A scalar whose quote style is changed in place | The YAML plugin keeps a stale tree, so the plugin injects nothing in doubt; the expression is injected and checked again after a reload |
| A keystroke with several carets, one inside an expression and one outside | Known limitation: the host's quote is not escaped, and the platform puts both typed characters into the first expression |
| Toggling a comment in a quoted YAML scalar over several lines | The JSONata commenter does nothing there, since the injected text of such a scalar is one line. A line comment on a later line is the YAML plugin's: it puts `#` at the start of the line, which in a quoted scalar becomes part of the value |
| Names of local variables written with escapes, such as `$caf\u00e9` in JSON next to `$café` | Names are compared decoded from the host's escapes, so the two are the same variable. Navigation and highlighting follow them; a rename refuses where the raw text of an occurrence is not the name |
| Arity of calls | The parameter model cannot express variadic and context arguments, so no check of the number of arguments exists (AWS does not check it either) |
| The complete list of fields of the `$states.context` object and of predefined error names | Taken from the AWS documentation during stage 1 and 2 |
| Whether a later IDE still ships Guava, which the layout library needs | Both supported versions ship it, and the library accepts any version from 15 on. If one stops, the plugin bundles its own copy; the plugin verifier, which runs against every supported version, would show the missing classes. Release 1.0.0 carries the seven ELK and EMF jars and no Guava, and the verifier reports it compatible with 2026.2.3 and 2026.3 |
| The Eclipse EMF jars refer to Eclipse runtime classes that the plugin does not carry | The layout never loads them: it ran in the IDE throughout the checks of stage 4, and the plugin verifier reports nothing for either version. Checked again whenever the library version changes |
| Following one path where paths share a line in the compact layout | Settled in the running IDE in stage 4: a selected catch path among shared lines of the large definition is drawn in the selection colour over its whole length, and both its states are outlined. Where many lines run side by side, as near `ClassifyFault` in the large definition, the picture stays crowded (next row) |
| The interactive part of the native panel (clicks, zoom, pan, hover) | Built and checked in stage 4 in the running IDE (section 8): clicks on states, paths and the background, hover, zoom, pan by dragging, Fit, the focus after a click and the three modes behave as section 6.5 says. What stayed open is in the rows below |
| Long paths in the compact layout of a large machine | In the compact layout of the large definition, long paths that lead back and long catch paths run as large loops around the whole diagram, and near some states many catch and plain lines run side by side. Left as is for 1.0; other ELK options may improve it later |
| The structure view does not follow a click in the diagram | With the IDE's default settings, the Structure tool window keeps its old row when a box is clicked, though the caret moves. The README names the tool window's option Always Select Opened Element, found in the platform's resources; its effect has not been seen in the IDE |
| A broken line in a YAML file | The diagram is redrawn without the out-of-date mark and shows what the tolerant reading gives: with the colon of a state's key deleted, that state's box was gone and two grey boxes "Type" and "End" appeared, while the editor showed the errors. Retyping the colon restored the diagram. Marking it would need a detection of YAML damage of the plugin's own |
| Catch switch and details panel in several open panes | The values are remembered for the whole IDE, but each open pane keeps its own until the file is reopened: a change in one file's pane does not reach the pane of another open file. Known, left for a later version |
| A box wider than the pane | After such a box is expanded, it is brought into view at its left edge, and its own toggle at its top right lies outside the view. The user has to pan to collapse it again |
| One test run that stalled | Once, in a run of the layout tests, the first test took 928 seconds; rerun alone it took 2.8 seconds, and ten later runs of the layout and scene tests in a row took 15 to 20 seconds each. The cause was never found. Since then the timing test measures warm runs, the Gradle test task and the GitHub jobs have time limits (section 8), and the layout itself has a limit of ten seconds (section 6.5) |
| The width test on Linux | The GitHub build runs on Linux, where the pane's fonts differ from macOS. The test of the 2,200-pixel limit with the pane's own fonts (1,525 on macOS) has not run there yet; the first GitHub build will show it |
| The IDE's zoom and UI font size | The diagram's fonts and its first view follow neither the IDE's zoom nor its UI font size. Known, left for a later version |
| A new selection after an edit | After an edit, a box that becomes selected is not brought into view until the caret moves. Known, left for a later version |
| Layouts of a hidden diagram | Layouts also run while the diagram is hidden in the editor-only mode. Known, left for a later version |
| A file renamed to a name that is not an ASL name | It keeps its split editor, with the diagram marked out of date, until it is reopened. Known, left for a later version |
| IntelliJ IDEA 2026.3 | The plugin verifier reports the plugin compatible. The automated tests and the observed sessions ran in 2026.2.3; in 2026.3 the commissioner installed the distribution file and found the diagram working, with a click on a state, on a path and on a link in the details |
| What was not observed in a running IDE | Light edit mode, remote development, the Windows and Linux desktops, and keyboard and screen reader access to the diagram. None of them was tried in stage 4 |
| The screenshots | They come from the sandbox IDE, which runs without a licence, so the title bar of every full-window picture shows "Unlock Ultimate". The Problems tool window in the validation picture shows the demo project's location in its first row, which cannot be hidden |
| Signing and uploading | Left to the commissioner, as are the Marketplace tags, which `docs/marketplace/listing.md` only suggests |
