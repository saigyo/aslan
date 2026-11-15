# Aslan

Editor support for Amazon States Language (AWS Step Functions) definitions in JetBrains IDEs.

[![Build](https://github.com/saigyo/aslan/actions/workflows/build.yml/badge.svg)](https://github.com/saigyo/aslan/actions/workflows/build.yml)

## Features

- **Completion** of field names and fixed values (state types, comparison operators, error names and similar) for the structure at the caret, and of state names in transition fields such as `Next` and `Default`. Choosing a state type in a new YAML state also inserts the fields that type needs.
- **Validation** with 40 checks for missing, unknown or misplaced fields, wrong value kinds and ranges, Task resources and roles that AWS rejects, transitions to missing states, states that nothing leads to, duplicate state names, malformed paths and timestamps, and similar problems. A problem is shown as an error only where AWS rejects the definition; everything that deploys is at most a warning. The checks were compared with the definition validation of AWS Step Functions on about 1,400 small definitions, and the recorded answers are part of the test suite. Every check has its own entry under Settings | Editor | Inspections | Amazon States Language, where it can be switched off or given another severity.
- **Quick documentation** for field names and values, in the plugin's own words, with a link to the AWS documentation page.
- **Navigation and rename** for state names: go to the state a transition points to, find the transitions that point to a state, and rename a state together with all of them. A rename stays inside its own state machine, and the plugin reports name conflicts.
- **Structure view** that lists the states by type, with the states of Map and Parallel states nested below them. It works for YAML and JSON.
- **Highlighting** of ASL field names, state names and transition targets, with colours that can be changed under Settings | Editor | Color Scheme | Amazon States Language.
- **JSONata** in `{% … %}` strings of states that use JSONata, in YAML (quoted, literal and folded scalars) and in JSON. An expression is highlighted, with its own colour settings, and syntax-checked exactly as Step Functions parses it (JSONata 2.0.6, including its rules for where a `/` starts a regular expression). Six inspections report a syntax error; a field of `$states` that does not exist where it is used (for example `$states.result` outside a Task, Map or Parallel state); an operator of JSONata 2.1 (`?:`, `??`), which Step Functions does not have; a field name, `$` or `$$` where there is no input; a call of an unknown function; and `$eval`, which Step Functions does not support. Each can be switched off.
- **JSONata editing:** completion of the built-in functions and the Step Functions additions, of `$states` and its fields (including the fields of `$states.context`), of local variables and of keywords; quick documentation and parameter hints for functions; go to declaration, find usages, usage highlighting and rename for local variables (`$x := …` and function parameters); matching of brackets and quotes; comment toggling. Typing the quote of the surrounding YAML or JSON string inside an expression inserts its escaped form.
- **State machine variables** (`Assign` and `$name`) in YAML and JSON, in JSONata and JSONPath states. Each key of an `Assign` is an assignment and each `$name` that is no local variable, built-in function or `$states` is a read of the variable of the same scope; two Parallel branches that assign the same name have two variables. With the caret on a variable, Highlight Usages marks its assignments and reads in different colours, in the YAML or JSON key and inside expressions. Next Highlighted Usage and Previous Highlighted Usage step through them (Ctrl+Option+Down and Ctrl+Option+Up in the default macOS keymap; the default Windows and Linux keymaps bind none, so assign one under Settings | Keymap or call the action by name). From inside an expression, Next and Previous Highlighted Usage step only through that expression. Go to Declaration from a read lists the assignments with the state and place of each, Find Usages groups reads and writes, Rename changes keys and expressions together and refuses when it cannot be sure to keep the meaning, and completion after `$` in JSONata offers the variables visible there.
- **Variable checks**, seven inspections of their own: a read of a variable nothing assigns (an error in a JSONPath string and in a Distributed Map, as AWS rejects it; a warning in JSONata elsewhere, where AWS only warns); an `Assign` key AWS rejects as a name (an error) or that is no plain name (a warning); an assignment in a Map or Parallel state of a name that an outer state machine assigns before (an error, as AWS rejects it); and a variable that is assigned but never read (a weak warning). The levels rest on 62 further recorded answers of AWS. Where a definition does not say every name it assigns, for example through a placeholder in `Assign`, nothing that depends on the missing names is reported.
- **Placeholders** of the form `${name}` are recognised as template placeholders. They are never reported as problems, and Highlight Usages marks all occurrences of the same placeholder.

An expression that contains `${` outside a string literal is not judged at all, because a deployment tool replaces that text before AWS sees it. The plugin does not check the syntax of regular expressions, the parent operator `%`, function signatures, or the number and types of the arguments of a call.

The plugin makes no network requests. YAML anchors, aliases and merge keys, and the contents of free-form data fields, are never reported.

## Recognised files

A file is treated as an ASL file when its name ends in `.asl.yaml`, `.asl.yml` or `.asl.json`. Further file name patterns, such as `*.statemachine.yaml`, can be added under Settings | Languages & Frameworks | Amazon States Language. Files keep their YAML or JSON file type, and the plugin contributes nothing to other files.

## Status

In development and not yet published. Stages 1 to 3 of 4 (ASL-aware editing, JSONata support and state machine variables) are complete. The remaining stage is a live diagram.

## Requirements

- IntelliJ IDEA or another JetBrains IDE with YAML support, version 2026.2 or newer.
- Java 25 to build the plugin.

## Building

```
./gradlew test           # run the tests
./gradlew buildPlugin    # build the plugin zip in build/distributions
./gradlew runIde         # start a sandbox IDE with the plugin
./gradlew verifyPlugin   # check compatibility with other IDE versions
```

To check that definitions kept outside the repository show no errors, run the tests with
`./gradlew test --tests "io.github.saigyo.aslan.fixtures.PrivateFilesTest" -Paslan.privateFixtures=<directory>`.
The test reads the definition files directly in that directory. Without the property it looks in a git-ignored
`private-fixtures/` directory, and it does nothing when it finds no definitions. A failure names the problem and the line,
never the file content.

## Design

The design is in [docs/superpowers/specs/2026-11-13-aslan-design.md](docs/superpowers/specs/2026-11-13-aslan-design.md). The implementation plans are in [docs/superpowers/plans](docs/superpowers/plans).

## Licence

Apache-2.0. See [LICENSE](LICENSE).

The project includes third-party material (see also [NOTICE](NOTICE)):

- The JSONata scanner, `src/main/kotlin/io/github/saigyo/aslan/jsonata/parser/JsonataScanner.kt`, and the checks after parsing in `JsonataTreeChecks.kt` beside it, are ported from the tokenizer and parser of jsonata-js 2.0.6 (https://github.com/jsonata-js/jsonata, tag v2.0.6). MIT License, © Copyright IBM Corp. 2016, 2018. The licence is bundled in the plugin at `META-INF/licenses/jsonata-LICENSE.txt`.
- The descriptions of the JSONata functions in `src/main/kotlin/io/github/saigyo/aslan/spec/data/JsonataFunctions.kt` are adapted from the JSONata documentation. Same licence and copyright holder.
- Test data, not shipped in the plugin: the official JSONata test suite distilled into `src/test/testData/jsonata/suite.json`, and the recorded answers of the reference parser in `reference.json` beside it. MIT License, © Copyright IBM Corp. 2016, 2018; the licence is in `src/test/testData/jsonata/LICENSE`.
- Build tooling, not shipped in the plugin: the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper`). Apache License 2.0, © 2015 the original authors.
