# Distribution and operational checks

The native exporter is built per host with Dart 3.9.2, analyzer 8.4.1, exporter
0.3.18, protocol 1 and JDK 21. Linux x86-64 has been verified locally. Linux
arm64, macOS x86-64/arm64 and Windows x86-64 require local validation before
claiming release support. SDK resources are **not** embedded:
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
CPG, reopen it and repeat the query. Local integration tests also exercise
`joern-parse` and console import through an isolated installation. Prepare the
native exporter explicitly before staging; CI and release workflow changes are
outside this frontend implementation's scope.

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

The seven pinned OSS packages and graph audit results are described in
[corpus/README.md](corpus/README.md). Sources are pinned by archive and library
SHA-256, with transitive analysis dependencies locked separately. Only production
`lib/` sources are scanned. Preparation is explicit and ordinary tests are offline.

```sh
python joern-cli/frontends/dartsrc2cpg/scripts/corpus.py --prepare --check
export DART_CORPUS_TESTS=1
export DART_ASTGEN="$PWD/joern-cli/frontends/dartsrc2cpg/bin/dart_astgen"
sbt 'dartsrc2cpg/testOnly *DartCorpusTests'
```

Use `--from-cache "$HOME/.pub-cache"` instead of `--prepare` for offline preparation.
Each preparation replaces scratch sources under `agents/dart-corpus`; run it
before graph tests, never concurrently. `--prepare-only` skips native measurements.
`--check` requires identical exporter coverage counts and allows five times the
recorded elapsed time and peak RSS. These are coarse regression alarms, not
throughput guarantees. Update baselines only after reviewing changed counts.

The graph suite validates schema and V3 invariants, saves/reloads each CPG, and
walks references, argument bindings, method identities, internal targets and CFG
boundaries. It rejects unexpected executable UNKNOWN nodes. The separately
pinned Flutter fixture remains documented in
[src/test/resources/flutter/README.md](src/test/resources/flutter/README.md).

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

The corpus suite also creates and reloads OSS dataflow overlays for every graph,
checks per-method definition budgets, and executes the committed positive/negative
queries with stock semantics and the optional `async`, `worker_manager`,
`iterable` and `bytes` models. The staged distribution includes all four models; see
[RUNTIME_SUMMARIES.md](RUNTIME_SUMMARIES.md) for explicit query-time loading.

The [application corpus](corpus/applications/README.md) adds pinned LocalSend,
Saber and Dart Sass checkouts with dependency locks and explicit protobuf
preparation. Run it with `DART_APPLICATION_TESTS=1`; retain `DART_CORPUS_TESTS=1`
for the original library corpus in a separate invocation. The staged frontend
also includes the optional `dataflow/worker_manager.semantics` summary.
