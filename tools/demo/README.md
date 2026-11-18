# Demo project

`make-demo.sh` creates `build/demo/film-archive/`, a small project to open in the sandbox IDE. The sandbox IDE is opened only on this project, never on the repository, so that screenshots show nothing but the invented film archive definitions:

```sh
tools/demo/make-demo.sh
./gradlew runIde --args="$PWD/build/demo/film-archive"
```

The script deletes the folder and fills it again with copies of the synthetic definitions in `src/test/testData/clean/`:

- `intake.asl.yaml`: the large intake definition (`film-archive-intake.asl.yaml`), for the diagram.
- `reel-restoration.asl.yaml` and `reel-check.asl.json`: two smaller definitions, one of them in JSON.
- `lambdas/src/`: invented TypeScript sources of the Lambda functions the definitions call, with their contracts in `shared/contracts.ts`. The Lambda states link to them from their comments (and `reel-check.asl.json` from a `Comment` field), for the links to code in the editor and the details panel.
- `intake-with-problems.asl.yaml`: the intake definition with three planted problems, for the inspections: a `Next` that names no state, a Task without `Resource` and an unknown `JitterStrategy`. The script stops with an error when one of its edits finds nothing to change.

# Screenshots

The pictures in `docs/images/` are used in the repository README, in the plugin description, and in the Media section of the Marketplace listing. The six full-window pictures are in `docs/images/media/`. The four detail pictures are in `docs/images/`.

## Conditions

Every picture was taken in the sandbox IDE (`./gradlew runIde --args="$PWD/build/demo/film-archive"`, after `tools/demo/make-demo.sh`), opened on the demo project and on nothing else. The IDE used its default light theme and default font sizes. The window was set to 1280 by 800 points with

```sh
osascript -e 'tell application "System Events" to tell (first process whose bundle identifier is "com.jetbrains.jbr.java") to set size of window 1 to {1280, 800}'
```

which gives 2560 by 1600 pixels on a display of double density. The Project tool window was closed, and no notification balloon was showing.

## Capturing a picture

`tools/demo/capture/capture.sh <file>` captures the sandbox IDE's window. The application appears under the name "Main". The script takes the screen rectangle that the window occupies, so that the IDE's popups (completion lists, tooltips) are in the picture, and then cuts it to the window's own shape, so that nothing behind the window's rounded corners is in it. It needs macOS with the Swift command-line tools and the screen recording permission for the terminal, and the IDE's window must be in front. `window.swift` finds the window, `compose.swift` applies the window's shape, and `crop.swift` cuts a rectangle out of a picture (pixels, origin at the top left) and can scale it down to a given width.

## The pictures

| File | Shows |
|---|---|
| `media/01-overview.png` | `intake.asl.yaml` in split mode, compact layout. The Structure tool window on the left has the state `AcquireBatchLock` selected, the editor is on that state, the diagram shows its box marked, and the details panel shows its rows. |
| `media/02-completion.png` | `reel-restoration.asl.yaml`, editor only. A new state `ExtraCheck` of type Task is being typed at the end, with the completion list of the fields a Task may have and their kinds. |
| `media/03-validation.png` | `intake-with-problems.asl.yaml`, editor only. The error on `Next: CleanUpIncomingFolder` is underlined with its tooltip, and the Problems tool window lists the three planted problems. |
| `media/04-jsonata.png` | `intake.asl.yaml`, editor only. The JSONata expression of the first branch of `PublishEvents` (about twenty lines), with the completion list of functions and their signatures after typing `$su`. |
| `media/05-variables.png` | `intake.asl.yaml`, editor only. The caret is on `$shelfPages` in the state `ReadShelfIndex`, the assignment and the reads are highlighted in two colours, and Find Usages below lists the reads. |
| `media/06-diagram.png` | `reel-restoration.asl.yaml`, diagram only, loose layout. A Choice path is selected, catch paths are muted, the two Map boxes show their inner states, and the details panel is collapsed. |
| `completion.png` | From `02-completion.png`: `crop.swift media/02-completion.png 84 350 1600 1210 completion.png` |
| `jsonata.png` | From `04-jsonata.png`: `crop.swift media/04-jsonata.png 100 385 1600 985 jsonata.png` |
| `variables.png` | From `05-variables.png`: `crop.swift media/05-variables.png 84 300 1600 1240 variables.png` |
| `diagram.png` | From `01-overview.png` without the Structure tool window, scaled down to a width of 1600: `crop.swift media/01-overview.png 546 85 1934 1455 diagram.png 1600` |

The crop commands are run from `docs/images/`, with `crop.swift` run through `swift`.

Two pictures differ from the plan's list. Picture 2 shows the completion list without quick documentation beside it, because the plugin gives quick documentation on hover and at the caret, not for entries of the list. Picture 5 shows a variable with two assignments and three reads, chosen because both highlight colours are in view.

## Things to know when looking at the pictures

- The title bar shows "Unlock Ultimate" because the sandbox IDE runs without a licence.
- The first row of the Problems tool window in `03-validation.png` shows the demo project's location as the IDE prints it.
- For `03-validation.png`, "Weak Warning" and "Show Other Problems" were switched off in the Problems tool window's view options, so that the IDE's own writing hints do not appear beside the plugin's three errors.
