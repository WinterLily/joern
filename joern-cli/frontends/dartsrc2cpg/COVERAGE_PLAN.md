# Dart coverage completion plan

Status: implementation in progress. Shared-engine regressions for nested output
arguments and argument-specific return summaries are committed. The source
review ledger, pinned AST inventory, aliases, callable tear-offs, null-aware
collections, case labels, VM/web selection and first server/parser holdout are
implemented. Resolved user operators, null-aware updates and guarded late
field/local initialization now have focused regressions and execution oracles;
shared finally/abrupt-exit routing and overridden return dependencies are also
covered. Nested collection loops now accumulate element values with explicit
list/set/map updates and bounded execution traces. Semantic and lifecycle
qualification obligations remain open. Selected SDK conformance cases now expose
and cover separate shared-case guard/body captures and distinct logical-or joins;
metamorphic pairs and targeted mutations check the graph contracts. Ordinary
static/top-level initialization now uses guarded getters with write-before-read,
retry and reentrant-final execution checks. The byte-copy witness has a complete
transition review and an opt-in SDK byte-content model with size/isolation controls.
Caller-forwarding witnesses now have full transition reviews and a reduced
regression that exposed summary bypass in shared output-parameter expansion.
Primitive operator summaries preserve operand isolation, and unresolved dynamic
operators retain conservative dispatch. Interpolation retains user conversions,
nullable branches and operand isolation with distinct pinned VM/dart2js ordering
oracles and an explicit report field. Generated enum values, ordinal/name storage,
concrete accessors and default conversions have override and execution regressions;
interface dispatch and heap qualification remain open. Ordered catch filters,
explicit caught-value bindings and pending returns across handled cleanup failures
now have focused regressions and execution checks. Intraprocedural throw/rethrow
payloads preserve separate value/stack channels across cleanup, including exit
replacement by return, throw and outward loop jumps. Internal call exceptions now
use separate value/stack channels with wrapper, rethrow, repeated-call isolation
and normal-return-summary regressions. Unavailable external and implicit runtime
payloads remain explicit query limitations. The frontend is an
initial implementation. Constant-field demands now survive internal calls and
reject the documented returned-object unrelated-field false positive. Receiver
alias writes now preserve stable direct reference copies, and constant-field
overwrites discard old values only when every CFG route crosses a replacement
store. Stable-parameter field writes now propagate across internal calls and
caller aliases, with conditional-write, rebinding and exceptional-exit controls.
Mutable aliases and general interprocedural heap updates remain unqualified. Capture
identities now have regressions for unrelated/shadowed locals,
replacement within closures and nested captures; callback timing remains open.
Record field-isolation, interleaved named/positional indexing and source-order
evaluation regressions also have execution oracles. Constant and relational pattern
comparisons preserve receiver direction, user operator targets and null guards,
with ordinary and dynamic equality evaluation traces. Ordinary object and record
field extraction now uses lazy storage shared across cases within each match.
List patterns now resolve and cache length, index and slice reads, with bounded
wildcard/rest execution traces. Index/slice results retain instantiated pattern
types and nested wrapper erasures separately from generic member declarations
and child tests; cached views retain their current types. Rest extraction omits
the end argument when no elements follow, allowing each implementation's default
adapter to apply; computed tail bounds and explicit null remain distinct. Map patterns now retain
resolved index/presence calls, nullable-value guards and per-match constant-key storage, with alias/null
keys, nested receivers, failed guards and generic nullable/nonnullable execution
checks. Map index calls now retain instantiated nullable results before child
matching, including cached wrapper views and caller identities. Object-pattern
requirements intersect known receiver bounds rather than replacing narrower
static/representation constraints. Cast and null-assertion results now retain
successful scalar, generic, record/function and instantiated wrapper identities;
saved/cache uses preserve representation constraints. An accepted own-operator extension map triggers
a pinned compiler crash; separate structural/compiler controls record that
disagreement without an execution claim. Comparison invocations now share constant arguments and normalized equality
results within a match, while retaining constant/relational receiver direction.
Extension invocation keys include inferred argument identities, with same/different
substitution execution checks. Generic class/method/alias parameter declarations
now retain scoped identities, explicit/default bounds and Dart generic signatures,
with nested scope, recursive bound and nullable-value regressions. Inline generic
function types now retain distinct anonymous scopes under aliases, methods and
bounds. Explicit generic function references now retain instantiated static types
and source, with stable alias target, operand isolation and named/default binding
regressions. Nested references no longer supply guessed targets for conditional
or returned function values. Known immutable callable targets now survive
casts/assertions, saved values and final pattern bindings, retaining generic
identities, bound receiver capture and omitted defaults. Positional fallback
binding after named arguments preserves target slots and source evaluation order.
Constructor-value calls retain checks and reference reads before argument evaluation
and allocation. Changing and unknown callables remain unresolved. Super calls/accessors/operators and bound tear-offs
now retain static targets; extension tear-offs/setters and static member shadows
have operand-isolation and bounded execution controls. Ordinary virtual calls
retain analyzer-selected hierarchy implementations, including covariant/generic
overrides and source implicit field accessor bodies. Private library identity and
receiver static-type filtering have isolation regressions. Differing override defaults, extra optional parameters and reordered named
input/output bindings now have per-target adapters, bounded SDK oracles and
independent receiver regressions. Default adapters now share equivalent bodies
without merging omission masks or captures; cross-file positional defaults and
receiver/argument traces have SDK/graph controls. Captured receiver state flows
through explicit entry-point capture edges. Primitive constructor values now
retain the selected caller when captured reads enter an enclosing parameter;
saved-receiver rebinding and nested aliases have depth-four/eight controls.
The shared context correction is Dart-only, with ordinary-call, wrong-owner and
foreign-language boundary tests. A bounded Dart-only static-storage traversal now
recovers DART-FLOW-006's original `lazyIncrement` dependency and has call-order,
overwrite, loop, independent-slot and repeated-call controls. Initialization-state
correlation remains open. Exceptional stores now reach catch-time reads with
normal/thrown exit isolation, cleanup overwrites and rethrow/return replacement
controls. DART-FLOW-007's original field-copy false positive is now rejected: RHS tasks,
caches and held combinations retain invocation-specific pending exit demands.
DART-FLOW-008's original local-join false positive is now rejected by bounded
CFG availability checks on RHS reaching definitions. Completed-assignment points,
normal versus failed cleanup entry, consumed demands, prior handled cleanup and
return replacement have controls. Initialization state, cross-context value/heap
qualification and general predicate feasibility remain unfinished.
An opt-in Dart witness bound now retains distinct routes through intraprocedural,
held-task and final selection, with explicit pruning diagnostics and detailed
call-context reports. Three-route branch/call isolation has graph and bounded
SDK execution controls; C/Java/JavaScript/Kotlin keep longest-witness selection.
The complete four-witness review classifies all eighty query families, including
conservative detours and endpoint identities. Omitted alternatives and limited
negative searches remain unqualified; Gate 1 stays open.
Bounded witness ordering now skips ranking identical entries and unnecessary
length groups, with task-context, field/output-flag, tie and pruning regressions.
An isolated four-witness/two-held-round audit now passes baseline comparisons
on all twelve graphs under explicit worker heap/CPU and wall-clock budgets.
All returned paths have complete transition dispositions and pinned source
evidence, with library or pure helper execution controls. These controls qualify
exercised behavior rather than every retained detour or omitted route.
Equivalent Dart recursive task states now stop when a previous state had at
least as much remaining call depth. Sink, call stack, exception channel, field
and pending-exit identities remain distinct. Reduced mutually recursive caller
controls cover connected/unrelated inputs, shared caches and foreign-language
boundaries. All twelve corpus graphs and their bounded witness audits now pass against this
prerequisite. Higher-depth Args follow-ups retain their controls without diagnosed
limits at eight/sixteen/thirty-two; default depth-four negatives and general
recursion remain open.
Held-task combination rounds now have an optional Dart-only budget with explicit
inconclusive diagnostics; default and foreign-language searches retain their
previous behavior. A four-witness package audit exceeded its seven-minute worker
budget at the analyzer callback query and remains an inconclusive resource case.
The new four-witness/two-round result does not qualify that historical unbounded
search or the fifty-round interruption.
Runtime receiver contexts, runtime
type environments and bound checks remain open. Mixin superclass operations now include preceding implementations from
observed applications, with private lookup, cross-file and bound-operation
regressions. Application-specific contexts remain open; default adaptation preserves each selected target's named slots. Broader dispatch and
type-model qualification remain open. This is
not complete language or runtime analysis support.
Shared support is restricted to `DART` graphs; existing-language CFG/dataflow
behavior and operator summaries retain their pre-Dart semantics. Added regression
tests live in this frontend and include checks for that language boundary.
This plan supersedes the completion assumptions in the original implementation
plan in `agents/plan.md`; it does not mark the remaining work as done.

Synchronous for-in now retains analyzer-selected iterator/moveNext/current calls
for known interface types instead of losing user implementations behind synthetic
operators. Declared, assigned, collection and record-pattern forms have resolved
target, receiver-isolation and native member-order controls. Pattern loops save
current once before destructuring. Static lookup now follows declared/promoted
type-parameter bounds and retains instantiated current-result types separately
from generic declarations. Iterator getter return bounds now supply member lookup
without replacing symbolic iterator results or receivers. Dynamic and async SDK
resolution, generic runtime
substitutions and iterator element/heap flows remain
open; this partial qualification does not close the loop rules or any gate.

## What completion means

Define completion against a pinned Dart language version, supported SDK/analyzer
pairs and explicit analysis guarantees. Start with the existing Dart 3.9.2 target.
A future SDK or language feature requires a separate qualification run.

Track four independently releasable claims:

1. Language representation: every in-scope declaration, statement and expression
   has an intentional representation, correct identities and evaluation order.
2. Static analysis: resolution, calls, CFG and explicit value dependencies meet
   documented soundness/precision contracts, with limits visible in reports.
3. Runtime/library models: each supported SDK, package or Flutter API has a
   versioned, tested contract. Registration does not itself prove invocation.
4. Operational support: reproducible preparation, installation, partial analysis,
   resource limits and supported platforms are tested.

Full syntax coverage is attainable for a pinned version. Perfect sound and
precise dataflow for arbitrary Dart programs is not an attainable release gate.
Dynamic behavior, unbounded execution and external/native code require explicit
approximations or scope boundaries. Do not describe a partially modeled runtime
as complete Dart dataflow coverage.

## Current evidence and its limits

The corpus contains seven library releases and three application/compiler
checkouts: 1,322 Dart files and 76,200 internal methods in the selected source
roots (including generated initialization and implicit field accessors, before per-target
default-argument adapters). The application scopes are documented in [applications/README.md](corpus/applications/README.md).
Dependencies are resolved but their bodies are generally outside each graph.

The original committed queries check 67 selected relationships: 46 expected flows and 21
expected non-flows. Stock semantics matches 65/67; the optional summaries match
67/67. The Gate 1 increments add nine positive controls (76 total); see the semantic
audit for current results. Two stock false positives and the bounded alias/field
contracts are documented. These are counts of endpoint expectations, not counts
of independently verified paths or estimates of whole-program precision/recall.

The work included source-grounded expectations, positive/negative queries,
inspection of failures, focused regressions, whole-graph structural walks and
queries after graph reload. The query harness requires unique endpoints and,
for some tests, a named callee in a witness. The original reports retained up to three witnesses per
query; the Gate 1 harness now retains all returned witnesses by default. It does not validate every witness hop, all alternative routes, path
feasibility or every negative result's reason for absence. Queries run at maximum
call depth four. A depth-eight analyzer query exhausted the test JVM heap.

A follow-up inspection found a concrete reason to strengthen validation:
LocalSend's `certificate-to-hash` endpoint dependency is correct, but a saved
witness traverses the `where` predicate's boolean result, callback METHOD_REF and
collection receiver. This needs classification against explicit value-flow and
filter-selection semantics; the passing endpoint assertion does not establish
that route's correctness. Saber's configuration-to-upload witness follows JSON
encoding and byte conversion, but relies on external-call behavior. Sass's
`source-to-parser` witness proves argument forwarding, not correctness of the
subsequent parser/evaluator pipeline.

Inherited class member dispatch through extension types now uses known
instantiated representation constraints. Nested wrappers, bounded/nullable
receivers, accessors/operators, cascades, captured tear-offs and wrapped
iterator/current results have native and graph controls. Original static wrapper
types remain in the graph, and own extension members use static dispatch.
[Representation evidence](corpus/extension-erasure-review.json) records corpus
metadata and unchanged graph counts. Object-pattern accessor uses now carry
required class constraints, and object/record field results retain instantiated
types and per-use cached erasures. Type-failure exclusion, nested results and
getter-once traces have bounded controls. Complete pattern CFG/payload effects,
representation storage aliasing, record/function effects, runtime generic
environments and full extension-type rules remain open.

## Gate 1 — Audit the semantic evidence before expanding claims

Progress: see the [semantic audit](corpus/SEMANTIC_AUDIT.md). Witness reporting
now defaults to all returned paths and records any explicit reporting truncation.
All 21 negative queries have enforced distinct-node positive controls (nine new controls).
The certificate route has a reduced Scala regression, a Dart execution oracle
and a complete transition review. The committed review snapshot covers all 76 expectations
and all 63 distinct returned paths (540 transitions), including two endpoint identities and both stock false
positives. It is historical and does not certify newly generated paths. Seven
modeled negative searches remain inconclusive in the current ordinary reports
(eight with the bounded alternative configuration). Unreturned
alternatives and negative-search qualification remain outstanding.

The [four-witness results](corpus/four-witness-results.json) retain 131 detailed
stock paths and 117 modeled paths with unchanged endpoint outcomes. The
[holdout review](corpus/holdout-witness-review.json) classifies all 19 transitions
in the three distinct Shelf/YAML paths, with same-budget positive controls for
both isolation queries. Eight modeled negative searches elsewhere remain
inconclusive. Additional sequences are not necessarily new semantic route families.
The [analyzer review](corpus/analyzer-alternative-review.json) classifies 478
transitions across 16 distinct paths, retaining readonly argument-output,
receiver/field, external-read and caller detours as approximations rather than
exact runtime provenance. Direct uint32 high-byte binding and fork offset
assignment now have mixed-byte/flush-boundary and shared-byte/independent-offset
execution controls.
The [Sass review](corpus/sass-alternative-review.json) classifies all 400
transitions across 25 distinct paths for its eleven queries. Direct parser
forwarding, readonly output detours, substring argument effects and callback
approximations have separate dispositions; limited negative searches remain
inconclusive.
The [LocalSend/Saber review](corpus/applications-alternative-review.json)
classifies 621 transitions across 51 distinct paths for all twenty-two queries.
Certificate/callback, readonly caller-output, MapEntry/collection and worker
receiver detours retain their approximation status. Native controls exercise the
pinned URI helper class, filename extension and Base64 codec; they do not execute
Flutter delivery, file saving, encryption or worker scheduling.

The [args/collection/meta review](corpus/package-bindings-alternative-review.json)
classifies all 40 transitions in fourteen distinct paths for twenty-two queries.
Direct forwarding, matching field-formal stores and the reverse-loop temporary
have pinned execution controls. Both default args negative searches retain call-depth limits. The separate
[recursive task depth review](corpus/recursive-task-depth-review.json) covers both
model sets and fixed depths four/eight/sixteen/thirty-two: limits disappear at
eight and above while independent controls remain connected. This qualifies
those exercised graph/model budgets, with runtime/heap absence and omitted
cyclic witnesses still outside the claim.

The [path/async/http_parser review](corpus/package-flows-alternative-review.json)
classifies all 1,136 transitions in 40 distinct paths for its fifteen queries.
The complete audit retains 248 selected paths sharing 149 distinct routes and
2,694 classified transitions across all eighty queries. Endpoint identities,
caller re-entry, size/control detours and external effects retain their limitations;
eight modeled negatives and omitted alternatives remain unqualified.

Bounded alternative reporting is now available through `DartWitnessAuditTests`
on saved corpus graphs. It preserves intermediate call contexts and reports
`witness-alternatives` when selection omits distinct paths. Intermediate pruning,
query-depth limits and source-parameter aggregation still prevent an exhaustive
route claim. Default corpus reports retain their existing selection contract.


- [x] Review all 67 existing expectations against pinned source. Record exact
      endpoints, intended transformations/callees and relevant branch conditions.
- [x] Review at least one complete returned witness for every positive query and
      every retained alternative; remove the reporting cap and retain provenance.
- [ ] Qualify additional alternatives excluded by the engine's longest-witness
      selection. The returned-path snapshot does not establish all route families.
- [x] Classify each retained transition: assignment, parameter binding, return, field,
      alias, capture, collection element, callback invocation or external summary.
      Separate explicit value flow from control/selection dependencies.
- [x] Investigate the certificate-hashing route above first. Add a reduced
      predicate-versus-element regression before choosing a modeling fix.
- [ ] For every negative query, add a nearby positive control and demonstrate
      that absence is not caused by missing targets, missing overlays, missing
      code, exhausted depth or a resource limit.
- [x] Preserve separate outcomes for stock semantics, optional models, known
      approximation, unsupported behavior and inconclusive/budget-limited queries.
- [x] Commit compact reviewed witnesses and reasons, not just pass booleans.
      Include source commit, exporter version, model set and query limits.

Exit: every existing query has a semantic review disposition; suspicious routes
have a reduced reproducer and a tracked owner. No questionable route is promoted
to a correctness claim because its endpoint assertion passes. Revisit published
coverage wording if any positive exists only through an unjustified shortcut.

## Gate 2 — Establish a language conformance inventory and oracle

Progress: the 175-row analyzer inventory now links each in-scope visitor to
pinned formal/feature sources and candidate SDK cases. The [source index](conformance/source-index.json)
accounts for 195 base sections, thirty accepted feature documents and all 4,172
pinned SDK language test candidates. Of 75 unmapped base sections, eight are
document context and 67 retain semantic-rule obligations. Candidate-index entries
remain references; separately adapted manifest cases have explicit valid/diagnostic
classifications and bounded execution tests. Existing
per-stage semantic qualifications are unchanged. The [qualification matrix](conformance/qualification-matrix.json)
now assigns all visitor, base-section and feature-document rows seven explicit
stage cells, with 120 named test links and stale-evidence checks. Shared profiles
describe selected assertions; complete specification rules retain unqualified
obligations. Rule-specific test mapping and broader executable traces remain unfinished.
The asynchronous for-in rule now has a partial cancellation contract, with native
completion-order/error controls and CFG cleanup checks; its broader stream and
value-flow obligations stay open. Two synchronous SDK cases now check iterator
getter side effects in the VM and dart2js, and retain the exact String-as-iterable
diagnostic. Their adapted source hashes and pinned Git blob identities are verified;
these checks do not qualify every loop rule or static flow.

- [ ] Inventory Dart 3.9.2 constructs from the language specification, analyzer
      AST surface and pinned SDK language tests. Do not infer coverage solely
      from syntax appearing in the current corpus.
- [ ] Create a machine-readable matrix linking each construct to exporter,
      lowering, resolution, CFG, value-flow, negative and interaction tests.
      Mark structural-only, conservative and unsupported cells explicitly.
- [x] Select or adapt upstream conformance cases with license/provenance records.
      Separate valid syntax, deliberate diagnostic cases and unimplemented syntax.
- [ ] Build executable microprograms with instrumented evaluation traces to check
      order, side effects, dispatch, exceptions and callback behavior. Observed
      execution is evidence for exercised paths, not proof of absent static flow.
- [x] Add metamorphic pairs: rename locals, extract/in-line helpers, reorder named
      arguments, add dead code, replace an input with a constant, overwrite a local,
      split into files, and introduce an independent receiver/object.
- [x] Use bounded exhaustive inputs where practical. Add mutation tests showing
      that the suite detects removed REF/call edges, swapped arguments, broken
      return flow and spurious receiver propagation.

Exit: every language construct has a classified row and test obligations. Missing
lowerings fail the inventory check; executable UNKNOWN nodes cannot silently pass.
The oracle does not use the frontend's own output as its expected semantics.

## Gate 3 — Complete declarations, expressions and control flow

Prioritize issues found by Gates 1–2, then close known gaps:

Await-for lowering now places iteration inside awaited iterator cancellation
cleanup. Body break/return/throw, an outward labeled continue and throwing
collection elements enter cleanup; a local continue remains in the loop.
Cancellation failures and delayed completion have independent VM controls.
This does not establish general stream delivery, subscription state or async
payload/heap qualification.

- [ ] Generic type aliases, generic function instantiation, variance/bounds,
      nullability and promoted types: preserve identity and relationships without
      pretending to instantiate all generic combinations.
- [ ] Constructors, factories, redirect/super chains, initializers, late/final
      fields, accessors, operator overloads and compound/null-aware updates:
      verify value flow and exactly-once receiver/index evaluation.
- [ ] Enums, mixin application order, extension dispatch and extension-type
      representation: test behavior as well as node presence; account for
      language-generated enum members.
- [ ] Records and all pattern forms: binding, field/getter extraction, rest
      values, guard order, failure paths and assignment behavior.
- [ ] Collections and spreads: distinguish elements/keys/values, mutation and
      accumulation, including nested collection-for and collection-if.
- [ ] Switch labels and continue-to-case, labeled exits, short-circuiting,
      assertions and unreachable code: verify CFG successors and value paths.
- [ ] Exceptions: throw/rethrow, catch variables and filters, potentially throwing
      calls, finally on return/break/continue/throw and finally overriding an exit.
- [ ] Library/part identities, deferred imports, conditional environments,
      language overrides and malformed/missing-dependency modes: explicit contracts.

For each gap, add a minimal realistic failing test, fix the lowering, then test
its interactions (for example patterns in async loops or closures inside cascades).

Exit: all in-scope constructs are represented and pass their declared obligations;
there is no silent executable-body loss. Any CFG engine limitation preventing a
required contract is a blocking dependency, not a completed frontend feature.

## Gate 4 — Qualify interprocedural and heap dataflow

- [ ] Direct/virtual/interface/super/mixin/extension dispatch, named/default
      arguments, recursion and generic calls: compare targets to analyzer facts
      and executable cases, including deliberately unresolved dynamic calls.
- [ ] Higher-order values: mutable callback variables, parameters, returns,
      containers, tear-offs, bound receivers and nested captures. Define the
      points-to approximation and distinguish callable identity from object state.
- [ ] Heap model: fields of the same object, independent objects of the same type,
      aliases, writes/overwrites, constructor results, records and collection slots.
      The initial `Box(input).other` false positive, stable receiver-alias writes
      and constant-field overwrites have reduced regressions, including stable
      reference parameters across calls. Mutable aliases, collection slots and
      general interprocedural updates remain open.
- [ ] Verify positive and negative dependencies across file/package boundaries,
      callback boundaries and repeated calls with unrelated inputs.
- [ ] Test several bounded call-depth settings, recursion and large methods.
      Missing results under a limit must be reported as inconclusive where the
      limit prevents the claimed guarantee, not as a semantic negative.
- [ ] Classify each defect as frontend lowering, missing library model or shared
      engine behavior. Fix frontend issues locally. If a required guarantee needs
      a shared-engine change, prepare the smallest cross-language reproducer and
      regression-backed change separately; do not disguise it with Dart-specific
      graph distortions or broad taint summaries.

Exit: value-flow, field/alias and call-resolution contracts pass the conformance
suite and reviewed corpus paths. Measured false positives and false negatives
are reported by feature and model set with denominators. No blanket precision
claim is made for capabilities the shared engine cannot supply.

## Gate 5 — Model SDK, async and framework boundaries

Work in this order, guided by missing edges observed in real projects:

- [ ] Common value transformations and effects: strings, collections, iterables,
      codecs, buffers, file/network I/O and relevant mutation APIs. Specify receiver,
      argument, return and heap effects; keep sanitization claims separate.
- [ ] Future/Completer: success/error payloads, await, then/catchError/whenComplete,
      chaining, exceptions and callback return values. Separate independent futures.
- [ ] Stream/controllers/subscriptions and generators: yield/yield*, map/filter,
      listen, await-for, error/done channels, cancellation and independent streams.
- [ ] Isolates, worker execution, FFI and platform channels: define message/payload
      boundaries and model assumptions. Mark unavailable native behavior explicitly.
- [ ] Flutter: callback registration and modeled event delivery, state updates,
      navigation, controllers/listeners and chosen state-management packages.
      Framework lifecycle and event ordering need separate evidence from constructor
      argument or callback-body flow.
- [ ] Version and test every model against the actual library implementation.
      Each model needs flow-preserving and isolation tests plus a real-project case.
      Keep model activation visible and publish stock/modelled results separately.

Exit: every advertised library/framework capability has a versioned contract and
positive/negative evidence. Arbitrary third-party libraries remain explicitly
outside complete runtime coverage until analyzed or modeled.

## Gate 6 — Demonstrate generalization and release readiness

- [ ] Expand whole-program scope where relevant: LocalSend's common/CLI packages,
      application-owned modules and selected dependency bodies. Report exact roots
      and unresolved/external boundaries rather than saying “whole application”.
- [ ] Add server-side Dart, command-line tools, multi-package workspaces and a web
      conditional-import target, alongside the existing Flutter applications.
- [ ] Trace representative vertical flows: receive-to-file, document decode/edit/
      save/sync, and source parse/evaluate/serialize. Include unrelated fields,
      overwritten data and constant branches as controls.
- [ ] Reserve projects or releases as a holdout set. Fixing against a corpus and
      rechecking only that corpus is not evidence of generalization. After fixes,
      rerun the holdout and record newly discovered gaps.
- [ ] Run the conformance suite on each advertised SDK/analyzer combination and
      platform. Separate native exporter, staged CLI, console and dataflow checks.
- [ ] Enforce file coverage, executable UNKNOWN, diagnostic, skipped-method,
      timeout/truncation, peak-memory and query-depth reporting. Failure or skip
      is visible in the release report; partial results cannot receive a clean label.

CI and release workflow changes are outside this implementation's scope. Run
semantic, corpus, model and resource checks locally with explicit, pinned downloads
and code generation.

Exit: every construct in the pinned scope is accounted for; all required semantic
and operational gates pass; there are no undisclosed correctness failures or
inconclusive checks in the advertised capabilities. Remaining conservative
approximations and unsupported runtime boundaries are part of the release contract.

## Execution order and reporting

Start with Gate 1, not additional corpus size or new feature claims. Establish the
inventory/oracle next. Prioritize CFG/value-flow correctness before large runtime
summary catalogs; foundational errors otherwise contaminate every downstream test.
Gates 3–5 are iterative, with the same regression/corpus loop after each fix.

Keep implementation changes within `dartsrc2cpg` except justified integration or
shared-engine prerequisites. Preserve current corpus regressions. At each gate,
publish completed obligations, failing/inconclusive cases, reviewed witnesses and
remaining dependencies. Run relevant checks and commit only scoped work; never push
without a separate instruction. Estimate later milestones after Gate 1 and the
inventory reveal the actual defect rate and shared-engine constraints. This is
sustained engineering work; the prototype's elapsed time is not a completion estimate.
