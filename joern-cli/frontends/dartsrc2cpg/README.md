# Dart source frontend

`dartsrc2cpg` uses a pinned Dart analyzer exporter to construct CPGs for core
Dart source semantics: libraries, classes, constructors, functions and closures,
parameters, control flow, null-aware expressions, cascades and collections.
Tests cover graph structure, resolution, CFGs, positive/negative dataflow and
saving/reopening graphs. Unsupported syntax is reported and retained as UNKNOWN.

See [core graph conventions and coverage](SEMANTICS.md) for lowering decisions,
test coverage and analysis limits.

## Build and test

Use JDK 21, the repository's Sbt **2.0.9**, and Dart **3.9.2** with analyzer
**8.4.1**. Dependencies are locked. From the repository root:

```sh
export DART_SDK=/absolute/path/to/dart-sdk
export PATH="$DART_SDK/bin:$PATH"
(cd joern-cli/frontends/dartsrc2cpg/astgen && dart pub get --enforce-lockfile)
mkdir -p joern-cli/frontends/dartsrc2cpg/bin
dart compile exe joern-cli/frontends/dartsrc2cpg/astgen/bin/dart_astgen.dart \
  -o joern-cli/frontends/dartsrc2cpg/bin/dart_astgen
export DART_ASTGEN="$PWD/joern-cli/frontends/dartsrc2cpg/bin/dart_astgen"
sbt 'dartsrc2cpg/test' 'dartsrc2cpg/stage'
(cd joern-cli/frontends/dartsrc2cpg/astgen && dart analyze && dart test)
```

On Windows, compile as `bin/dart_astgen.exe`. Staging packages the native exporter;
it still requires the analysis SDK at runtime. Dependency installation and native
compilation are explicit preparation steps, never scan side effects. Only Linux
x86-64 staging has been exercised. Cross-platform releases remain milestone 5 work.

Test the staged frontend through `joern-parse` and the real console import helper:

```sh
export DART_FRONTEND_STAGE="$PWD/joern-cli/frontends/dartsrc2cpg/target/universal/stage"
sbt 'joerncli/testOnly *DartIntegrationTests *JoernParseTests' \
    'console/testOnly *LanguageHelperTests'
```

`DartIntegrationTests` creates an isolated installation under `agents/`, using the
staged frontend and distribution launcher. Its staged import test is canceled when
`DART_FRONTEND_STAGE` is absent. Frontend tests require the exporter and SDK; they
accept `DART_ASTGEN` and `DART_SDK`, falling back to local repository development
paths. Temporary test projects live under `agents/` and are removed after each test.

The frontend is registered in the root Sbt build and Joern distribution mappings.
The complete distribution uses the repository's usual `sbt stage` build path.

## Acceptance fixture and query

The committed package is [src/test/resources/dataflow](src/test/resources/dataflow).
Run the standalone staged CLI:

```sh
joern-cli/frontends/dartsrc2cpg/target/universal/stage/bin/dartsrc2cpg \
  joern-cli/frontends/dartsrc2cpg/src/test/resources/dataflow \
  --dart-sdk "$DART_SDK" -o agents/dart-proof.bin
```

In a full Joern distribution, detection and explicit selection are supported:

```sh
./joern-parse /absolute/path/to/dataflow --language dart -o /absolute/path/to/cpg.bin
```

The console helper applies default overlays; add OSS dataflow and query:

```scala
importCode.dart("/absolute/path/to/dataflow")
run.ossdataflow
cpg.call.nameExact("sink").argument.reachableByFlows(cpg.identifier.nameExact("input")).p
```

`DartFrontendTests` asserts a path containing the `relay` parameter, resolution to
`lib/helper.dart`, and the same query after graph reload. Passing `'constant'` to
`relay`, passing `input` to an unused named parameter, or overwriting the forwarded
local must produce no path.

## Graph conventions

- The shared `DartLanguage.Name` is `DART`. CPG 1.7.78 has no generated Dart
  constant; replace this integration identifier when an upstream constant becomes
  available. No schema fork is required.
- Exported declaration identities become method full names and call targets.
  They are relative to the package root. Single-file scans discover the nearest
  `pubspec.yaml` to preserve context.
- Parameters are indexed from one. Argument `order` follows source evaluation
  order; `argumentIndex` follows exported parameter bindings. Named arguments also
  carry `argumentName`. This satisfies the engine's index-based call-boundary lookup
  without changing CFG evaluation order. Reordered-argument regressions cover both
  flow and absence of flow from an unused parameter.
- Source snippets use UTF-16 offsets, matching JVM strings. Line and column numbers
  are one-based; columns remain UTF-16 code units. A non-BMP literal preceding a
  call is covered at the Scala boundary.
- Tests enable AST schema validation, post-frontend validation at V3, default
  overlays, and optional OSS dataflow.

## Exporter and limitations

See [the JSON Lines protocol](astgen/PROTOCOL.md). The runner rejects incompatible
protocol/SDK/analyzer versions and missing summary records. Source diagnostics
produce partial graphs and are logged. Scans use existing package configuration
and never fetch dependencies, build applications, or generate code. Existing
generated Dart files are included; `.git`, `.dart_tool`, and directory symlinks
are skipped by the exporter.

Core language modeling is implemented; this is not general Dart/Flutter runtime
analysis. Unknown dynamic calls, arbitrary mutable function values, external
library effects, precise exception/finally routing and lazy initializer scheduling
have the limitations documented in [SEMANTICS.md](SEMANTICS.md). Modern patterns,
records, async behavior and Flutter framework semantics remain later milestones.
The Flutter-style exporter fixture tests partial output without a Flutter SDK;
resolved Flutter analysis remains untested. Resource limits, crash recovery, full
analysis-option exclusions, and release infrastructure ownership remain future work.
