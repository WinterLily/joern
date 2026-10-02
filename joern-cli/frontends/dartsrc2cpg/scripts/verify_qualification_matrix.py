"""Validate declared test links and open obligations in the Dart stage matrix."""
import hashlib

from verify_conformance_sources import CONFORMANCE, read, require, verify as verify_sources

STAGES = {"exporter", "lowering", "resolution", "cfg", "valueFlow", "negative", "interaction"}
STATUSES = {"unqualified", "unsupported", "structural-only", "tested-conservative", "not-applicable"}


def verify():
    verify_sources()
    inventory_file = CONFORMANCE / "inventory.json"
    inventory = read(inventory_file)
    index = read(CONFORMANCE / "source-index.json")
    matrix = read(CONFORMANCE / "qualification-matrix.json")
    require(matrix["schemaVersion"] == 1 and matrix["sdk"] == inventory["sdk"]
            and matrix["sdkRevision"] == inventory["sdkRevision"] and matrix["analyzer"] == inventory["analyzer"],
            "Mixed matrix version/pins")
    require(matrix["inventorySha256"] == hashlib.sha256(inventory_file.read_bytes()).hexdigest()
            and matrix["sourceIndexSha256"] == inventory["sourceIndex"]["sha256"], "Stale matrix inventory")
    require(set(matrix["stages"]) == STAGES and len(matrix["stages"]) == len(STAGES)
            and set(matrix["statuses"]) == STATUSES and len(matrix["statuses"]) == len(STATUSES),
            "Missing matrix stage/status")
    evidence = matrix["evidence"]
    for name, item in evidence.items():
        file = CONFORMANCE.parent / item["file"]
        require(file.resolve().is_relative_to(CONFORMANCE.parent) and file.is_file(), f"Missing evidence: {name}")
        source = file.read_text()
        require(hashlib.sha256(file.read_bytes()).hexdigest() == item["sha256"]
                and source.count(item["selector"]) == 1 and item["scope"], f"Changed test evidence: {name}")
        require(item["stages"] and set(item["stages"]) <= STAGES, f"Unknown test role: {name}")
    profiles = matrix["profiles"]
    require(set(profiles) == set(inventory["contracts"]) | {"rule-obligations", "document-context"},
            "Missing contract profile")
    for name, profile in profiles.items():
        require(set(profile) == STAGES, f"Missing profile stage: {name}")
        for stage, cell in profile.items():
            require(cell["status"] in STATUSES and cell["obligation"], f"Missing stage disposition: {name}/{stage}")
            require(set(cell["evidence"]) <= evidence.keys(), f"Unknown stage evidence: {name}/{stage}")
            require(all(stage in evidence[e]["stages"] for e in cell["evidence"]),
                    f"Wrong test role: {name}/{stage}")
            if cell["status"] == "tested-conservative":
                require(cell["evidence"], f"Untested stage promoted: {name}/{stage}")
    visitors = matrix["visitors"]
    expected = {r["construct"]: r for r in inventory["constructs"]}
    require({r["construct"] for r in visitors} == expected.keys() and len(visitors) == len(expected),
            "Missing visitor matrix row")
    for row in visitors:
        old = expected[row["construct"]]
        require(row["profile"] == old["contract"] and row["profile"] in profiles
                and all(row[k] == old[k] for k in ["scope", "qualification", "exporterCase"]),
                f"Changed visitor contract: {row['construct']}")
    contexts = {g["section"] for g in inventory["specificationGaps"] if g["kind"] == "document-context"}
    for rows, key, expected_sources in [
        (matrix["rules"], "section", index["language"]["baseSpecification"]["sections"]),
        (matrix["features"], "file", [f["path"] for f in index["language"]["featureSpecifications"]]),
    ]:
        require({r[key] for r in rows} == set(expected_sources) and len(rows) == len(expected_sources),
                f"Missing {key} matrix row")
        for row in rows:
            profile = "document-context" if key == "section" and row[key] in contexts else "rule-obligations"
            require(row["profile"] == profile and row["scope"], f"Promoted complete rule: {row[key]}")
            related = [r for r in inventory["constructs"]
                       if row[key] in r["references"]["sections" if key == "section" else "features"]]
            require(row["relatedConstructs"] == [r["construct"] for r in related]
                    and row["relatedProfiles"] == sorted({r["contract"] for r in related}),
                    f"Changed related evidence: {row[key]}")
    require(all(c["status"] == "unqualified" for c in profiles["rule-obligations"].values())
            and all(c["status"] == "not-applicable" for c in profiles["document-context"].values()),
            "Generic rule obligations promoted without rule-specific review")
    require(all(c["status"] == ("not-applicable" if stage == "interaction" else "unsupported")
                for stage, c in profiles["outside"].items()), "Outside-scope contract promoted")
    return len(visitors), len(matrix["rules"]), len(matrix["features"]), len(evidence)


if __name__ == "__main__":
    visitors, rules, features, evidence = verify()
    print(f"{visitors} visitor rows, {rules} specification rows, {features} feature rows, "
          f"{evidence} named test links; complete-rule and release qualification remain open")
