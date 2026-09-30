"""Summarize completed corpus audits without promoting endpoint matches to semantic proofs."""
import argparse
import json
from pathlib import Path

FRONTEND = Path(__file__).resolve().parents[1]
ROOT = FRONTEND.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", choices=["packages", "applications", "holdout"])
    args = parser.parse_args()
    corpus = FRONTEND / "corpus" / ("" if args.corpus == "packages" else args.corpus)
    scratch = ROOT / "agents" / {
        "packages": "dart-corpus", "applications": "application-corpus/results", "holdout": "dart-holdout"
    }[args.corpus]
    results = []
    for project in json.loads((corpus / "projects.json").read_text()):
        name = f"{project['name']}-{project['version']}"
        directory = scratch / name
        audit = json.loads((directory / "dataflow-audit.json").read_text())
        overlay = json.loads((directory / "dataflow-overlay.json").read_text())
        overlay.pop("elapsedMillis")
        checks = []
        for stock, modeled in zip(audit["defaultSemantics"], audit["dartSummaries"], strict=True):
            if stock["id"] != modeled["id"]:
                raise ValueError(f"Unpaired query results: {name}")
            check = dict(
                id=stock["id"], expectedFlow=stock["expected"], sources=stock["sources"], sinks=stock["sinks"],
                stockFlow=stock["paths"] > 0, stockPassed=stock["passed"],
                modeledFlow=modeled["paths"] > 0, modeledPassed=modeled["passed"],
                viaSatisfied=modeled["viaSatisfied"], semanticReview="see source review ledger; not path certification",
                stockOutcome=stock["outcome"], modeledOutcome=modeled["outcome"],
                limitations={"stock": stock["limitations"], "modeled": modeled["limitations"]},
                omittedWitnesses={"stock": stock["omittedWitnesses"], "modeled": modeled["omittedWitnesses"]},
            )
            for key in ("positiveControl", "positiveControlPassed"):
                if key in modeled:
                    check[key] = modeled[key]
            checks.append(check)
        results.append(dict(
            project=name, source=audit["source"], exporter=audit["coverage"]["exporter"], overlay=overlay,
            modelFiles=audit["modelFiles"], reachingDefEdges=audit["reachingDefEdges"],
            maxCallDepth=audit["maxCallDepth"], pathSelection="longest-per-endpoint-pair", checks=checks,
        ))
        print(f"{name}: {len(checks)} endpoints; "
              f"stock {sum(c['stockPassed'] for c in checks)}, modeled {sum(c['modeledPassed'] for c in checks)}")
    (corpus / "dataflow-results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
