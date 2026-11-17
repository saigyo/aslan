# Diagram: a compact layout in which every path has its own line

Decided with the commissioner on 2026-11-17. It amends section 6.5 of `2026-11-13-aslan-design.md`; once built, that section is brought in line with this one.

## Intent

The compact layout lets the paths into a state share a line. That makes it narrow, but in larger machines many paths run over each other, and the commissioner found it almost unusable. A throwaway version of the loose layout with ELK's network-simplex placement of the states fitted the width of the commissioner's own large machine into the screen, with only vertical scrolling and with every path distinct. That layout becomes the new compact one.

## Decisions

- **The compact layout keeps every path on its own line.** It places the states with ELK's network-simplex strategy, as it does today, and cuts labels at 20 characters. The ports that made the paths into one state share a line, and every path out of a state leave at one point, are removed. The commissioner chose to replace the old compact layout, not to keep it as a third one.
- **The loose layout is unchanged:** ELK's default placement and labels of up to 30 characters.
- **Both layouts route alike:** a path that leads back leaves its state at the bottom and enters its target at the top (feedback edges), the second pass against paths of one state that cross applies to both, and so do the greedy switch and the clearance for start and end marks. **(my call, made while building it)** The second pass is kept only when it is also no wider than the first; in the compact layout of the large synthetic definition it would have halved the 4 crossing pairs but added 383 pixels, so the first pass stays there.
- **The width limit changes.** The compact layout of the large synthetic definition may be about 2,500 pixels wide at full size, measured with 6 pixels per character, instead of 2,200; the commissioner accepted that. The test holds it to 2,600.
- **The size rule stays:** a file with more than 25 states opens in the compact layout.

## Approaches

- (a) The compact layout becomes the loose one with network-simplex placement and shorter labels. **Chosen.**
- (b) A third layout beside the two: rejected by the commissioner; the shared lines are not worth a choice in the toolbar. A settings page for options chosen once (such as the placement of the states) is a later, separate feature.
- (c) Network-simplex placement in the loose layout too, with the compact one unchanged: leaves the overlapping lines the commissioner objected to.

## Testing

The existing layout tests hold for both layouts: no path out of the top or into the bottom, none through a start or end mark, right angles, no overlap. A compact layout has no more pairs of crossing paths of one state than a single pass gives. The width tests use the new limit. The interactive check is the commissioner's, in the sandbox IDE.
