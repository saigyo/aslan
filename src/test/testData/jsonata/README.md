# JSONata reference data

Source: https://github.com/jsonata-js/jsonata, tag `v2.0.6`, commit `6f1fc886aa1e3e6bdbfffcdd80e63ede1d7d6b6b`. The JSONata project is MIT licensed; `LICENSE` is a copy of its licence.

- `suite.json` lists every case of the project's test suite (`test/test-suite/groups`) as `{"id": "<group>/<file>#<index>", "expr": ..., "code": ...}`. `id` uses the file name without `.json`; `code` is the case's expected error code, or null. It is produced by `tools/jsonata-reference/distill.py`.
- `reference.json` holds what the reference parser answers for the expressions of the suite, some hand-picked quirks and mutated variants of the suite expressions: `{"expr": ..., "code": ...}`, where `code` is the thrown error's code, `"EXCEPTION"` for an error without a code, or null when the expression parses. It is produced by `tools/jsonata-reference/record.mjs`.

To re-record, see `tools/jsonata-reference/README.md`.
