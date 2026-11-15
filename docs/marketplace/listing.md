# Marketplace listing

What to do and enter when the plugin is put on the JetBrains Marketplace, in order. Signing and uploading are outside this project.

## 1. Before uploading

Make the repository `saigyo/aslan` on GitHub public and merge the branch with this file into `main`. The plugin description shows three images from `https://raw.githubusercontent.com/saigyo/aslan/main/docs/images/` and links to the README and the issue tracker; until both steps are done, the images do not show and the links do not work.

## 2. Build, sign and upload

Build the plugin with `./gradlew buildPlugin`. The zip is `build/distributions/aslan-<version>.zip`. Sign it and upload it as the JetBrains documentation describes ("Plugin Signing" and "Publishing a Plugin" in the IntelliJ Platform SDK documentation).

Name, vendor, description, change notes, version and logo come from the zip. The vendor is `saigyo`, with the email `aslan@kaixo.de` and the website `https://github.com/saigyo/aslan`.

## 3. The fields of the upload form and the plugin page

| Field | Enter |
|---|---|
| Licence | Apache-2.0, linked to `https://github.com/saigyo/aslan/blob/main/LICENSE` |
| Source code | `https://github.com/saigyo/aslan` |
| Issue tracker | `https://github.com/saigyo/aslan/issues` |
| Documentation | `https://github.com/saigyo/aslan#readme` |
| Tags | Choose from the list the form offers. Suggested, where the form has them: Languages, Code Editing, Inspection, Navigation, Visualization, Cloud |
| Getting Started | The text of [getting-started.html](getting-started.html), pasted as HTML |

## 4. Media

Upload the six screenshots in `docs/images/media/` in this order. Each is 2560 by 1600 pixels.

| File | Caption |
|---|---|
| `01-overview.png` | The Structure tool window, the editor and the diagram, with the same state selected in all three. |
| `02-completion.png` | Completion of the fields a Task state may have, with the kind of value each takes. |
| `03-validation.png` | Problems that AWS would reject, underlined in the editor and listed in the Problems tool window. |
| `04-jsonata.png` | A JSONata expression, highlighted, with completion of functions and their signatures. |
| `05-variables.png` | The assignments and reads of a variable highlighted in two colours, with Find Usages below. |
| `06-diagram.png` | The diagram alone in the loose layout, with a Choice path selected and catch paths muted. |

Three things to know about the pictures before uploading:

- The title bar shows "Unlock Ultimate", because the sandbox IDE they were taken in runs without a licence.
- The first row of the Problems tool window in `03-validation.png` shows the location of the demo project on the machine where the pictures were taken, as the IDE prints it.
- The images inside the description are links to `raw.githubusercontent.com/saigyo/aslan/main/…`. They show only once the repository is public and this branch is merged (step 1).

How the pictures were taken is in `tools/demo/README.md`.
