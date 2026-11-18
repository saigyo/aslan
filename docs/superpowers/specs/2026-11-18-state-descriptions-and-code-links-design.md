# State descriptions and links from comments to code

Decided with the commissioner on 2026-11-18. It amends section 6.5 of `2026-11-13-aslan-design.md` (details panel) and adds links in comments to the editor; once built, that section and the README are brought in line with this one.

## Intent

The commissioner browses large state machines in the preview alone. Most states are described by `#` comment lines directly above the state's name, and some also carry ASL's `Comment` field. To read a description today, the commissioner has to switch to the split mode. The details panel should show it.

Comments also name the code a state depends on: the source file of the Lambda function it calls, and the file and type of the data it takes and returns, for example a TypeScript interface. The commissioner wants to jump from such a comment to the file, and preferably to the type's declaration, both in the editor and from the details panel, so the preview never has to be left.

## Goals, non-goals, anti-goals

- **Goals:** the description and the comment lines of a state in the details panel; file paths with an optional type in comments as links, in the editor and in the details panel; a weak warning for a path that names no file and for a type that is not found.
- **Non-goals (for a later iteration, as the commissioner asked):** completion of paths while typing; paths updated when a file is renamed or moved; descriptions of the machine as a whole or of a branch or item processor.
- **Anti-goals:** a dependency on the TypeScript or any other language plugin; a link or warning on a word that is plainly not a path, such as a URL or a version number; text from a comment read as markup in the panel.

## Approaches

- **Links in the editor.** (a) A `PsiReferenceProvider` on YAML comments, as the plugin already has for state names and variables, which gives Cmd+Click and Go to Declaration. (b) An annotator plus a navigation action of its own: more code for the same result. **Chosen: (a).**
- **The jump to a type.** (a) Look for a declaration with the name in the syntax tree of the target file, for any language the IDE can read, and fall back to the first whole-word occurrence in its text. (b) The IDE's symbol index: finds the name in every file, works only after indexing, and still has to be narrowed to the named file. (c) The TypeScript plugin's own API: ties the plugin to one language and to IDEs that have it. **Chosen: (a).**
- **The syntax of a reference (decided by the commissioner).** (a) No marker: a word that looks like a path is a link when it names a file, with `#Type` for a type. (b) A marker such as `@see path#Type`. (c) A link written as `[[path#Type]]`. **Chosen: (a)**, since existing comments work as they are and the warning catches typos.

## The design

### What counts as a path

A word in a comment counts as a path when it holds at least one `/`, its last part has a file extension (letters and digits after a dot), and it is not part of a URL (no `://` in the word). It may start with `./` or `../`, and may be wrapped in backticks, quotes or brackets, which are not part of it; a comma, full stop, colon, semicolon or closing bracket at its end is not part of it either. A `#` directly after the extension starts the type: an identifier (letters, digits, `_` and `$`, not starting with a digit).

Examples: `lambdas/src/intake/handler.ts`, `lambdas/src/shared/contracts.ts#IntakeInput`, `` `../schemas/order.json` ``. Not paths: `https://example.com/a.json`, `v1.2`, `and/or`.

### Where a path is resolved

A path is resolved against these folders in turn, and the first that holds the file wins: the folder of the ASL file; the content roots that hold the ASL file, innermost first; the root of its version control repository; the project's base folder. **(my call)** The list covers a repository whose infrastructure code is a module of its own, where the paths are usually written from the repository root. A path starting with `/` skips the ASL file's folder. A path that resolves to no file, or to a folder, is not a link.

### The jump to a type

The type is the first element of the target file's syntax tree, in document order, that declares a name equal to the type (a named element with a name identifier). When the IDE cannot read the file's language, or finds no such element, the jump goes to the first whole-word occurrence of the name in the file's text. When the name does not occur at all, the jump goes to the start of the file.

### In the editor

- Every path in a `#` comment of an ASL file in YAML carries a reference to its file, and its `#Type` a reference to the declaration. Cmd+Click, Go to Declaration and the hover underline work on both.
- **(my call)** A path in the string value of a state's `Comment` field carries the same references, in YAML and JSON, since JSON has no other comments.
- A path-like word whose file is not found is marked with a weak warning, "File not found: <path>"; a type that is neither declared nor written in the file, "Type <name> not found in <file name>". Both come from one inspection that can be turned off. A word that is not a path by the rule above is never marked.

### In the details panel

The rows of a state gain two, placed first, each left out when it has nothing to show:

- **Description:** the text of the state's `Comment` field, then the block of `#` lines directly above the state's name. The block consists of the comment lines that stand alone on their line, directly above the key with no blank line between, and that start in the same column as the key. Its lines are joined into one paragraph and wrapped to the panel's width; a line that is only `#` starts a new paragraph. The `#` and one space after it are dropped.
- **Notes:** the comment lines inside the state, one per line, as written without the `#` and one space after it. **(my call)** Inside the state means after the line of its key and before the key of the next state at the same level (or the end of the enclosing `States`), in a column to the right of the state's key. So a comment after the last field, indented like the fields, is a note; a comment in the key's column that is not the next state's description, such as a section heading between two states, belongs to no state and is shown nowhere. Lines inside a state nested in it (a branch of a Parallel or the item processor of a Map), including the nested state's description, are not notes of the outer state. A comment at the end of a line, after a value, is a note too.

Paths in both rows are links. A click opens the file in an editor tab of its own, at the type when there is one, as Go to Declaration does; the ASL file stays open. A link that no longer resolves when clicked does nothing.

The text of both rows is literal, never markup, and starts where the other values start, as the wrapped condition does today.

## Left to the builder

- How wrapped text with links inside it is shown in the panel.
- Whether the rows of a state keep the comments as text and resolve paths only when a link is clicked, or resolve them when the rows are made (they are made in a read action).
- The exact wording of the inspection's name.

## Testing

- The rule of what counts as a path, with the examples above and the edge cases: punctuation at the end, backticks, a type after the path, a URL, a word with a slash and no extension.
- Resolution against the ASL file's folder, then a content root, then the project's base folder, and a path starting with `/`; a folder is not a link.
- The jump to a type in a file whose language the test IDE reads (such as a Java or JSON file), the text fallback in a plain text file, and a missing name.
- Description and notes of a state: the block above the key, stopped by a blank line and by a line in another column; the `Comment` field first; a comment after the last field is a note; a section heading in the key's column belongs to no state; notes not taken from a nested state or from the next state's description; comments at the end of a line; a state in JSON with a `Comment` field.
- The warnings, and no warning on the examples that are not paths.
- A click on a link in the panel opens the file at the type.
- All test files are made up for the tests; none comes from the commissioner's own projects.
