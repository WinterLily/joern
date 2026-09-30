#!/usr/bin/env python3
"""Prepare pinned application sources and dependencies, or verify their library hashes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

FRONTEND = Path(__file__).resolve().parents[1]
REPOSITORY = FRONTEND.parents[2]
CORPUS = FRONTEND / "corpus/applications"
SCRATCH = REPOSITORY / "agents/application-corpus"


def run(*args, cwd=None, env=None):
    subprocess.run(args, cwd=cwd, env=env, check=True)


def checkout(url, commit, target):
    if not target.exists():
        target.mkdir(parents=True)
        run("git", "init", "-q", str(target))
        run("git", "fetch", "--depth", "1", url, commit, cwd=target)
        run("git", "checkout", "--detach", "FETCH_HEAD", cwd=target)
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=target, text=True).strip()
    if actual != commit:
        raise ValueError(f"Unexpected checkout at {target}: {actual}")


def library_hash(root):
    digest = hashlib.sha256()
    for source in sorted((root / "lib").rglob("*")):
        if source.is_file():
            digest.update(source.relative_to(root).as_posix().encode() + b"\0" + source.read_bytes() + b"\0")
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare", action="store_true", help="Fetch sources, resolve dependencies and generate Sass protocol files")
    parser.add_argument("--dart-sdk", type=Path, default=REPOSITORY / "agents/toolchains/dart-sdk")
    parser.add_argument("--flutter", type=Path, default=REPOSITORY / "agents/toolchains/flutter")
    args = parser.parse_args()
    dart = str(args.dart_sdk.resolve() / "bin/dart")
    projects = json.loads((CORPUS / "projects.json").read_text())
    SCRATCH.mkdir(parents=True, exist_ok=True)
    for project in projects:
        root = SCRATCH / project["checkout"]
        if args.prepare:
            checkout(project["repository"], project["commit"], SCRATCH / project["name"])
            flutter = args.flutter.resolve()
            if project["name"] == "localsend":
                spec = project["flutter"]
                flutter = SCRATCH / "flutter-localsend"
                checkout("https://github.com/flutter/flutter", spec["commit"], flutter)
                cache = flutter / "bin/cache"
                cache.mkdir(exist_ok=True)
                # Pub only needs framework sources and SDK metadata; analysis uses the supplied Dart SDK.
                (cache / "flutter.version.json").write_text(json.dumps({
                    "frameworkVersion": spec["version"], "flutterVersion": spec["version"],
                    "frameworkRevision": spec["commit"], "engineRevision": spec["engine"],
                }))
                archive = SCRATCH / "sky_engine.zip"
                if not archive.exists():
                    urllib.request.urlretrieve(
                        f"https://storage.googleapis.com/flutter_infra_release/flutter/{spec['engine']}/sky_engine.zip", archive)
                if hashlib.sha256(archive.read_bytes()).hexdigest() != spec["skyEngineSha256"]:
                    raise ValueError("Sky engine archive checksum mismatch")
                with zipfile.ZipFile(archive) as bundle:
                    bundle.extractall(cache / "pkg")
            elif project["name"] == "saber":
                metadata = json.loads((flutter / "bin/cache/flutter.version.json").read_text())
                if metadata["frameworkVersion"] != project["flutter"]["version"]:
                    raise ValueError("Saber requires the pinned Flutter framework version")
            shutil.copyfile(CORPUS / "locks" / f"{project['name']}.lock", root / "pubspec.lock")
            env = dict(os.environ, FLUTTER_ROOT=str(flutter))
            run(dart, "pub", "get", "--enforce-lockfile", cwd=root, env=env)
            if "protocol" in project:
                spec = project["protocol"]
                if subprocess.check_output(["protoc", "--version"], text=True).strip() != f"libprotoc {spec['protoc']}":
                    raise ValueError("Use the pinned protoc version for reproducible generated sources")
                language = root / "build/language"
                checkout(spec["repository"], spec["commit"], language)
                plugin = root / "build/protoc-gen-dart"
                # JSON quoting is Python source here, never shell command quoting.
                plugin.write_text("#!/usr/bin/env python3\nimport os\nos.chdir(" + json.dumps(str(root)) +
                                  ")\nos.execv(" + json.dumps(dart) + ", [" + json.dumps(dart) +
                                  ", 'run', 'protoc_plugin'] + __import__('sys').argv[1:])\n")
                plugin.chmod(0o755)
                run("protoc", f"-I{language / 'spec'}", f"--plugin=protoc-gen-dart={plugin}",
                    f"--dart_out={root / 'lib/src/embedded'}", str(language / "spec/embedded_sass.proto"), cwd=root)
        actual = library_hash(root)
        if actual != project["librarySha256"]:
            raise ValueError(f"Library checksum mismatch for {project['name']}: {actual}")
        print(f"{project['name']}: pinned library hash verified", flush=True)


if __name__ == "__main__":
    main()
