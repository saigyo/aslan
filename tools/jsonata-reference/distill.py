#!/usr/bin/env python3
"""Distils the JSONata test suite into suite.json and the corpus for record.mjs.

Usage: python3 -I tools/jsonata-reference/distill.py <jsonata checkout>
"""
import json
import os
import random
import sys

QUIRKS = [
    "[/a/]", "(/a/)", "-/a/", '{"a": /a/}', '$match("a", /a/)', "$x[] / 2", "$x^(a) / 2",
    "function($x){$x} / 2", "$f(?) / 2", "$x ~> |a|{}| / 2", "1 / 2 / 3", "(1) / 2", "[1] / 2",
    '{"a":1} / 2', "$x.a / 2", "and", "or.and.in", "a and and", "$a ?: $b", "$a ?? $b", "$f(??)",
    "${Bucket}", "${a:b}", 'a"b', "a$b", "$$", "$", "//", "/a/x", "/[^]/", "/a{/", "/a{2,1}/",
    "/(/", "1 +", "1 =", "$states.", "(", "[1,", "", " ",
]
INSERTED = "()[]{}.,;:?/*%|&=<>!~@#^$\"'`-+ "
MAX_MUTATED = 300


def main():
    checkout = sys.argv[1]
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    groups = os.path.join(checkout, "test", "test-suite", "groups")
    suite = []
    for group in sorted(os.listdir(groups)):
        gdir = os.path.join(groups, group)
        if not os.path.isdir(gdir):
            continue
        for name in sorted(os.listdir(gdir)):
            if not name.endswith(".json"):
                continue
            with open(os.path.join(gdir, name), encoding="utf-8") as f:
                data = json.load(f)
            cases = data if isinstance(data, list) else [data]
            for index, case in enumerate(cases):
                if "expr" in case:
                    expr = case["expr"]
                else:
                    with open(os.path.join(gdir, case["expr-file"]), encoding="utf-8") as f:
                        expr = f.read()
                code = case.get("code")
                if code is None and isinstance(case.get("error"), dict):
                    code = case["error"].get("code")
                suite.append({"id": "%s/%s#%d" % (group, name[:-5], index), "expr": expr, "code": code})

    out_dir = os.path.join(repo, "src", "test", "testData", "jsonata")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "suite.json"), "w", encoding="utf-8") as f:
        f.write("[\n")
        f.write(",\n".join(json.dumps(e) for e in suite))
        f.write("\n]\n")

    distinct = sorted({e["expr"] for e in suite})
    corpus = set(distinct) | set(QUIRKS)
    rng = random.Random(206)
    for expr in distinct:
        if len(expr) > MAX_MUTATED:
            continue
        mutants = []
        if expr:
            i = rng.randrange(len(expr))
            mutants.append(expr[:i] + expr[i + 1:])
        i = rng.randrange(len(expr) + 1)
        mutants.append(expr[:i] + rng.choice(INSERTED) + expr[i:])
        if len(expr) >= 2:
            i = rng.randrange(len(expr) - 1)
            mutants.append(expr[:i] + expr[i + 1] + expr[i] + expr[i + 2:])
        corpus.update(m for m in mutants if m != expr)
    with open(os.path.join(repo, ".superpowers", "jsonata-corpus.json"), "w", encoding="utf-8") as f:
        json.dump(sorted(corpus), f, indent=0)
        f.write("\n")
    print("suite: %d entries, %d with an S code; corpus: %d strings"
          % (len(suite), sum(1 for e in suite if (e["code"] or "").startswith("S")), len(corpus)))


main()
