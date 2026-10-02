"""Verify reference provenance without treating SDK candidates as executed tests."""
import argparse
import hashlib
import json
import re
import urllib.request
from pathlib import Path

CONFORMANCE = Path(__file__).resolve().parents[1] / "conformance"


def read(path):
    return json.loads(path.read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fetch(source_root):
    index = read(CONFORMANCE / "source-index.json")
    source_root = source_root.resolve()
    agents = CONFORMANCE.parents[3] / "agents"
    require(source_root.is_relative_to(agents), "Downloads must be under the repository agents/ directory")

    def download(url, relative):
        target = source_root / relative
        if not target.exists():
            request = urllib.request.Request(url, headers={"User-Agent": "dartsrc2cpg-conformance"})
            content = urllib.request.urlopen(request, timeout=30).read()
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        return target

    sdk = index["sdk"]
    api = "https://api.github.com/repos/dart-lang/sdk/git/trees/"
    root = read(download(api + sdk["revision"], "sdk-root-tree.json"))
    tests = next(p["sha"] for p in root["tree"] if p["path"] == "tests")
    download(api + tests, "sdk-tests-tree.json")
    download(api + sdk["languageTree"] + "?recursive=1", "sdk-language-tree.json")
    language = index["language"]
    revision = language["revision"]
    download("https://api.github.com/repos/dart-lang/language/git/trees/" + revision + "?recursive=1",
             "language-tree.json")
    for spec in [language["baseSpecification"], *language["featureSpecifications"]]:
        download("https://raw.githubusercontent.com/dart-lang/language/" + revision + "/" + spec["path"],
                 spec["path"])


def verify(source_root=None):
    inventory = read(CONFORMANCE / "inventory.json")
    reference = inventory["sourceIndex"]
    index_file = CONFORMANCE / reference["file"]
    require(hashlib.sha256(index_file.read_bytes()).hexdigest() == reference["sha256"], "Changed source index")
    index = read(index_file)
    sdk, language = index["sdk"], index["language"]
    require(sdk["version"] == inventory["sdk"] and sdk["revision"] == inventory["sdkRevision"], "Mixed SDK pins")
    cases = dict(sdk["candidateCases"])
    require(len(cases) == len(sdk["candidateCases"]) and all(
        p.endswith("_test.dart") and re.fullmatch(r"[0-9a-f]{40}", sha) for p, sha in cases.items()
    ), "Invalid SDK candidate index")
    sections = language["baseSpecification"]["sections"]
    require(len(set(sections)) == len(sections), "Duplicate specification section")
    features = {p["path"]: p for p in language["featureSpecifications"]}
    require(len(features) == len(language["featureSpecifications"]), "Duplicate feature specification")
    for row in inventory["constructs"]:
        refs = row["references"]
        require(set(refs["sections"]) <= set(sections) and set(refs["features"]) <= features.keys()
                and set(refs["sdkCandidateCases"]) <= cases.keys(), f"Unknown source reference: {row['construct']}")
        if row["scope"] == "dart-3.9":
            require((refs["sections"] or refs["features"]) and refs["sdkCandidateCases"]
                    and refs["status"] == "reference-only; candidate cases unreviewed and not executed",
                    f"Missing reference or promoted candidate: {row['construct']}")
        else:
            require(not any(refs[k] for k in ["sections", "features", "sdkCandidateCases"])
                    and refs["status"].startswith("outside pinned public language scope"),
                    f"Enabled outside-scope construct: {row['construct']}")
    referenced = {s for row in inventory["constructs"] for s in row["references"]["sections"]}
    gaps = inventory["specificationGaps"]
    require({g["section"] for g in gaps} == set(sections) - referenced
            and len({g["section"] for g in gaps}) == len(gaps)
            and all(g["reason"] and ((g["kind"] == "semantic-rule" and g["qualification"] == "unqualified")
                                    or (g["kind"] == "document-context" and g["qualification"] == "not-applicable"))
                    for g in gaps),
            "Missing or promoted specification obligations")
    require({f for row in inventory["constructs"] for f in row["references"]["features"]} == features.keys(),
            "Unmapped feature specification")
    if source_root:
        sdk_tree = read(source_root / "sdk-language-tree.json")
        require(not sdk_tree["truncated"] and sdk_tree["sha"] == sdk["languageTree"], "Incomplete SDK tree")
        require(cases == {p["path"]: p["sha"] for p in sdk_tree["tree"]
                          if p["type"] == "blob" and p["path"].endswith("_test.dart")}, "Missing SDK cases")
        require(read(source_root / "sdk-root-tree.json")["sha"] == sdk["revision"], "Changed SDK root")
        root_entries = read(source_root / "sdk-root-tree.json")["tree"]
        tests_tree = read(source_root / "sdk-tests-tree.json")
        require(next(p["sha"] for p in root_entries if p["path"] == "tests") == tests_tree["sha"]
                and next(p["sha"] for p in tests_tree["tree"] if p["path"] == "language") == sdk_tree["sha"],
                "Changed SDK language tree binding")
        language_tree = read(source_root / "language-tree.json")
        require(not language_tree["truncated"] and language_tree["sha"] == language["revision"],
                "Changed language reference revision")
        blobs = {p["path"]: p["sha"] for p in language_tree["tree"] if p["type"] == "blob"}
        for spec in [language["baseSpecification"], *features.values()]:
            content = (source_root / spec["path"]).read_bytes()
            blob = hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()
            require(hashlib.sha256(content).hexdigest() == spec["sha256"] and blob == blobs[spec["path"]],
                    f"Changed pinned specification: {spec['path']}")
        content = (source_root / language["baseSpecification"]["path"]).read_text()
        observed = re.findall(r"\\(?:chapter|section|subsection|subsubsection)\{[^\n]+\}\s*\\LMLabel\{([^}]+)\}", content)
        require(observed == sections, "Missing specification sections")
    return len(inventory["constructs"]), len(sections), len(features), len(cases)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, help="Downloaded pinned trees/specifications under agents/")
    parser.add_argument("--fetch", action="store_true", help="Fetch missing immutable evidence into --source-root")
    args = parser.parse_args()
    if args.fetch:
        if not args.source_root:
            parser.error("--fetch requires --source-root under agents/")
        fetch(args.source_root)
    visitors, sections, features, cases = verify(args.source_root)
    print(f"{visitors} visitor references, {sections} base sections, {features} feature documents, "
          f"{cases} SDK candidate cases; semantic qualification remains separate")


if __name__ == "__main__":
    main()
