"""Build or smoke-test a host-native exporter; never fetch project dependencies during scans."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

FRONTEND = Path(__file__).resolve().parents[1]
ROOT = FRONTEND.parents[2]


def run(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, help="Smoke-test an existing standalone Sbt stage")
    args = parser.parse_args()
    sdk = Path(os.environ["DART_SDK"]).resolve()
    dart = sdk / "bin" / ("dart.exe" if os.name == "nt" else "dart")
    binary = "dart_astgen.exe" if os.name == "nt" else "dart_astgen"
    if args.stage:
        exporter = args.stage.resolve() / "bin" / binary
    else:
        run(dart, "pub", "get", "--enforce-lockfile", cwd=FRONTEND / "astgen")
        (FRONTEND / "bin").mkdir(exist_ok=True)
        exporter = FRONTEND / "bin" / binary
        run(dart, "compile", "exe", FRONTEND / "astgen/bin/dart_astgen.dart", "-o", exporter)
    (ROOT / "agents").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dart package λ ", dir=ROOT / "agents") as scratch:
        stage = args.stage
        if stage:
            archive = shutil.make_archive(str(ROOT / "agents/dart-stage"), "gztar", root_dir=stage)
            stage = Path(scratch) / "installation"
            with tarfile.open(archive, "r:gz") as package:
                package.extractall(stage, filter="data")
            exporter = stage / "bin" / binary
            for model in (FRONTEND / "dataflow").glob("*.semantics"):
                assert (stage / "dataflow" / model.name).read_bytes() == model.read_bytes(), model.name
        project = Path(scratch) / "project"
        shutil.copytree(FRONTEND / "src/test/resources/dataflow", project, dirs_exist_ok=True)
        result = run(exporter, project, project, sdk, "--metrics", capture_output=True, encoding="utf-8")
        records = [json.loads(line) for line in result.stdout.splitlines()]
        assert records[0]["sdkVersion"] == "3.9.2"
        assert records[-1]["files"] == 2
        assert all(unit["status"] == "resolved" for unit in records[1:-1])
        assert records[-1]["peakRssBytes"] > 0
        if args.stage:
            launcher = stage / "bin" / ("dartsrc2cpg.bat" if os.name == "nt" else "dartsrc2cpg")
            run(
                launcher, project, "--dart-sdk", sdk,
                "--dart-report", project / "report.json", "-o", project / "cpg.bin",
                env={**os.environ, "DART_ASTGEN": str(exporter)},
            )
            assert (project / "cpg.bin").stat().st_size > 0
            assert json.loads((project / "report.json").read_text())["includedFiles"] == 2
    print("Native exporter smoke test passed")


if __name__ == "__main__":
    main()
