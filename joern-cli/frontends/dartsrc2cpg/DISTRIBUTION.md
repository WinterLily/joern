# Distribution and operational checks

The native exporter is built per host. CI targets Linux x86-64/arm64, macOS
x86-64/arm64 and Windows x86-64 with Dart 3.9.2, analyzer 8.4.1, exporter 0.3.0,
protocol 1 and JDK 21. Linux x86-64 has been verified locally; the other targets
require successful runs of `.github/workflows/dart.yml` before claiming release
validation. Windows arm64 distributions use the x86-64 exporter under emulation;
this is not a declared native Dart target. SDK resources are **not** embedded:
install the matching Dart SDK and set `DART_SDK`, or pass `--dart-sdk`.

## Build and install

From the repository root, with Python 3.12+, the pinned Dart SDK and Sbt installed:

```sh
export DART_SDK=/absolute/path/to/dart-sdk
python joern-cli/frontends/dartsrc2cpg/scripts/package.py
sbt 'dartsrc2cpg/test' 'dartsrc2cpg/stage'
python joern-cli/frontends/dartsrc2cpg/scripts/package.py \
  --stage joern-cli/frontends/dartsrc2cpg/target/universal/stage
```

The preparation script runs locked dependency installation, native compilation,
and a two-file exporter smoke test in a path containing spaces and Unicode. The
stage smoke test archives the stage, extracts it into a fresh installation path,
invokes the installed CLI and checks its graph and metrics. The archive is written
to `agents/dart-stage.tar.gz` and preserves executable permissions.
The Scala acceptance tests apply overlays, query cross-file dataflow, save the
CPG, reopen it and repeat the query. CI also exercises `joern-parse` and console
import through an isolated installation. Release workflows run the same native
preparation before Sbt distribution staging. CI uploads the standalone stage and
corpus measurements for each platform; it does not bundle an analysis SDK.

Copy the whole standalone stage, including `bin` and `lib`, to install it. Set
`DART_SDK` on the destination machine and invoke `bin/dartsrc2cpg` (or `.bat`).
`DART_ASTGEN` / `--dart-astgen` can override the bundled executable. The script
never downloads dependencies of the project being scanned.

## Resource limits and failures

`--dart-timeout` defaults to 300 seconds. `--dart-max-output-bytes` defaults to
256 MiB; exceeding it terminates the exporter. Stderr is drained concurrently
and retained up to 64 KiB for crash diagnostics. Process arguments are passed
without shell interpolation. Missing inputs, SDKs or executables, mismatched SDK
versions, crashes, malformed JSON, incompatible protocol/exporter versions,
truncated streams, duplicate file records and invalid file identities fail the
scan before graph construction. Exporter failures do not yield a successful
partial graph. Per-file resolution failures do: syntax and a diagnostic are
retained, and other files continue. Unreadable source produces an empty parsed
unit with a diagnostic. Catastrophic analyzer errors can still abort the scan.

Analysis-option exclusions can prevent resolution; they do not remove source
from the exported graph. Use Joern's `--exclude`/`--exclude-regex` options for
graph exclusions. These filters run after export, so excluded files still incur
analysis cost. `.git`, `.dart_tool` and directory symlinks are not traversed.

Use `--dart-report agents/scan.json` to write coverage and resource metrics:
exported/included files, files skipped by Joern filters, parsed fallback files,
partial files, unsupported AST nodes, unresolved invocation targets, elapsed
frontend milliseconds and native exporter peak RSS bytes. Unresolved calls count
syntax without an exported target, including function-value invocations; it is
not a count of missing CPG CALL edges. Elapsed time includes export and graph
construction, but excludes overlays. RSS measures the exporter process only,
not JVM memory. Hidden directory exclusions are not included in skippedFiles.
Metrics are also logged. Runtime observations are deliberately absent from the
default deterministic JSONL stream; the runner requests `--metrics` explicitly.

## Reproducible corpus

`corpus/projects.json` pins real pub.dev releases of `path` and `collection` by
version and archive SHA-256. Only `lib/` is scanned, with an explicit self-package
configuration and no dependency fetching. Prepare outside ordinary unit tests:

```sh
python joern-cli/frontends/dartsrc2cpg/scripts/corpus.py --prepare --check
export DART_CORPUS_TESTS=1
export DART_ASTGEN="$PWD/joern-cli/frontends/dartsrc2cpg/bin/dart_astgen"
sbt 'dartsrc2cpg/testOnly *DartCorpusTests'
```

Subsequent runs omit `--prepare` to use cached, checksum-verified archives under
`agents/dart-corpus`. Each run re-extracts the archive to discard local changes.
Corpus tests assert internal call resolution and named methods (`normalize`,
`binarySearch`) before and after graph reload. The separately pinned Flutter
3.35.3 fixture and its query assertions are documented in
[src/test/resources/flutter/README.md](src/test/resources/flutter/README.md).

`corpus/baseline.json` records Linux x86-64 native exporter measurements:
13 / 29 files, 6,084 / 16,422 AST nodes, no partial files, 1 / 8 unsupported nodes,
and 0 / 107 unresolved invocations. Initial elapsed times were 232 / 288 ms,
with peak RSS 112,533,504 / 105,852,928 bytes. CI requires identical structural
counts and allows five times the baseline elapsed time and memory to accommodate
host variation. These are coarse regression alarms, not throughput guarantees.
Update the baseline only after reviewing the changed counts and measurements.
The graph tests provide independent semantic assertions; exporter counts alone
are insufficient. Flutter timing is not included in these package baselines.

## Bazel

Bazel remains experimental, matching repository policy. The frontend provides
library, executable and test targets; native exporter preparation stays explicit:

```sh
bazel build //joern-cli/frontends/dartsrc2cpg:dartsrc2cpg-bin
bazel test //joern-cli/frontends/dartsrc2cpg:tests \
  --test_env=DART_TEST_REPOSITORY="$PWD" \
  --test_env=DART_SDK="$DART_SDK" --test_env=DART_ASTGEN="$DART_ASTGEN"
```

Tests are local and use the real checkout for fixtures and `agents/` scratch
space. Sbt remains the release packaging path. Bazel requires access to the
repository's pinned `bazel_tooling` Git dependency and Maven repositories.
