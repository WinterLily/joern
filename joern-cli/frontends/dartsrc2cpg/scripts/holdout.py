"""Prepare the reserved server/parser holdout with pinned source roots and dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

FRONTEND = Path(__file__).resolve().parents[1]
ROOT = FRONTEND.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--from-cache", type=Path)
    source.add_argument("--prepare", action="store_true", help="Allow pinned archive and dependency downloads")
    args = parser.parse_args()
    corpus = FRONTEND / "corpus/holdout"
    projects = json.loads((corpus / "projects.json").read_text())
    scratch = ROOT / "agents/dart-holdout"
    dependencies = scratch / "dependencies"
    dependencies.mkdir(parents=True, exist_ok=True)
    (dependencies / "pubspec.yaml").write_text(
        "name: dart_holdout_dependencies\nenvironment:\n  sdk: ^3.9.2\ndependencies:\n"
        + "".join(f"  {p['name']}: {p['version']}\n" for p in projects)
    )
    shutil.copyfile(corpus / "pubspec.lock", dependencies / "pubspec.lock")
    environment = os.environ.copy()
    if args.from_cache:
        args.from_cache = args.from_cache.resolve()
        environment["PUB_CACHE"] = str(args.from_cache)
    dart = Path(os.environ["DART_SDK"]) / "bin" / ("dart.exe" if os.name == "nt" else "dart")
    subprocess.run(
        [str(dart.resolve()), "pub", "get", "--enforce-lockfile", "--no-example", *([] if args.prepare else ["--offline"])],
        cwd=dependencies, env=environment, check=True, timeout=300,
    )
    config = json.loads((dependencies / ".dart_tool/package_config.json").read_text())
    config["packages"] = [p for p in config["packages"] if p["name"] != "dart_holdout_dependencies"]
    for project in projects:
        name = f"{project['name']}-{project['version']}"
        with tempfile.TemporaryDirectory(prefix="holdout-", dir=scratch) as temporary:
            if args.prepare:
                archive = Path(temporary) / "source.tar.gz"
                with urllib.request.urlopen(f"https://pub.dev/api/archives/{name}.tar.gz", timeout=60) as response:
                    archive.write_bytes(response.read())
                if hashlib.sha256(archive.read_bytes()).hexdigest() != project["sha256"]:
                    raise ValueError(f"Archive checksum mismatch: {name}")
                source_root = Path(temporary) / "source"
                with tarfile.open(archive) as package:
                    package.extractall(source_root, filter="data")
            else:
                source_root = args.from_cache / "hosted/pub.dev" / name
            digest = hashlib.sha256()
            files = [f for root in project["roots"] for f in (source_root / root).rglob("*") if f.is_file()]
            for file in sorted(files):
                digest.update(file.relative_to(source_root).as_posix().encode() + b"\0" + file.read_bytes() + b"\0")
            if digest.hexdigest() != project["sourceSha256"]:
                raise ValueError(f"Source checksum mismatch: {name}")
            destination = scratch / name
            if destination.exists():
                shutil.rmtree(destination)
            destination.mkdir()
            for root in project["roots"]:
                shutil.copytree(source_root / root, destination / root)
            for metadata in ("pubspec.yaml", "LICENSE", "AUTHORS"):
                if (source_root / metadata).exists():
                    shutil.copyfile(source_root / metadata, destination / metadata)
            resolved = json.loads(json.dumps(config))
            for package in resolved["packages"]:
                if package["name"] == project["name"]:
                    package["rootUri"] = destination.as_uri()
            (destination / ".dart_tool").mkdir()
            (destination / ".dart_tool/package_config.json").write_text(json.dumps(resolved))
            print(f"Prepared {name}: {', '.join(project['roots'])}")


if __name__ == "__main__":
    main()
