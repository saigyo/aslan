# Marketplace listing

What to do and enter when the plugin is put on the JetBrains Marketplace, in order.

## 1. Before uploading

- Set up the signing key and the GitHub secrets with `tools/release/signing.sh`, run in your own terminal. It makes a private key and a self-signed certificate outside the repository and stores them as secrets for the release workflow.
- Push the tag `v1.0.0`. The release workflow builds, checks and signs the plugin and attaches the signed zip, `aslan-1.0.0.zip`, to the GitHub release.

## 2. The first upload

The first version must be uploaded by hand. Sign in on https://plugins.jetbrains.com with your JetBrains account, accept the Developer Agreement, and choose Add new plugin on your profile page. Upload the signed zip from the GitHub release.

The upload creates your vendor profile. Its vendor ID cannot be changed later; its public name, email and website can. Name, vendor, description, change notes, version and logo come from the zip. The vendor in plugin.xml is Markus Ackermann, with the email `aslan@kaixo.de` and the website `https://github.com/saigyo/aslan`; reviewers check that both work. Give the vendor profile the same public name.

Once JetBrains has approved the first version, re-run `tools/release/signing.sh` to store the Marketplace token and switch on the upload by the release workflow (the repository variable `MARKETPLACE_UPLOAD`). Every later version is reviewed by JetBrains too, so it appears on the Marketplace some days after its GitHub release.

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

Upload the seven screenshots in `docs/images/media/` in this order. Each is 2560 by 1600 pixels, taken in the default light theme.

| File | Caption |
|---|---|
| `01-overview.png` | The Structure tool window, the editor and the diagram, with the same state selected in all three. |
| `02-completion.png` | Completion of the fields a Task state may have, with the kind of value each takes. |
| `03-validation.png` | Problems that AWS would reject, underlined in the editor and listed in the Problems tool window. |
| `04-jsonata.png` | A JSONata expression, highlighted, with completion of functions and their signatures. |
| `05-variables.png` | The assignments and reads of a variable highlighted in two colours, with Find Usages below. |
| `06-diagram.png` | The diagram alone in the loose layout, with a Choice path selected and catch paths muted. |
| `07-links.png` | A state's comments as its description and notes in the details panel, with links to the Lambda handler and its types. |

The first row of the Problems tool window in `03-validation.png` shows the location of the demo project on the machine where the pictures were taken, as the IDE prints it. How the pictures were taken is in `tools/demo/README.md`.
