# Recorded answers of AWS

These files hold small state machine definitions together with what AWS answered for each, recorded on 2026-11-14 with

    aws stepfunctions validate-state-machine-definition --definition file://<definition> --severity WARNING

- `verdicts.json`: one condition per definition. `aws` is `OK` when the definition can be deployed and `FAIL` when it cannot; `diagnostics` are the messages of AWS. `AwsVerdictsTest` requires that the checks show no error on an accepted definition and an error on every rejected one, apart from the gaps it lists with their reasons.
- `field-names.json`: many candidate field names in one position per definition. `unsupported` are the names AWS reported as not supported there. `AwsFieldNamesTest` requires that the plugin agrees.

All names, account numbers and regions in the definitions are made up. To re-record, send each `definition` to the command above and replace the stored answer; the result does not depend on the account.

Some definitions depend on how a number is spelled (`5.0`, `1E2`, `15e-1`), so `verdicts.json` must be edited as text. A tool that parses and rewrites it would change these numbers.
