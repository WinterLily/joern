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
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--prepare", action="store_true", help="Allow downloading the pinned archives")
    parser.add_argument("--output", type=Path, default=ROOT / "agents/dart-corpus-results.json")
    parser.add_argument("--check", action="store_true", help="Enforce the measured coverage and resource budgets")
    source.add_argument("--from-cache", type=Path, help="Use a pub cache instead of downloading archives")
    parser.add_argument("--prepare-only", action="store_true", help="Prepare sources and dependencies without measuring")
    args = parser.parse_args()
    sdk = Path(os.environ["DART_SDK"]).resolve()
    binary = "dart_astgen.exe" if os.name == "nt" else "dart_astgen"
    exporter = Path(os.environ.get("DART_ASTGEN", FRONTEND / "bin" / binary))
    projects = json.loads((FRONTEND / "corpus/projects.json").read_text())
    baseline = json.loads((FRONTEND / "corpus/baseline.json").read_text()) if args.check else None
    dependencies = ROOT / "agents/dart-corpus/dependencies"
    dependencies.mkdir(parents=True, exist_ok=True)
    (dependencies / "pubspec.yaml").write_text(
        "name: dart_corpus_dependencies\nenvironment:\n  sdk: ^3.9.2\ndependencies:\n" +
        "".join(f"  {p['name']}: {p['version']}\n" for p in projects)
    )
    shutil.copyfile(FRONTEND / "corpus/pubspec.lock", dependencies / "pubspec.lock")
    dart = sdk / "bin" / ("dart.exe" if os.name == "nt" else "dart")
    environment = os.environ.copy()
    if args.from_cache:
        args.from_cache = args.from_cache.resolve()
        environment["PUB_CACHE"] = str(args.from_cache)
    subprocess.run(
        [str(dart), "pub", "get", *([] if args.prepare else ["--offline"]), "--enforce-lockfile", "--no-example"],
        cwd=dependencies, env=environment, check=True, timeout=300,
    )
    package_config = json.loads((dependencies / ".dart_tool/package_config.json").read_text())
    package_config["packages"] = [p for p in package_config["packages"] if p["name"] != "dart_corpus_dependencies"]
    results = []
    for project in projects:
        name, version = project["name"], project["version"]
        directory = ROOT / "agents/dart-corpus" / f"{name}-{version}"
        archive = directory.parent / f"{name}-{version}.tar.gz"
        if args.prepare:
            archive.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(f"https://pub.dev/api/archives/{name}-{version}.tar.gz", timeout=60) as response:
                archive.write_bytes(response.read())
        if directory.exists():
            shutil.rmtree(directory)
        if args.from_cache:
            shutil.copytree(args.from_cache / "hosted/pub.dev" / f"{name}-{version}", directory)
        else:
            data = archive.read_bytes()
            assert hashlib.sha256(data).hexdigest() == project["sha256"], f"Archive checksum mismatch: {name}"
            directory.mkdir(parents=True)
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as package:
                package.extractall(directory, filter="data")
        digest = hashlib.sha256()
        for source in sorted((directory / "lib").rglob("*")):
            if source.is_file():
                digest.update(source.relative_to(directory).as_posix().encode() + b"\0" + source.read_bytes() + b"\0")
        assert digest.hexdigest() == project["librarySha256"], f"Source checksum mismatch: {name}"
        config = directory / ".dart_tool/package_config.json"
        config.parent.mkdir(exist_ok=True)
        resolved = json.loads(json.dumps(package_config))
        for package in resolved["packages"]:
            if package["name"] == name:
                package["rootUri"] = directory.as_uri()
        config.write_text(json.dumps(resolved))
        if args.prepare_only:
            continue
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
    if args.prepare_only:
        return
    args.output.write_text(json.dumps({
        "platform": platform.platform(), "resourceBudgetMultiplier": 5, "projects": results,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
