# Aslan

Editor support for Amazon States Language (AWS Step Functions) definitions in JetBrains IDEs.

[![Build](https://github.com/saigyo/aslan/actions/workflows/build.yml/badge.svg)](https://github.com/saigyo/aslan/actions/workflows/build.yml)

## Status

In development and not yet published. Stage 1 of 4 (ASL-aware editing) is in progress. The other stages are JSONata support, variable navigation and a live diagram.

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

## Design

The design is in [docs/superpowers/specs/2026-11-13-aslan-design.md](docs/superpowers/specs/2026-11-13-aslan-design.md). The implementation plans are in [docs/superpowers/plans](docs/superpowers/plans).

## Licence

Apache-2.0. See [LICENSE](LICENSE).
