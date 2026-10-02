"""Check adapted SDK cases against their pinned source identities and diagnostics."""
import hashlib
import re

from verify_conformance_sources import CONFORMANCE, read, require


def verify():
    sdk = read(CONFORMANCE / "source-index.json")["sdk"]
    upstream = CONFORMANCE / "upstream"
    manifest = read(upstream / "manifest.json")
    require(manifest["sdk"] == sdk["version"] and manifest["revision"] == sdk["revision"]
            and manifest["repository"] == sdk["repository"], "Mixed upstream SDK pins")
    require(all((upstream / manifest[name]).is_file() for name in ["license", "authors"]),
            "Missing upstream license/authors")
    candidates = dict(sdk["candidateCases"])
    cases = manifest["cases"]
    require(cases and len({case["file"] for case in cases}) == len(cases), "Duplicate or missing adapted case")
    for case in cases:
        file = upstream / case["file"]
        require(file.resolve().is_relative_to(upstream.resolve()) and file.is_file(), "Missing adapted source")
        relative = case["upstreamPath"].removeprefix(sdk["caseRoot"] + "/")
        require(relative in candidates and case["upstreamGitBlob"] == candidates[relative],
                "Unknown or changed SDK case identity")
        require(re.fullmatch(r"[0-9a-f]{64}", case["upstreamSha256"])
                and hashlib.sha256(file.read_bytes()).hexdigest() == case["adaptedSha256"],
                "Changed adapted source/hash")
        require(case["adaptation"] and case["qualification"], "Missing adapted case scope")
        classification = case["classification"]
        require(classification in {"valid", "deliberate-diagnostic"}, "Unknown case classification")
        diagnostics = case.get("expectedDiagnostics", [])
        require(isinstance(diagnostics, list) and len(set(diagnostics)) == len(diagnostics)
                and all(isinstance(code, str) and re.fullmatch(r"[a-z_]+", code) for code in diagnostics)
                and bool(diagnostics) == (classification == "deliberate-diagnostic"),
                "Missing or contradictory diagnostic contract")
    return len(cases)


if __name__ == "__main__":
    print(f"{verify()} pinned adapted cases; runtime and graph qualification remain separate")
