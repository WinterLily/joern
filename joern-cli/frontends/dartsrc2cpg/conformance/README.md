# Pinned qualification evidence

`inventory.json` accounts for the analyzer 8.4.1 visitor surface at Dart 3.9.2.
Its test checks that every visitor construct is classified and that declared
exporter cases still exist. A row marked `unqualified` is an open semantic
obligation, even when its syntax is exported. This inventory is not yet a complete
cross-reference to specification sections and SDK language tests.

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
