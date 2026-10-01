# OSS Dart corpus audit

For full application/compiler checkouts, see the [LocalSend, Saber and Dart Sass
corpus](applications/README.md), including source-to-sink dataflow checks.

This corpus scans production `lib/` sources from seven open-source pub.dev
releases. It spans a three-file annotation library through the Dart analyzer.
Package tests, examples and dependency source trees are not included in each CPG.
Dependencies are available to the analyzer for resolution.

`projects.json` pins release versions, archive SHA-256 and library tree SHA-256.
`pubspec.lock` pins the separate dependency-resolution harness; project dev
packages are deliberately excluded. Source licenses remain in the extracted
packages. The library digest hashes sorted relative POSIX file paths, a NUL,
file bytes, and another NUL for every file under `lib/`.

## Reproduce

Build the native exporter as described in [../README.md](../README.md), then:

```sh
export DART_SDK="$PWD/agents/toolchains/dart-sdk"
export DART_ASTGEN="$PWD/joern-cli/frontends/dartsrc2cpg/bin/dart_astgen"
python3 joern-cli/frontends/dartsrc2cpg/scripts/corpus.py --prepare --check
DART_CORPUS_TESTS=1 sbt 'dartsrc2cpg/testOnly *DartCorpusTests'
```

For offline preparation, substitute `--from-cache "$HOME/.pub-cache"` for
`--prepare`. This audit used the local pub cache because network DNS was
unavailable; every library tree matched its recorded digest. Cached sources do
not claim fresh archive verification. The archive path independently verifies
the release archive hash before extraction and the same library digest afterward.
Preparation replaces scratch checkouts, so do not run it alongside graph tests.

Each test creates `agents/dart-corpus/<package>-<version>/cpg.bin`, applies default
overlays and OSS dataflow, closes/reopens it, and writes `audit.json`,
`dataflow-overlay.json` and `dataflow-audit.json`. Tests enable schema and V3
post-frontend validation. The subsequent walk checks unique method identities,
AST ownership, argument indices and explicit parameter bindings, reference names
and lexical targets, method references, internal methods incorrectly represented
as external, unexpected executable UNKNOWN nodes, and CFG edges crossing methods.
A named API probe per package also guards against empty or unrelated graphs.
Argument zero is an object receiver and need not have a formal parameter.
Function-value targets have a RECEIVER edge but are not mutable argument zero.

## Results

Measured on Linux x86-64, Dart 3.9.2, analyzer 8.4.1, exporter 0.3.1, JDK 21.
All 571 source files resolve without error diagnostics. Every generated/reloaded
graph passes the audit. `baseline.json` records native exporter coverage and
resource measurements; `graph-baseline.json` records the audited graph counts.

| Package | Files | Internal methods | Calls | UNKNOWN declarations |
| --- | ---: | ---: | ---: | ---: |
| path-1.9.1 | 13 | 191 | 1,754 | 0 |
| collection-1.19.1 | 29 | 685 | 4,441 | 0 |
| meta-1.17.0 | 3 | 35 | 319 | 0 |
| args-2.7.0 | 12 | 154 | 1,913 | 0 |
| async-2.13.0 | 45 | 532 | 3,039 | 5 |
| http_parser-4.1.2 | 11 | 64 | 906 | 0 |
| analyzer-8.4.1 | 458 | 21,884 | 198,056 | 66 |

The remaining 71 UNKNOWN declarations are generic type aliases (5 in `async`,
66 in `analyzer`); none contains an omitted executable body. Exporter unsupported
counts also include documentation/type syntax beneath those aliases, so they
are larger than graph UNKNOWN counts. Unresolved invocation counts include
function-value calls and external/dynamic behavior; zero audit failures is not
a claim of complete runtime resolution or exhaustive Dart semantic correctness.

## Dataflow assessment

OSS dataflow is generated for all 23,545 internal methods, then queried after
saving and reopening each graph. The definition limit is explicitly 20,000:
analyzer's `CompileTimeErrorCode.<clinit>` has 6,675 generated definitions and
would be skipped by the stock 4,000 limit. The test counts definitions for every
method and fails if any exceeds the configured limit.

`dataflow-probes.json` defines 42 positive and negative endpoint queries (36 original
expectations and six added positive controls)
against the unmodified package code. Each endpoint must select exactly one node;
selected interprocedural checks also require a witness through the named callee.
Checks cover constructor fields, named argument isolation, returned values,
forwarding across files, callback arguments, loops, byte buffers, arithmetic and
error messages. Queries use the engine's default maximum call depth of four.
These are sampled semantic checks, not exhaustive path or program verification.

| Package | Checks | Stock semantics passing | With Dart summary passing |
| --- | ---: | ---: | ---: |
| path | 5 | 5 | 5 |
| collection | 7 | 7 | 7 |
| meta | 9 | 9 | 9 |
| args | 6 | 6 | 6 |
| async | 4 | 3 | 4 |
| http_parser | 5 | 5 | 5 |
| analyzer | 6 | 6 | 6 |

The stock failure is a false positive from `ErrorResult.error` to the unrelated
stack-trace argument of `Completer.completeError`. Default external-call
semantics mix its arguments through the receiver. The optional
[SDK summary](../RUNTIME_SUMMARIES.md) preserves both inputs in the completer
without copying them into each other. Both modes are tested and reported; the
corpus test explicitly expects the stock failure instead of hiding it.
`dataflow-results.json` records counts and individual outcomes. Scratch reports
include source locations and all returned witness paths per query, with explicit
counts for any requested reporting truncation. See the [semantic audit](SEMANTIC_AUDIT.md)
for query limits, positive controls and unresolved witness findings.

A focused regression also records the shared engine's field approximation:
`Box(input).other` can be tainted even when `other` is constant, because the
returned object carries constructor input across method boundaries. This is a
known false positive, not precise field isolation. Dynamic callback resolution,
Future/Stream scheduling and framework lifecycle remain outside this assessment.
A depth-eight corpus experiment exhausted the test JVM heap during an analyzer
query; the completed assessment uses depth four and makes no deeper-path claim.

## Defects reproduced and fixed

- `async` callbacks wrote fields without capturing `this`; writes and callable
  references now participate in capture discovery, with local REF targets.
- Counter updates in `async` and `analyzer` lost operand identities. Increment
  and decrement now retain read/write targets; custom accessor updates evaluate
  the receiver once and distinguish prefix/postfix results.
- Enhanced enums in `http_parser` and `analyzer` lost constructors and method
  bodies. Their constants, initialization and source methods are now represented.
- Assertions in `path`, `collection`, `args`, `async` and `analyzer` lost condition
  and message evaluation. Graphs now preserve enabled/disabled and failure paths.
- Two labeled loop bodies in `analyzer` were discarded. Labeled break and continue
  now route to separate exit/update targets.
- Dynamic object patterns in `analyzer` emitted unbound property identifiers.
  They now access the matched receiver before binding the pattern variable.

The dataflow assessment additionally reproduced and fixed:

- Missing constructor-to-result flow, including `path.Context.setExtension`:
  allocation, initialization and the returned object now share a local, and
  generative constructors return `this` in the graph.
- False flow through function-value targets, including `collection.binarySearch`:
  a callable target is no longer treated as mutable argument zero.

Focused regressions accompany these fixes. Remaining language and runtime limits
are described in [../SEMANTICS.md](../SEMANTICS.md) and [../FEATURES.md](../FEATURES.md).

Validation passed all 56 frontend/runner tests, including resolved Flutter, and
all seven corpus tests, with no skips. After adjusting expected CALL counts for
the added allocation/result assignments, the seven corpus tests were rerun and
passed. Staging, Scala formatting and lint checks passed; the staged SDK summary
matches the source file. All seven library source hashes still match the pinned
manifest. Scratch graphs and detailed logs are excluded from version control.

Graph baselines retain counts before per-target default-argument adapters. The
corpus test checks the reported adapter count against generated methods, checks
one correctly bound delegate per adapter, bound capture entry edges and call-to-adapter
link counts, then adds exactly one method and one
call per adapter to the method/call baseline. Counts for the remaining graph
remain unchanged. Compact reports expose these additions separately; adapter
counts are representation evidence, not a runtime correctness denominator.
