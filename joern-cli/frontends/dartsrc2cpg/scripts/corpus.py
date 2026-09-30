"""Prepare hash-pinned pub.dev sources explicitly, then measure native exporter regressions."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request

FRONTEND = Path(__file__).resolve().parents[1]
ROOT = FRONTEND.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare", action="store_true", help="Allow downloading the pinned archives")
    parser.add_argument("--output", type=Path, default=ROOT / "agents/dart-corpus-results.json")
    parser.add_argument("--check", action="store_true", help="Enforce the measured coverage and resource budgets")
    args = parser.parse_args()
    sdk = Path(os.environ["DART_SDK"]).resolve()
    binary = "dart_astgen.exe" if os.name == "nt" else "dart_astgen"
    exporter = Path(os.environ.get("DART_ASTGEN", FRONTEND / "bin" / binary))
    projects = json.loads((FRONTEND / "corpus/projects.json").read_text())
    baseline = json.loads((FRONTEND / "corpus/baseline.json").read_text()) if args.check else None
    results = []
    for project in projects:
        name, version = project["name"], project["version"]
        directory = ROOT / "agents/dart-corpus" / f"{name}-{version}"
        archive = directory.parent / f"{name}-{version}.tar.gz"
        if args.prepare:
            archive.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(f"https://pub.dev/api/archives/{name}-{version}.tar.gz", timeout=60) as response:
                archive.write_bytes(response.read())
        data = archive.read_bytes()
        assert hashlib.sha256(data).hexdigest() == project["sha256"], f"Archive checksum mismatch: {name}"
        # Re-extract verified inputs so local edits cannot silently alter a baseline.
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as package:
            package.extractall(directory, filter="data")
        config = directory / ".dart_tool/package_config.json"
        config.parent.mkdir()
        config.write_text(json.dumps({
            "configVersion": 2,
            "packages": [{
                "name": name, "rootUri": "../", "packageUri": "lib/", "languageVersion": "3.4",
            }],
        }))
        result = subprocess.run(
            [str(exporter), str(directory), str(directory / "lib"), str(sdk), "--metrics"],
            check=True, capture_output=True, encoding="utf-8", timeout=300,
        )
        records = [json.loads(line) for line in result.stdout.splitlines()]
        units = records[1:-1]
        assert records[-1]["files"] == len(units) and units
        nodes = [node for unit in units for node in unit["nodes"]]
        metrics = {
            "name": name,
            "version": version,
            "files": len(units),
            "nodes": len(nodes),
            "partialFiles": sum(unit["status"] != "resolved" for unit in units),
            "unsupportedConstructs": sum(node["kind"] == "Unsupported" for node in nodes),
            "unresolvedCalls": sum(
                node["kind"] in {
                    "MethodInvocation", "FunctionExpressionInvocation", "InstanceCreationExpression",
                } and not node.get("target")
                for node in nodes
            ),
            "elapsedMillis": records[-1]["elapsedMillis"],
            "peakRssBytes": records[-1]["peakRssBytes"],
        }
        if baseline:
            expected = next(item for item in baseline["projects"] if item["name"] == name)
            for key in ("files", "nodes", "partialFiles", "unsupportedConstructs", "unresolvedCalls"):
                assert metrics[key] == expected[key], f"{name}: changed {key}: {metrics[key]} != {expected[key]}"
            for key in ("elapsedMillis", "peakRssBytes"):
                limit = expected[key] * baseline["resourceBudgetMultiplier"]
                assert metrics[key] <= limit, f"{name}: exceeded {key} budget"
        results.append(metrics)
        print(json.dumps(metrics))
    args.output.write_text(json.dumps({
        "platform": platform.platform(), "resourceBudgetMultiplier": 5, "projects": results,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
