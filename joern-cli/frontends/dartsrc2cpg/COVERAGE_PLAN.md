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
replacement within closures and nested captures; callback timing remains open. This is
not complete language or runtime analysis support.
This plan supersedes the completion assumptions in the original implementation
plan in `agents/plan.md`; it does not mark the remaining work as done.

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
checkouts: 1,322 Dart files and 63,655 internal methods in the selected source
roots (including generated initialization accessors). The application scopes are documented in [applications/README.md](corpus/applications/README.md).
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

## Gate 1 — Audit the semantic evidence before expanding claims

Progress: see the [semantic audit](corpus/SEMANTIC_AUDIT.md). Witness reporting
now defaults to all returned paths and records any explicit reporting truncation.
All 21 negative queries have enforced distinct-node positive controls (nine new controls).
The certificate route has a reduced Scala regression, a Dart execution oracle
and a complete transition review. The current snapshot reviews all 76 expectations
and all 63 distinct returned paths (540 transitions), including two endpoint identities and both stock false
positives. Five modeled negative searches remain inconclusive. Unreturned
alternatives and negative-search qualification remain outstanding.


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
- [x] CI tiers: fast semantic regressions on changes; pinned corpus and model
      checks on relevant changes; scheduled broader/holdout/resource runs. Keep
      large downloads and code generation explicit, pinned and reproducible.
      The workflow is configured and its commands validated locally; hosted
      platform qualification remains a separate unchecked obligation above.
- [ ] Enforce file coverage, executable UNKNOWN, diagnostic, skipped-method,
      timeout/truncation, peak-memory and query-depth reporting. Failure or skip
      is visible in the release report; partial results cannot receive a clean label.

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
