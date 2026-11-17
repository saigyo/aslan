# Diagram: paths leave at the bottom, details of a path, native zoom

Approved by the commissioner on 2026-11-17. It amends section 6.5 of `2026-11-13-aslan-design.md`; once built, that section is brought in line with this one.

## Intent

Three improvements the commissioner asked for after using the diagram on a real project:

1. A path that leaves a state at the top is confusing, because only paths that come in usually connect there. Paths should leave at the bottom; leaving at the left or right side is acceptable.
2. Selecting a path shows nothing in the details panel. It should name the two states the path connects, as links like those in a state's details, and for a path out of a Choice state show its condition, like the label on the path.
3. The diagram should zoom with Cmd+Plus and Cmd+Minus and with the trackpad's pinch on macOS, in addition to the zoom buttons.

## Goals, non-goals, anti-goals

- **Goals:** the three changes above, in both layouts and in every definition the tests run over.
- **Non-goals:** a shortcut for actual size or for fit; zoom keys while the text editor has the focus; details of a path beyond the rows named below.
- **Anti-goals:** taking Cmd+Plus and Cmd+Minus away from folding in the text editor; a layout that breaks the width limit of the compact layout (2,200 pixels for the large synthetic definition).

## What was found

- Only the loose layout lets a path leave at the top. Measured on 2026-11-17: 4 of the 112 paths of `film-archive-intake.asl.yaml` and 1 of the 14 of `reel-restoration.asl.yaml`, all of them paths that lead back to an earlier state. ELK turns such a path for cycle breaking and, by default, attaches it to the source's top and the target's bottom. The compact layout has fixed ports (out at the bottom, in at the top) and is not affected.
- ELK's option `LayeredOptions.FEEDBACK_EDGES` routes such a path out of the source's bottom, around the side and into the target's top. Tried on 2026-11-17 in the loose layout: every path of every definition then leaves at the bottom and enters at the top, and the layout tests pass. The large synthetic definition grows from 4,762 to 4,971 pixels wide, `reel-restoration.asl.yaml` from 984 to 1,026; the heights barely change.
- The model already holds what the details of a path need: `DiagramEdge` has its kind, its two state IDs, its label and its hover text, and `DiagramState` has the name and offset of each state.
- The IDE's image viewer zooms with the shortcuts of the actions `ExpandAll` and `CollapseAll` (`use-shortcut-of`), which are Cmd+Plus and Cmd+Minus in the macOS keymap and Ctrl+Plus and Ctrl+Minus in the default one.
- The IDE delivers the trackpad's pinch to the nearest enclosing component that implements `com.intellij.ui.components.ZoomableViewport` and whose `getMagnificator()` is not null: `magnificationStarted(point)`, then `magnify(m)` with the sum of the magnification so far, then `magnificationFinished(m)`. The interface and `Magnificator` are not marked internal. `JBViewport` turns `m` into a scale as `1 + m` for `m >= 0` and `1 / (1 - m)` below.

## Approaches

- **Paths out of the bottom.** (a) ELK's feedback-edge routing in the loose layout. (b) Fixed ports in the loose layout too, as in the compact one: this also makes all paths into a state share one point, which is what the compact layout trades for width and the loose layout exists to avoid. (c) Moving the ends of turned paths after the layout: fragile, since ELK's routing around the nodes would no longer hold. **Chosen: (a)**, the smallest change with the measured result.
- **Details of a path.** (a) Rows made from the plain model when the path is selected. (b) Rows made in the read action with the states' rows, kept in the model: no new information would come from the PSI. **Chosen: (a).**
- **Keys.** (a) The diagram's zoom actions take the shortcuts of `ExpandAll` and `CollapseAll` from the active keymap, registered on the diagram, as the image viewer does. (b) Fixed keystrokes: they would ignore a user's keymap. (c) Our own actions in the keymap: the user would have to bind them. **Chosen: (a).**
- **Pinch.** (a) The IDE's `ZoomableViewport` hook, zooming live around the pointer. (b) Apple's gesture classes directly: they need an export of a JDK-internal package. **Chosen: (a).**

## The design

**Paths.** In the loose layout, `FEEDBACK_EDGES` is set on every graph that holds states (the root, each group and each branch). The compact layout is unchanged. The width limit of the compact layout is unaffected; the loose layout may grow somewhat wider.

**Details of a path.** When a path is selected, the header reads `<from> → <to>` (the states' names) and the rows are, each left out when it has nothing to show:

- From: the source state, a link to its name;
- To: the target state, a link to its name;
- Condition: for a Choice rule its whole condition, not cut (the hover text when there is one, else the label), wrapped in the panel; for a `Default` path "Default";
- Errors: for a catch path the names in its `ErrorEquals` (the commissioner's request names no row for catch paths; this one is my call).

A link moves the caret to the state, which selects that state, as the links of a state's details do. The path's rows hold no PSI and are made from the model.

**Zoom.** Zoom in and zoom out also run on the shortcuts of `ExpandAll` and `CollapseAll` of the active keymap while the diagram has the keyboard focus, which a click into the diagram gives it; with the focus in the text editor the keys keep their folding meaning there. They zoom around the middle of the pane, as the buttons do. A pinch on the trackpad over the diagram zooms around the pointer while the fingers move, within the scale limits of the zoom buttons (0.2 to 3). Ctrl or Cmd with the wheel zooms as before.

## Left to the builder

- The exact name of the wrapper that implements `ZoomableViewport`, and whether the pinch scale is applied from the view at the start of the gesture (preferred: no drift) or step by step.
- How the condition wraps in the details panel.

## Testing

- Layout: no path of any definition, in either layout, leaves its source at the top (its first point on the top edge of the source's box) or enters its target at the bottom; the existing layout tests stay green.
- Details: the rows and header for a `Next`, a Choice rule (JSONata and JSONPath), a `Default` and a catch path; a link reports the target state's offset; texts stay literal.
- Zoom: the zoom actions carry the shortcuts of `ExpandAll` and `CollapseAll`; the pinch hook scales around the given point within the limits. The pinch itself is checked by the commissioner on a trackpad, since it cannot be produced by screen control.
