"""Summarize bounded witness audits without certifying the returned routes."""
import argparse
import hashlib
import json
from pathlib import Path

FRONTEND = Path(__file__).resolve().parents[1]
ROOT = FRONTEND.parents[2]
REVIEWS = {("http_parser-4.1.2", "captured-media-type"): "media-type-alternative-review.json"}
FORWARDING_REVIEW = "forwarding-alternative-review.json"


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def validate_forwarding_review(project, review, mode, query):
    selected = review["modes"][mode]
    if review["expectedFlow"] != query["expected"] or any(
        value != query.get(key) for key, value in selected.items() if key != "pathIndices"
    ):
        raise ValueError(f"Changed reviewed query: {project['project']} {query['id']}")
    paths = []
    for index in selected["pathIndices"]:
        path = project["paths"][index]
        if len(path["transitions"]) != len(path["elements"]) - 1:
            raise ValueError(f"Incomplete transition review: {query['id']}")
        nodes = []
        for node_id, context, visible, output, edge, field in path["elements"]:
            node = dict(project["nodes"][node_id], nodeId=node_id)
            if "call" in node:
                node["call"] = project["calls"][node["call"]]
            if "argumentsOf" in node:
                node["argumentsOf"] = [
                    dict(project["calls"][call], argumentIndex=argument) for call, argument in node["argumentsOf"]
                ]
            node.update(
                callSiteStack=[project["calls"][call] for call in project["contexts"][context]],
                visible=visible, isOutputArg=output, outEdgeLabel=edge, fieldDemand=field, storageDemands=[],
            )
            nodes.append(node)
        paths.append(nodes)
    if sorted(map(digest, paths)) != sorted(map(digest, query["detailedWitnesses"])):
        raise ValueError(f"Changed reviewed paths: {project['project']} {query['id']}")


def summarize():
    projects = []
    fingerprints = set()
    forwarding = json.loads((FRONTEND / "corpus" / FORWARDING_REVIEW).read_text())
    if forwarding["schemaVersion"] != 2:
        raise ValueError("Unsupported forwarding review schema")
    for project in forwarding["projects"]:
        for path in project["paths"]:
            if any(category not in forwarding["transitionClasses"] for category in path["transitions"]):
                raise ValueError(f"Unknown transition classification: {project['project']}")
    for oracle in forwarding["executionOracles"]:
        if "sha256" in oracle and hashlib.sha256((FRONTEND / oracle["file"]).read_bytes()).hexdigest() != oracle["sha256"]:
            raise ValueError(f"Changed execution oracle: {oracle['file']}")
    forwarding = {project["project"]: project for project in forwarding["projects"]}
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
            reviewed = forwarding.get(name)
            if reviewed and (
                reviewed["analysisSources"] != original["analysisSources"]
                or reviewed["source"] != alternatives["source"]
                or reviewed["exporter"] != alternatives["exporter"]
                or reviewed["modelFilesSha256"] != {
                    key: hashlib.sha256(value.encode()).hexdigest() for key, value in alternatives["modelFiles"].items()
                }
            ):
                raise ValueError(f"Stale forwarding review: {name}")
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
                    maxStaticStorageNodes=stock["maxStaticStorageNodes"],
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
                    reviewed_paths = [path["nodes"] for path in review["reviews"]]
                    observed = [[
                        {key: node[key] for key in keys} | {"callSiteStack": [
                            {"nodeId": call["nodeId"], "target": call["target"]} for call in node["callSiteStack"]
                        ]} for node in path
                    ] for path in stock["detailedWitnesses"]]
                    if sorted(map(digest, reviewed_paths)) != sorted(map(digest, observed)):
                        raise ValueError(f"Changed reviewed paths: {name} {stock['id']}")
                    checks[-1]["semanticReview"] = f"see {review_file}; direct capture and conservative output detour classified"
                elif reviewed:
                    review = next((query for query in reviewed["queries"] if query["id"] == stock["id"]), None)
                    if review:
                        for mode, query in [("stock", stock), ("modeled", modeled)]:
                            validate_forwarding_review(reviewed, review, mode, query)
                        checks[-1]["semanticReview"] = f"see {FORWARDING_REVIEW}; {review['disposition']}"
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
