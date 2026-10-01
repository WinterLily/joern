"""Check isolated four-witness audits against saved baselines and holdout reviews."""
import argparse
import hashlib
import json
from pathlib import Path

from summarize_alternatives import FRONTEND, ROOT, digest, validate_forwarding_review

LIMITS = (
    "maxCallDepth", "maxFieldDepth", "maxWitnessesPerEndpoint", "maxHeldTaskIterations",
    "maxStaticStorageNodes", "maxArgsToAllow", "maxOutputArgsExpansion",
)
REVIEW = "holdout-witness-review.json"


def read(path):
    return json.loads(path.read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def summarize(audit_root, resource_file):
    resources = read(resource_file)
    require([r["category"] for r in resources] == ["packages", "applications", "holdout"],
            "Incomplete resource observations")
    for resource in resources:
        require(resource["outcome"] == "completed" and resource["exitCode"] == 0
                and resource["elapsedMillis"] < resource["budgetMillis"], "Audit exceeded its resource contract")
    review = read(FRONTEND / "corpus" / REVIEW)
    require(review["schemaVersion"] == 2, "Unsupported holdout review schema")
    for oracle in review["executionOracles"]:
        require(hashlib.sha256((FRONTEND / oracle["file"]).read_bytes()).hexdigest() == oracle["sha256"],
                "Changed execution oracle")
    reviewed = {p["project"]: p for p in review["projects"]}
    projects = []
    fingerprints = []
    for category, subdir, scratch in [
        ("packages", "", ROOT / "agents/dart-corpus"),
        ("applications", "applications", ROOT / "agents/application-corpus/results"),
        ("holdout", "holdout", ROOT / "agents/dart-holdout"),
    ]:
        for source in read(FRONTEND / "corpus" / subdir / "projects.json"):
            name = f"{source['name']}-{source['version']}"
            audit = read(audit_root / category / name / "alternative-audit.json")
            two = read(scratch / name / "alternative-audit.json")
            ordinary = read(scratch / name / "dataflow-audit.json")
            fingerprint = ordinary["analysisSources"]
            require(audit["graphAnalysisSources"] == two["graphAnalysisSources"] == fingerprint,
                    f"Stale graph provenance: {name}")
            fingerprints.append(fingerprint)
            models = {k: hashlib.sha256(v.encode()).hexdigest() for k, v in audit["modelFiles"].items()}
            require(audit["source"] == two["source"] == ordinary["source"]
                    and audit["exporter"] == two["exporter"] == ordinary["coverage"]["exporter"]
                    and audit["modelFiles"] == two["modelFiles"] == ordinary["modelFiles"],
                    f"Changed source, exporter or models: {name}")
            proof = reviewed.get(name)
            if proof:
                require(proof["source"] == audit["source"] and proof["analysisSources"] == fingerprint
                        and proof["exporter"] == audit["exporter"] and proof["modelFilesSha256"] == models,
                        f"Stale holdout review: {name}")
                for evidence in proof["sourceEvidence"]:
                    file = scratch / name / evidence["file"]
                    require(hashlib.sha256(file.read_bytes()).hexdigest() == evidence["fileSha256"],
                            f"Changed reviewed source: {file}")
                for path in proof["paths"]:
                    require(all(c in review["transitionClasses"] for c in path["transitions"]),
                            f"Unknown transition classification: {name}")
            checks = []
            for key in ["defaultSemantics", "dartSummaries"]:
                require([q["id"] for q in audit[key]] == [q["id"] for q in two[key]]
                        == [q["id"] for q in ordinary[key]], f"Incomplete query set: {name}")
                if proof:
                    require({q["id"] for q in proof["queries"]} == {q["id"] for q in audit[key]},
                            f"Incomplete holdout review: {name}")
            for stock, modeled in zip(audit["defaultSemantics"], audit["dartSummaries"], strict=True):
                require(stock["id"] == modeled["id"], f"Unpaired queries: {name}")
                require(all(stock[k] == modeled[k] for k in LIMITS), f"Unpaired query limits: {name}")
                modes = {}
                for mode, key, query in [("stock", "defaultSemantics", stock), ("modeled", "dartSummaries", modeled)]:
                    previous = next(q for q in two[key] if q["id"] == query["id"])
                    baseline = next(q for q in ordinary[key] if q["id"] == query["id"])
                    require(all(query[k] == previous[k] == baseline[k] for k in ["passed", "sources", "sinks"]),
                            f"Changed endpoint observation: {name} {query['id']}")
                    require(not query["omittedWitnesses"] and not query["omittedDetailedWitnesses"],
                            f"Reporting omitted paths: {name} {query['id']}")
                    require(query["maxWitnessesPerEndpoint"] == 4 and query["maxHeldTaskIterations"] == 2,
                            f"Changed query budget: {name} {query['id']}")
                    current = {digest(path) for path in query["detailedWitnesses"]}
                    old = {digest(path) for path in previous["detailedWitnesses"]}
                    modes[mode] = {k: query[k] for k in [
                        "paths", "detailedPaths", "outcome", "passed", "sources", "sinks", "distinctEndpoints",
                        "limitations", "positiveControlSatisfied",
                    ] if k in query}
                    modes[mode].update(
                        visibleLengths=[len(p) for p in query["witnesses"]],
                        detailedLengths=[len(p) for p in query["detailedWitnesses"]],
                        twoWitnessDetailedPaths=previous["detailedPaths"], newDetailedSequences=len(current - old),
                        twoWitnessSequencesNotRetained=len(old - current),
                        detailedWitnessesSha256=digest(query["detailedWitnesses"]),
                    )
                    if proof:
                        selected = next(q for q in proof["queries"] if q["id"] == query["id"])
                        validate_forwarding_review(proof, selected, mode, query)
                checks.append(dict(
                    id=stock["id"], expectedFlow=stock["expected"], source=stock["source"], sink=stock["sink"],
                    positiveControl=stock.get("positiveControl"), queryLimits={k: stock[k] for k in LIMITS}, modes=modes,
                    semanticReview=f"see {REVIEW}; {selected['disposition']}" if proof else
                    "pending transition review; additional sequences are not necessarily distinct semantic route families",
                ))
            projects.append(dict(project=name, category=category, source=audit["source"], exporter=audit["exporter"],
                                 analysisSources=fingerprint, modelFilesSha256=models, checks=checks))
    require(len(projects) == 12 and all(f == fingerprints[0] for f in fingerprints), "Mixed graph fingerprints")
    fingerprint = fingerprints[0]
    files = []
    for root in fingerprint["roots"]:
        path = ROOT / root
        files.extend([path] if path.is_file() else (p for p in path.rglob("*") if p.is_file()))
    current = hashlib.sha256()
    for file in sorted(files, key=lambda p: p.relative_to(ROOT).as_posix()):
        current.update(file.relative_to(ROOT).as_posix().encode() + b"\0" + file.read_bytes() + b"\0")
    require(current.hexdigest() == fingerprint["sha256"] and len(files) == fingerprint["files"],
            "Graph and current analysis implementation fingerprints differ")
    return dict(
        schemaVersion=1, analysisSources=fingerprint, analysisBaseCommit="c4e2523dc",
        scope="Twelve pinned saved graphs under four witnesses/two held rounds; explicit worker heap/CPU and separate resource budgets.",
        resources=resources, limitations=[
            "Four witnesses/two rounds does not qualify historical unbounded-round or fifty-round searches.",
            "Intermediate pruning, depth and held-round budgets exclude routes; negative searches with limits remain inconclusive.",
            "New sequences may differ only in temporary/context details; larger bounds need not retain all smaller-bound sequences.",
            "Memory values are sampled process-family sums, not measured peak RSS or the heap of one process.",
            "Only the four holdout queries have full transition reviews in this snapshot; other additional routes remain pending.",
        ], projects=projects,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-root", type=Path, default=ROOT / "agents/dart-witness-four")
    parser.add_argument("--resources", type=Path, default=ROOT / "agents/four-witness-resource-results.json")
    parser.add_argument("--output", type=Path, default=FRONTEND / "corpus/four-witness-results.json")
    args = parser.parse_args()
    report = summarize(args.audit_root, args.resources)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    checks = [q for p in report["projects"] for q in p["checks"]]
    for mode in ["stock", "modeled"]:
        values = [q["modes"][mode] for q in checks]
        print(f"{mode}: {sum(q['passed'] for q in values)}/{len(values)} matched expectations, "
              f"{sum(q['paths'] for q in values)} visible/{sum(q['detailedPaths'] for q in values)} detailed paths, "
              f"{sum(bool(q['limitations']) for q in values)} limited queries")


if __name__ == "__main__":
    main()
