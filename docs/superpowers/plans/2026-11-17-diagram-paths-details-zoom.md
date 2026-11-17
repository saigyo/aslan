# Diagram: Paths Out of the Bottom, Details of a Path, Native Zoom — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the loose layout no path leaves a state at the top; a selected path shows its states and condition in the details panel; the diagram zooms with the keymap's Expand All / Collapse All keys and with the trackpad's pinch.

**Architecture:** One ELK option in `DiagramLayouter`; rows for a path made from the plain `DiagramModel` in `DiagramDetails` and shown by `DiagramPane`; the zoom actions get the two actions' shortcuts on the diagram component; a new container around `DiagramPanel` implements the platform's `ZoomableViewport` and applies the pinch to the panel's `Viewport`.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2.3 (verified against 2026.3), Eclipse ELK 0.12.1, Swing, JUnit 3 style platform tests (`BasePlatformTestCase` through `DiagramTestCase`).

**Spec:** `docs/superpowers/specs/2026-11-17-diagram-paths-details-zoom-design.md`, amending section 6.5 of `docs/superpowers/specs/2026-11-13-aslan-design.md`.

## Global Constraints

- Texts from the file are never read as HTML: every label goes through `DiagramText.literal`, or into a component that does not render HTML.
- The compact layout of the large synthetic definition stays at most 2,200 pixels wide (`DiagramPaneTest` already checks it).
- No PSI in `DiagramModel` or in rows made after the read action.
- Platform API only: no `@ApiStatus.Internal` classes, no `com.apple.*` classes. `ZoomableViewport` and `Magnificator` (`com.intellij.ui.components`) are public.
- Tests run with `./gradlew test --tests '<class>'`; the whole check is `./gradlew check` and `./gradlew verifyPlugin`.
- Never stage with `git add -A` or `git add .`; stage the named paths. Never touch `verarbeitung.asl.yaml`.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01CKu1kuBh3LXs1XXzTNAyDh
  ```
- Documentation style: plain, complete sentences, no metaphors; the design's section 6.5 marks decisions made while building as **(my call, made while building the diagram improvements of 2026-11-17)**.

## Review Focus

1. A JSONata condition of several hundred characters, selected in a pane about 390 pixels wide, wraps in the details panel instead of widening it (test in Task 2).
2. A link in a path's details moves the caret and so selects that state, even though a path was selected before (test in Task 2).
3. A path between two states inside a Map or a Parallel branch shows the right names and offsets (test in Task 2).
4. A pinch while no diagram is shown (a message instead, or no view yet) changes nothing and throws nothing (test in Task 4).
5. Paths inside groups and branches leave at the bottom too, since `configure` runs for every graph (covered by Task 1's test over all definitions).

---

### Task 1: Paths leave at the bottom in the loose layout

**Files:**
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramLayout.kt` (`Run.configure`)
- Test: `src/test/kotlin/io/github/saigyo/aslan/diagram/DiagramLayoutTest.kt`
- Docs: `docs/superpowers/specs/2026-11-13-aslan-design.md` section 6.5, "Layout options" and the measured sizes below it

**Interfaces:** none new.

- [ ] **Step 1: Write the failing test** `test no path leaves its state at the top or enters it at the bottom`: for every `(name, model)` of `definitions()` and every `LayoutMode`, lay out with `lay(model, mode)`; for each `LaidEdge`, with `from = layout.node(e.edge.from).bounds` and `to = layout.node(e.edge.to).bounds`, assert `abs(e.points.first().y - from.minY) >= 0.5` and `abs(e.points.last().y - to.maxY) >= 0.5`, with the message `"$name $mode ${e.edge.id}"`.
- [ ] **Step 2: Run it and see it fail**
  Run: `./gradlew test --tests '*DiagramLayoutTest*'`
  Expected: FAIL for `film-archive-intake.asl.yaml LOOSE` (4 paths) and `reel-restoration.asl.yaml LOOSE` (1 path).
- [ ] **Step 3: Implement.** In `configure`, when `mode == LayoutMode.LOOSE`, set `LayeredOptions.FEEDBACK_EDGES` to `true`, with a one-line comment: a path that closes a loop otherwise leaves its source at the top and enters its target at the bottom. The compact layout keeps its ports and gets no such option.
- [ ] **Step 4: Run the layout and pane tests**
  Run: `./gradlew test --tests '*DiagramLayoutTest*' --tests '*DiagramPaneTest*' --tests '*DiagramSceneTest*'`
  Expected: PASS.
- [ ] **Step 5: Update the design.** In section 6.5, "Layout options": add to the loose layout that a path that closes a loop leaves at the bottom, runs around the side and enters its target at the top (ELK's feedback edges), with the 2026-11-17 marker; in "Measured at 6 pixels per character …", the loose size becomes 4,971 by 5,406. Measure it with the test's 6-pixel measure (`DiagramLayouter.layout(large(), LayoutMode.LOOSE, emptySet(), measure)`) and write the numbers you get if they differ.
- [ ] **Step 6: Commit** `fix: paths that lead back leave a state at the bottom in the loose layout` with the two source files and the design.

### Task 2: Details of a selected path

**Files:**
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramDetails.kt` (new `pathRows`)
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramModel.kt` (`DetailItem.Kind` gains `WRAPPED`)
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramDetailsPanel.kt` (wrapped items; content tracks the viewport's width; empty text)
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramPane.kt` (`showDetails`)
- Test: `DiagramDetailsTest.kt`, `DiagramPaneTest.kt`, `DiagramControllerTest.kt`
- Docs: design section 6.5 "Details panel"; `README.md` line about the details panel (around line 98)

**Interfaces:**
- Produces: `DiagramDetails.pathRows(model: DiagramModel, edge: DiagramEdge): List<DetailRow>` — pure, no PSI, callable on any thread.
- Produces: `DetailItem.Kind.WRAPPED` — a plain text that the panel wraps to its width.

Rows, each left out when empty, in this order (labels verbatim):
- `From`: one item, the source state's `name`, `kind = STATE`, `offset = state.offset`.
- `To`: the same for the target.
- `Condition`: for `TransitionKind.CHOICE`, one item `edge.title ?: edge.label`, `kind = WRAPPED` (left out when both are null); for `TransitionKind.DEFAULT`, one plain item `Default`.
- `Errors`: for `TransitionKind.CATCH`, one plain item `edge.title` when not null (the error names, already joined with `", "`).

The header of a selected path is `"${from.name} → ${to.name}"`. The panel's empty text becomes `Select a state or a path to see its details`.

- [ ] **Step 1: Write the failing tests in `DiagramDetailsTest`** (with `build(archive)` and inline texts in the style of the existing tests):
  - `test the rows of a Next path name its two states`: edge `s1.n` of `archive` (Scan → Each) gives labels `["From", "To"]`, texts `["Scan"]` and `["Each"]`, kinds `STATE`, offsets equal to `model.state("s1")!!.offset` and the offset of `Each`.
  - `test the rows of a JSONPath Choice rule show its whole condition`: edge `s0.c0` gives labels `["From", "To", "Condition"]` and the condition item `$.format = "nitrate"` with kind `WRAPPED`.
  - `test the rows of a JSONata Choice rule show the whole expression`: a JSONata Choice whose condition is 120 characters long; the condition item equals the expression without `{%`/`%}` and with runs of white space made one (the edge's `label`), not cut at 30.
  - `test the rows of a Default path say Default`: edge `s0.d` gives `Condition` with the plain item `Default`.
  - `test the rows of a catch path name its errors`: edge of `Scan`'s Catcher gives labels `["From", "To", "Errors"]` and the item `States.ALL`; a Catcher without `ErrorEquals` gives `["From", "To"]`.
  - `test the rows of a path inside a Map name the inner states`: the edge `Frame → Last` gives `Frame` and `Last` with those states' offsets.
- [ ] **Step 2: Run them and see them fail** — `./gradlew test --tests '*DiagramDetailsTest*'`; expected: compilation failure, `pathRows` unresolved.
- [ ] **Step 3: Implement `pathRows` and `Kind.WRAPPED`.** Look up the states with `model.state(edge.from)` and `model.state(edge.to)`; a missing state leaves its row out.
- [ ] **Step 4: Run** `./gradlew test --tests '*DiagramDetailsTest*'`; expected: PASS.
- [ ] **Step 5: Write the failing pane tests in `DiagramPaneTest`.**
  - Rename `test a click on a path selects the path and shows no details` to `test a click on a path selects the path and shows its details`; replace its last assertion with `assertEquals(listOf("From", "To", "Condition"), pane.rowLabels())`, `assertEquals(DiagramText.literal("Sort → Scan"), pane.details.header.text)`, and links `Sort` and `Scan` present in `pane.links()`.
  - In `test a selected path is kept by show`, assert the path's row labels again after the second `show`.
  - `test a long condition wraps in a narrow pane`: a JSONata Choice whose condition is 400 characters of words; `pane(390, 700)`, `pane.select(<that edge>)`, lay the pane out again; then the details' `content.width <= (details.body as JScrollPane).viewport.width`, and the component that shows the condition is a `JTextComponent` whose text equals the condition and whose height is at least twice its font's line height.
  - In `test texts from the file are shown literally`: `pane.select("s1.c0")`; the `JTextComponent` in the details holds `condition` as it is (it starts with `<html>`), and the header is `DiagramText.literal("B → $name")`. Extend the helper `texts()` with `is JTextComponent -> it.text`.
- [ ] **Step 6: Run them and see them fail** — `./gradlew test --tests '*DiagramPaneTest*'`.
- [ ] **Step 7: Implement in the pane and the panel.**
  - `DiagramPane.showDetails()`: a selected state as before; a selected path shows the header above and `DiagramDetails.pathRows(model, edge)`; nothing selected as before.
  - `DiagramDetailsPanel`: an item of kind `WRAPPED` is a read-only, non-focusable, non-opaque `JBTextArea` with line wrap at word boundaries, the label font, no border, filling the value column (so it gets the column's width). `content` tracks the viewport's width (implement `Scrollable` with `getScrollableTracksViewportWidth() = true`, height from the preferred size), so a wrapped item and the existing `WrapLayout` rows wrap to the pane instead of scrolling sideways. New empty text as above.
- [ ] **Step 8: Write the controller test** `test a link in the details of a path selects that state` in `DiagramControllerTest`, with its existing fake view: show `archive`, select the path `s0.c0` through `view.onSelect`, call `view.onNavigate(model.state("s1")!!.offset)`, run the pending caret selection the way the existing caret tests do, and assert the controller's selection and the view's last `select` are `s1`. It is expected to pass without a code change; if it fails, fix the controller.
- [ ] **Step 9: Run** `./gradlew test --tests '*Diagram*'`; expected: PASS.
- [ ] **Step 10: Update the docs.** Design 6.5 "Details panel": the rows of a selected path as above, with the marker, and that the Errors row is the builder's call. `README.md`: the details panel also shows a selected path's two states, as links, and its condition, or its errors for a catch path.
- [ ] **Step 11: Commit** `feat: the details panel shows a selected path's states and condition`.

### Task 3: Zoom with the keymap's Expand All and Collapse All keys

**Files:**
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramPane.kt` (init)
- Test: `DiagramPaneTest.kt`
- Docs: design 6.5 "Interaction", the zoom bullet; `README.md` "Zoom and move" (around line 99)

**Interfaces:** none new; `zoomIn` and `zoomOut` stay `internal val … : AnAction`.

- [ ] **Step 1: Write the failing test** `test zoom in and out take the shortcuts of expand all and collapse all`: for `pane.zoomIn` and `IdeActions.ACTION_EXPAND_ALL`, and for `pane.zoomOut` and `IdeActions.ACTION_COLLAPSE_ALL`: the action's `shortcutSet.shortcuts` equal `KeymapUtil.getActiveKeymapShortcuts(id).shortcuts` and are not empty, and `ActionUtil.getActions(pane.diagram)` contains the action. What the action does is already tested through the button.
- [ ] **Step 2: Run it and see it fail** — `./gradlew test --tests '*DiagramPaneTest*'`.
- [ ] **Step 3: Implement.** In `init`, `zoomIn.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_EXPAND_ALL), diagram)` and the same for `zoomOut` with `ACTION_COLLAPSE_ALL`; a comment that the IDE's image viewer zooms on the same keys and that, registered on the diagram, they work only while it has the focus, so folding keeps them in the text editor.
- [ ] **Step 4: Run** `./gradlew test --tests '*DiagramPaneTest*'`; expected: PASS.
- [ ] **Step 5: Update the docs.** Design 6.5: the zoom keys and why these. README "Zoom and move": Cmd+Plus and Cmd+Minus on macOS, Ctrl+Plus and Ctrl+Minus on Windows and Linux (the keys of Expand All and Collapse All in the keymap), after a click into the diagram.
- [ ] **Step 6: Commit** `feat: the diagram zooms with the keys of expand all and collapse all`.

### Task 4: Zoom with the trackpad's pinch

**Files:**
- Create: `src/main/kotlin/io/github/saigyo/aslan/diagram/PinchZoom.kt`
- Modify: `src/main/kotlin/io/github/saigyo/aslan/diagram/DiagramPane.kt` (the diagram goes into the new container)
- Test: create `src/test/kotlin/io/github/saigyo/aslan/diagram/PinchZoomTest.kt`; `ViewportTest.kt` if `Viewport` gains a helper
- Docs: design 6.5 zoom bullet; `README.md` "Zoom and move"

**Interfaces:**
- Produces: `internal class PinchZoom(val diagram: DiagramPanel) : JPanel(BorderLayout()), ZoomableViewport` holding `diagram` in its centre.
  - `getMagnificator(): Magnificator` — never null (the IDE only delivers a pinch to a viewport with one); its `magnify(scale, at)` returns `at`, since this class does not use the platform's image-scaling delegate.
  - `magnificationStarted(at: Point)` — remembers the diagram's current `Viewport` and `at` converted to the diagram's coordinates; does nothing while `diagram.viewport` is null or `diagram.message` is not null.
  - `magnify(magnification: Double)` — sets `diagram.viewport = start.zoomAt(scale(magnification), x, y)` and repaints; `magnification` is the sum since the start, as the IDE sends it.
  - `magnificationFinished(magnification: Double)` — applies `magnify(magnification)` once more and forgets the start.
  - `internal fun scale(magnification: Double): Double` = `1 + m` for `m >= 0`, `1 / (1 - m)` below, as `JBViewport` does.
- Consumes: `Viewport.zoomAt` (keeps the point under the pointer and holds to `MIN_SCALE` 0.2 and `MAX_SCALE` 3.0).

- [ ] **Step 1: Write the failing tests** in `PinchZoomTest` (extends `DiagramTestCase`; a `DiagramPane` sized as in `DiagramPaneTest`, showing `small()`):
  - `test a pinch zooms around the pointer`: `magnificationStarted(Point(200, 150))` in the container's coordinates, `magnify(0.5)`: scale is 1.5 times the start, and the diagram point under (200, 150) is the same before and after; `magnify(1.0)` then gives 2 times the start (sums, not steps).
  - `test a pinch inwards zooms out`: `magnify(-1.0)` gives half the start scale.
  - `test a pinch holds to the zoom limits`: `magnify(10.0)` gives 3.0, `magnify(-50.0)` gives 0.2.
  - `test a pinch after its end changes nothing`: after `magnificationFinished(0.5)`, `magnify(2.0)` leaves the viewport as it was.
  - `test a pinch without a diagram changes nothing`: on a pane without `show`, and on one whose diagram shows its message, the three calls leave `diagram.viewport` unchanged and throw nothing.
  - `test the diagram lies in the pinch container`: `SwingUtilities.getAncestorOfClass(ZoomableViewport::class.java, pane.diagram)` is a `PinchZoom` and its `getMagnificator()` is not null.
- [ ] **Step 2: Run them and see them fail** — `./gradlew test --tests '*PinchZoomTest*'`; expected: compilation failure.
- [ ] **Step 3: Implement `PinchZoom`** and, in `DiagramPane`, add `PinchZoom(diagram)` to the centre instead of `diagram`; expose it as `internal val pinch` for the tests. Its KDoc says how the IDE finds it (the nearest ancestor of the component under the pointer that implements `ZoomableViewport`) and that the pinch is applied from the view at its start, so it does not drift.
- [ ] **Step 4: Run** `./gradlew test --tests '*Diagram*' --tests '*PinchZoomTest*' --tests '*ViewportTest*'`; expected: PASS.
- [ ] **Step 5: Update the docs.** Design 6.5 zoom bullet: the pinch, around the pointer, within the limits of the buttons, through the platform's `ZoomableViewport`. README "Zoom and move": pinch on a trackpad on macOS.
- [ ] **Step 6: Commit** `feat: the diagram zooms with the trackpad's pinch`.

### After the last task

- `./gradlew check` and `./gradlew verifyPlugin` (expected: all tests pass; 2026.2.3 and 2026.3 compatible).
- The private check, run by the coordinator in the main session: `./gradlew test -Paslan.privateFixtures="$PWD"` (prints line numbers and counts only).
- An interactive check in the sandbox IDE by a computer-use subagent: a loop path in the loose layout of `film-archive-intake.asl.yaml` leaves at the bottom; clicking a path fills the details; Cmd+Plus and Cmd+Minus zoom after a click into the diagram and fold in the text editor. The commissioner checks the pinch on a trackpad.
