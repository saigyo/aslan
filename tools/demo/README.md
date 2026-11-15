# Demo project

`make-demo.sh` creates `build/demo/film-archive/`, a small project to open in the sandbox IDE. The sandbox IDE is opened only on this project, never on the repository, so that screenshots show nothing but the invented film archive definitions:

```sh
tools/demo/make-demo.sh
./gradlew runIde --args="$PWD/build/demo/film-archive"
```

The script deletes the folder and fills it again with copies of the synthetic definitions in `src/test/testData/clean/`:

- `intake.asl.yaml`: the large intake definition (`film-archive-intake.asl.yaml`), for the diagram.
- `reel-restoration.asl.yaml` and `reel-check.asl.json`: two smaller definitions, one of them in JSON.
- `intake-with-problems.asl.yaml`: the intake definition with three planted problems, for the inspections: a `Next` that names no state, a Task without `Resource` and an unknown `JitterStrategy`. The script stops with an error when one of its edits finds nothing to change.
