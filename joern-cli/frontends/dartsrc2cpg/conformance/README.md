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

The Dart execution tests provide independent runtime oracles for operators,
late initialization, exception cleanup and collection evaluation. Bounded inputs
include numeric updates from -1 through 1 and nested collection counts from 0
through 2. Execution establishes the behavior of those paths only. In particular,
a predicate that changes collection membership may change observable output
without contributing an explicit element value.

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
