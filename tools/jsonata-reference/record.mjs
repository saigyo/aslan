// Records what the reference JSONata parser answers for every string of the corpus.
// Usage: node tools/jsonata-reference/record.mjs <jsonata checkout>
// Reads .superpowers/jsonata-corpus.json, writes src/test/testData/jsonata/reference.json.
// It loads and runs the downloaded parser, so run it only on a checkout you trust.
import { createRequire } from "node:module";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const checkout = process.argv[2];
if (!checkout) {
  console.error("usage: node record.mjs <jsonata checkout>");
  process.exit(2);
}
const require = createRequire(import.meta.url);
const parser = require(resolve(checkout, "src", "parser.js"));
const corpus = JSON.parse(readFileSync(".superpowers/jsonata-corpus.json", "utf8"));

const entries = corpus.map((expr) => {
  let code = null;
  try {
    parser(expr);
  } catch (e) {
    code = e && typeof e.code === "string" ? e.code : "EXCEPTION";
  }
  return JSON.stringify({ expr, code });
});

writeFileSync("src/test/testData/jsonata/reference.json", "[\n" + entries.join(",\n") + "\n]\n");
console.log(`${entries.length} expressions recorded`);
