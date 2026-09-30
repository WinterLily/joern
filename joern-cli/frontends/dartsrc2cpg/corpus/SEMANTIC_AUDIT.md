# Semantic evidence audit

Gate 1 is in progress. Endpoint regression success is distinct from reviewed
value-flow correctness. The original 67 expectations remain; eight additional
positive controls bring the suite to 75 expectations (54 positive, 21 negative).
All negative probes now name a positive control with the same source selector.
A control must resolve unique endpoints, observe a flow through any required
callee, and use the same call depth. This detects missing source connectivity;
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
header, file coverage and the exact optional model text. Stock and optional
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

The [source review ledger](semantic-reviews.json) records all 75 expectations,
source hashes, relevant conditions and endpoint dispositions. It preserves the
historical exporter 0.3.2 witnesses for comparison; it does not certify each hop.
The originally flagged route families now have reduced fixtures and complete
transition reviews. Ownership and remaining approximations are recorded below.
Other source-ledger witnesses still require transition qualification.

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
