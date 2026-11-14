// Records what the reference JSONata parser answers for every string of the corpus.
// Usage: node tools/jsonata-reference/record.mjs <jsonata checkout>
// Reads .superpowers/jsonata-corpus.json, writes src/test/testData/jsonata/reference.json.
// It loads and runs the downloaded parser, so run it only on a checkout you trust.
import { createRequire } from "node:module";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const checkout = process.argv[2];
if (!checkout) {
  console.error("usage: node record.mjs <jsonata checkout>");
  process.exit(2);
}
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const require = createRequire(import.meta.url);
const parser = require(resolve(checkout, "src", "parser.js"));
const corpus = JSON.parse(readFileSync(resolve(root, ".superpowers", "jsonata-corpus.json"), "utf8"));

let failed = false;
const entries = corpus.map((expr) => {
  let code = null;
  try {
    parser(expr);
  } catch (e) {
    if (e && typeof e.code === "string") code = e.code;
    else if (e instanceof SyntaxError) code = "EXCEPTION"; // what new RegExp throws
    else {
      failed = true;
      console.error(JSON.stringify(expr), String(e));
    }
  }
  return JSON.stringify({ expr, code });
});
if (failed) {
  console.error("error without a code that is not a SyntaxError; nothing written");
  process.exit(1);
}

writeFileSync(resolve(root, "src", "test", "testData", "jsonata", "reference.json"), "[\n" + entries.join(",\n") + "\n]\n");
console.log(`${entries.length} expressions recorded`);
