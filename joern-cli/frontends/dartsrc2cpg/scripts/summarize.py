"""Summarize completed corpus audits without promoting endpoint matches to semantic proofs."""
import argparse
import json
from pathlib import Path

FRONTEND = Path(__file__).resolve().parents[1]
ROOT = FRONTEND.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", choices=["packages", "applications", "holdout"])
    parser.add_argument("--output", type=Path, help="Write a run artifact instead of updating the committed report")
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
                distinctEndpoints=modeled["distinctEndpoints"], viaSatisfied=modeled["viaSatisfied"], semanticReview="see witness-reviews.json and source ledger; snapshots do not certify current paths",
                stockOutcome=stock["outcome"], modeledOutcome=modeled["outcome"],
                limitations={"stock": stock["limitations"], "modeled": modeled["limitations"]},
                omittedWitnesses={"stock": stock["omittedWitnesses"], "modeled": modeled["omittedWitnesses"]},
            )
            if "positiveControl" in modeled:
                check["positiveControl"] = modeled["positiveControl"]
                check["positiveControlSatisfied"] = {
                    "stock": stock["positiveControlSatisfied"], "modeled": modeled["positiveControlSatisfied"]
                }
            checks.append(check)
        results.append(dict(
            project=name, analysisSources=audit["analysisSources"], source=audit["source"], exporter=audit["coverage"]["exporter"], overlay=overlay,
            stringConversionOrder=audit["coverage"]["stringConversionOrder"],
            virtualDispatch=audit["coverage"]["virtualDispatch"],
            modelFiles=audit["modelFiles"], reachingDefEdges=audit["reachingDefEdges"],
            maxCallDepth=audit["maxCallDepth"], maxFieldDepth=audit["maxFieldDepth"],
            pathSelection="longest-per-endpoint-pair", checks=checks,
        ))
        print(f"{name}: {len(checks)} endpoints; "
              f"stock {sum(c['stockPassed'] for c in checks)}, modeled {sum(c['modeledPassed'] for c in checks)}; "
              f"inconclusive stock {sum(c['stockOutcome'].startswith('inconclusive-') for c in checks)}, "
              f"modeled {sum(c['modeledOutcome'].startswith('inconclusive-') for c in checks)}")
    output = args.output or corpus / "dataflow-results.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
