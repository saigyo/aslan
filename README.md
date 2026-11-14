# Aslan

Editor support for Amazon States Language (AWS Step Functions) definitions in JetBrains IDEs.

[![Build](https://github.com/saigyo/aslan/actions/workflows/build.yml/badge.svg)](https://github.com/saigyo/aslan/actions/workflows/build.yml)

## Features

- **Completion** of field names and fixed values (state types, comparison operators, error names and similar) for the structure at the caret, and of state names in transition fields such as `Next` and `Default`. Choosing a state type in a new YAML state also inserts the fields that type needs.
- **Validation** with 38 checks for missing or misplaced fields, wrong value kinds, Task resources that are not ARNs or name an unknown service integration, roles that are not IAM role ARNs, transitions to missing states, unreachable states, duplicate state names, malformed paths and timestamps, and similar problems. A problem is shown as an error only where AWS documents that it rejects the definition; everything else is at most a warning. Every check has its own entry under Settings | Editor | Inspections | Amazon States Language, where it can be switched off or given another severity.
- **Quick documentation** for field names and values, in the plugin's own words, with a link to the AWS documentation page.
- **Navigation and rename** for state names: go to the state a transition points to, find the transitions that point to a state, and rename a state together with all of them. A rename stays inside its own state machine, and the plugin reports name conflicts.
- **Structure view** that lists the states by type, with the states of Map and Parallel states nested below them. It works for YAML and JSON.
- **Highlighting** of ASL field names, state names and transition targets, with colours that can be changed under Settings | Editor | Color Scheme | Amazon States Language.
- **Placeholders** of the form `${name}` are recognised as template placeholders. They are never reported as problems, and Highlight Usages marks all occurrences of the same placeholder.

The plugin makes no network requests. YAML anchors, aliases and merge keys, and the contents of free-form data fields, are never reported.

## Recognised files

A file is treated as an ASL file when its name ends in `.asl.yaml`, `.asl.yml` or `.asl.json`. Further file name patterns, such as `*.statemachine.yaml`, can be added under Settings | Languages & Frameworks | Amazon States Language. Files keep their YAML or JSON file type, and the plugin contributes nothing to other files.

## Status

In development and not yet published. Stage 1 of 4 (ASL-aware editing) is complete. The other stages are JSONata support, variable navigation and a live diagram.

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
