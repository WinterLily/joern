# Pinned qualification evidence

`inventory.json` accounts for the analyzer 8.4.1 visitor surface at Dart 3.9.2.
Its test checks that every visitor construct is classified and that declared
exporter cases still exist. A row marked `unqualified` is an open semantic
obligation, even when its syntax is exported. Every in-scope visitor row now references
pinned base-specification sections, accepted feature documents and candidate SDK
cases. These are source references; candidate cases are unreviewed and unexecuted
unless separately qualified by the existing upstream manifest and tests.

[source-index.json](source-index.json) records 195 base section labels, thirty
accepted feature documents and the complete 4,172 `_test.dart` candidate index
from the Dart 3.9.2 SDK language-test tree. Git blob identities and immutable
repository revisions preserve provenance. The language repository snapshot is
the last commit before the SDK revision's committer timestamp; it is reference
material rather than a compiler dependency. The [formal specification](https://dart.dev/resources/language/spec)
is unfinished, so accepted feature documents supplement the older base draft.
Diagnostic, VM and experimental SDK tests are included in the candidate index;
their presence does not establish valid Dart 3.9 syntax or a passing oracle.

Seventy-six base sections have no construct-specific cross-reference yet and
remain explicit `specificationGaps`: eight document-context sections are not
standalone constructs, while 68 semantic-rule sections remain unqualified. Existing per-stage contracts and semantic
qualifications are unchanged. The source inventory does not close Gate 2's
complete language-rule matrix or executable-oracle obligations.

[qualification-matrix.json](qualification-matrix.json) assigns seven stage cells
to all 175 visitors, 195 base sections and thirty feature documents. Shared visitor
profiles link 47 named graph, exporter and native tests, with file hashes and
explicit assertion scopes. `tested-conservative` describes those selected tests;
it does not qualify every visitor using the profile. Generic declaration/bound
checks remain `structural-only`, and untested CFG cells remain `unqualified`.
Specification sections and feature documents retain complete-rule obligations;
their related visitor links do not imply complete rule coverage. The matrix is
declared evidence, not an execution report or a release qualification.

Check committed references without network access:

```sh
python3 joern-cli/frontends/dartsrc2cpg/scripts/verify_conformance_sources.py
python3 joern-cli/frontends/dartsrc2cpg/scripts/verify_qualification_matrix.py
python3 joern-cli/frontends/dartsrc2cpg/scripts/test_qualification_matrix.py
```

Fetch missing immutable evidence under `agents/` and verify the full pinned SDK
index, section extraction and actual specification hashes:

```sh
python3 joern-cli/frontends/dartsrc2cpg/scripts/verify_conformance_sources.py \
  --fetch --source-root agents/language-inventory
```

The verifier checks cached content rather than silently replacing mismatches.
The matrix verifier rejects stale test files, missing or ambiguous test selectors,
missing rows/stages, tests assigned to the wrong stage and unsupported promotions.
The native inventory test also checks every visitor's known references and keeps
outside-scope visitors disabled. Neither check promotes a candidate into runtime
or graph qualification.

`DartFrontendTests` exercises these paired transformations against the same
source-to-sink dependency: local renaming, helper extraction/inlining, named
argument reordering, a dead helper, constant replacement, local overwrite and
moving the called declaration into another file. Every variant checks the
resolved internal target. A separate independent-receiver case pairs the
negative result with a positive argument-flow control. These cases qualify the
selected calls; they do not claim allocation-sensitive heap analysis.

`DartMutationTests` exports one valid program, checks its graph contract, then
rebuilds independent graphs after deliberately removing a reference identity or
call target, swapping named-argument bindings, or replacing a returned expression
with a constant. Each mutation must violate its corresponding graph/dataflow
contract. A separate test injects a deliberately incorrect receiver-to-return
summary and requires the constant-return isolation control to detect it. Graph
identity checks matter: name-based reaching definitions can hide a missing REF
from an endpoint-only query. These are targeted mutation checks, not a mutation
score over every frontend implementation statement.

`witness_alternatives.dart` has three feasible value routes and repeated-call
isolation controls. Its independent Dart oracle exhausts both branch booleans for
two input values and checks exact helper traces. `DartFrontendTests` checks the
same graph under default and bounded witness selection, including reporting
truncation and saved call contexts. `WitnessSelectionTests` covers intermediate
task selection, held-task reuse, deterministic bounds and the C/Java/JavaScript/
Kotlin boundary. This qualifies the selected route families and auditing controls;
bounded enumeration is not exhaustive path analysis.
`BoundedWitnessOrderingTests` checks skipped ranking work, identical rows,
isolated lengths, differing task contexts, stable ties, field/output flags and
pruning diagnostics. The [ordering review](../corpus/witness-ordering-review.json)
records the measured workload and same-input sampled comparisons.
The opt-in `corpus_capture_test.dart` executes the prepared http_parser 4.1.2
release against two valid inputs and two independent invalid inputs. Enable it
with `DART_CORPUS_TESTS=1`; it fetches no dependencies. It exercises the source's
synchronous wrapper and captured input, without asserting general callback timing
or external parser implementation coverage. A second pinned-package check uses
three inputs to separate the FormatException message input from its preserved
source/offset, and checks the distinct SourceSpanFormatException message, span
identity and exactly-once body invocation. It uses source_span 1.10.2 from the
prepared package configuration.

The Dart execution tests provide independent runtime oracles for operators,
late initialization, exception cleanup and collection evaluation. Bounded inputs
include numeric updates from -1 through 1 and nested collection counts from 0
through 2. Execution establishes the behavior of those paths only. In particular,
a predicate that changes collection membership may change observable output
without contributing an explicit element value.

`static_storage_test.dart` executes three input values through direct/nested
stores, repeated calls, independent owner/field controls and saved reads. Both
branch booleans exercise conditional/loop writes and overwrites on every branch.
The graph regressions recover these value dependencies through a bounded,
Dart-only shared-engine prerequisite; C/Java/JavaScript/Kotlin and instance-field
traversal retain their prior behavior. Call-depth and storage-node exhaustion
report explicit limits. The throwing-helper/catch dependency is now recovered.
`static_exception_storage_test.dart` checks three inputs and both branch booleans
through normal versus caught reads, cleanup overwrites, nested cleanup, rethrow
and return/throw replacement. Dart CFG exit tags preserve completed cleanup versus
failed-call evidence without changing CFG edges. Private pending-exit demands
keep direct joined-cleanup reads isolated. The RHS task now retains that pending-exit demand, fixing
DART-FLOW-007's static field copy. Saved helper reads, an intervening other-slot
write and simultaneous normal/caught sinks check context/cache isolation at depths
four/eight and one/two-witness selection. DART-FLOW-008's local
assignment join is now rejected by CFG availability under the pending exit.
Completed assignments, consumed demands, earlier handled cleanup, return
replacement, loop copies and same-source normal/caught sinks have controls;
truncated value searches are explicitly inconclusive. Initialization-state correlation and broader exception/
heap qualification remain open. The [exception witness review](../corpus/static-exception-witness-review.json)
is the historical DART-FLOW-007 counterexample; the
[historical copy review](../corpus/static-cleanup-copy-witness-review.json) records
the historical corrected field-copy controls and local-join defect. The
[local exit review](../corpus/static-local-exit-witness-review.json) records the
current correction.

`upstream/manifest.json` records selected SDK language tests at the pinned SDK
revision, original/adapted hashes, license and the exact adaptations. Valid
runtime cases retain their original bodies and expected values; only the expect
import changes to a small local assertion adapter. A separate reduced diagnostic
case must remain partial and preserve both expected type errors. The runtime
suite executes null-aware evaluation order, late-field initialization and pattern
guard captures. These selections supplement the inventory, not the entire SDK
conformance suite. The shared-case capture test exposed merged guard/body storage
and missing common-body selection; both now have frontend regressions. A related
identity test separates two logical-or joins in one function.

Interpolation has separate VM and dart2js order contracts. The default/native
oracle checks the VM's evaluation-before-conversion phase; the opt-in backend
suite also compiles the same driver to AOT and JavaScript and checks each backend's
observed trace. Run from `astgen` with `DART_RUNTIME_TESTS=1 dart test`; Node.js must
be installed. Temporary binaries and JavaScript go under `agents/` and are removed
after the check. These checks also cover adjacent/nested strings, a call boundary,
throwing expressions/conversions and nullable values.
