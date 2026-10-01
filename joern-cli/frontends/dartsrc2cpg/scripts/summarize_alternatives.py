"""Summarize bounded witness audits without certifying the returned routes."""
import argparse
import hashlib
import json
from pathlib import Path

FRONTEND = Path(__file__).resolve().parents[1]
ROOT = FRONTEND.parents[2]
REVIEWS = {("http_parser-4.1.2", "captured-media-type"): "media-type-alternative-review.json"}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def summarize():
    projects = []
    fingerprints = set()
    for category, scratch in [
        ("", ROOT / "agents/dart-corpus"),
        ("applications", ROOT / "agents/application-corpus/results"),
        ("holdout", ROOT / "agents/dart-holdout"),
    ]:
        for project in json.loads((FRONTEND / "corpus" / category / "projects.json").read_text()):
            name = f"{project['name']}-{project['version']}"
            directory = scratch / name
            original = json.loads((directory / "dataflow-audit.json").read_text())
            alternatives = json.loads((directory / "alternative-audit.json").read_text())
            if alternatives["graphAnalysisSources"] != original["analysisSources"]:
                raise ValueError(f"Stale graph provenance: {name}")
            fingerprints.add(original["analysisSources"]["sha256"])
            checks = []
            for stock, modeled in zip(alternatives["defaultSemantics"], alternatives["dartSummaries"], strict=True):
                if stock["id"] != modeled["id"]:
                    raise ValueError(f"Unpaired alternative queries: {name}")
                modes = {}
                for mode, query in [("stock", stock), ("modeled", modeled)]:
                    previous = next(q for q in original[
                        "defaultSemantics" if mode == "stock" else "dartSummaries"
                    ] if q["id"] == query["id"])
                    if query["omittedWitnesses"] or query["omittedDetailedWitnesses"]:
                        raise ValueError(f"Reporting omitted witnesses: {name} {query['id']}")
                    if (query["paths"] > 0) != (previous["paths"] > 0):
                        raise ValueError(f"Changed endpoint observation: {name} {query['id']}")
                    modes[mode] = dict(
                        paths=query["paths"], detailedPaths=query["detailedPaths"],
                        legacyPaths=previous["paths"],
                        visibleLengths=[len(path) for path in query["witnesses"]],
                        detailedLengths=[len(path) for path in query["detailedWitnesses"]],
                        outcome=query["outcome"], limitations=query["limitations"], passed=query["passed"],
                        sources=query["sources"], sinks=query["sinks"], distinctEndpoints=query["distinctEndpoints"],
                        viaSatisfied=query["viaSatisfied"], positiveControlSatisfied=query.get("positiveControlSatisfied"),
                        witnessesSha256=digest(query["witnesses"]),
                        detailedWitnessesSha256=digest(query["detailedWitnesses"]),
                    )
                checks.append(dict(
                    id=stock["id"], expectedFlow=stock["expected"], source=stock["source"], sink=stock["sink"],
                    maxCallDepth=stock["maxCallDepth"], maxFieldDepth=stock["maxFieldDepth"],
                    maxWitnessesPerEndpoint=stock["maxWitnessesPerEndpoint"], modes=modes,
                    maxHeldTaskIterations=stock["maxHeldTaskIterations"],
                    maxArgsToAllow=stock["maxArgsToAllow"], maxOutputArgsExpansion=stock["maxOutputArgsExpansion"],
                    positiveControl=stock.get("positiveControl"),
                    semanticReview="pending transition review; endpoint observations and route counts are not correctness claims",
                ))
                review_file = REVIEWS.get((name, stock["id"]))
                if review_file:
                    review = json.loads((FRONTEND / "corpus" / review_file).read_text())
                    if review["analysisSources"] != original["analysisSources"]:
                        raise ValueError(f"Stale transition review: {name} {stock['id']}")
                    keys = review["reviews"][0]["nodes"][0].keys() - {"callSiteStack"}
                    reviewed = [path["nodes"] for path in review["reviews"]]
                    observed = [[
                        {key: node[key] for key in keys} | {"callSiteStack": [
                            {"nodeId": call["nodeId"], "target": call["target"]} for call in node["callSiteStack"]
                        ]} for node in path
                    ] for path in stock["detailedWitnesses"]]
                    if sorted(map(digest, reviewed)) != sorted(map(digest, observed)):
                        raise ValueError(f"Changed reviewed paths: {name} {stock['id']}")
                    checks[-1]["semanticReview"] = f"see {review_file}; direct capture and conservative output detour classified"
            projects.append(dict(
                project=name, category=category or "packages", source=alternatives["source"],
                exporter=alternatives["exporter"],
                modelFilesSha256={name: digest(text) for name, text in alternatives["modelFiles"].items()},
                checks=checks,
            ))
    if len(fingerprints) != 1:
        raise ValueError("Corpus graphs do not share one analysis source fingerprint")
    analysis_sources = original["analysisSources"]
    files = []
    for root in analysis_sources["roots"]:
        path = ROOT / root
        files.extend([path] if path.is_file() else (file for file in path.rglob("*") if file.is_file()))
    current = hashlib.sha256()
    for file in sorted(files, key=lambda path: path.relative_to(ROOT).as_posix()):
        current.update(file.relative_to(ROOT).as_posix().encode())
        current.update(b"\0")
        current.update(file.read_bytes())
        current.update(b"\0")
    if current.hexdigest() not in fingerprints or len(files) != analysis_sources["files"]:
        raise ValueError("Graph and current query implementation fingerprints differ")
    return dict(
        schemaVersion=1, analysisSources=analysis_sources,
        scope="Twelve pinned saved graphs; bounded alternative queries and raw-path provenance, pending route review",
        pathSelection="longest-first bounded alternatives at intraprocedural, held-task and final selection stages",
        limitations=[
            "An intermediate witness bound may prune route families before final selection.",
            "Distinct detailed paths may collapse to the same visible node sequence.",
            "Depth and other search omissions remain explicit; no exhaustive or path-feasibility claim follows.",
            "Historical reviewed witnesses do not certify these new paths.",
        ],
        projects=projects,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=FRONTEND / "corpus/alternative-witness-results.json")
    args = parser.parse_args()
    report = summarize()
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    checks = [query for project in report["projects"] for query in project["checks"]]
    for mode in ["stock", "modeled"]:
        paths = sum(query["modes"][mode]["paths"] for query in checks)
        detailed = sum(query["modes"][mode]["detailedPaths"] for query in checks)
        limited = sum(bool(query["modes"][mode]["limitations"]) for query in checks)
        print(f"{mode}: {len(checks)} endpoints, {paths} visible / {detailed} detailed witnesses; {limited} limited queries")


if __name__ == "__main__":
    main()
