# Recording the JSONata reference

Both scripts work from a checkout of JSONata 2.0.6 (the files in `src/test/testData/jsonata` name the exact commit).

```sh
git clone --depth 1 --branch v2.0.6 https://github.com/jsonata-js/jsonata.git .superpowers/jsonata-2.0.6
python3 -I tools/jsonata-reference/distill.py .superpowers/jsonata-2.0.6
node tools/jsonata-reference/record.mjs .superpowers/jsonata-2.0.6
```

- `distill.py` writes `src/test/testData/jsonata/suite.json` and the corpus `.superpowers/jsonata-corpus.json` (suite expressions, a list of quirks, and three seeded mutants of each short expression). It is deterministic.
- `record.mjs` runs the reference parser over the corpus and writes `src/test/testData/jsonata/reference.json`. It executes downloaded code, so run it only on a checkout you have checked.
