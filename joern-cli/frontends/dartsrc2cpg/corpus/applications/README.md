# Application and compiler corpus

This extends the small-library corpus with release checkouts of deployed software:
[LocalSend](https://github.com/localsend/localsend), a cross-platform file-sharing
app; [Saber](https://github.com/saber-notes/saber), a handwriting/notes app; and
[Dart Sass](https://github.com/sass/dart-sass), the reference Sass compiler.
`projects.json` records exact commits and library hashes. `locks/` pins resolved
hosted and Git dependencies, including otherwise mutable Git branches.

## Scope and preparation

Every Dart file under each application's `lib/` is included, including checked-in
translations and serialization code. This covers application UI, networking,
persistence and compiler implementation. Platform-native code, dependency bodies,
LocalSend's separate CLI/common packages, tests and development tools are outside
these graphs. Dependencies remain available for symbol resolution. These are CPG
and dataflow tests, not executions of the applications or their UI test suites.

| Project | Release | Dart files | Generated files¹ | Internal methods | CALLs | UNKNOWN aliases |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| LocalSend | 1.17.0 | 260 | 75 | 21,014 | 64,517 | 2 |
| Saber | 0.26.0 | 143 | 17 | 7,220 | 39,180 | 7 |
| Dart Sass | 1.89.2 | 348 | 3 | 6,057 | 66,535 | 16 |

¹ Files named `.g.dart`, `.mapper.dart`, `.freezed.dart` or protobuf output.
Generated translations account for many methods; totals are not claims about
handwritten method counts. The 25 UNKNOWN nodes are type aliases, with no omitted
executable bodies.

Use Dart 3.9.2, the rebuilt exporter 0.3.13, JDK 21, Flutter 3.35.3 for Saber and
`protoc` 36.1. The preparation script fetches LocalSend's pinned Flutter 3.24.5
framework and engine Dart sources; Pub and analysis run with Dart 3.9.2. No native
Flutter engine is executed. The script generates Sass's three missing protobuf
files using the pinned `sass/sass` schema and locked `protoc_plugin`. Application
source files and pubspec constraints are not patched.

From the repository root:

```sh
python3 joern-cli/frontends/dartsrc2cpg/scripts/application_corpus.py --prepare
export DART_SDK="$PWD/agents/toolchains/dart-sdk"
export DART_ASTGEN="$PWD/joern-cli/frontends/dartsrc2cpg/bin/dart_astgen"
DART_APPLICATION_TESTS=1 sbt 'dartsrc2cpg/testOnly *DartCorpusTests'
```

The script accepts `--dart-sdk` and `--flutter`. Without `--prepare`, it only
checks library hashes. Preparation performs dependency installation and code
generation explicitly; scans never do so. Do not prepare while scans are running.
All checkouts, generated code, CPGs and detailed reports live under
`agents/application-corpus/`; no third-party source is vendored here.

## Graph and dataflow assessment

All 751 files resolve with zero parsed fallbacks, partial units or skipped files.
Graphs pass schema validation, V3 post-frontend validation and the original corpus
walk after saving/reloading. Compiler-generated deferred-import `loadLibrary`
stubs are permitted as external: they have no source body to lower.

OSS dataflow covers all 34,291 internal methods. The largest method has 3,085
generated definitions, below both the engine's default 4,000 limit and the suite's
20,000 limit. The suite checks every method before building the overlay and
queries the reopened graph at maximum call depth four.

`dataflow-probes.json` contains 31 source-grounded positive and negative checks:
10 for LocalSend, 11 for Saber and 10 for Sass. Stock semantics passes 30/31;
all 31 pass with the optional summaries. They cover:

- LocalSend: certificate decoding/hashing, output-file paths, cross-method save
  argument bindings, content-URI encoding and filename construction.
- Saber: stored-data codecs, JSON-to-WebDAV upload, encryption closure capture,
  encryption results, independent work priority and quota serialization.
- Sass: public compilation API forwarding, source-to-parser flow, parser branch
  dispatch, whitespace scanning, formatting and independent verbosity settings.

Each source and sink must select exactly one node. Selected interprocedural
checks require a witness through the intended callee. Full scratch reports retain
source locations and witness paths; `dataflow-results.json` contains compact
outcomes. The graph is scanned in full, but these queries sample semantic
behavior: they do not exhaustively prove all paths or framework behavior.

Stock external-call semantics produce one false positive: Saber's encrypted path
can flow into `WorkPriority.veryHigh` through `Executor.execute`'s receiver. The
optional `worker_manager` summary separates the inputs while preserving the task
to result dependency. Both stock and modeled outcomes are recorded; the stock
failure is explicitly asserted, not silently treated as correct behavior. See
[summary loading and limits](../../RUNTIME_SUMMARIES.md). The inherited shared
engine field approximation and lack of precise scheduling remain limitations.

## Defects found and fixed

- Generated files excluded from lint analysis were parsed without resolution.
  Explicit scan inputs now use their enclosing analysis context; LocalSend's 54
  translation files no longer fall back to parsing.
- Top-level initializers in different parts shared a method identity. Each part
  now has a distinct initializer while retaining its defining library namespace.
- Unqualified calls nested in cascade arguments/closures incorrectly used the
  cascade receiver, sometimes creating REF edges across methods. Only analyzer-
  marked cascade accesses now use that receiver.
- Parsed fallback classes could have empty type identities. Fallback identities
  now include the source file, offset and name.
- Sass symbol literals were UNKNOWN nodes. They now retain typed LITERAL nodes.
- External tear-offs used only as values lacked target methods. A frontend pass
  now creates external method stubs from their exported declarations.

Focused regressions cover excluded-file resolution, cascade markers and lexical
receivers, part initializers, symbol literals and external tear-off targets.

Validation passed the 66 frontend/runner/original-corpus tests (including resolved
Flutter), the three application corpus tests, staging, Scala formatting/lint and
Dart analysis. The preparation script was rerun with enforced dependency locks
and reproduced all three library hashes. All 18 native exporter tests passed, including the separately enabled Flutter
case, updated protocol and excluded-file behavior.

Follow-up witness review found that the positive `certificate-to-hash` query can
traverse the filter predicate result and callback reference. Its endpoint
dependency remains correct, but the route requires semantic classification; it
is not evidence of precise iterable/callback modeling. See the first gate in the
[coverage plan](../../COVERAGE_PLAN.md) for the required witness audit.

Gate 1 adds two positive controls to the original 31 application expectations,
for 33 checks. All negative queries now require a passing control from the same
source at the same depth. Stock semantics matches 32/33; optional summaries
match 33/33. The [semantic audit](../SEMANTIC_AUDIT.md) records the complete
certificate witness review and additional unresolved routes. These endpoint
counts do not certify the routes or discharge the remaining review obligations.
