# Recorded answers of AWS

These files hold small state machine definitions together with what AWS answered for each, recorded on 2026-11-14 with

    aws stepfunctions validate-state-machine-definition --definition file://<definition> --severity WARNING

- `verdicts.json`: one condition per definition. `aws` is `OK` when the definition can be deployed and `FAIL` when it cannot; `diagnostics` are the messages of AWS. `AwsVerdictsTest` requires that the checks show no error on an accepted definition and an error on every rejected one, apart from the gaps it lists with their reasons.
- `field-names.json`: many candidate field names in one position per definition. `unsupported` are the names AWS reported as not supported there. `AwsFieldNamesTest` requires that the plugin agrees.

The definitions whose names start with `r9-` and `r10-` are about JSONata expressions: the 40 syntax errors of the official JSONata test suite, valid controls, the places where `$states.result` and `$states.errorOutput` exist, and expressions without an input; `r10-` adds further places of a name without an input, further shapes of `$states` fields, and text around the delimiters. `AwsVerdictsTest` also compares the JSONata checks with these answers one by one.

The 45 definitions whose names start with `r11-`, recorded on 2026-11-14, are about state machine variables: `r11-p-` and `r11-j-` read `$reel` from JSONPath and JSONata in every relation between the read and the assignment (none, before, the same state, later, inside or outside an inline or Distributed Map, a Parallel branch, a Catcher, a Choice rule), `r11-a-` probe the names an `Assign` key may have and an inner machine assigning an outer name. `AwsVerdictsTest` compares the variable checks with them one by one.

The 11 definitions whose names start with `r12-`, recorded on 2026-11-15, follow up on round 11: `r12-o-` assign a name inside a Map or Parallel state and again outside it only after that state, in its own `Assign` or in one of its Catchers; `r12-p-` probe an outer assignment before an inline Map and the rejected names in JSONPath states. `AwsVerdictsTest` compares the variable checks with them one by one too.

The 6 definitions whose names start with `r13-`, recorded on 2026-11-15, ask the finer points of that order: an outer assignment in a Catcher or a Choice rule whose `Next` leads to the Map, a state `Assign` whose only path to the Map is its Catcher, a Choice rule `Assign` on another rule's path, an assignment after the Map that a loop leads back from, and two levels of nesting.

All names, account numbers and regions in the definitions are made up. To re-record, send each `definition` to the command above and replace the stored answer; the result does not depend on the account.

Some definitions depend on how a number is spelled (`5.0`, `1E2`, `15e-1`), so `verdicts.json` must be edited as text. A tool that parses and rewrites it would change these numbers.
