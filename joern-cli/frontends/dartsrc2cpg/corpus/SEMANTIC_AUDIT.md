# Semantic evidence audit

Gate 1 is in progress. Endpoint regression success is distinct from reviewed
value-flow correctness. The original 67 expectations remain; nine additional
positive controls bring the suite to 76 expectations (55 positive, 21 negative).
All negative probes now name a positive control with the same source selector.
A control must resolve unique endpoints, observe a flow through any required
callee, have distinct source and sink nodes, and use the same call depth.
Node-to-itself probes remain structural identity checks and cannot qualify a
negative result. This detects missing source connectivity;
it does not prove completeness of the negative query's search.

`CorpusDataflow` retains every returned witness by default. An explicit reporting
limit records retained and omitted counts. This does not remove engine search
limits. Each result records call depth, argument expansion limits, endpoint
selectors, node labels and IDs. Node IDs apply only to the recorded graph.
The shared engine now exposes per-query discarded-work reasons: call depth,
parameter/argument expansion, output-argument expansion and failed tasks. Empty
results with any recorded limitation are `inconclusive-query-limits`; other empty
results remain `no-flow-observed-within-limits`, not proof of semantic absence.
`searchComplete` only means no such discarded work was observed. The engine
selects the longest witness per endpoint pair before returning results, so
retaining every returned path still does not expose all alternative routes.
Missing controls make a negative result inconclusive and fail the regression.
The harness requires the dataflow overlay and keeps semantic review `pending`.

Corpus reports include pinned source metadata, the actual exporter protocol
header, file coverage and the exact optional model text. New reports also include
`analysisSources`, a SHA-256 fingerprint of the listed frontend/exporter/harness,
shared engine, semantic traversal, CFG/linking and build/dependency source roots.
The hash uses sorted repository-relative paths, NUL, file contents and NUL. It
identifies those checked-out sources, not a signed binary or the complete host
environment. Stock and optional
model results remain separate. Full reports are generated under
`agents/dart-corpus/*/dataflow-audit.json` and
`agents/application-corpus/results/*/dataflow-audit.json`.

## DART-FLOW-001: predicate result enters collection value flow

Owner: shared dataflow query engine and future Dart iterable models.
Disposition: reproduced stock approximation; optional pure-iterable model has
positive/negative controls. Arbitrary callback side effects remain outside it.

The [complete reviewed witness](certificate-witness-review.json) preserves all
36 displayed nodes and classifies all 35 transitions for LocalSend's
`certificate-to-hash`. Its source commit, exporter, model sets and limits are
included. The source endpoint dependency is justified: PEM content is decoded
to DER bytes and hashed. The route through the filter's boolean return,
METHOD_REF and collection receiver is not an explicit element-value route.
Repeated traversal of this route is also retained in the review artifact.

The shared engine's `TaskCreator.tasksForUnresolvedOutArgs` logic (the `forMethodRefs`
block) follows referenced method returns from METHOD_REF output arguments.
External-call propagation then admits callback-result/receiver dependencies.
Dart lowering retains the callback as a real argument; deleting it or its REF
would discard valid callable identity. No such graph workaround was applied.

The shared Scala/Dart fixture `src/test/resources/semantics/iterable_selection.dart`
separates predicate capture, element flow and unrelated input. The Scala test
records the current approximation, including METHOD_REF traversal, alongside
positive and negative controls. The Dart oracle tests empty and nonempty inputs
and lazy predicate evaluation in element order. The pinned SDK's
`WhereIterator.current` returns the original iterator element, while `moveNext`
uses the predicate only for selection. The oracle is evidence for exercised
executions, not proof of absent static dependencies. A model must distinguish
selection and element value flow and preserve predicate side effects; a blanket
callback-to-result summary cannot provide that contract.

## Further witness findings

The [source review ledger](semantic-reviews.json) records the original 75 expectations,
source hashes, relevant conditions and endpoint dispositions. It preserves the
historical exporter 0.3.2 witnesses for comparison; it does not certify each hop.
The originally flagged route families now have reduced fixtures and complete
transition reviews. Ownership and remaining approximations are recorded below.
The newer [complete snapshot review](witness-reviews.json) classifies every
retained transition across all 76 current expectations. It includes conservative
and inconclusive dispositions rather than promoting every endpoint match to a
semantic guarantee.

| Issue | Probe | Observed route requiring review |
| --- | --- | --- |
| DART-FLOW-002 | analyzer `list-element-callback` | [Complete transition review](field-witness-review.json) and reduced inheritance/callback fixture reproduce the parameter-output and receiver-field detour. Bounded runtime traces check independent field contents and order; shared field-alias precision remains conservative. |
| DART-FLOW-003 | http_parser `chunk-interprocedural-copy` | Reduced byte-content/size/isolation cases and [full transition review](byte-copy-review.json) added. Optional bytes model yields direct parameter forwarding; stock equality and external-call shortcuts remain explicitly classified. |
| DART-FLOW-004 | path `normalize-fast-return`; Sass `scss-source-forwarding`/`css-source-forwarding` | [Complete transition review](caller-witness-review.json), reduced parameter-output detour and separate-call controls added. The regression exposed and fixed summary bypass during nested output expansion; unmodeled effects remain conservative. |
| DART-FLOW-005 | LocalSend `filename-extension` | [Complete transition review](callback-witness-review.json) and reduced indexed-mapping fixture expose external constructor argument mixing and callback-result feedback into the mapping receiver/index. |

The remaining source/branch reviews, alternative-route reviews and reduced
reproducers are outstanding. Positive controls do not discharge those obligations.
No gate completion or expanded language/runtime coverage is claimed by this audit.

## Validation of this increment

The frontend/runner/package suite passed 68 tests, including the enabled Flutter
fixture and all seven packages. All three application corpus tests passed.
The native Dart suite passed 20 tests, including the Flutter exporter case and
two runtime oracle tests. Across 75 corpus expectations, stock semantics matched
73 and optional models matched 75; the two pre-existing stock false positives
remain. Every negative's positive control passed in both model sets.
These are endpoint results, not 75 semantic correctness certifications.

## Exporter 0.3.3 validation

All 74 frontend/runner/package checks, three application checks, two holdout
checks and 24 Dart tests passed on Linux with Dart 3.9.2. Dart analysis is clean.
The original 75 endpoint expectations remain 73/75 stock and 75/75 modeled;
the holdout adds four separately reported checks, all matched in both modes.

The [new modeled certificate witness](certificate-modeled-review.json) contains
14 nodes and 13 classified transitions. It follows element values without the
boolean predicate detour and records no discarded-work limitation. External
string/byte transformations still rely on summaries. Other queries do record
depth exhaustion, including negative probes; their endpoint match does not
remove the `inconclusive-query-limits` disposition.

## Shared finally regression

The pending-exit/return-dependency change passed 83 Dart frontend/runner/package
checks, all three applications, both holdouts, the focused shared CFG/DDG tests
and the C/C++ dataflow/CFG regressions. The C++ early-call expectation now includes
its exceptional path and empty-catch continuation. A broader x2cpg run passed
114 tests but failed three artifact-download tests with DNS resolution errors;
those network-dependent operational checks are not reported as passed.

## Primitive operators and unresolved dispatch

Read-only comparison, arithmetic, bitwise and logical summaries now preserve
operand-to-result flow without treating the other operand as a write. Shared C
regressions cover six comparisons and twelve binary operators; Dart regressions
also cover integer division and unsigned shift. Unresolved dynamic operators and
nonprimitive equality remain calls with conservative effects. Generated boolean
guards now have boolean result types.

The frontend/runner/mutation/package run passed 98 tests, all three application
checks and both holdout checks passed. The 75 corpus endpoint expectations remain
73/75 stock and 75/75 modeled, with four additional holdout expectations matching
both modes. Existing witness and search-limit caveats still apply. Call-count
baseline changes reflect explicit dynamic operator calls; file and method counts
are unchanged. Shared validation passed all 40 engine tests and 134 C dataflow
tests. The byte-copy review records the shared-engine commits separately from its
historical exporter header.

## Caller forwarding review

The three DART-FLOW-004 witnesses have 40 displayed nodes and 37 classified
transitions. Stock and modeled witnesses are identical in the recorded snapshot.
Their source dependencies are justified by direct argument forwarding, while the
parameter-output detours through `fromUri` and `readFile` do not show runtime
writes to immutable String arguments. All three searches recorded discarded work
at the call-depth limit; alternative paths are not returned by the engine.

The reduced fixture reproduces the same nested-call detour and checks a separate
invocation returning a constant. A fixture-only read-only summary initially
failed to prevent the detour: shared output-parameter expansion ignored that
summary. Commit `a5ec1a2f7` fixes the bypass. The runtime oracle checks five inputs,
including whitespace, and confirms forwarding and independent calls. No purity
summary for an entire package is inferred from this fixture.

## Receiver-field witness review

DART-FLOW-002 now has a complete review of all 46 displayed nodes and 45
transitions. Its list-element dependency is justified by `writeItem(items[i])`.
The retained route instead leaves the list through an output parameter, combines
field and whole-receiver dependencies across inherited writer methods, and
re-enters `writeList`. The reduced fixture reproduces that route through three
independent list fields. The runtime oracle varies one first-field value while
checking all other field values and the exact write order. This qualifies the
observed approximation; it does not supply field-sensitive alias analysis for
arbitrary callback effects.

## Callback feedback review

DART-FLOW-005 now has a complete review of both returned route variants: 57 displayed nodes and 55
transitions. Filename interpolation supplies the endpoint dependency directly.
The saved route also treats MapEntry's value as a possible key write, follows a
callback return through METHOD_REF, and feeds it into the mapping receiver and
positional index or map-entry value. It also exposes an interpolation operand
write and an immutable substring receiver write in those variants. The reduced fixture reproduces the callback/index re-entry;
the runtime oracle supplies a sequential indexed mapper and checks keys, outputs,
visit indices and unchanged input elements for zero to two elements. No framework
lifecycle or arbitrary callback execution guarantee is inferred from that model.

Validation: all 87 frontend checks, 47 native Dart tests (including Flutter and
the three new runtime oracles), four staged CLI checks and 14 console checks
passed. The package/runner/mutation run passed 99 checks before the two additional
witness fixtures; all three applications and both holdouts also passed. The
staged archive smoke test and bundled-model integrity checks passed.

## Interpolation qualification

The callback review exposed a false operand write in generic string formatting.
Exporter 0.3.9 retains resolved implicit `toString` targets, nullable branches and
user conversion effects. String assembly now uses read-only binary concatenation.
The new isolation regression failed before this lowering and passes afterward.
The previous callback feedback regression still passes: external mapping and
constructor effects remain a separate, documented approximation.

Execution traces exposed different conversion schedules in the pinned backends.
VM JIT and AOT evaluate embedded expressions before conversion; dart2js converts
each expression before evaluating the next. The frontend follows its selected
VM/default or web target and records `stringConversionOrder`. Adjacent literals,
directly nested strings, a call boundary, conversion/expression failures and nulls
have independent execution oracles and CFG checks. No universal runtime order is
inferred from one backend.

The interpolation increment passed 104 frontend/runner/mutation/package checks,
all three applications, both holdouts and 55 native Dart tests with the Flutter
and JIT/AOT/dart2js checks enabled. Native AST coverage and resource budgets passed
for all seven packages. File and internal-method counts are unchanged; call-count
baselines were reviewed for generated captures, conversion calls and concatenation.
Endpoint expectations remain 73/75 stock and 75/75 modeled, plus four matching
holdout checks in both modes. Witness alternatives and query limits remain open.

Staged installation and bundled-model integrity checks also passed, followed by
four CLI integration checks and 14 console checks with exporter 0.3.9.

## Generated enum members

Exporter 0.3.10 adds ordinal and type metadata plus the generated `values` field
identity. The frontend initializes ordinal/private name storage, constructs the
ordered values list and provides distinct helpers for concrete enum accessors
and default string conversion. The first override regression failed because a
helper with the same name/signature displaced the source method in shared dynamic
linking; distinct helper names correct that without changing shared dispatch.
Runtime oracles cover immutable values, identity/order, enhanced named
constructors, explicit extension dispatch, overrides, super and bound methods.

Native enumeration counts explain every internal-method baseline change: one
enum in http_parser, 66 in analyzer, 14 in LocalSend, 15 in Saber, 25 in Sass and
three in yaml. Each contributes three helpers. The corresponding call increases
come from two per-constant storage writes, constant references in `values`, and
helper/list construction. All other projects keep their graph counts.

The callback feedback regression now queries the index parameter directly.
Selecting one longest return witness is not a stable way to require a particular
alternative detour; the direct query retains the METHOD_REF approximation check.
This changes the test target, not the frontend's callback model. Interface enum
dispatch, global initialization activation and allocation-sensitive heap flow
remain unqualified; see the enum contract in the conformance inventory.

Validation: 105 frontend/runner/mutation/package checks, all three applications,
58 native Dart tests (including Flutter and JIT/AOT/dart2js execution), native
coverage/resource checks for seven packages, and the staged archive/model-integrity
smoke test passed. Endpoint expectations remain 73/75 stock and 75/75 modeled.
These totals include budget-limited negative queries and are not a count of
qualified semantic guarantees.
Both holdout graph checks, four staged CLI integration checks and 14 console
checks also passed with exporter 0.3.10.

## Positive-control qualification

A failing audit regression showed that a node-to-itself witness could qualify
an unrelated negative search. The harness now requires distinct control endpoints
and records `distinctEndpoints`. The original async error/value argument checks
remain explicit structural identities. A new error-to-completer control observes
an actual receiver-write dependency; its complete stock/modeled transition and
SDK source review are in [completer-control-review.json](completer-control-review.json).
The compact report now retains `positiveControlSatisfied` for both model sets;
the old summarizer incorrectly looked for a nonexistent `positiveControlPassed`.

The default-zone runtime oracle observes the supplied error and stack trace on
the selected future, independent successful completion, and rejection of a second
completion. A separate zone oracle replaces the error with a constant object.
This demonstrates a real boundary of the optional summary: arbitrary zone
interception is not modeled. Neither oracle proves static callback delivery.
The completeError negative search still records discarded call-depth work and
remains inconclusive. Positive controls do not override query-limit diagnostics.

Validation: 98 frontend/runner/mutation checks, all seven package audits, all
three application audits and both holdout audits passed. Both new completer
runtime oracles and Dart analysis passed. The recorded source fingerprint was
independently recomputed and matches across all 12 projects. The 76 original-corpus
expectations match 74 stock and 76 modeled; four stock and five modeled negative
searches are still inconclusive because of call-depth omissions. The four holdout
expectations match in both modes. Matching an expected absence is not qualification
of an inconclusive negative.


## Complete returned-witness snapshot

[witness-reviews.json](witness-reviews.json) covers 76 queries in both modes:
112 returned witnesses, shared as 63 distinct paths with 540 classified
transitions. It includes the two stock false positives. The two one-node paths
are explicitly classified as endpoint identities, not propagation evidence.
Each transition position maps to a documented category; repeated nodes, caller
re-entry and callback feedback are retained. Stock/model limits and controls stay
separate. The 28 source-evidence file hashes match the pinned checkouts.

Call nodes now retain their target and resolved callees. Direct argument contexts,
formal indices and callback method identities make call-boundary reviews
inspectable. Every ordinary actual/formal binding in the snapshot matches a
recorded argument position and resolved callee; every return-to-call transition
matches a resolved callee. A native validation test requires all current probes,
all retained path transitions and those bindings to remain accounted for.

These checks validate the recorded representation, not path feasibility or
complete dispatch. The review distinguishes explicit copies from parameter-output
detours, field/whole-object aliases, callback-result approximations, external
argument writes and selection/position values. Readonly codec/string/encryption
receiver effects and a collapsed polymorphic rootLength result remain conservative
obligations with named owners. Five modeled negative searches are still
inconclusive; alternatives excluded by longest-witness selection are not certified.

Validation: 105 frontend/runner/mutation/package checks, three application audits
and two holdout audits passed with the expanded witness metadata. The snapshot
validation test passed. Graph baselines and endpoint expectations are unchanged.
The complete native suite passed 61 tests with Flutter and JIT/AOT/dart2js checks
enabled, including the snapshot validation and both completer execution oracles.

## Ordered catch dispatch (exporter 0.3.11)

The exporter now retains resolved catch filters. Lowering creates one total
catch dispatcher per protected body, saves exception and stack channels once,
and selects clauses in source order with an explicit unmatched rethrow. Clause
locals bind to separate saved channels; a rethrow uses its lexical handler's
values. The shared engine regression checks the actual return-to-exit dependency,
because an endpoint query alone found a parameter-to-exit shortcut even when
that return edge was missing. Exceptions handled inside cleanup preserve the
pending return; outer catches, rethrows and replacement returns do not.

The reviewed call-count delta is four calls per dispatcher, one per catch
parameter binding and one per typed filter: 1,681 calls across the original ten
projects and 30 across shelf/yaml. Exporting filter type subtrees adds 173 and four
AST nodes respectively. Internal method and file counts are unchanged. These
counts were derived independently from exporter records before updating graph
baselines. Runtime checks exercise typed/fallback selection, overlapping filters,
unmatched propagation, rethrow object identity and handled cleanup failures.
Thrown-payload dependencies between handlers and calls remain an open engine
contract; the new intrinsics identify channels without claiming that flow.

## Intraprocedural caught-value flow

The follow-up shared-engine regressions connect explicit throw operands to their
lexical catch channels. Value and stack operands remain separate, including
rethrow and constructed exceptions. Pending exception values now traverse normal
cleanup and cleanup-local handled failures. Replacement throws, returns, and
break/continue leaving cleanup discard the suspended value. Local loop jumps
preserve it. The jump regression initially admitted the original exception after
a cleanup break reached a later potentially throwing call; it now rejects that
route. The same exit-preservation walk checks pending returns.

The Dart fixture checks positive flows and isolation at actual return statements,
rather than relying on a parameter-to-method-exit shortcut. Runtime checks verify
object identity, cleanup traces, replacement constants, and local/outward jumps.
The subsequent cross-call increment addresses internal explicit throws; implicit
runtime failures remain open.

## Cross-method exception values

Separate query channels now carry escaping exception values and stacks from
internal callees. The reduced shared regression queries normal results and
caught payloads together, including repeated calls with unrelated inputs and
normal-return summaries that either preserve or omit return flow. A call used as
a source denotes its normal result, not its exception payload. Cache keys and
held-task completion preserve that distinction and the active call stack.

The Dart fixture exercises wrappers, rethrow, constructed exceptions, suppressed
and handled failures, repeated calls and stack isolation at actual return
statements. Independent runtime checks verify identity and constant replacements.
Depth-limited searches and unavailable external, unresolved or implicit exception
channels expose query limitations. This does not qualify arbitrary exception-type
feasibility, native payloads or heap precision.

## Field demands across internal calls

The former `Box(input).other` witness passed through `this.value`, the whole
returned receiver, and then the unrelated constant field. Queries now carry a
constant field path through internal return/argument boundaries, cache entries
and held tasks. Field reads on direct call results also have an explicit base
dependency; the reduced shared fixture exposed that previously missing edge.
An analyzer query exposed nonconverging recursive field contexts. Prefixes now
default to depth four, widening deeper suffixes conservatively and recording
`field-depth-widening`; reports include the field limit separately from call depth.

The shared regression queries two fields and two unrelated inputs together,
through an internal wrapper, with direct field copies and with an opaque summary.
Opaque summaries remain conservative about object layout. Dart checks add nested
members, read aliases and caught objects, with an independent execution oracle.
Stable direct reference copies now preserve receiver-alias writes, including the
lowering's generated receiver captures. The alias must have one definition that
dominates its use, with no rebinding or mutable closure capture. This policy is
limited to Dart; the shared regression retains C's value-copy isolation. Field
dependencies and definition kills compare storage identities instead of relying
on equal source text. Independent objects with identical field-access code have
a reduced regression.

Intraprocedural constant-field overwrites now remove an older value only when
every CFG route from its definition to the read crosses a replacement store.
Regressions cover both-branch replacement, an optional branch, zero-iteration and
mandatory loops, assignment exceptions, parent-field replacement, reintroduced
input and a value saved before the overwrite. The independent Dart oracle checks
both branch outcomes and bounded loop counts. Opaque summaries, mutable aliases,
closure effects, collection slots and interprocedural heap updates remain outside
this qualified subset; this does not establish general allocation-sensitive
heap analysis.
