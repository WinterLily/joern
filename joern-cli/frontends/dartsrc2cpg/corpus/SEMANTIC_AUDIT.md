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
limited to Dart; the shared regression retains C's value-copy isolation. Direct
aliases of a captured `this` remain stable because Dart cannot rebind the receiver;
captured local and parameter variables retain the conservative restriction.
Field dependencies and definition kills compare storage identities instead of relying
on equal source text. Independent objects with identical field-access code have
a reduced regression.

Intraprocedural constant-field overwrites now remove an older value only when
every CFG route from its definition to the read crosses a replacement store.
Regressions cover both-branch replacement, an optional branch, zero-iteration and
mandatory loops, assignment exceptions, parent-field replacement, reintroduced
input and a value saved before the overwrite. The independent Dart oracle checks
both branch outcomes and bounded loop counts. Opaque summaries, mutable aliases,
closure effects, collection slots and general interprocedural heap updates remain outside
this qualified subset; this does not establish general allocation-sensitive
heap analysis.

Stable reference parameters now carry field replacement through internal calls.
Output parameters have no physical CFG position, so replacement is checked at
method exit, including potentially escaping calls before the store. A throwing
right-hand side can preserve the old field; throwing after replacement does not
restore it. Rebinding a parameter is excluded from this replacement rule because
it does not replace the caller's object. Definition kills and subsequent uses
also follow stable direct aliases, preventing stale pre-call fields from bypassing
an update through an aliased argument while preserving unaffected fields.

The shared field matrix includes direct and called replacements, caller aliases,
opaque summaries and unchanged C value-copy behavior. Dart execution and graph
checks add conditional writes, saved pre-write values, independent objects,
unrelated fields, parameter rebinding and exceptions before/after a write.
This remains a bounded constant-field contract: general mutable points-to,
effects after parameter rebinding and callback-driven heap mutation remain open.

## Captured binding isolation

The reduced `capture_isolation.dart` execution oracle exposed three false
positives (an unrelated local, a shadowed local and an overwritten parameter)
and a missing nested-capture dependency. The shared engine previously connected
a captured parameter to every identifier in the closure. Capture edges now follow
REF identities and matching closure-binding IDs, including nested proxy locals.
Only reads reachable from closure entry before replacing that binding receive
the incoming value. Conditional replacement retains the route that preserves it.
Bindings without any lexical uses retain the legacy conservative parameter
behavior: Kotlin also uses them to model collection inputs delivered to lambdas.
That fallback is not a qualified lexical capture or callback-invocation model.

The shared regression checks local and parameter sources, same-name independent
storage, nested captures before and after replacement, and a bypass branch. The
Dart regression queries actual return statements and has an independent oracle
over two values and both conditional outcomes. These checks qualify incoming
capture values within the called scope; they do not qualify closure invocation
timing, arbitrary mutable callback targets, captured writes back to an enclosing
scope or all mutations between registration and invocation.

JavaScript's existing dataflow suite passes its closure checks. Its unrelated
nested-if witness-shape assertion also fails with this change removed: it expects
two intermediate comparison nodes absent from the returned path. That baseline
failure is not counted as a passing cross-language suite.

## Record construction and projection

The record fixture exposed six unrelated-field/replacement false positives under
the former aggregate operator. Record construction now initializes explicit
positional and named slots in source order, so bounded field demands can isolate
them across calls, nested records and destructuring. The positional initializer
name comes from its slot index, not the identifier or invocation used as its
value. Named fields can precede positional fields in Dart; they do not consume
positional indices in either literals or patterns. Separate mixed-order cases
exposed and cover both indexing errors.

The Dart oracle checks fourteen positive/negative field relationships over two
inputs and records the order of effectful named-field expressions. Graph tests
check both dependency isolation and exactly-once source-order evaluation.
The zero-argument record operator represents construction; subsequent field
initializations establish its contents. This does not qualify constant-record
canonicalization, arbitrary equality or all refutable pattern-shape constraints.

## Pattern comparisons and nullable equality

The [Dart pattern specification](https://github.com/dart-lang/language/blob/main/accepted/3.0/patterns/feature-specification.md)
defines the receiver direction and invocation reuse rules.

Constant patterns previously used the matched value as the equality receiver and
both constant and relational patterns omitted user operator targets. The reduced
fixture now distinguishes `constant == input` from `input == constant`, including
relational `!=` and `>`. Nullable relational equality needs lookup on the matched
interface type: analyzer 8.4.1's pattern element can name `Object.==` even though
the runtime invokes the concrete override.

Equality lowering saves both operands in source order before testing either for
null. User dispatch occurs only when both are non-null; `!=` negates that result.
The execution oracle checks null/value combinations and dynamic dispatch; the
graph regression checks internal targets, argument isolation, operand order and
the null bypass branch. Literal-null equality has no user call. These checks do
not qualify cross-case invocation caching, generic/interface target completeness,
or path-sensitive match feasibility.

Validation: all 76 native tests and 113 frontend/package tests pass. The seven
library call-count changes were independently derived from exporter facts: seven
additional calls per guarded equality, minus the former extra negation around
literal-null inequality. No source-file, internal-method or UNKNOWN count changed.
The same accounting matches all three applications and both holdouts, whose
graph and endpoint checks pass. All twelve reports share the current source
fingerprint and exporter 0.3.12. Staged packaging, four CLI integration tests and
fourteen console tests pass; the existing inconclusive queries remain open.

## Lazy pattern field extraction

A stateful getter exposed a second pattern defect: the graph lowered the same
field read independently for each case. Ordinary object and record fields now
share lazy storage within one matching construct. Parent extraction paths keep
nested receivers separate. Guards can fail after extracting a field without
forcing it to be extracted again. The runtime fixture also checks logical
patterns, switch statements, unrelated null input, object wildcard side effects,
and two independent matches in one method.

The graph retains initialization guards at each possible first-use location;
this is not a claim that the path-insensitive engine proves the guard outcomes.
Extension members retain separate storage at each source access until their
substituted invocation identities are exported. Comparison and collection
invocation reuse remain open.

The field-isolation controls exposed two shared prerequisites. Nested expression
blocks lost their final value at a return or call argument; the reaching-definition
pass now follows nested final blocks and parameter definitions. Reduced graphs
under C and Dart metadata, plus C statement-expression regressions, check both
flow and a constant final value after an unrelated input read. The synthetic
`patternShape` and `isInitialized` predicates now have read-only summaries: the
former fallback could route a field value through a pattern literal and back
into the aggregate receiver. Direct and wrapped getters, nested accesses, selected
fields and unrelated fields have paired runtime/graph controls. Queries use depth
four and check that no search-limit diagnostics occurred.

Validation passes: 50 shared-engine tests, 136 C/C++ dataflow tests, 114 Dart
frontend/package tests, all three applications and both holdouts, 77 native tests,
staged packaging, four CLI integration tests and fourteen console tests. The new
native isolation cases also pass after their addition. Every graph-count delta
matches three additional calls per pattern field (state check, negation and
initialization store); file, internal-method and UNKNOWN counts are unchanged.
All twelve refreshed reports have the same source fingerprint. Existing stock
false positives and inconclusive searches retain their prior dispositions.

## List pattern extraction and invocation reuse

List patterns previously used an opaque shape predicate, intrinsic indexing and
a rest operator. That omitted user-defined list member targets, evaluated untyped
wildcards and repeated reads across cases. Exporter 0.3.13 records the required
list type and length/index/sublist targets; lowering uses explicit size tests and
lazy extraction storage. Prefix and tail positions have different keys, while
matching tail offsets share a key across cases with different prefixes. Rest
slices retain their prefix/trailing counts. A trailing rest passes the optional
null end argument; a rest-only pattern avoids an unnecessary length read.

The pinned runtime oracle checks lengths zero through four, empty and rest-only
patterns, typed/untyped wildcards, repeated cases, prefix/tail extraction and
slice bounds. Null, string and integer elements exercise typed wildcard outcomes.
The graph regression checks targets, argument positions, shared storage and
omitted wildcard accesses. These are extraction contracts, not a claim of exact
collection-slot dataflow, complete virtual targets or generic match feasibility.
Comparison invocations inside list patterns still need their own cache handling.

Validation passes: 79 native tests, 115 Dart frontend/package tests, all three
applications and both holdouts, staged packaging, four CLI integration tests and
fourteen console tests. Graph-count changes were derived independently from native
exporter facts: length guards, skipped wildcard reads, cached prefix/tail reads
and slices account for every delta. File, internal-method and UNKNOWN counts are
unchanged. All twelve reports share the current source fingerprint and exporter
0.3.13. The existing inconclusive queries remain open.

## Restrict shared support to Dart

The earlier shared fixes also changed existing-language behavior. Those global
effects have been removed: legacy CFG return/throw/try construction, capture
lookup, block values, field matching, summary filtering and output expansion are
preserved for other languages. The extended paths require `DART` metadata.
Primitive and pattern read-only summaries use separate `<operator>.dart.*`
method full names emitted by this frontend; standard operator summaries are
unchanged. Call names still identify the standard operators for CFG construction.

Added regressions now live in the Dart frontend. Their language matrices check
legacy block, capture, return-summary, nested-output and finally behavior under
C, JavaScript, Java and Kotlin metadata alongside the Dart extensions. All prior
C/C++ test-file changes are reverted rather than changing their expectations.

Validation passes: 36 original shared-engine tests, 177 original C/C++ dataflow
and CFG tests, 16 shared CFG/dominator tests, all 40 JavaScript dataflow tests,
five JavaScript closure tests and five Kotlin lambda dataflow tests. The Dart
frontend's 135 tests, all three applications, both holdouts, staged packaging,
four CLI integration tests and fourteen console tests also pass. Legacy CFG
function bodies and standard operator summaries match the pre-Dart source;
shared-engine, shared-CFG and C/C++ test files match that source exactly. All
twelve corpus reports share the new source fingerprint. Their endpoint outcomes
and graph baselines are unchanged; inconclusive searches remain unqualified.

## Map pattern presence and invocation reuse

Map patterns previously used an opaque shape predicate and intrinsic indexing.
That omitted user index targets, conflated missing keys with present null values
and had no invocation reuse across cases. Exporter 0.3.14 retains required-map
and value types, index/containsKey targets and unit-local constant-key identities.
Lowering evaluates each key once, caches reads and presence separately within a
match, and tests entries in source order. Presence checks run only after a null
read and a successful `null is V` test. Wildcard entries still read their keys.
These rules follow the [Dart pattern specification](https://github.com/dart-lang/language/blob/main/accepted/3.0/patterns/feature-specification.md#pattern-matching).

The pinned runtime oracle exercises missing/present keys, null/string/int values,
constant aliases and null keys, failed cases/guards, separate matches, nested maps
and generic nullable/nonnullable value types. Graph regressions check resolved
internal members, short-circuit CFG bypasses, shared initialization storage and
separate receiver paths. Map membership and a mapped object's `containsKey`
getter have separate cache identities. Internal index-return flow has independent
receiver and constant-result negative controls.

Exact mutable collection slots, complete virtual targets and path-sensitive
generic match feasibility remain unqualified. Comparison and substituted
extension invocation reuse remain open. Existing inconclusive corpus searches
retain their prior dispositions.

Validation passes: Dart analyze, all 81 native tests with VM/web and Flutter
checks enabled, all 136 frontend/package tests, all three applications, both
holdouts, staged packaging, four CLI integration tests and fourteen console
tests. All twelve refreshed corpus reports share the current source fingerprint
and exporter 0.3.14; endpoint outcomes and limitations retain their prior
dispositions. The sole affected corpus pattern is Sass's main-package export
selection. Its thirteen added calls consist of a saved key, lazy read storage,
null/type/presence tests, lazy presence storage and conjunction with the value
pattern. All file, internal-method and UNKNOWN counts are unchanged. Source
changes are confined to this frontend.

## Comparison and extension invocation reuse

Exporter 0.3.15 retains comparison constant identities and the declaration and
inferred type arguments of selected extension pattern members. The lowering
caches comparison results per receiver path and constant within one match.
Relational `==` and `!=` share an equality invocation; constant-pattern equality
retains its separate receiver direction. Different constants, nested receivers
and separate matching constructs retain separate storage.

Extension cache keys distinguish declarations and inferred argument identities.
The pinned analyzer can discard substitutions when a getter or operator signature
does not mention its type parameter, despite the implementation using that
parameter. The exporter recovers arguments using the analyzer's inference routine
for the already selected extension. When recovery is unavailable, calls retain
separate storage. A regression distinguishes arguments from different libraries
even when their display names are identical.

The runtime oracles cover null receivers, failed guards, logical alternatives,
constant aliases, equality direction and generic extension bodies. Graph tests
check storage identity, resolved internal operators/getters and static extension
dispatch. These establish the exercised invocation contracts; complete virtual
targets, generic match feasibility and the existing inconclusive corpus searches
remain open.

Validation passes: Dart analyze and all 85 native tests with VM/web and Flutter
checks enabled; all 138 frontend/package tests, all three applications, both
holdouts, staged packaging, four CLI integration tests and fourteen console tests.
All twelve refreshed reports share one current source fingerprint and exporter
0.3.15. Their endpoint checks and limitations retain their prior dispositions.
The graph deltas are independently predicted from source AST facts: each
constant or relational pattern adds an initialization check, its negation and
a cache assignment. Analyzer adds 147 calls, LocalSend 126, Saber 336, Sass
3,363 and YAML 147. File, internal-method and UNKNOWN counts are unchanged.

## Scoped generic parameters and bounds

Exporter 0.3.16 retains declaration identities for type parameters instead of
collapsing unrelated declarations to names such as `T`. Class, method and alias
parameters are nested TYPE_DECLs with bound relationships and a
`dart.typeParameter` annotation. Bounds on other parameters refer to their scoped
identities. Recursive interface bounds retain their Dart signature and interface
declaration; implicit bounds retain `Object?`.

Methods, members, locals, types and aliases retain Dart generic signatures in the
existing CPG property. These preserve nullable and parameterized source/display
types alongside declaration-based type relations. Regressions cover unrelated
and nested scopes, bounds on another parameter, recursive bounds, aliases,
nullable locals, TYPE-to-TYPE_DECL links and calls selected from a generic bound.
The runtime oracle checks generic identity functions, bound member calls, alias
assignments and nullable values. Inline generic function-type scopes, runtime
type-argument substitution and promoted-type feasibility remain unqualified.

Validation passes: Dart analyze, all 87 native tests with VM/web and Flutter
checks enabled, all 139 frontend/package tests, all three applications, both
holdouts, staged packaging, four CLI integration tests and fourteen console tests.
All twelve refreshed reports share one current source fingerprint and exporter
0.3.16. Graph baselines, endpoint checks and limitations are unchanged.

## Anonymous generic function scopes

Exporter 0.3.17 anchors generic function pseudo-declarations to their source
offsets. Previously, unnamed fragments could share `#-1:GENERIC_FUNCTION_TYPE`
identities even within one alias. Function-type annotations now retain their
signature declarations and displayed types; method return annotations retain
their syntax children. Old-style callback parameters keep a separate derived
signature identity alongside the parameter's variable identity. Fresh inference
variables with no declaration use `ANY` instead of a fabricated shared identity.

Anonymous generic function signatures are TYPE_DECLs marked by
`dart.functionType`. Their parameters retain scoped bounds, and signatures nest
under the enclosing alias, method, type or bound parameter. Declarations whose
enclosing source scope is absent from the graph retain namespace placement.
Regressions distinguish two function types in one record alias, old/modern
callback parameters, getter return types and function types inside bounds.
The SDK oracle checks alias assignments and invocation results with independent
Data/num bounds. Runtime type substitution, generic tear-off instantiation and
general callback target selection remain unqualified.

Validation passes: Dart analyze, all 89 native tests with VM/web and Flutter
checks enabled, all 140 frontend/package tests, all three applications, both
holdouts, staged packaging, four CLI integration tests and fourteen console tests.
All twelve refreshed reports share one current source fingerprint and exporter
0.3.17. Graph baselines, endpoint checks and limitations are unchanged.

## Selected super and extension targets

Superclass calls, accessors and operators previously used virtual dispatch,
allowing subclass overrides to become targets of `super`. Bound superclass and
extension tear-offs also invoked their selected members virtually. These now
retain static targets. Known function-value calls select their function value;
ordinary instance tear-off wrappers still invoke the captured receiver virtually.
Static tear-offs cannot select subclass static shadows. `super` keeps a REF to
the same `this` receiver with the analyzer's superclass static type. Generated
enum superclass conversions retain their existing concrete helper identities.

This uses the same static-versus-virtual distinction as Java's call lowering.
The implementation changes are entirely within the Dart frontend; shared call
graph behavior and other language frontends are unchanged. Graph regressions
check selected target sets, ordinary virtual override controls, getter/setter and
operator value flow, bound invocation and first-versus-ignored argument isolation.
The SDK oracle exercises bounded inputs and independent implementation traces.
Covariant/generic override signatures, synthetic field-accessor overrides and
application-specific mixin superclass selection remain separate obligations.

The earlier `field_witness.dart` regression required a longest witness through
all three subclass fields. That route depended on virtual `super.write` links
back into subclass implementations. The updated regression checks correct Base
and Fields superclass links while retaining the legitimate list-element/callback
dependency. Historical transition snapshots keep their original source/version
provenance; they do not certify current routes. General receiver-field and
parameter-output precision remain open.

Validation passes: Dart analyze, all 91 native tests with VM/web and Flutter
checks enabled, the refreshed inventory test, all 143 frontend/package tests,
all three applications, both holdouts, staged packaging, four CLI integration
tests and fourteen console tests. All twelve refreshed reports share one current
source fingerprint and exporter 0.3.17. Graph baselines, reaching-definition
counts, endpoint checks and limitations are unchanged.

## Explicit generic tear-offs and stable function aliases

Explicit generic function references previously discarded their instantiated
type and complete source. They now retain the analyzer's static function type on
the value expression and its enclosing evaluation block. Bound receiver
evaluation and capture edges remain intact. This follows the
[explicit instantiation specification](https://github.com/dart-lang/language/blob/main/accepted/2.15/constructor-tearoffs/feature-specification.md#explicitly-instantiated-classes-and-functions).

Final local function copies now retain known targets through parentheses and
explicit instantiation. Target discovery uses the actual initializer value.
Previously, any last nested METHOD_REF could become the target, including a
conditional branch or a callback passed to a factory that returned a different
function. Such expressions now retain unresolved target selection. General
mutable/conditional/returned function points-to analysis, runtime type
environments and instantiation bound-check exceptions remain open.

Graph regressions check String/int instantiated types, source and REF identities,
bound captures, one receiver evaluation, target links, named/default binding and
first-versus-ignored argument flow. Negative target controls cover mutable,
conditional and returned values. The pinned SDK oracle checks runtime function
types, bounded inputs and branches, returned values and receiver/type traces.

Validation passes: Dart analyze, all 90 native tests with VM/web and Flutter
checks enabled, all 142 frontend/package tests, all three applications, both
holdouts, staged packaging, four CLI integration tests and fourteen console tests.
All twelve refreshed reports share one current source fingerprint and exporter
0.3.17. Graph baselines, endpoint checks and limitations are unchanged.

## Mixin superclass implementation unions

The selected-superclass regression exposed a separate Dart requirement: a
mixin's `on` constraint provides the static member declaration, while each
application may supply a different preceding implementation. The reduced graph
initially retained only Base, missing Prefix and an earlier Prior mixin. The
pinned SDK oracle independently selects those implementations for their
applications, including a named application and a subclass with a later override.
This follows Dart's [mixin superclass constraints](https://dart.dev/language/mixins#use-the-on-clause-to-declare-a-superclass).

Exporter 0.3.18 records lexical mixin superclass operations and preceding
implementation facts. It uses the pinned analyzer's super-invoked-name inventory,
reverse mixin application order and superclass implementation lookup. Private
names use the mixin's library, excluding same-named private members elsewhere.
A Dart-owned pass adds CALL edges to available implementations before shared
overlays. The same mixin body unions observed application targets and retains its
resolved constraint target; it does not specialize bodies or select a unique
application for each caller. Unscanned applications, unavailable bodies and
different named-parameter orders remain unqualified. General covariant/generic
override compatibility and synthetic field-accessor overrides remain open.

Graph regressions check direct/bound super calls, getters/setters, arithmetic and
index operators, source/ignored operand isolation and target sets excluding later
overrides. They also split applications into another file. The exporter regression
checks named applications and private library identities. The runtime oracle
checks bounded values and implementation traces across five receiver classes.

Validation passes: Dart analyze, all 93 native tests with VM/web and Flutter
checks enabled, the refreshed inventory test, all 144 frontend/package tests,
all three applications, both holdouts, staged packaging, four CLI integration
tests and fourteen console tests. All twelve refreshed reports share one current
source fingerprint and exporter 0.3.18. Graph baselines, reaching-definition
counts, endpoint checks and limitations are unchanged. All changes are within
the Dart frontend; other language handling and shared passes are unchanged.

## Analyzer hierarchy dispatch and implicit field accessors

Exporter 0.3.19 records concrete implementations selected by the pinned analyzer
for scanned class hierarchies. The reduced override case previously missed
covariant and renamed generic overrides under signature matching. A Dart-owned
pass now links those implementations while retaining declaration signatures and
dynamic dispatch. Private lookup uses the member's library; receiver static types
exclude unrelated sibling implementations. Abstract source declarations remain
represented but do not act as executable targets. External API declarations
remain placeholders for unavailable implementations and optional summaries.

Source instance fields now have implicit accessor bodies. Ordinary storage reads
and writes retain their existing lowering when no observed override changes the
member. Virtual and super accesses retain accessor calls, while constructor field
initialization writes storage directly. Final fields with explicit setters do not
produce duplicate setters. Lazy-field increments/decrements now include their
setter operation. Independent bounded SDK oracles check implementation traces,
covariant rejection, constructor/super storage and old/new increment values;
graph regressions check target sets and source/ignored operand isolation.

The shared dynamic linker has one directly necessary integration guard: marked
DART calls retain the frontend's selected target set. It otherwise added invalid
same-named private or incompatible targets after Dart resolution. The regression
checks marked/unmarked DART, C, Java, JavaScript and Kotlin graphs; other languages
retain existing linking rules. No other frontend implementation changed.

The [accessor count review](accessor-count-review.json) derives the method/call
increases independently from source accessor declarations and seven lazy updates.
All twelve graph baselines match those predictions. Repeated hierarchy metadata
was compacted, scanned declarations supply their accessor facts, and false flags
may be omitted; repeated symbol records are merged across units. The analyzer
export is 261,416,148 bytes, within the unchanged 268,435,456-byte limit.

Validation passes: clean Dart analysis, all 97 native tests with runtime and
Flutter checks enabled, the updated inventory check, all 148 frontend/package
tests, all three applications, both holdouts, staged packaging, four CLI checks,
fourteen console tests and shared formatting checks. All twelve refreshed reports
share current source fingerprint
`ef1fd1c0a972db8c0d602a6c45e680a229017f069850fa2ac62fe0ae6c908ea1`
and exporter 0.3.19. Original corpus endpoints remain 74/76 stock and 76/76 modeled;
all four holdout expectations match. Five modeled negatives remain inconclusive.
The historical witness review does not certify newly generated paths.

The new dispatch reports expose 105 calls without observed targets across async
and analyzer, and six unmodeled external implicit accessors at Sass's dependency
boundaries. Scanned source accessors pass the source-target audit. These counters
make missing implementations visible; they do not prove whole-program dispatch.
Unscanned subclasses/bodies, runtime receiver contexts, type-check feasibility,
differing override defaults and named output bindings remain unqualified.

DART-FLOW-006 (initial finding; see the static-storage increment below): the reduced `lazyIncrement` program stores input in a static field
and returns its old value after incrementing it. The runtime oracle confirms the
input-to-return dependency. The graph retains getter/setter calls and the input
passed to the setter, but the shared engine does not recover that dependency
across static storage. Owner: interprocedural heap analysis; this is an open false
negative, not a completed value-flow contract. No broader frontend/runtime
completion or majority-correctness claim follows from this increment.

## Named override arguments and target defaults

The reduced `override_arguments.dart` case exposed three distinct failures:
ordinary bound wrappers dropped named argument identities, output-parameter
binding used declaration positions after an override reordered names, and omitted
arguments carried defaults from the interface instead of each implementation.
An override's additional optional parameter was also absent from its invocation.
The independent SDK oracle exercises direct, bound and mixin defaults, explicit
literals equal to declaration defaults, bounded input/ignored values and writes
to independent cells. Graph checks distinguish returned extra defaults from
unused defaults and preserve the positive writer/negative unrelated receiver
pair, including a void delegate with an additional optional parameter.

Bound wrappers now forward names. Dart-only output binding selects names and
falls back to position only for opaque external `pN` slots. A Dart-owned pass
adds one ordinary adapter method per selected implementation where default
binding differs. Explicit arguments remain evaluated by the caller; each adapter
forwards values and supplies its own target's defaults. A Dart-only static-linker
guard preserves those selected adapter targets. Boundary regressions exercise
C, Java, JavaScript and Kotlin as well as marked/unmarked Dart calls; their
existing linking and output binding remain unchanged.

Corpus baselines retain the graph before default adapters. The harness checks
adapter counts, delegate target identities and named source bindings, then permits
exactly one added method and call per adapter. Opaque external slots remain
visible in `externalTargetAdapters`; they do not receive a source-name binding
qualification. See `default-argument-count-review.json` for the refreshed counts.
These structural checks and the bounded oracle do not establish runtime receiver
contexts, all callable/default interactions, arbitrary constant object state or
unavailable external signatures/bodies. Adapters also consume call depth. The
static-storage false negative DART-FLOW-006 remains open.

Validation passed clean Dart analysis, all 98 native tests with runtime/Flutter
enabled, the updated inventory check, all 153 frontend/package tests, all three
applications, both holdouts, staged packaging, four CLI tests, fourteen console
tests, shared formatting and 29 shared access-path tests. All twelve refreshed
reports and the independently checked current source tree share fingerprint
`ddf35474712ce8ae07f1e8edc1c5430e85919c69cf9af34e9911f685a4b9284f`
(389 files), with unchanged exporter 0.3.19. Original endpoints remain 74/76 stock
and 76/76 modeled; all four holdout expectations match. Five modeled negatives
remain inconclusive, and historical witness snapshots do not certify new paths.

The representation adds 50,929 adapter methods across these twelve graphs,
including 6,006 delegating to external placeholders. Large hierarchy unions,
especially in Flutter applications, multiply adapters per invocation; this cost
is visible in the reports and needs further resource qualification. These counts
are generated representation nodes, not newly implemented source methods or
proofs of feasible runtime targets. No majority-correctness or frontend completion
claim follows from this iteration.

## Shared default adapters and bound entry-point captures

The preceding checkpoint's per-call adapters duplicated LocalSend's 967
Object.toString invocation unions into 39,647 methods. Each library now shares
bodies with equal declarations, exact targets and supplied-slot masks. Bound
declarations retain their own capture identities. Generic delegate code uses
parameter/default expressions instead of the first caller's source text.
The report separates unique adapter bodies from call-to-adapter links.

The new cross-file SDK oracle checks named and positional override defaults,
receiver/argument evaluation traces, repeated calls and separate captured
receivers. Graph checks preserve the source CFG order, exactly-once receiver
invocation, positive/negative invocation contexts, different omission masks,
distinct captures and returned-versus-unused positional defaults. These checks
retain the target union rather than narrowing it for smaller graphs.

A stronger captured-state query revealed a false negative in the previous
checkpoint as well as the reuse implementation. Copying the original closure ID
onto the adapter's local did not make capture discovery visit the adapter body.
Bound entry points now have ordinary METHOD_REF/CAPTURE edges created alongside
the original bound reference. They share its saved receiver; the original bound
reference remains the resulting block value. The SDK oracle returns the second
receiver's state; graph queries now retain that receiver's dependency and reject
the independent first receiver's dependency. No shared engine changes were
needed. This qualifies the reduced interface/capture case, not general mutable
callable or allocation-sensitive heap behavior.

All 99 native tests, clean Dart analysis, the updated inventory, all 155
frontend/package tests, three applications, both holdouts, staged packaging, four
CLI checks, fourteen console tests and formatting checks pass. Twelve refreshed
reports share independently checked source fingerprint
`9e629ed09004d9533c8160ef55c5ccd7c00a7a27f7932df195c0473a403c8b67`
(391 files), with unchanged exporter 0.3.19. Adapter bodies decrease from 50,929
to 7,606; all 50,929 call-to-adapter links remain. Of the unique bodies, 522
still delegate to opaque external placeholders. LocalSend now has 2,747 bodies
for the same 39,647 links. Namespace/declaration/mask boundaries limit sharing;
call depth and broader resource/type/receiver qualification remain open.

Original endpoint expectations remain 74/76 stock and 76/76 modeled; all four
holdout expectations match. Five modeled negative searches remain inconclusive.
Historical witness snapshots do not certify these refreshed paths. The static
setter/getter false negative DART-FLOW-006 and the remaining coverage-plan gates
are unchanged.


## Captured enclosing parameters retain caller context

The previous receiver-object query did not exercise primitive input forwarded
through an ordinary caller and constructor. The SDK oracle now constructs two
NamedState objects from separate strings, invokes the left bound receiver and
returns the right receiver's state. The graph missed the second string at call
depths four and eight with no reported query limitation. The captured read had
reached the lexical owner's parameter while its closure invocation remained at
the top of the call stack, so parameter expansion looked for arguments at the
wrong call site.

TaskCreator now removes Dart closure frames only when ordinary METHOD_REF/CAPTURE
edges establish their lexical owner, including nested captured scopes. It retains
the selected ordinary caller, call depth accounting and constant-field demand.
The regression checks constructor values, original receiver rebinding after
capture, nested aliases and named input isolation at depths four and eight with
empty diagnostics. The independent SDK oracle checks empty and nonempty inputs.
A graph-level boundary regression covers direct matched calls, absent CAPTURE,
wrong owners, nested captured scopes and C, Java, JavaScript and Kotlin. Foreign
language contexts return through the existing path without context adjustment.
This shared prerequisite fixes the reproduced Dart failure; it does not qualify
arbitrary mutable callable targets, allocation-sensitive heap updates or static
storage.

All 99 native tests, clean Dart analysis, the updated inventory, all 157
frontend/package tests, three applications, both holdouts, staged packaging,
four CLI checks, fourteen console tests, 29 shared access-path tests and formatting
checks pass. The twelve refreshed reports share independently checked fingerprint
`95938dc61bd10cdb3e17cca97470e2afee7d232be68097ab849f39fbe5f3d7f5`
(392 files), with unchanged exporter 0.3.19. Counts remain 7,606 unique default
adapters, 50,929 links and 522 unique external delegates. Original endpoint
expectations remain 74/76 stock, 76/76 modeled and 4/4 holdout. Five modeled
negative searches remain inconclusive; historical snapshots do not certify
current paths. DART-FLOW-006 and the remaining plan gates stay open.

## Bounded witness alternatives and combination limits

This section records checkpoint `5880702d2`; the static-storage increment below
supersedes its source fingerprint and negative-limit counts.

The opt-in Dart witness bound retains distinct raw paths through intraprocedural,
held-task and final selection. It preserves call stacks, output flags/channels
and field demands; the default and foreign-language selection remain unchanged.
The separate held-task round bound reports `held-task-iterations` when pending
combinations remain. A negative result under that limit is inconclusive. Tests
exercise three feasible value routes, independent repeated calls, deterministic
truncation, held-task reuse and the C/Java/JavaScript/Kotlin boundary. An independent
SDK oracle exhausts two input values and both branch booleans with exact traces.

The [alternative results](alternative-witness-results.json) cover all twelve saved
graphs and 80 endpoint expectations in each model mode, including the four holdout
checks. At call/field depth four, two witnesses and two held-task rounds, stock
semantics returns 86 visible and 89 detailed paths; optional models return 81 and
83. The corresponding ordinary reports return 59 and 57 visible paths. These
counts describe returned node sequences, not independently feasible executions.
There are 41 stock and 40 modeled queries with recorded limitations; five queries
in each mode record an unfinished held-task search. Six modeled negatives remain
inconclusive in this configuration. No reporting cap omits returned witnesses.

The [media-type review](media-type-alternative-review.json) classifies all six
transitions across both returned capture paths. The newly visible two-node route
reads the enclosing factory parameter directly in its callback. The six-node
route contains conservative String argument-output effects through
`wrapFormatException`; it does not establish runtime parameter mutation. The
source invokes the callback synchronously. The opt-in `corpus_capture_test.dart`
executes http_parser 4.1.2 on two valid and two independent invalid inputs, checking
parsed content and retained error sources. The sink remains the external scanner
argument; this does not qualify its implementation or the subsequent parser.
Other new corpus alternatives still require transition review.

The [budget record](alternative-witness-budget.json) preserves a historical
four-witness run that exceeded a seven-minute package-worker wall budget and a
later two-witness, fifty-round interruption, both at the analyzer callback query.
A round bound does not bound one round's runtime. The completed two-round package
audit took about five and a half minutes; application and holdout queries also
completed. Larger searches remain resource qualification obligations.

At that checkpoint, ordinary and bounded reports shared source fingerprint
`4ca12f51b696f7688a2fbf704dbc951a937b05b91fb0e5021145f90eea7b6a6a`
(396 files) and exporter 0.3.19. The summary scripts independently verify the
implementation fingerprint and the reviewed raw paths/call contexts.
Ordinary endpoints remain 74/76 stock and 76/76 modeled, with five modeled negative
searches inconclusive; holdouts remain 4/4. Validation passed 101 native runtime/
Flutter/corpus tests, 162 frontend/package tests, three application graphs, two
holdout graphs, staging, four CLI tests, fourteen console tests and formatting.
The optional saved-graph audit is separately enabled and passed all three corpus
categories. Historical reviewed snapshots do not certify these new alternatives.
DART-FLOW-006 static storage and the remaining coverage-plan obligations stay open.

## Bounded static-storage prerequisite

DART-FLOW-006's original `lazyIncrement` input-to-return regression now passes.
The Dart-only shared engine follows static reads through CFG predecessors and
resolved calls, retaining the selected caller context and stopping each route at
its nearest matching owner/member store. It creates an ordinary RHS value task;
no accessor signatures, receiver parameters or source DDG edges are invented.
Foreign and instance-field traversal retain their prior behavior.

The independent `static_storage_test.dart` oracle exercises three inputs and both
branch booleans. Graph controls cover direct/nested stores, independent fields
and owners, repeated helper calls, conditional/loop writes, overwrites on both
branches, and saved reads before subsequent writes. Depth and node-budget
exhaustion are diagnosed. The [reviewed storage snapshot](static-storage-witness-review.json)
retains six returned paths for `direct`, `nested` and `lastCall`, with dispositions
for all 123 transitions and their complete call contexts. Void method exits are
memory-effect evidence, not scalar return dependencies. Generated accessor code
repeats declaration text; operator/argument identities distinguish actual stores
and reads. All reviewed exchange frames select `exchange(input)`.

This does not close DART-FLOW-006. Initialization-state correlation remains
conservative. The runtime `exceptional` case stores input in a throwing helper
and reads it in a catch; its graph query still misses that memory effect and
reports `static-storage-exception-state`. External effects, initialization checks
and unfinished storage searches also make absence inconclusive. The ordinary
corpus now has seven modeled negative searches with limits: `chunk-empty-return`
and `config-not-directory` join the previous five. Endpoint expectations remain
stock 74/76, modeled 76/76, and holdout 4/4.

All twelve graphs and reports share fingerprint
`bd43efa880caaa4af2e98f2655a0d8bee1e00e961bb7520ee7d016a0ca2cf70d`
(399 analysis files); exporter version remains 0.3.19. Independent source/count
checks passed. Qualification passed 102 native runtime/Flutter/corpus tests,
165 frontend/package tests (the optional saved-graph audit is canceled in this
ordinary run), three application graphs, two holdouts, staging, four CLI tests
and fourteen console tests, with clean analysis and formatting checks.

The refreshed two-witness/two-round audit retains all 80 endpoint observations:
stock 86 visible/89 detailed paths and modeled 81/83, unchanged from the preceding
snapshot. Limits affect 43 stock and 42 modeled queries; seven stock and eight
modeled negative searches are inconclusive. Both modes hit the 10,000-position
storage budget on `plural-count-not-name-control`; each still has five queries
with held-task round limits. The http_parser capture review was rechecked against
the current raw paths. Other new corpus routes remain pending transition review.
Larger search budgets and the remaining coverage-plan gates are unfinished.

## Static memory on exceptional and resumed cleanup exits

This section records checkpoint `d98a3b345`; the RHS increment below supersedes
its implementation fingerprint and original field-copy false positive.

The preceding static-storage section records checkpoint `bba06f047`; this
increment supersedes its source fingerprint and the missed `exceptional` case.
That throwing-helper/catch dependency now passes. The CFG producer retains its
existing exit kinds as Dart-only `dart.cfg.exit` tags: normal completion, failure
before a call completes, and pending exceptions resumed after cleanup. ASTs,
CFG edges and accessor signatures retain their existing meaning. Storage queries
keep private pending normal/thrown demands through selected callee frames;
cleanup returns/throws replace an exit, and later matching stores kill earlier
values. Old graphs lacking the exit contract report a limit and need regeneration.

The new cross-file graph and independent `static_exception_storage_test.dart`
oracle cover normal versus caught reads, nested throwing calls, repeated-call
isolation, caught overwrites, conditional/unconditional cleanup, unrelated-slot
cleanup, nested cleanup, rethrow, swallowed exceptions and return/throw replacement.
The native oracle executes three input values and both branch booleans. A shared
CFG regression checks the exit tags and the C/Java/JavaScript/Kotlin boundary;
existing CFG and dominator regressions also pass. Unmodeled implicit failure
origins stop with `static-storage-implicit-exception-effects`, rather than
assuming a failed assignment can expose an earlier unrelated store.

DART-FLOW-007 remains a concrete shared-engine defect. `copiedCleanup` copies
`Shared.other` into `Shared.value` in its finally body. `otherBranch` writes input
only before throwing, and writes a constant on its normal branch. The runtime
`copiedNormal` returns that constant or throws; the graph returns an input path
with `static-storage-joined-exits`. The matching store schedules an ordinary RHS
value task that loses the pending exit demand. `copiedCaught` is the nearby
positive control. Direct joined-cleanup normal reads reject the throw-only input,
but their absence remains inconclusive under the other recorded limits.

The [exception snapshot](static-exception-witness-review.json) retains all returned
14 paths (398 transitions) from the selected reduced queries, including both
false-positive paths, exact call contexts and producer exit tags. Every returned transition has a disposition;
void memory-effect exits are distinct from scalar returns and exception payloads.
The [ordinary storage snapshot](static-storage-witness-review.json) is refreshed
under the same implementation. These are bounded dependency reviews, not complete
CFG execution traces or feasibility proofs. Initialization-state correlation,
external effects, implicit runtime failures and the remaining plan gates stay open.

All twelve graphs share independently checked fingerprint
`3877d5d83af53418f3e3f3fa785694f63e39f9248dcedc0ad6a1484ab2020ca6`
(400 analysis files), with unchanged exporter 0.3.19. Ordinary endpoint observations
remain stock 74/76, modeled 76/76, and holdout 4/4. Seven modeled negative searches
remain inconclusive. The two-witness/two-held-round audit retains all 80 endpoint
observations per mode (stock matches 78/80, modeled 80/80) and retains the preceding 86 visible/89 detailed stock paths
and 81/83 modeled paths. Limits affect 43 stock and 42 modeled queries, including
seven stock/eight modeled inconclusive negatives and five held-round limits per
mode. Its package worker took 6 minutes 47 seconds. The HTTP capture review and
deterministic compact summary were rechecked against these current raw contexts;
other new corpus alternatives still require transition review.

Validation passed 103 native runtime/Flutter/corpus tests, clean Dart analysis,
167 frontend/package tests (the optional saved-graph audit is separately enabled),
three application graphs, two holdout graphs, staging, four CLI tests and fourteen
console tests. Focused shared CFG/dominator checks and formatting passed. This
increment fixes exceptional storage traversal and explicitly records the remaining
RHS-demand defect; it does not close initialization, heap or release qualification.

## Preserve cleanup exit demands through RHS value tasks

This section records checkpoint `09b8f5a2c`; the local-exit increment below
supersedes its fingerprint and the DART-FLOW-008 counterexample.

DART-FLOW-007's original `copiedNormal` false positive is rejected. Static-storage
RHS tasks retain demands keyed by method and selected caller stack; ordinary DDG
expansion, parameter/return/output tasks, task caches, held combinations and
witness identity preserve them independently of scalar/exception channels and
field demands. A subsequent getter restores the matching invocation's pending
cleanup exit. Detailed witnesses serialize the demands. Foreign/default tasks
have empty demands and retain their existing traversal and selection behavior.

The graph controls cover field copies, a saved getter value through two helpers,
an intervening other-slot overwrite, repeated independent inputs and simultaneous
normal/caught sinks from the same call site. Depths four/eight and default/two-
witness selection pass. The independent SDK oracle executes three inputs and
both branch booleans. `copiedBothNormal` and `copiedBothCaught` also provide a
same-source negative/positive pair that exercises task/cache context isolation.

The [copy snapshot](static-cleanup-copy-witness-review.json) retains 12 paths and
311 classified transitions across 13 queries, including complete call contexts,
producer exit tags and serialized demand sets. Forwarding controls share the
exact negative source endpoints. Four absence observations match their expected
endpoints but remain inconclusive under the recorded limits. The original
[exception snapshot](static-exception-witness-review.json) is historical evidence
for the defect before this fix, rather than certification of the new queries.

DART-FLOW-008 is the remaining diagnosed local-join counterexample.
`localCopyCleanup` assigns input to a local only before throwing, then copies that
local into static storage in finally. Its normal runtime result is constant.
The query retains a normal demand but ordinary local reaching definitions still
join the throw-only assignment into that normal result. `localCopyCaught` returns
input on the thrown branch and supplies the nearby control. The snapshot retains
both routes and their differing demands. This establishes context transport and
the selected field-copy correction, not general DDG feasibility or initialization/
heap qualification.

All twelve graphs share independently checked fingerprint
`401483031286b439c21e6e9630971b286c2f6625e2d44667c5d43a2d9e03b01e`
(400 analysis files), with unchanged exporter 0.3.19 and unchanged representation
counts. Ordinary endpoints remain stock 74/76, modeled 76/76 and holdout 4/4;
seven modeled negative searches remain inconclusive. Validation passed all 103
native runtime/Flutter/corpus tests, clean analysis, the inventory check, 167
frontend/package tests, three application graphs, both holdouts, staging, four
CLI tests, fourteen console tests and 29 shared access-path tests. Focused storage/
witness selection boundary tests and formatting passed. Remaining coverage-plan
obligations stay open.

The refreshed two-witness/two-round audit preserves all 80 observations per mode
(stock matches 78/80, modeled 80/80),
with unchanged 86 visible/89 detailed stock paths and 81/83 modeled paths.
Limits affect 43 stock and 42 modeled queries; seven stock/eight modeled negatives
remain inconclusive, and each mode has five held-round limits. The package worker
completed in 9 minutes 48 seconds within the monitored ten-minute budget;
applications and holdouts also passed. The profile places the expensive work in
held-task witness ordering/combination. This run does not qualify historical
larger searches. The HTTP capture review, source/count checks and deterministic
summary were regenerated and verified against current raw contexts. Other new
corpus alternatives and the remaining plan gates still require qualification.

## Local value availability under cleanup exits

DART-FLOW-008's original `localCopyNormal` false positive is rejected. RHS tasks
check local reaching definitions against bounded CFG routes under the selected
invocation's pending exit. Assignment values are available at the completed
assignment, and crossing the relevant cleanup consumes its demand. Earlier handled
cleanup may therefore have a different exit kind. The Dart-only producer contract
is `complete:2`: it distinguishes normal cleanup entry from failure before a
protected operation completes. Catch entry also distinguishes a completed cleanup
resuming an exception from a failed cleanup operation. Older exit metadata is an
explicit limit requiring graph regeneration.

Graph and independent SDK controls cover normal/caught local copies, loop copies,
simultaneous normal/caught sinks, return replacement, inline cleanup and a saved
value from earlier handled cleanup. The last case returns input through a helper;
its constant-return replacement rejects the prior input. Depths four/eight and
one/two-witness selection pass. A one-position value-search budget produces an
explicit `static-storage-value-search` limit rather than an asserted semantic
negative.

The [local exit snapshot](static-local-exit-witness-review.json) retains all
11 returned paths and 199 classified transitions across 13 queries. Four absence
observations have matching same-source positive controls and budgets, but remain
inconclusive under their recorded limits. The prior-cleanup paths separately
classify scalar return and conservative unchanged-argument output; the latter
does not establish String mutation. The inline cleanup retains two value-temporary routes to the same setter;
both preserve the thrown demand, and their distinct nodes remain in the review. The [ordinary storage snapshot](static-storage-witness-review.json)
is refreshed with six paths and 123 classified transitions. The field-copy and
exception snapshots retain their historical provenance. These bounded reviews
are dependency evidence, not complete execution traces or predicate proofs.

All twelve graphs share independently checked fingerprint
`da1afc7d6dd7d24b92bb946dbc1422655fe666e5231d32887d2477d8f98ec028`
(402 analysis files), with unchanged exporter 0.3.19 and representation counts.
Ordinary endpoints remain stock 74/76, modeled 76/76 and holdout 4/4; seven modeled
negative searches remain inconclusive. Validation passed 103 native runtime/
Flutter/corpus tests, clean Dart analysis, 167 frontend/package tests, three
application graphs, both holdouts, staging, four CLI tests and fourteen console
tests. Focused storage/witness tests, 16 shared CFG/dominator checks, 29 shared
access-path checks and formatting also passed. Initialization-state correlation,
cross-context values, implicit/external effects, broader heap/lifecycle contracts
and the remaining coverage-plan obligations stay open.

The refreshed two-witness/two-held-round audit preserves all 80 observations per
mode: stock matches 78/80 expectations, including the two known false positives,
and modeled matches 80/80. It retains 86 visible/89 detailed stock paths and
81/83 modeled paths. Limits affect 43 stock and 42 modeled queries; seven stock/
eight modeled negative searches remain inconclusive, and each mode has five
held-round limits. The package test completed in 8 minutes 48 seconds within the
monitored ten-minute worker budget; applications and holdouts also passed. A
worker sample observed approximately 6.5 GiB resident memory, with active held-task
witness deduplication. This sample is not a peak-memory qualification. Larger
historical searches remain inconclusive. The HTTP capture review and deterministic
summary were rechecked against current raw contexts; other corpus alternatives
still require review.

## Review selected bounded forwarding alternatives

The [forwarding snapshot](forwarding-alternative-review.json) reviews seven
path/async/http_parser queries under checkpoint `234571fe5`: 25 selected stock/
modeled paths share 18 distinct detailed routes and 517 classified transitions.
It retains exact nodes, call targets, argument slots, selected caller contexts,
output flags, field demands, model hashes, source evidence and query budgets.
Scalar return bindings and constructor argument-output return evidence already
in a caller frame have separate categories. The latter is conservative output
analysis, rather than proof of a scalar return or runtime mutation.

`normalize-fast-return` and `set-extension-path` retain readonly output/caller,
aggregate and external-call detours. The two stock `error-not-stacktrace` routes
remain known false positives: direct argument mixing and receiver-mediated mixing.
Its versioned optional summary rejects the mix with the same-budget positive
control. `value-to-event-sink` selects the same graph node as source and sink;
its longer wrapper/aggregate routes do not provide independent forwarding evidence.
The stock byte-copy routes follow size/allocation and external argument effects,
while the byte model retains direct input-to-bytes forwarding. Returned-buffer
routes revisit selected conversion callers and retain conservative copy/receiver
effects. Neither route family qualifies arbitrary callback delivery, allocation
identity or byte slots. The error-message alternatives differ in a saved-value
temporary and retain the explicit interpolation dependency on its selected catch.

A new independent pinned-package execution check runs http_parser 4.1.2 with
source_span 1.10.2 on three message inputs. It checks exactly-once synchronous
body execution, an independent normal result, FormatException message interpolation
with preserved independent source/offset, and the separate span exception branch
with unchanged span identity and a message independent of the input. This
supplements the captured-input oracle and the existing reduced readonly/byte-copy
and SDK completion controls. It does not qualify the full parser pipeline or
arbitrary event scheduling.

The compact corpus summary now links these seven dispositions after verifying
source/exporter/model provenance, query limits and exact reconstructed raw paths
in both modes. Seven deliberate mutations are rejected: stale analysis sources,
changed caller context, changed output flag, missing transition, changed depth,
unknown category and stale execution oracle. Repeated summary generation is
byte-identical. Six focused native oracle tests, clean Dart analysis and the
inventory check pass. The twelve graph fingerprints/counts were independently
rechecked and remain those of `234571fe5` (402 analysis files); graph/query
implementation and the recorded bounded results are unchanged.

The HTTP capture review remains current. Other corpus alternatives, larger
searches, inconclusive negatives and the remaining coverage-plan gates stay open.
No checkbox closes on the basis of these selected routes.

## Avoid unnecessary bounded witness ranking

Dart's opt-in witness selection retains its longest-first ordering and full tie
breaker while skipping work that cannot affect the chosen rows. Identical table
entries and singleton length groups need no key. Length groups are processed in
descending order until the witness bound fills; unvisited shorter groups still
report omitted alternatives. Equal paths with differing task stacks rank before
path deduplication. Default and foreign-language selection retain their prior
implementation. The new frontend-owned `BoundedWitnessOrderingTests` covers these
contracts, stable ties and field/output flags. Its unnecessary-work regression
fails against the original implementation.

The [ordering review](witness-ordering-review.json) retains single observations
of the saved analyzer `list-element-callback` stock query: original 280,380 ms,
prototype 118,987 ms and final 109,570 ms, including graph loading and query work
but excluding sbt startup/compilation. Temporary prototype instrumentation sampled
4,325 of 21,627,390 selection invocations, including trivial single-candidate calls;
old/new choices and fresh pruning diagnostics matched for every sampled input.
Instrumentation is removed. These are measured workload observations and selection
checks, rather than a general performance guarantee.

Before optimization, the standalone baseline did not reproduce the saved
package-audit detailed paths, although endpoints, route counts and diagnostics
matched. That initial assertion failure is retained; exact whole-query replay
remains unproven. The reviewed seven forwarding queries were therefore refreshed
from their actual current returned paths and rechecked against pinned source and
binding metadata. They retain 25 selected paths shared as 19 distinct routes and
533 classified transitions. Several differ in temporary/caller details from the
previous snapshot. The HTTP capture, six-path ordinary storage and eleven-path
local-exit reviews also have current provenance. Historical copy/exception
snapshots keep their original fingerprints.

All twelve graphs share independently checked fingerprint
`067ae48b90f8f891485eedfc142d904ce8069cd5fd5b82a3dff90ca521385e7c`
(403 analysis files), with unchanged exporter 0.3.19 and representation counts.
Validation passed 104 native runtime/Flutter/corpus tests, 172 frontend/package
tests, three application graphs, both holdouts, staging, four CLI tests and
fourteen console tests. Twelve focused storage/witness tests and formatting pass.
Ordinary endpoint observations remain stock 74/76, modeled 76/76 and holdout 4/4;
seven modeled negative searches remain inconclusive.

The refreshed two-witness/two-held-round audit passes its baseline comparisons on
all twelve graphs. Stock matches 78/80 expectations, including its two known false
positives; modeled matches 80/80. It retains 86 visible/89 detailed stock paths and
81/83 modeled paths, with limits affecting 43/42 queries, seven/eight inconclusive
negative searches and five held-round limits per mode. The package test took
5 minutes 11 seconds within the monitored ten-minute worker budget; applications
and holdouts passed. Summary generation is byte-deterministic, and all seven
review-verifier rejection mutations pass. The qualification worker's observed
automatic maximum heap was 8,162,115,584 bytes; the sbt parent's 6 GiB setting is
separate. Resident-memory samples do not qualify peak memory. Larger searches,
other route families, negative-search qualification and all remaining plan gates
stay open.

## Four-witness resource qualification and holdout transition review

The [isolated four-witness results](four-witness-results.json) use the same twelve
saved graphs and analysis fingerprint as the ordering qualification. Both model
modes preserve every ordinary and two-witness endpoint outcome and source/sink
count: stock matches 78/80 expectations, including its two known false positives;
models match 80/80. There is no reporting truncation. Stock retains 120 visible/
131 detailed paths; models retain 109/117. Query limitations affect 42/40 checks,
with seven/eight inconclusive negative searches and five held-round limitations
per mode. The full detailed sequences include 48/39 sequences absent from the
two-witness snapshot, while six/five previous sequences are not retained. Different
temporaries and contexts can change those comparisons; these counts do not count
new independently feasible semantic routes.

Each category ran in a separate subprocess with a ten-minute wall-clock budget,
an explicit forked-worker maximum heap of 8 GiB and four worker processors. The
package run completed in 396,421 ms, applications in 43,259 ms and holdouts in
27,156 ms, including startup and graph loading. Once-per-second process-family
RSS samples reached 5,965,540 / 5,213,700 / 2,279,068 KiB respectively. These are
sampled resident sums, not measured peaks or one worker's heap. This successful
four-witness/two-round configuration does not certify the historical unbounded
four-witness or fifty-round interruptions. The missing timing executable and a
temporary test-driver compilation error were setup failures corrected before
these runs; neither was a semantic or resource outcome.

The [holdout review](holdout-witness-review.json) preserves all nodes, resolved
calls, field demands, visibility/output flags and empty caller stacks for the
four initial Shelf/YAML queries in both modes. Six selected paths share three
distinct routes with 19 classified transitions. Shelf projects `request.url`,
saves it for interpolation and passes the resulting text to `Response.ok`.
The two routes differ in a generated concatenation node. URI conversion remains
an external return approximation. YAML passes source slot one through
`loadYamlNode`, `loadYamlDocument` and `Loader`; each binding has an exact internal
target and formal slot. This is source forwarding, not parser correctness.

The status/recovery isolation queries have distinct endpoints, positive controls
with identical sources and budgets, and no recorded query limitation. Shelf's
status is the redirecting constructor's constant 200; YAML forwards `recover`
independently from document text. The native oracle executes the actual pinned
Shelf handler and YAML APIs on three independent inputs, both recovery flags,
two recoverable missing-colon errors and one fatal parser error. An initial
assumption that recovery accepted incomplete flow sequences failed against the
actual library; the corrected oracle retains that fatal boundary.

Validation passes all 106 native tests with runtime, Flutter, corpus and holdout
flags enabled, final-file analysis and formatting, independently checked unchanged
403-file/all-twelve-graph provenance, byte-identical summary regeneration and
thirteen verifier mutations for stale sources/oracles, altered demands/calls/flags,
missing classifications/queries, reporting omissions and unfinished resource runs.
The verifier reconstructs detailed paths before linking the holdout review.
Other new route families, the eight modeled inconclusive negative searches and
the remaining plan gates stay open. No server callback delivery or full parser
pipeline qualification follows from these observations.

## Analyzer byte-write and callback alternative review

The [analyzer review](analyzer-alternative-review.json) adds four query families
from the current four-witness/two-round snapshot: byte forwarding, list callback
input, reset isolation and its positive control. Twenty-four selected paths share
15 distinct routes with 529 classified transitions. Source file hashes cover all
represented internal methods, and the compact dictionaries preserve exact call
slots, selected stacks, visibility/output flags and field demands. Binding and
return transitions were checked against resolved targets and available frames.

The source directly forwards `writeByte` to `_addByte`, which stores its byte RHS.
The returned byte-write and positive-control routes instead leave through readonly
integer output slots, select enum/directive-kind or length/offset callers and then
re-enter the writer. They differ in caller context. These are the conservative
argument-output mechanism already tracked by DART-FLOW-004, not runtime integer
mutation, allocation identity or a proof of the intervening caller order.

`writeList` supplies `items[i]` to its callback. Its longer routes select a
`typeParameterFragments` caller, traverse metadata, record positional fields or
type arguments, then re-enter another list-writing call. Selected `length` getters
and external `getUint8` effects add receiver/field and offset-to-buffer detours.
Stock and modeled reports return different routes with the same endpoint outcome.
These mechanisms are conservative shared-engine/library/dispatch approximations;
they do not prove exact element provenance or a particular runtime list class.
The endpoint is the callback argument, not callback delivery through a framework.
The pending broader heap/dispatch/model obligations own those remaining claims.

The reset sink assigns literal zero when the fixed buffer capacity is reached.
The negative query returns no path and records no query limitation. Its control
uses the identical `_addByte` source and budgets and reaches the byte store, while
retaining the control's own depth/field/external/pruning limitations. The constant
source assignment explains the bounded absence; this does not establish arbitrary
exception or heap feasibility.

`binary_writer_test.dart` executes the pinned analyzer 8.4.1 implementation.
Bytes 0, 1 and 255 survive 131,073 writes across the 128 KiB flush boundary, with
independent writers and offsets. Empty, single and three-element immutable lists
produce exact callback visits and serialized bytes; interleaved writes to another
writer remain independent. These exercised standard lists do not certify the
internal getter/type-fragment detours in the saved witnesses.

Both review sets pass thirteen verifier rejection checks each, including missing
declared queries, altered fields/calls/flags/budgets, stale source/oracle hashes,
reporting omissions and incomplete resource runs. The summary is byte-identical
on regeneration and now links eight reviewed query families. All 108 native tests
pass with runtime/Flutter/corpus/holdout enabled, as do final-file analysis and
formatting and unchanged analysis provenance. The installed native BinaryWriter
source matches the pinned graph source byte for byte. Other additional
routes and negative searches remain open.

## Complete bounded Sass query review

The [Sass snapshot](sass-alternative-review.json) covers all eleven compiler
queries in both modes at witness bound four and two held rounds. Forty-one
selected paths share 23 distinct routes and 330 classified transitions. Seven
represented source files retain pinned hashes, including synchronous/asynchronous
compiler and evaluator callers. Full call slots, selected stacks, callback targets,
visibility/output flags and empty demand sets are preserved. The summary verifies
source/exporter/models/budgets and reconstructs each detailed path before linking
its disposition.

Public and internal string APIs forward the source directly to their selected
formal parameters. CSS/SCSS factories have one direct route and two readonly
contents-output detours through synchronous/asynchronous `readFile` callers.
Those longer DART-FLOW-004 routes do not prove mutation of immutable Strings,
actual file reads or runtime selection of either syntax branch.

Trimming returns an empty string for all-whitespace input or a substring between
computed bounds. The retained routes instead visit `_firstNonWhitespace`'s
unchanged argument output and `_lastNonWhitespace`'s length/index/return values,
then cross external `substring` arguments from end to start to receiver. Their
local index visits and end-return branches differ. These cross-argument effects
are conservative library approximations, not mutations of String or integer
inputs; depth, held-round and pruning limitations remain explicit.

Indentation's modeled route follows the line lambda's parameter/return through
its METHOD_REF and join under the opt-in iterable contract. Stock instead leaves
`indent` through a scalar return into `bulletedList`, traverses its callback result
and collection receiver, and re-enters the callback and `indent` through a rest
pattern. That route retains callback/receiver/caller approximations; it is not a
runtime collection mutation or arbitrary delivery witness.

The plural name reaches its singular return directly. Count influences branch
selection but has no explicit payload path to that return, with no recorded
negative-query limitation. Its comparison-input positive control uses the same
source and budgets, while keeping its own extensive query limitations and
readonly evaluator-caller detours. Verbosity isolation retains call-depth and
pruning diagnostics and remains inconclusive even though source and execution
controls keep the source and option separate.

The pinned execution oracle in `application_boundaries_test.dart` exercises the
actual Sass 1.89.2 public APIs, CSS/SCSS parser factories and utilities. Three colors
produce distinct text, source spans and compiler output with both verbosity flags;
public String/result APIs agree. Additional cases cover ASCII/non-ASCII whitespace,
CSS escape preservation, independent names/counts/explicit plurals, indentation,
empty/single/multiline bullets under explicit ASCII/Unicode glyph modes and
separate inputs. An initial single-bullet expectation omitted its trailing
newline and indentation: the source list pattern matches with an empty rest,
which is then indented. The corrected oracle checks that boundary in both glyph
modes. This is exercised compiler
and utility evidence, not whole-pipeline or general branch-feasibility proof.

All three application library hashes remain pinned. Validation passes final-file
analysis/formatting, unchanged 403-file/all-twelve-graph analysis provenance,
byte-identical report regeneration and eleven verifier rejection cases, including
altered caller stacks, output flags and callback targets. Existing holdout/analyzer
rejection checks also pass. The full native suite passes 109 tests with runtime,
Flutter, corpus, holdout and application flags enabled. Other additional routes,
eight modeled inconclusive negative searches and the remaining plan gates stay open.


## Complete bounded LocalSend and Saber query review

The [LocalSend/Saber snapshot](applications-alternative-review.json) covers all
22 queries in both modes: 84 selected paths share 51 distinct routes and 634
classified transitions. Nine represented source files retain pinned hashes.
Full call bindings, caller stacks, callback targets, output/visibility flags,
field demands and empty storage-demand sets are reconstructed against the saved four-witness reports.
Together with Sass, all 33 application queries have transition dispositions.

Certificate paths retain the known stock callback-selection approximation.
Save-path and URI-suffix alternatives visit readonly parameter outputs and
unrelated callers before re-entry; they do not establish immutable String
mutation or platform file execution. Tree decoding retains external index and
substring argument effects. Filename routes include MapEntry value/key feedback,
callback references, collection fields and extension receivers. Their differing
edge flags are preserved, while exact collection slots and caller ordering remain
unqualified. Native URI and extension controls establish only exercised helper
behavior. URI-suffix and save-name negative searches with limits remain inconclusive.

Saber Base64 routes distinguish encoding, decoding and the input-preserving
Uint8List branch. Native controls cover empty/distinct arrays, round trips, null,
invalid decoder input and alias identity with an independent array. Configuration
JSON routes distinguish constructor temporaries but do not execute a network
upload; codeUnits followed by Uint8List construction is not an arbitrary Unicode
encoding qualification. Captured path parameters bind to lexical lambda reads
without qualifying delivery, cache state, encryption keys or scheduling. The
stock priority false positive retains worker receiver feedback; modeled isolation
uses the existing versioned summary and a same-source positive control. Quota
projections and encryption results retain external/aggregate/callback effects.
Configuration-directory isolation remains inconclusive under its recorded limits.

Validation passes the full 111-test native suite with runtime, Flutter, corpus,
holdout and application controls enabled, final-file analysis and formatting,
all three pinned application hashes and unchanged 403-file/all-twelve-graph
provenance. Thirteen new verifier rejection checks and the previous 37 checks
reject altered evidence, and summary regeneration is byte-identical. The LocalSend
oracle extracts its pure URI class verbatim rather than executing the Flutter
library. Other package alternatives, eight modeled inconclusive negatives and
remaining coverage-plan gates stay open.


## Bounded args, collection and meta query review

The [binding snapshot](package-bindings-alternative-review.json) covers all 22
args/collection/meta queries in both modes. Twenty-eight selected paths share
fourteen distinct routes and forty classified transitions. Six pinned source
files, exact positional slots, selected caller frames and path flags are retained.
The verifier requires each of these project query sets to be complete.

Args supplied values return directly or through the deprecated forwarding method.
The ArgResults helper binds name and parsed to separate constructor field formals.
Both negative searches report call-depth and remain inconclusive, despite passing
same-budget controls and exercised runtime separation. Collection search values
and lists bind separately, while the reverse-loop temporary carries the selected
old element to the opposite assignment. Limits on that swap route remain explicit;
constant -1 returns describe payload independence rather than control independence.
Meta fields retain matching named/positional constructor assignments and constant
null isolation. Collection/meta negatives record no query limitations and passing
controls, without certifying larger searches or general heap/collection precision.

`package_boundaries_test.dart` executes the actual pinned packages. Args checks
nonnull/null/deprecated forwarding, independent constructor names/parsed values
and fresh empty lists when the low-level option's defaultsTo is null. The initial
oracle incorrectly assumed ArgParser.addMultiOption also used a null default:
that API supplies its configured list, so its result can be shared. The corrected
control invokes actual newOption with null defaultsTo; no application source was
changed. An initial collection driver also required the internal algorithms import
because binarySearchBy is not exported from the public library. Both failures are
retained as oracle setup/expectation corrections, not frontend defects.

Collection checks empty/single/distinct sorted lists, present/missing keys, the
first callback input, identity and empty/single/even/odd/subrange reversal. Meta
checks independent UseResult named fields, the unnamed null field and distinct
public TargetKind constants. The latter does not execute arbitrary inputs through
the private constructor. The summary now links 63 fully reviewed query families;
seventeen package queries, eight inconclusive modeled negatives and other plan
gates remain open.

Validation passes all 114 native tests with runtime, Flutter, corpus, holdout and
application flags enabled, final-file analysis/formatting, thirteen additional
verifier rejection checks and the previous fifty checks. Missing args, collection
or meta queries, changed positional formals, caller stacks, flags, source/oracle
hashes and limits are rejected. Summary regeneration is byte-identical and the
403-file/all-twelve-graph source fingerprint remains unchanged.


## Complete bounded package path review

The [path/async/http_parser review](package-flows-alternative-review.json) adds all
fifteen remaining queries, with 61 selected paths sharing 38 distinct routes and
941 classified transitions. Fourteen represented method files retain pinned
hashes. The [analyzer review](analyzer-alternative-review.json) now covers all six
queries: 28 selected paths, seventeen distinct routes and 535 transitions. Its two
new routes directly bind the shifted/masked uint32 high byte to _addByte4 and
assign the fork offset; their search limits remain explicit.

All eighty queries now retain full dispositions for 248 selected paths sharing
146 distinct routes and 2,499 transitions. The summary requires every project and
query, verifies all declared review totals, checks represented method-file hashes,
and reconstructs call slots, contexts, visibility/output flags and field demands
against the raw reports. Storage-demand sets are empty in these paths. Earlier
analyzer/application representation text incorrectly described all demand sets as
empty; field demands were preserved in the data and that wording is corrected.

Path normalization routes select prettyUri/relative callers and readonly helper
outputs before re-entry. setExtension routes propagate separators through the
ParsedPath field formal and return, then readonly receiver outputs and external
builder effects. The retained separators demand does not establish exact character
provenance or independent fields/allocations. Direct suffix addition and public
basename forwarding remain separate. Actual pinned calls exercise normalized and
slow branches, independent paths/suffixes, hidden/multiple extensions and basename.

Async's error-to-argument and value-to-sink source/sink pairs are identities.
Value alternatives visit delegate/release wrappers and re-enter a whole-result
receiver, sometimes consuming repeated value demands. They do not establish
precise wrapper allocation or arbitrary delivery. Stock completeError mixes error
with trace; the modeled negative removes those paths but retains depth/pruning
limits, so it remains inconclusive. Its receiver-storage query records a versioned
state-dependency contract rather than a scalar return. Actual ErrorResult completion
checks separate error/trace values; explicit delegate/release sinks and fromValue
operations exercise selected values and independent receivers without scheduler
qualification.

HTTP chunk stock copy paths follow length, size/header allocation or cursor/start
positions and setRange effects instead of byte-content provenance. Modeled copy
binds bytes directly, while buffer-return routes retain repeated add/addSlice
caller re-entry and temporary variants. These effects do not prove exact destination
slots or caller order. Empty returns retain depth/storage/initialization limits and
remain inconclusive. Media-type direct capture and readonly output detours are
classified separately; wrapper interpolation variants retain external-exception
limits. Actual chunk encoding checks empty/distinct bytes, copied results, slices,
empty nonlast/last output and selected sink close; the driver imports the internal
encoder because it is absent from http_parser's public export. The initial import
failure is retained as setup evidence, not a semantic defect. Existing parser and
format-wrapper controls remain linked.

The actual analyzer tests add mixed uint32 bytes in fresh/flush-boundary buffers
and reader forks sharing bytes while offsets advance independently. Seven focused
native cases pass; all 119 native tests pass with runtime/Flutter/corpus/holdout/
application flags, as do analysis and formatting. The prior 63 verifier rejection
checks and seventeen new checks pass, including missing project queries, source
hashes, altered field demands and forged totals. Final source provenance remains
403 files/all twelve graphs, and summary regeneration is byte-identical. Eight
modeled negative searches, omitted alternatives, historical interrupted searches
and Gates 2–6 remain open; this completes returned-path review only.


## Pinned specification and SDK reference inventory

Gate 2 now supplements its 175 analyzer visitor rows with immutable source
references and candidate SDK cases. The source index records 195 base-specification
section labels, thirty accepted feature documents with actual SHA-256/Git blob
hashes, and all 4,172 `_test.dart` candidates in the pinned SDK language subtree.
Each enabled visitor has known source/candidate references; outside-scope visitors
retain no enabled qualification. Of 76 unmapped base sections, eight are document
context and 68 retain explicit unqualified semantic-rule obligations. Existing semantic contract classifications are
unchanged. These links and filenames are not executed conformance evidence.

The verifier checks the committed index hash, matching SDK pins, all row references,
unqualified gap accounting and feature-document coverage. With downloaded evidence
it also checks the SDK root/tests/language tree binding, complete candidate set,
language repository revision and actual specification hashes/section extraction.
Optional fetching uses immutable URLs and caches only under agents/. The language
snapshot precedes the SDK revision's committer date; it is reference material,
not a compiler dependency or completed Dart 3 specification. The official
specification remains unfinished and accepted feature documents supplement it.

The native inventory test checks visitor references and explicit gaps. Thirteen
mutation controls reject removed gaps/SDK cases, promoted candidates, unknown
sections/features/cases, duplicate cases, mixed revisions/tree IDs and stale
specification hashes. Gate 2 stays open for remaining language-rule rows,
construct-specific stage tests and executable traces.

The stage matrix now accounts for all 175 visitors, 195 sections and thirty
feature documents, linking 47 named tests through hash-checked evidence. Each
profile has exporter, lowering, resolution, CFG, value-flow, negative and
interaction cells. Selected graph assertions and bounded native cases have
separate scopes; generic declaration/bound checks remain structural. Specification
sections and complete feature rules retain unqualified obligations even when
related visitor profiles have tested cells. Fourteen rejection controls cover
stale evidence, missing rows/stages, mixed pins and unsupported promotions. The
full 119-test native suite, analysis, formatting and the matrix verifier pass.
This supplies explicit stage accounting, not complete-rule qualification or an
execution certificate for every linked Scala test.

## Await-for cancellation and refreshed corpus evidence

The reduced graph regression initially found zero cancellation calls for two
asynchronous loops. Lowering now wraps the saved iterator's loop in `try/finally`
with an awaited `cancel` call. Body break/return/throw and an outward labeled
continue enter that cleanup; a local continue stays in the loop. Throwing
collection elements also enter cleanup. The iterator is created before the
protected body; synchronous loops retain their previous lowering. StreamIterator
already closes on completion/error, so its final cancellation is a no-op after
normal completion. The generated moveNext/cancel calls remain unresolved SDK
boundaries; this change does not qualify subscription state or event delivery.

Five native tests cover delayed cancellation across an event turn, body exit
identity, local continue, cancellation failure replacing a pending exit and
throwing collection elements. A deliberately unawaited-cleanup mutation verifies
that the completion observer detects early exit. The full 124-test native suite,
analysis and formatting pass. The frontend run passes 167 checks, including an
additional saved-graph count check; eight opt-in tests are canceled in that run.
All twelve corpus graph tests then pass, together with the after-count check,
staged CLI checks and language selection checks. The first corpus invocation
omitted DART_SDK and failed before exporting; the corrected run uses the pinned
SDK. A formatting check that included ignored scratch review tests reported those
scratch files; the final check of repository sources passes.

The [count review](async-iteration-count-review.json) accounts for seventeen
existing stream iterators: async 1, LocalSend app 8, Saber 6 and Sass 2. Each adds
exactly two calls and no internal methods or UNKNOWNs. Permanent corpus checks
require the cleanup receiver to reference the same saved iterator declaration
and require an await successor. All twelve refreshed graphs share source hash
`6a92ea4bf560d1c655b02efcb3b34bf999e1aab8fc5db39f6deebceba02685f9`
(403 files); exporter 0.3.19 and the library model files are unchanged.

Both two-witness/two-held-round and four-witness/two-held-round audits pass under
separate ten-minute category budgets with explicit 8 GiB/four-CPU workers. The
four-witness package/application/holdout subprocesses take 399078/43216/28137 ms,
including startup and graph loading. Sampled process-family RSS is
6344160/5488968/2304644 KiB, not an exact peak or a single worker's heap.
Current four-witness outcomes are stock 78/80 and modeled 80/80, with
122 visible/131 detailed stock paths and 111 visible/117 detailed modeled paths.
There are 42/41 limited queries, seven/eight inconclusive negatives and five
held-round limits in each mode. LocalSend's modeled certificate hash search now
reports pruning; selected Saber searches also retain exception-state limits.

All eighty returned-path dispositions are refreshed: 248 selected paths share
144 distinct paths and 2426 transitions. Unchanged routes match previously
reviewed node/call/context/flag/demand facts after removing graph IDs only. New
variants have separate refresh dispositions: analyzer record named/positional
fields and flat-buffer getUint32 effects, two Sass substring shortcuts and HTTP
setRange/header/return detours. The two-witness HTTP buffer routes are longer
than the four-witness selections (95 stock and 76 modeled nodes), so larger bounds
do not imply inclusion of smaller-bound routes. Exact binding/context checks and
full reconstruction retain those approximations; no real repeated-call order,
allocation identity or callback delivery is inferred. All 87 review-verifier
rejection checks pass. Ordinary static-storage and pending-local-exit reviews are
also rebuilt (6 paths/123 transitions and 11 paths/199 transitions).

The qualification matrix now has 53 named links, sixteen rejection controls and
a partial asynchronous for-in cancellation contract for ForStatement/ForElement.
That section now has construct references; 67 semantic-rule sections still lack
them. Complete rules, stream resolution/value flow and Gates 1–6 remain open.


Validation passes the 119-test native suite and final focused inventory check,
analysis/formatting and thirteen source-verifier rejection checks. Full cached
provenance verification and unchanged 403-file/all-twelve-graph provenance pass;
the four-witness summary still regenerates byte-identically. No semantic status
is upgraded by the new source references.


## Resolved synchronous iteration and refreshed evidence

Exporter 0.3.20 retains analyzer-selected iterator, moveNext and current members
for synchronous for-in over known interface types. Lowering uses those calls and
saves a pattern loop's current value once before extraction. Concrete Cursor
receivers exclude unrelated implementations; an interface-typed control retains
the observed implementation union. Declared, assigned, collection and record
pattern forms have graph and native evaluation-order controls, including empty
iteration. Dynamic and bounded type-parameter receivers and asynchronous SDK
member resolution remain open. Generic declaration identities do not establish
instantiated current-value types, runtime substitutions or iterator heap flow.

The [count review](synchronous-iteration-count-review.json) compares the previous
lowering against the same current exported facts on all twelve source roots.
Across 2,165 for-in loops (including 17 asynchronous controls), 82 change their
call counts: args -2, analyzer
-31 and Sass -64. Each pattern now reads current once; additional saved-value
assignments explain the net reduction of 97 calls. Internal methods and UNKNOWN
counts are unchanged. The initial comparison uses a 404-file fingerprint recorded
in that review. A subsequent test assertion was strengthened to check callee
owner identities, with a positive interface control. All twelve graphs and both
bounded audits were then regenerated at the final fingerprint
`fae2ab7ea70e9a3c442f62127604fd86c742f53d293ca79090e127207db91719`
(404 files), rather than substituting provenance metadata.

The native suite passes 127 tests with all runtime/corpus opt-ins enabled.
Analysis and formatting of the four changed Dart files pass; a whole-test-tree
format sweep separately reports a pre-existing caller_forwarding_test.dart
formatting difference. The frontend target and normal-source Scala format checks
pass. Its final invocation executes 124 frontend tests; other unchanged suites
were cached after the earlier successful 166-test invocation. All twelve corpus
graph checks, four staged CLI checks and fourteen language-selection checks pass.
Two additional storage review tests rebuild six static-storage paths/123
transitions and eleven local-exit paths/199 transitions.

Both bounded audits finish within separate ten-minute category budgets, including
startup and graph loading, using explicit 8 GiB/four-CPU workers. Two-witness
package/application/holdout times are 188022/44230/29147 ms, with sampled
process-family RSS 5155164/4500760/1759312 KiB. Four-witness times are
397149/46250/30156 ms and RSS 6152836/5149084/2297716 KiB. These samples are
not exact peaks or measurements of one worker's heap. An earlier four-witness
run was deliberately interrupted to strengthen the owner assertion; it is not
a resource failure or current qualification evidence.

Four-witness outcomes remain stock 78/80 and modeled 80/80. Stock retains
122 visible/131 detailed paths and modeled 109/117, with 42/41 limited queries.
All eighty query dispositions cover 248 selected paths, 150 distinct routes and
2,735 transitions. Analyzer contributes 18 routes/625 transitions, Sass 25/384
and path/async/http_parser 39/1,033; other project totals remain unchanged.
Thirty-one changed selected paths across the two audits were read against pinned
source and exact call/context/output-flag/field-demand facts. Analyzer routes
include the resolved iteration calls and external Iterator.current effects.
Sass substring, path readonly/dispatch and HTTP size/header/caller detours retain
explicit approximations. Changed HTTP selections do not imply that iteration
caused those routes, that larger witness bounds contain smaller-bound selections,
or that runtime call order, allocation identity or byte provenance is established.
Unchanged routes match prior reviews after removing graph node IDs only.

All 87 witness-review rejection controls pass; both summaries reproduce
byte-identically from fresh reports. The matrix has 57 named links and a partial
synchronous-iteration profile. Its six tests/sixteen rejection controls, native
inventory check and source verifier/thirteen rejection controls pass. Complete
language rules, eight inconclusive modeled negatives, omitted alternatives and
Gates 1–6 remain open.


## Pinned synchronous SDK oracles

Two further SDK 3.9.2 sources are classified in the upstream manifest. The
for_in_side_effects regression keeps its body and expected value, adapting only
the assertion import. Its iterator getter's global write executes in the VM
and remains observable after dart2js compilation and Node.js execution. The
for_in3 diagnostic source is unmodified: iterating a String retains exactly
`for_in_of_invalid_type` and a partial exported unit. The valid source has no
unsupported kinds and retains the source getter plus moveNext/current targets.
This is runtime and diagnostic evidence, not graph CFG, static field-flow,
generic bound or heap qualification. Initial exporter assertions used an AST
visitor name instead of the exported ForEachParts kind and assumed an absolute
file identity; they were corrected to the actual protocol and source-relative
identity before qualification.

All six manifest sources now record SDK Git blob identities, checked against the
pinned candidate index. Retrieved originals also match their recorded SHA-256
hashes; adapted contents have a persistent hash/classification verifier. Its two
tests cover the baseline and nine stale/contradictory manifest rejection controls.
The stage matrix has sixty named links and keeps the SDK diagnostic negative
control separate from static flow absence. All six matrix tests/sixteen rejection
controls pass. The focused six-test SDK suite and full 129-test native suite pass with all
runtime/corpus opt-ins enabled. Native analysis and changed-file formatting pass.
Analysis source hash remains
`fae2ab7ea70e9a3c442f62127604fd86c742f53d293ca79090e127207db91719`
(404 files), independently matching all twelve graphs. Full loop rules and
Gates 1–6 remain open.


## Generic iteration bounds and instantiated static results

Exporter 0.3.21 follows declared/promoted iterable type-parameter bounds, with
cycle detection and a nonnullable interface check. Iterator, moveNext and current
lookups retain normalized declaration targets. New currentType/currentTypeId
facts separately retain the instantiated getter result; synthetic current calls
and saved pattern values use that static type. The parameter's symbolic type and
the callee's generic return declaration remain unchanged. Focused controls cover
chained/recursive bounds, inherited members, nullable promotion, scoped symbolic
element types and record patterns. Invalid nullable/unbounded sources preserve
exact analyzer diagnostics without guessed member facts; dynamic and asynchronous
SDK operations remain unresolved.

Runtime validation exposed a pinned-toolchain disagreement: analyzer 8.4.1
accepts the bounded record-pattern loop in generic_iteration_pattern.dart, but
the Dart 3.9.2 compiler rejects T as not implementing Iterable<dynamic>. The
source is retained separately for exporter/graph assertions. A kernel compilation
control checks that exact rejection and absence of an output artifact, rather
than importing the source into a passing execution oracle. Ordinary bounded
loops independently check member order, nullable guards and recursive element
identity. The actual pinned collection package exercises its three promoted
generic-bound UnorderedIterableEquality equality/hash loops with empty, reversed,
duplicate, extra-element and null controls. These observations do not establish
static payload absence, arbitrary callback behavior or runtime generic environments.

The [static fact review](generic-iteration-review.json) independently re-exports
all twelve lib roots: 2,148 synchronous loops retain targets, including three
newly resolved type-parameter receivers in collection/src/equality.dart. In
2,147 cases the instantiated current type identity differs from its generic
declaration's return identity. The other seventeen for-in loops are asynchronous
controls; the earlier count comparison's 2,165 total includes them. All twelve
saved graph method/call counts match the pre-change audits, and corpus UNKNOWN
checks pass. Fresh graphs share source hash
`8d38cb5a00e2e084f0ca2aafa3ac34c7806d06b38764782aa0704abafa7adb17`
(406 files), independently verified against current source contents.

The full 134-test native suite passes with all runtime/corpus opt-ins, as do
native analysis and formatting of the six changed Dart files. All 168 frontend
tests pass across twenty suites; eight opt-in cases are canceled in that run.
All twelve corpus graph tests, four staged CLI checks and fourteen language
selection checks pass. The storage review tests rebuild six paths/123 transitions
and eleven local-exit paths/199 transitions. Normal-source Scala formatting passes.
The stage matrix now has 67 named links, with separate static, diagnostic,
runtime and compiler-boundary scopes. Six matrix tests/sixteen rejection controls,
upstream source tests/nine rejection controls and the source verifier/thirteen
rejection controls pass.

Two- and four-witness audits pass under separate ten-minute category budgets
including startup and graph loading, with explicit 8 GiB/four-CPU workers.
Two-witness package/application/holdout times are 191081/44236/28145 ms, with
sampled process-family RSS 5806504/4624356/1644600 KiB. Four-witness times are
402210/46249/30153 ms and RSS 6454292/5060428/2385988 KiB. These are sampled
family measurements, not exact peaks or a single worker heap.

Four-witness endpoints remain stock 78/80 and modeled 80/80, with
120 visible/131 detailed stock paths and 111/117 modeled paths. There are
42/41 limited queries and seven/eight inconclusive negatives. All eighty query
dispositions now cover 248 selected paths, 149 distinct routes and 2,673
transitions. Analyzer has 18 routes/629 transitions; path/async/http_parser has
38/967; other project totals remain unchanged. The two-witness forwarding review
has nineteen distinct paths/617 transitions. Twenty-seven changed selected paths
were checked against pinned source and exact call/context/output/visibility/demand
facts; unchanged paths match after removing graph node IDs only. All 87 witness
review rejection checks pass, and both summaries reproduce byte-identically.

Fresh analyzer routes retain metadata/record-bound offset demands and flat-buffer
length/getUint32/getUint8 receiver effects. The modeled variants retain a separate
BoolList offset receiver route. Fresh two-witness path selection uses URL
rootLength in stock mode and POSIX rootLength in modeled mode; the exact source
owner distinguishes those from the observed Windows dispatch candidate. HTTP
copy routes retain conservative setRange 0 -> 4 -> 1 -> 3 effects. Two-witness
buffer routes select an end/header detour before a later byte re-entry; fresh
four-witness routes instead re-enter byte parameters twice, with output-marked
returns and distinct add/addSlice frames. Shorter variants omit saved aliases
and preserve detail-only receiver visibility. These readonly/external/dispatch,
field aggregation, caller and allocation effects remain approximations. Neither
selection changes nor passing endpoint controls prove runtime receiver instances,
actual repeated-call order, precise element provenance or a causal effect of
generic iteration on the HTTP routes.

A further reduced source is accepted by both the analyzer and kernel compiler
but still lacks iteration facts when the iterator getter returns a bounded type
parameter rather than an interface type. That lookup gap is recorded for the
next implementation step. Runtime substitutions/bound checks, complete loop CFG
and payload/heap qualification, omitted alternatives, inconclusive negatives
and Gates 1–6 remain open.


## Iterator getter return bounds and nullable boundaries

Exporter 0.3.22 resolves synchronous iterator getter return type-parameter bounds
with a shared cycle-checked interface lookup. It retains the original scoped
`I`/`J` iterator result and saved member receiver types; the bound supplies
`moveNext`/`current` lookup and the instantiated element type, separately from
generic callee declarations. Concrete and chained cases have exporter/graph
controls and pinned native member-order/value execution checks. Exporter controls
also require the current element and iterator result parameters to belong to the
same function scope, rather than accepting a similarly named class parameter.
The reduced valid case previously omitted all three targets despite analyzer and
kernel acceptance; the focused regression failed before the fix.

An additional reduced negative exposed nullable type-parameter erasure during
bound lookup: `TypeParameterType.bound` discards an outer `?`, supplying targets
for invalid `T?` iterable or `I?` getter result types. Lookup now rejects a nullable
parameter before following it, including intermediate bounds. The existing
promoted nullable receiver controls continue to pass. Nine invalid/dynamic/async
loops omit iteration facts, retaining exactly three nullable-use errors, one
invalid-for-in error and four invalid-override errors from the pinned analyzer.
These are controls over the named sources, not qualification of every invalid
source or proof of negative payload-flow absence. The earlier bounded record
pattern compiler disagreement remains explicitly static-only.

The [return-bound review](iterator-return-review.json) records the reduced cases
and independently re-exports all twelve selected lib roots. Their complete saved
iteration observation sets remain identical to the preceding increment: 2,148
loops with targets, three bound receivers and 2,147 instantiated current IDs
that differ from generic declaration return IDs. No corpus loop uses the newly
covered iterator getter return form; the reduced controls directly exercise it.
All twelve saved method/call counts remain identical. Fresh graphs share source
hash `5381126986a8bc6c2fa819f56be744fee0f7c656a0de4539d90fb1e1d3c026c4`
(406 files), independently verified against current source contents.

All 134 native tests pass with runtime/Flutter/corpus/holdout/application opt-ins,
as do analysis and formatting of the four changed Dart files. A forced frontend
run executes all 168 tests across twenty suites, with eight opt-in cancellations.
All twelve corpus tests, four staged CLI tests and fourteen language-selection
tests pass. The separate storage review run passes both tests and regenerates
six paths/123 transitions and eleven local-exit paths/199 transitions. Normal
Scala formatting passes. The matrix retains 67 named links; its six tests/sixteen
rejection controls pass, along with the source verifier/thirteen rejection
controls and upstream two tests/nine rejection controls.

Both witness bounds pass under separate ten-minute category budgets including
startup/graph loading, with explicit 8 GiB/four-CPU workers. Two-witness
package/application/holdout times are 179993/44234/28144 ms and sampled process
family RSS 5730652/4693540/1966324 KiB. Four-witness times are
394160/49277/31162 ms and RSS 6223972/6072740/2548476 KiB. Sampling occurs once
per second; these measurements are not exact peaks or a single worker heap.

The fresh four-witness audit retains stock 78/80 and modeled 80/80 endpoint
expectations, 120 visible/131 detailed stock paths and 111/117 modeled paths,
42/41 limited queries and seven/eight inconclusive negatives. All eighty query
dispositions cover 248 selected paths, 149 distinct routes and 2,631 transitions.
Analyzer has sixteen routes/478 transitions; path/async/http_parser has forty
routes/1,076 transitions. The two-witness forwarding review has nineteen distinct
paths/605 transitions. Both summaries reproduce byte-identically, and all 87
review rejection controls pass. The verifier also rejected a missing Windows
source pin during refresh; that represented body was read and hash-pinned before
qualification completed.

Twenty changed selections were checked against actual source and full metadata.
Fresh stock analyzer variants retain `_element -> bound.namedFields` demands,
external iteration calls, readonly NodeList/BoolList length outputs and a separate
BoolList offset receiver demand through `_getByte`/`_getUint8`; the length detours
appear in opposite orders. Two-witness modeled path routes now select Windows
rootLength; a four-witness stock/modeled variant selects URL rootLength under
isAbsolute before a later normalization output. Exact method owners and dispatch
unions remain explicit. HTTP stock copy routes select setRange 0 -> 1 -> 3;
shorter variants preserve hidden receiver/constructor evidence while omitting
saved aliases. Stock buffer routes select direct conservative bytes 3 -> receiver
0 effects: two-witness routes re-enter end before bytes, while fresh four-witness
routes re-enter bytes before end/header/allocation effects. Output-marked results,
initial add/addSlice frames, later add frames, visibility and all field demands
are retained. These selections remain readonly/external/dispatch/field/caller
approximations, not proof of actual receiver instances, callback delivery,
repeated-call order, allocation identity or precise byte/element provenance.
Unchanged iteration observations do not establish the cause of route selection
changes; larger bounds do not imply inclusion of smaller-bound sequences.

Runtime substitutions and checked bounds, initialization-state correlation,
precise heap/element flows, complete loop CFG, erased extension receiver contexts,
omitted alternatives, limited negatives and Gates 1–6 remain open.


### Extension-type representation dispatch qualification

Exporter 0.3.23 retains declaration representation erasures and instantiated
expression constraints separately from original static type identities. Inherited
class members now use those constraints when filtering the analyzer hierarchy
union; own extension-type members retain static dispatch. Nested wrappers,
GenericView<Store>/GenericView<OtherStore>, bounded and nullable receivers,
accessors, indexed reads/writes, cascades and bound tear-offs have source-target
and unrelated-representation controls. Wrapped iterator/current results preserve
their original static extension types while saved receivers retain the known
class constraint. Tags are stored only for retained AST nodes; a graph-validation
failure exposed discarded provisional capture references during development.

The original valid extension Iterable control omitted its source Values.iterator
callee despite native execution and a resolved analyzer unit. The permanent
regression now requires that callee and rejects unrelated concrete implementations.
Three new native tests observe member/value/order behavior and independent object
values. These observations do not establish static heap aliasing, exact element
provenance, runtime generic environments or complete extension-type semantics.
Known interface/type-parameter representation constraints are exported;
record/function/dynamic representations do not acquire guessed class identities.

All 138 native tests pass with runtime, Flutter and all corpus opt-ins. Native
analysis and formatting of the four changed Dart files pass. All 169 frontend
tests pass across twenty suites, with eight separate opt-in cancellations. All
twelve project checks pass, as do four CLI integration checks, fourteen console
checks and normal Scala formatting. The source fingerprint is
`e93d7f16ba74f0ed500a0e2dfb6b80a06f917cb1ae2b49a97201fb8ee195c526`
over 407 files, shared by all twelve graphs. Their method/call counts and saved
iteration facts are unchanged. Independent exports observe 21 extension-type
symbol declarations and 1,148 expression erasures in analyzer, and nine/16 in
Sass. External declarations are included in those counts. Analyzer's JS EnumSet
record representation remains an explicit class-identity boundary; Sass interop
metadata does not qualify native JS execution or callback delivery. The
[representation review](extension-erasure-review.json) records source hashes and
exact comparison scope.

The stage matrix now links 72 named tests. Its six tests/sixteen rejection
controls pass, as do thirteen source-inventory rejection controls, full cached
source verification and two upstream-case tests/nine rejection controls. Full
rule cells and all Gates 1–6 remain open.

Two-witness/two-round audits complete in 183045/46257/30164 ms for packages,
applications and holdout, with sampled family RSS 5798016/4898400/1418176 KiB.
Four-witness/two-round audits complete in 395255/47265/32177 ms, with RSS
6215048/5457732/2164164 KiB. Each category stays within its 600000 ms budget;
workers explicitly use 8 GiB/four processors. Memory is a once-per-second
process-family sample, not exact peak RSS or one heap.

All returned four-witness dispositions cover 248 selected paths, 150 distinct
routes and 2,757 transitions across eighty queries. Stock/model endpoint outcomes
remain 78/80 and 80/80, with 120/131 and 109/117 visible/detailed paths and 42/41
limited queries. Seven stock and eight modeled negatives remain inconclusive.
The two-witness summaries retain stock 86/89 and modeled 81/83 paths with 43/42
limited queries; forwarding's seven queries retain 19 distinct paths and 637
transitions. MediaType retains two paths/six transitions. Fresh static-storage and
local-exit checks retain six paths/123 transitions and eleven paths/199 transitions.
Both bounded summaries reproduce byte-for-byte, and all 87 review rejection
controls pass against current proofs and resource records.

Twenty-one changed selections received separate source-grounded dispositions.
Four modeled analyzer variants retain metadata.annotations.offset or
_element -> bound.namedFields/positionalFields/typeArguments.offset demands,
then BoolList Uint32/Uint8 reads and readonly length outputs before items[i].
Exact demands, output/visibility flags, slots and frames remain explicit. Path
variants select the POSIX rootLength body beneath readonly normalization or
isAbsolute re-entry, retaining all style candidates. HTTP stock copy variants
retain conservative setRange receiver 0 -> start/skipCount 4 -> bytes 3 effects;
shorter variants omit saved aliases while preserving invisible evidence.
Two-witness stock buffer routes re-enter end before bytes; four-witness stock
routes re-enter bytes before end. Two-witness modeled routes select end/header
re-entry twice. These are readonly, field, external, dispatch, size/range and
caller approximations, not actual receiver instances, callback delivery,
repeated-call order, allocation identity or precise byte/element provenance.
No causal claim connects selected route changes to extension-type dispatch.

A separate reduced native control identifies the next pattern-specific boundary:
View<Store>(value: var text) over Object accepts Store and rejects OtherStore,
but the graph retains Object as the accessor receiver and links both source
value getters. Instantiated pattern receiver constraints remain to be implemented.
Representation storage aliasing, structured/function effects, runtime generic
qualification, omitted alternatives and limited negative searches remain open.

## Object-pattern receiver constraints and instantiated field results

Exporter 0.3.24 records each object pattern's required type and known class or
bounded-parameter erasure, and each object/record field's instantiated matched
value type. Lowering constrains the individual accessor receiver and preserves
result types and erasures separately for each use of shared extraction storage.
It retains original getter declaration identities and generic return types.
A reduced View<Store> pattern previously linked both Store.value and
OtherStore.value through Object; it now selects Store.value. Ordinary Store,
OtherStore, nested holder, record, representation-field, captured-field and
alternative-case controls check receiver separation and String results.
Cached field views retain their own Store/OtherStore constraints. Explicit
dynamic object patterns supply no guessed class constraint.

Four native controls exercise independent values, type-failure exclusion,
nested/record extraction, getter order and getter-once behavior across cases.
The full opt-in native suite passes 143 tests, Dart analysis reports no issues,
and the forced frontend suite passes 170 tests. All seven packages, three
applications and two holdout projects pass, as do four staged CLI checks and
fourteen staged console checks. All graphs share analysis-source SHA-256
`c860f38644b091fcd88fc4f962d7efeac3085766620c43f34ed22dcaeb326c52`
over 408 files. Their method/call counts and saved iteration/representation
facts are unchanged. Independent native exports observe 917 object patterns,
910 known required class erasures and 1,091 typed pattern fields. Seven explicit
dynamic patterns remain unconstrained; no corpus field result has a wrapper
erasure, so the reduced fixture supplies that interaction evidence. The
[pattern review](pattern-receiver-review.json) records exact source pins and
comparison scope. The stage matrix links 78 named tests; six matrix tests and
sixteen rejection controls pass, alongside thirteen source-index rejection
controls, full cached source verification and two upstream-case tests with
nine rejection controls. Complete rule cells and all Gates 1–6 remain open.

Two-witness/two-round audits complete in 191210/47275/29165 ms for packages,
applications and holdout, with sampled family RSS 5617128/4754560/1542056 KiB.
Four-witness/two-round audits complete in 405418/51300/33187 ms, with RSS
6131076/5382536/2305048 KiB. Each category remains within its 600000 ms budget;
workers explicitly use 8 GiB/four processors. These are once-per-second
process-family memory samples, not exact peak RSS or one heap.

The fresh four-witness review classifies 248 selected paths, 148 distinct routes
and 2,554 transitions across eighty queries. Stock/model endpoint outcomes
remain 78/80 and 80/80, with 122/131 and 111/117 visible/detailed paths and 42/41
limited queries. Seven stock and eight modeled negatives remain inconclusive.
The two-witness summaries retain stock 86/89 and modeled 81/83 paths with 43/42
limited queries. Forwarding's seven queries retain nineteen distinct paths and
617 transitions. MediaType retains two paths/six transitions; fresh storage and
local-exit checks retain six paths/123 transitions and eleven paths/199
transitions. Both summaries reproduce byte-for-byte; all 87 review rejection
controls pass against the refreshed proofs and resource records.

Twenty changed selections received separate source-grounded dispositions.
Two stock analyzer variants retain _element -> bound.positionalFields demands,
then readonly NodeList length and BoolList offset/_getByte/_getUint8 detours in
either order. Four Sass stock/model variants retain readonly helper output,
last-index arithmetic and the escape-end return, then substring end-to-receiver
or end-to-start effects. Fourteen HTTP variants retain exact setRange slots:
copy uses receiver 0 -> range end 2 -> skipCount 4 -> bytes 3; stock buffer
uses 3 -> 2 -> 4 -> 0. Two-witness buffer re-entry selects end then bytes;
four-witness stock selects bytes twice. Modeled byte-copy effects remain
separate. Shorter routes omit saved aliases while retaining hidden evidence.
Exact flags, slots, fields and frames remain explicit. These conservative
readonly, range, field, external, dispatch and caller detours do not establish
runtime receivers, callback delivery, branch feasibility, repeated-call order,
allocation identity or precise element provenance. No causal claim ties route
selection changes to object-pattern constraints.

The next reduced control exposes list-pattern result instantiation:
Values<String> supplies a native String through its resolved index getter, but
the graph result still has the getter declaration's scoped T. The declaration
should retain T while the extracted result preserves its instantiated type.
Full pattern CFG/payload effects, runtime generic environments, representation
storage aliasing, structured/function effects, omitted alternatives and limited
negative searches remain open.

## Instantiated list-pattern result types

Exporter 0.3.25 records the element type of each resolved list pattern's required
List<E>, including scoped generic identities and known wrapper erasures. Index
results now use E before child narrowing, and slice results use the required
List type independently of the override's declaration return type. Generic
getter declarations retain their original class parameters. A Values<String>
index read previously resolved its source getter but retained Values.T as its
result type; it now retains String. Cached Object/num views, caller-versus-class
T identities, scalar/slice/tail results and nested View<Store> getter exclusion
have exporter and graph controls. Four native tests exercise independent values,
child-test outcomes, getter-once alternatives and nested wrapper extraction.

The full opt-in native suite passes 148 tests, Dart analysis reports no issues,
and the forced frontend suite passes 171 tests. All seven packages, three
applications and two holdout projects pass, alongside four staged CLI and
fourteen staged console checks. Their graphs share analysis-source SHA-256
`5d91a08c4e2b4d47e123e1c664dc2ba15552afd24636dec2488bca49fb379858`
over 409 files. Method/call counts and all previous iteration, pattern and
representation observations are unchanged. Independent exports retain 122 list
patterns: three in analyzer, two in LocalSend and 117 in Sass. All have element
metadata distinct from their index declaration's generic return identity.
These counts include empty and wildcard patterns that do not extract elements;
they do not prove 122 getter invocations. No corpus element has a wrapper
erasure, so the reduced fixture supplies that interaction evidence. The
[result review](list-pattern-result-review.json) preserves source pins and exact
comparison scope. The stage matrix links 84 named tests. Six matrix tests and
sixteen rejection controls pass, as do thirteen source-index rejection controls,
full cached source verification and two upstream-case tests with nine rejection
controls. Complete rule cells and all Gates 1–6 remain open.

Two-witness/two-round audits complete in 237411/48279/31170 ms for packages,
applications and holdout, with sampled family RSS 5485700/4578832/1895316 KiB.
Four-witness/two-round audits complete in 419402/50288/32184 ms, with RSS
6126636/5073420/2267060 KiB. Each category remains within its 600000 ms budget;
workers explicitly use 8 GiB/four processors. Memory is a once-per-second
process-family sample, not exact peak RSS or one heap.

Fresh four-witness dispositions cover 248 selected paths, 148 distinct routes
and 2,556 transitions across eighty queries. Stock/model endpoint outcomes
remain 78/80 and 80/80, with 122/131 and 111/117 visible/detailed paths and 42/41
limited queries. Seven stock and eight modeled negatives remain inconclusive.
The two-witness summaries retain stock 86/89 and modeled 81/83 paths with 43/42
limited queries; forwarding's seven queries retain nineteen distinct paths and
617 transitions. MediaType retains two paths/six transitions, and fresh storage
and local-exit checks retain six paths/123 transitions and eleven paths/199
transitions. Both summaries reproduce byte-for-byte, and all 87 review rejection
controls pass against current proofs and resource records.

Fifteen changed selections received separate source-grounded dispositions.
Two stock analyzer variants retain _element -> bound.typeArguments demands,
then the interface-type branch, _writeTypeList and readonly NodeList/BoolList
length detours in either order. One modeled Path route selects readonly
normalization output, prettyUri/relative re-entry, then the POSIX rootLength
body while retaining all style candidates. Twelve HTTP selections retain
setRange copy order receiver 0 -> cursor 1 -> skipCount 4 -> bytes 3, and buffer
order 3 -> 1 -> 4 -> 0. Two-witness buffer re-entry selects end then bytes;
four-witness stock selects bytes twice. One shorter four-witness buffer route
omits the first skipCount detour; another omits a saved CR alias while retaining
hidden receiver evidence. Other shorter copy routes omit an allocation or
CR/LF alias. Exact flags, slots, demands and frames are preserved. These
readonly, field, external, size/range, dispatch and caller approximations do
not establish actual receivers, callback delivery, branch feasibility,
repeated-call order, allocation identity or precise element/byte provenance.
No causal claim ties those selections to list result types.

Reduced probes identify the next boundaries. A trailing list rest pattern
requires sublist(start) with its end argument omitted, but lowering supplies
null. An override whose default is 2 returns two items in the VM; the generated
call passes null and would retain all items instead. Head/tail, explicit-null,
independent override-default, cached-read and short-list failure observations
confirm that omission matters. Map index results still retain a declaration's
scoped V instead of its instantiated type. A broad Contract object pattern over
known Store also widens accessor lookup to unrelated OtherStore because its
required receiver tag replaces the narrower static constraint. These remain
open implementation obligations at that checkpoint; rest omission is addressed
below. Full pattern CFG/payload effects, runtime
generic environments, representation storage aliasing, structured/function
results, omitted alternatives and limited negative searches remain unqualified.

## Rest-pattern slice defaults

Rest extraction now omits the end argument when no elements follow the rest
pattern. The old lowering supplied null even when an override's default was 2,
retaining all items instead of the two returned by the VM. Existing per-target
adapters now supply each implementation's default. Computed tail ends and
explicit null stay supplied; shared adapter bodies retain distinct start inputs.
Five native controls cover independent receivers, defaults 2/1, ordinary List,
head/tail extraction, cached guard alternatives, skipped wildcards and short-list
RangeError. The old lowering fails the new graph regression. Native exporter
metadata and version 0.3.25 are unchanged.

The native suite passes 153 tests with all opt-ins, Dart analysis reports no
issues, and the forced frontend suite passes 172 tests across twenty suites
with eight opt-in cancellations. All seven packages, three applications and
two holdout projects pass, alongside four staged CLI and fourteen console checks.
All twelve graphs share analysis-source SHA-256
`87f52ad214fa9b743ef6333513ba3d760879f0aee4ce75fff1caa8b0517623e1`
over 410 files. Fresh storage/local-exit checks retain six paths/123 transitions
and eleven paths/199 transitions; formatting and compilation pass.

An independent source-pinned census retains 122 list patterns and 39 rest
patterns. All rests are in Sass: ten extractions omit end, six supply a tail end,
and 23 bare rests skip extraction. Sass gains nine shared external SDK adapters,
nine methods and nine delegate calls for ten newly adapted source calls/links.
The other eleven graphs' counts and default-adapter observations are unchanged.
The [rest default review](rest-pattern-default-review.json) records each graph's
before/after counts, source observations and pinned specification/SDK declarations.
Corpus slice targets have implicit null defaults; the reduced overrides supply
the nonnull-default behavior evidence. Source extraction counts do not identify
actual calls, elements or storage instances.

Two-witness/two-round audits complete in 199184/48278/32177 ms for packages,
applications and holdout, with sampled family RSS 6327268/4898920/1914384 KiB.
Four-witness/two-round audits complete in 389278/51297/34187 ms, with RSS
6617488/5075404/2476392 KiB. Every category passes its 600000 ms budget with
8 GiB/four-processor workers. RSS is a once-per-second process-family sample.

Fresh four-witness reviews retain 248 selected paths, 148 distinct routes and
2,588 transitions across eighty queries. Stock/model endpoint outcomes remain
78/80 and 80/80, with 120/131 and 109/117 visible/detailed paths, 42/41 limited
queries and seven/eight inconclusive negatives. Two-witness summaries retain
86/89 and 81/83 paths with 43/42 limited queries. Forwarding retains nineteen
distinct paths/623 transitions, Sass 23/346 and package flows 41/1,071. MediaType
retains two paths/six transitions. Both summaries reproduce byte-for-byte, and
all 87 review rejection controls pass. The matrix links ninety named tests;
six matrix tests/sixteen rejection controls, thirteen source-index rejection
controls, full cached source verification and two upstream-case tests/nine
rejection controls pass.

Twenty-four changed selections have separate source-grounded dispositions.
Sass indent routes now enter the new slice default adapter, with receiver-slot
binding, an external delegate supplying null, adapter return and cached rest
binding. Callback/caller re-entry and whole-collection effects remain conservative;
the pure indent oracle does not execute this bulletedList callback route. Shorter
routes omit a saved switch receiver or length detour, and another retains a
different switch edge label. Trim routes retain readonly parameter output and
substring bound/receiver detours. Path routes re-enter normalize through relative
and select the URL rootLength body while retaining all style candidates. HTTP
copy order is receiver 0 -> cursor 1 -> skipCount 4 -> computed end 2 -> bytes 3;
buffer order is 3 -> 1 -> 4 -> 2 -> 0, with footer bounds 1 -> 2 -> 0.
Two-witness buffer re-entry selects end then bytes; four-witness selects bytes
twice. Allocation/CR/LF alias omissions, hidden receivers, initial add/addSlice
frames and output flags remain explicit. These dispositions do not establish
actual receivers, callback delivery, invocation order, branch feasibility,
allocation identity or precise byte/element provenance.

Map index result instantiation and intersecting known/required object-pattern
receiver constraints remain implementation obligations. Complete pattern CFG and
implicit exception payloads, runtime receiver/generic environments, heap/alias
effects, omitted alternatives and limited negative searches remain unqualified.
All Gates 1–6 remain open.

## Map result views and intersecting pattern receivers

Exporter 0.3.26 retains the instantiated nullable return type of each map
pattern index call separately from its generic member declaration and the
child's matched type. A narrow getter returning String? keeps that result even
when the child matches Object?; a broad getter keeps Object? before a String
child test. Caller T remains distinct from the getter class's V. Inner and
outer wrapper results retain their current representation constraints, and
cached reads keep per-use result views. The map presence test continues to use
the required value's erased nullability. Five native tests check independent
values, missing/null entries, nonnull reads with false containsKey, narrowing,
guard alternatives, getter-once behavior and nested nullable wrappers.

Object-pattern requirements now add a receiver constraint instead of replacing
known static or representation bounds. Observed hierarchy candidates must satisfy
both. A Contract pattern over known Store excludes OtherStore; a broad Contract
input still retains both. Four native tests check ordinary, nullable, bounded,
wrapped, narrowed and cached alternatives. Both new graph regressions fail
against the previous implementation. Existing declaration fallbacks remain
conservative; this does not qualify instance-specific dispatch.

An own-operator extension map is accepted by analyzer 8.4.1 but crashes the
pinned Dart 3.9.2 compiler in ObjectAccessTarget.classMember/visitMapPattern,
without emitting a kernel artifact. Separate exporter and compiler controls
retain that disagreement as structural/failure evidence. The inherited-operator
outer wrapper executes successfully; the own-operator case has no runtime
qualification claim.

The full opt-in native suite passes 165 tests, Dart analysis reports no issues,
and the forced frontend suite passes 174 tests across twenty suites with eight
opt-in cancellations. All seven packages, three applications and two holdouts
pass, alongside four staged CLI and fourteen console checks. Their twelve graphs
share analysis-source SHA-256
`d6e307a96b67cfc6d04d4067e6d3878a120983cc5cae648819677f956ede2d3b`
over 413 files. Method/call counts, default-adapter observations and all previous
iteration, pattern and representation facts are unchanged. The
[type-view review](pattern-type-view-review.json) pins both reduced regressions,
the compiler disagreement and the sole corpus map pattern: Sass's dynamic-valued
'.' export case. Its index result is dynamic rather than the SDK declaration's
V? identity; no corpus map result has a wrapper erasure. The 917 object patterns
and one map pattern are source observations, not counts of executed calls or
storage instances. Fresh storage/local-exit reviews retain six paths/123
transitions and eleven paths/199 transitions. Formatting and compilation pass.

Two-witness/two-round audits complete in 187102/49279/33183 ms for packages,
applications and holdout, with sampled family RSS 6004156/5012228/1849608 KiB.
Four-witness/two-round audits complete in 430505/51297/34190 ms, with RSS
6325760/5036644/2391328 KiB. All categories pass their 600000 ms budgets with
8 GiB/four-processor workers. RSS is a once-per-second process-family sample,
not exact peak memory or the heap of one process.

Fresh four-witness reviews classify 248 selected paths, 146 distinct routes and
2,512 transitions across eighty queries. Analyzer retains fourteen distinct
paths/360 transitions, Sass 23/346 and package flows 41/1,113; two-witness
forwarding retains nineteen distinct paths/617 transitions. Stock/model endpoint
outcomes remain 78/80 and 80/80, with 120/131 and 109/117 visible/detailed paths,
42/41 limited queries and seven/eight inconclusive negatives. Two-witness
summaries retain 86/89 and 81/83 paths with 43/42 limited queries. MediaType
retains two paths/six transitions. Both summaries reproduce byte-for-byte, and all 87 review rejection controls
pass against the fresh proofs and resource records.

Twenty-four changed selections have separate source-grounded dispositions.
Analyzer stock and modeled variants retain the same two sixty-node paths:
iterator/current and element extraction lead through bound.namedFields, record
serialization and readonly NodeList/BoolList length detours in either order,
with offset demands and external byte reads. Path variants select isRelative or
isAbsolute caller-output detours and the URL rootLength body while retaining
all style candidates. HTTP stock copy order is receiver 0 -> computed end 2 ->
skipCount 4 -> bytes 3; stock buffer order is 3 -> 2 -> 4 -> 0, with footer
bounds 1 -> 2 -> 0. Two-witness buffer re-entry selects end then bytes, while
four-witness stock selects bytes twice and four-witness modeled selects bytes
then end/header.codeUnits. Shorter variants omit an allocation or CR/LF saved
alias, or an initial skipCount detour; hidden receiver evidence remains explicit.
Exact slots, flags, fields and frames are retained. These readonly, range, field,
external, dispatch and caller approximations do not establish actual receivers,
callback delivery, branch feasibility, repeated-call order, allocation identity
or precise byte/element provenance. No causal claim ties these route selections
to map result metadata or pattern receiver intersection.

The qualification matrix links 104 named tests. Six matrix tests/sixteen
rejection controls, thirteen source-index rejection controls, full cached source
verification and two upstream-case tests/nine rejection controls pass. The
current type-view fixes do not close complete pattern CFG/implicit payloads,
checked generic/receiver environments, representation storage aliasing,
structured/function results, omitted alternatives or limited negative searches.
All Gates 1–6 remain open.

## Successful pattern refinement results

Exporter 0.3.27 records the inner pattern's successful matched type for cast and
null-assertion patterns. Lowering preserves its result identity and known
instantiated wrapper erasure through saved values and cached getter extraction.
Previously both operations produced ANY: a View<Store> cast or assertion then
expanded its Contract getter union to Store and unrelated OtherStore. The fixed
calls retain View and Store constraints; bounded View<S> results retain S and its
Store bound. Plain class, scalar, scoped generic, record and function identities
remain separate from the enclosing input type, without guessed class erasures.
Both new graph regressions fail against the previous implementation, and nine
focused graph checks pass after the fix.

Four native tests exercise independent values, wrong-type/null TypeError before
any getter, generic String/String? casts, bounded wrapper values, true/false
guard alternatives with one getter read, record values and selected function
identity/invocation. The exporter regression checks the original scoped generic
owner and wrapper bounds. These observations do not qualify arbitrary runtime
generic checks, callable resolution, implicit failure payloads or heap effects.

The full opt-in native suite passes 170 tests, including VM/dart2js controls;
Dart analysis reports no issues. The forced frontend suite passes 176 tests
across twenty suites with eight opt-in cancellations. All seven packages,
three applications and two holdouts pass, alongside four staged CLI and
fourteen console checks. Their twelve graphs share analysis-source SHA-256
`22d474c7d4ac73cf90fcce403c1cc72bce73dd0720c1336f9be99d4158f29692`
over 414 files. Method/call counts, default-adapter observations and every prior
iteration, map/list-pattern, field and representation observation are unchanged.
The [refinement review](pattern-refinement-review.json) compares independent
0.3.26/0.3.27 exports of the same pinned sources. Its three corpus refinements
previously lacked result metadata: analyzer's LibraryElementImpl cast and Sass's
two String null assertions. No corpus refinement has a wrapper erasure; the
reduced fixture supplies wrapper/generic/record/function interaction evidence.
These source observations do not count executed checks, receivers, callbacks or
storage instances. Fresh storage/local-exit reviews retain six paths/123
transitions and eleven paths/199 transitions. Formatting and compilation pass.

Two-witness/two-round audits complete in 194165/49291/32174 ms for packages,
applications and holdout, with sampled family RSS 5477476/4770204/1840132 KiB.
Four-witness/two-round audits complete in 406395/50292/34189 ms, with RSS
6244508/5065808/2257788 KiB. All categories pass their 600000 ms budgets with
8 GiB/four-processor workers. RSS is sampled once per second over the process
family, not an exact peak or one process's heap.

Fresh four-witness dispositions cover 248 selected paths, 149 distinct routes
and 2,627 transitions across eighty queries. Analyzer retains seventeen distinct
paths/535 transitions, applications 52/675, Sass 23/346 and package flows
40/1,012. Two-witness forwarding retains seventeen distinct paths/553
transitions. Stock/model endpoint outcomes remain 78/80 and 80/80, with
120/131 and 109/117 visible/detailed paths, 42/41 limited queries and seven/eight
inconclusive negatives. Two-witness summaries retain 86/89 and 81/83 paths with
43/42 limited queries. MediaType retains two paths/six transitions. Both summaries
reproduce byte-for-byte, and all 87 review rejection controls pass against the
fresh proofs and resource records.

Twenty-one changed selections have separate source-grounded dispositions.
Analyzer stock variants follow metadata.annotations through readonly length
and parameter-output detours; one follows cached _length then bc/_buffer receiver
demands, the other offset argument arithmetic. The implicit metadata getter's
field declaration does not establish runtime initialization of the selected
fragment. Modeled variants follow the element getter's _element return through
bound.positionalFields and record serialization, with NodeList/BoolList length
detours in either order. All length/iterator candidate unions remain explicit.
LocalSend's changed modeled route crosses MapEntry value/key arguments, returns
through callback METHOD_REF/collection feedback, then re-enters via element.value
and extension getter. External substring bound-to-receiver effects and saved
interpolation values retain their conservative scope.

HTTP stock copy order is receiver 0 -> skipCount 4 -> computed end 2 -> bytes 3;
stock buffer order is 3 -> 2 -> 0, with footer bounds 1 -> 2 -> 0. Two-witness
buffer re-entry selects end then bytes; four-witness selects bytes twice.
Four-witness modeled copies use direct byte slot 3 -> receiver 0 effects.
Shorter variants omit an allocation or CR/LF saved alias while retaining hidden
receiver evidence. Exact slots, flags, demands and initial/later call frames
remain recorded. These readonly, range, field, external, dispatch and caller
approximations do not establish actual receivers, callback delivery, branch
feasibility, repeated-call order, allocation identity or precise byte/element
provenance. No causal claim ties these route selections to refinement metadata.

The qualification matrix links 111 named tests. Six matrix tests/sixteen
rejection controls, thirteen source-index rejection controls, full cached source
verification and two upstream-case tests/nine rejection controls pass. Complete
pattern CFG/implicit payloads, checked generic/receiver environments, callable
target propagation, representation storage aliasing, omitted alternatives and
limited negative searches remain unqualified. All Gates 1–6 remain open.

## Known callable refinement targets

Known immutable callable identity now survives cast/assert operands, saved values
and final pattern bindings. Joined final bindings retain a target only when every
alternative supplies the same target. Bound adapter and capture identities remain
intact through refinement, generic references, omitted defaults, repeated calls
and source receiver rebinding. Conditional, returned, nested-call, parameter,
mutable and extracted unknown pattern values stay unresolved; a descendant
METHOD_REF does not establish their selected value.

The new regressions exposed positional fallback binding after a named argument:
it previously counted that argument toward the positional slot. Fallback now
counts positional arguments separately while preserving source evaluation order.
Constructor-value invocation also retains the callable expression and checks
before allocation and arguments. The intermediate lowering discarded a direct
constructor cast; its separate regression fails before the evaluation fix.
The two original target/flow regressions fail against the previous implementation;
nine focused graph checks pass after all fixes.

Three native tests exercise independent inputs, true/false selected and mutable
callback branches, generic and named/default forms, receiver-once argument traces,
repeated invocation and receiver rebinding. An incompatible constructor-function
cast throws TypeError before argument evaluation or constructor events; successful
casts/assertions/aliases retain supplied and omitted nonnull defaults. These
bounded VM observations do not qualify general callback delivery, runtime generic
checks, implicit failure payloads or arbitrary heap identity.

The full opt-in native suite passes 173 tests, including the existing VM/dart2js
controls; Dart analysis reports no issues. The forced frontend suite passes 180
tests across twenty suites with eight opt-in cancellations. All seven packages,
three applications and two holdouts pass, alongside four staged CLI and fourteen
console checks. Their twelve graphs share analysis-source SHA-256
`fd6a6a8215cf5d7d63c58415c5c25d246071ed89f0f3113d8aa0b9f80cc29eaa`
over 415 files. Exporter protocol/metadata remain at 0.3.27. Method/call counts and
default-adapter observations are unchanged. The
[callable refinement review](callable-refinement-review.json) pins the before/after
graph provenance, reduced tests and narrow source census. It finds zero direct
refined-reference initializer candidates, excluding call results; it does not
census all stable alias chains, nested extraction or every if/switch input.
The reduced fixtures supply the new interaction evidence. Source counts do not
identify runtime callbacks, receivers or allocations.

The static-storage and local-exit witnesses also replay against this source:
six paths/123 transitions and eleven paths/199 transitions. The two focused
static tests pass. Fresh two/four-witness audits complete for all twelve graphs
under the ten-minute category budget, 8 GiB worker heap and four worker CPUs.
Two-witness package/application/holdout elapsed times are 225,338/50,288/35,195 ms,
with sampled process-family peak RSS 5,495,452/5,087,852/1,961,876 KiB.
Four-witness times are 399,332/53,308/35,198 ms and peaks
6,328,824/4,685,464/2,263,748 KiB. Startup and graph loading are included;
these observations do not qualify other budgets or unbounded searches.

Fresh four-witness endpoint outcomes remain stock 78/80 and modeled 80/80.
Stock retains 122 visible/131 detailed paths and 42 limited queries; modeled
retains 109/117 and 41. Eight modeled negative searches remain inconclusive.
All 248 selected paths have dispositions, sharing 147 distinct routes and
2,540 classified transitions. Analyzer retains 14 routes/360 transitions,
Sass 25/400, LocalSend/Saber 51/634, args/collection/meta 14/40,
Path/async/HTTP 40/1,087 and the holdouts 3/19. Two-witness forwarding retains
25 selections sharing 19 routes/647 transitions. Its complete summary retains
stock 86 visible/89 detailed paths with 43 limited queries and modeled 81/83
with 42. Both summaries reproduce byte for byte from the saved audits.

Twenty-eight changed selections (twenty four-witness and eight two-witness)
have separate pinned-source review. The remaining routes replay only after
removing graph node IDs; slots, targets, contexts, flags and demands are retained.
Analyzer's selected callback route now traverses cached element/bound/namedFields
and NodeList/_FbBoolList receiver-output detours before unresolved writeItem.
Sass's changed trim routes carry escape-index bounds into substring receiver or
start arguments. These remain conservative iterator, field, dispatch, readonly
output and external-call effects, with no concrete callback or character claim.

Path's selected POSIX rootLength body occurs inside either isAbsolute or the
later _needsNormalization, retaining the style candidate union. HTTP stock
copies select receiver 0 -> skipCount 4 -> cursor/start 1 -> bytes 3; buffer
copies select 3 -> 4 -> 1 -> 0 and footer 1 -> 2 -> 0. Two-witness stock buffer
re-entry selects end then bytes; four-witness stock selects bytes twice.
Four-witness modeled buffer re-entry selects bytes then end/header.codeUnits;
two-witness modeled selects end/header.codeUnits twice. Modeled byte effects
remain 3 -> 0. Shorter variants omit saved allocation/CR/LF aliases or initial
range operands while retaining hidden evidence. Initial and later call contexts
remain explicit. These paths do not establish feasible branch sequences,
actual sink/style/list instances, repeated-call order, allocation identity or
precise original-element provenance. No causal relationship between route
selection and the callable fix is claimed. Existing bounded helper executions
remain separate evidence; omitted routes and limited negatives remain open.

All seven review validators pass their 87 rejection controls. The direct media
capture review retains two paths/six transitions. The qualification matrix links
118 named tests; six matrix tests/sixteen rejection controls, thirteen source-index
controls, cached source verification and two upstream-case tests/nine rejection
controls pass. Complete pattern CFG, implicit payloads, runtime type/receiver
checks, arbitrary callable values, representation storage aliasing, callback
delivery, omitted alternatives and negative search completeness remain
unqualified. All Gates 1–6 remain open.

## Recursive caller task prerequisite

Both Args negative searches still report call-depth at depths four, eight and
sixteen on the preceding `ae35e62f7` graphs, despite their positive controls
passing at each budget. Scratch cutoff instrumentation reveals repeated Parser
caller/receiver states at increasing depths. Increasing the limit alone does not
settle these negatives. The source parser includes command recursion and receiver
reads through `_current`, `_validate` and child Parser construction; the selected
cutoffs include these conservative field/caller detours.

Dart task creation now stops a repeated fingerprint when its previous depth was
no greater: the previous task had at least as much budget available. All other
fingerprint fields remain equal, including selected call contexts, output channels,
field demands and pending storage exits. A later task with more depth available
still expands. Equivalent-state elimination occurs before depth reporting;
genuine unexplored depth cutoffs remain visible. Foreign-language filtering keeps
its preceding behavior. This is a directly required shared query-engine change,
with its regressions kept inside the Dart frontend.

Two reduced regressions fail against the previous engine and pass after the fix.
They exercise a mutually recursive caller cycle with connected/unrelated parameters,
depths four/eight, shared caches enabled/disabled and C/Java/JavaScript/Kotlin
boundaries. Generated-task controls preserve distinct calls, field demands,
exception channels, pending storage exits and increasing available budgets.
All twelve focused checks pass. The normal frontend suite passes 181 tests across
21 suites with nine opt-in cancellations; the separately enabled Flutter check
also passes. All 36 shared-engine tests and formatting checks pass. The matrix
links 120 named tests; its six tests/sixteen rejection controls pass.

Corpus and witness requalification for this prerequisite is recorded below. The
preceding source-pinned reports remain historical evidence. A scratch cycle-elimination
variant removed the two Args depth diagnostics at depths eight, sixteen and
thirty-two, retaining their positive controls, but that result is not promoted to
a fresh corpus or runtime absence claim. Cyclic witness enumeration, omitted
alternatives, general recursion, heap/callback boundaries and remaining limited
negatives stay open. All Gates 1–6 remain open.


## Recursive task corpus qualification

All seven packages, three applications and two holdouts pass, alongside four
staged CLI and fourteen console checks. The twelve graphs share analysis-source
SHA-256 `1f040f4a93ab7bb9c69a7f145f56c16ec34cf13454d624ff6743689178a27839`
over 416 files. Exporter metadata/protocol remain at 0.3.27; representation and
default-adapter counts are unchanged. Two focused static checks pass, and the
storage/local-exit reviews retain six paths/123 transitions and eleven/199.

The [recursive task depth review](recursive-task-depth-review.json) records 32
fresh observations across both model sets and four depths, with exact endpoint,
source, exporter, model and query-budget evidence. Both Args negatives remain
inconclusive at four. At eight, sixteen and thirty-two, they return no paths and
no diagnosed query limits while each independent positive control retains one
fully reported path. Default corpus budgets and outcomes are unchanged. These
observations qualify exercised graph/model searches; they do not prove complete
runtime/heap absence, cyclic witness enumeration or arbitrary callbacks.

Two-witness package/application/holdout audits complete in
189,153/52,309/35,198 ms, with sampled family peak RSS
5,697,940/4,488,012/2,046,404 KiB. Four-witness audits complete in
426,511/53,315/37,208 ms, with peaks 6,440,032/5,735,804/2,188,068 KiB.
Every category stays inside its ten-minute whole-process budget; workers use
8 GiB heaps and four CPUs, with startup/loading included and family RSS sampled
once per second. Other budgets and unbounded searches remain unqualified.

Four-witness outcomes remain stock 78/80 and modeled 80/80. Stock retains
120 visible/131 detailed paths and 42 limited queries; modeled retains 111/117
and 41. All 248 selections have dispositions, sharing 149 routes and 2,694
transitions: Analyzer 16/478, Sass 25/400, LocalSend/Saber 51/621,
args/collection/meta 14/40, Path/async/HTTP 40/1,136 and holdouts 3/19.
Two-witness forwarding retains 25 selections, 19 routes and 607 transitions.
The full two-witness summary retains stock 86 visible/89 detailed paths with
43 limited queries and modeled 81/83 with 42. Both summaries reproduce byte
for byte. All eight default modeled negative searches remain inconclusive;
the higher-depth Args follow-up is separate evidence.

Twenty-three changed selections (seventeen four-witness/six two-witness) receive
separate source-grounded dispositions. Analyzer's stock variants carry cached
_element/bound/positionalFields demands through writeType/_writeRecordType,
then NodeList and _FbBoolList readonly length detours in either order. All
length/iterator candidates remain explicit; unresolved writeItem is not promoted
to an executed callback. LocalSend's stock predicate/METHOD_REF/filter detour
omits the saved Uint8List allocation identifier while retaining hidden evidence.
The new one-node Async route is endpoint identity at sink.add argument one;
it does not independently establish delivery or a selected sink implementation.

HTTP stock copy now selects range receiver 0 -> skipCount 4 -> bytes 3,
omitting cursor/start 1 and computed end 2. Stock buffer copies select
3 -> 4 -> 0 and readonly footer end 2 -> computed start 1 -> receiver 0.
The initial addSlice context is retained; subsequent output-marked add re-entries
select end/header/allocation then bytes. Modeled copies retain direct 3 -> 0;
re-entry selects end/header.codeUnits then bytes. Two-witness variants match the
first two four-witness variants exactly. Shorter routes omit a saved allocation,
CR/LF identifier, both CR/LF identifiers or an initial range operand while retaining
hidden receiver evidence. All slots, targets, contexts, flags, edge labels and
demands are preserved. These field, iterator, readonly output, range, external,
dispatch and caller effects remain conservative; they do not establish actual
instances, feasible branches, repeated-call order, allocation identity or exact
original-element provenance. Bounded native helper observations remain separate.

The seven validators pass all 87 rejection controls; the direct media capture
review retains two paths/six transitions. The matrix links 120 named tests.
Its six tests/sixteen rejection controls, thirteen source-index controls,
full cached verification and two upstream-case tests/nine rejection controls
pass. General recursion, omitted alternatives, remaining limited negatives,
heap/type/callback/runtime and release qualification remain open. All Gates 1–6
remain open.


### Async negative depth follow-up

The [depth review](async-negative-depth-review.json) records eighteen observations
against the current frozen graph/source fingerprint `1f040f4a93ab7bb9c69a7f145f56c16ec34cf13454d624ff6743689178a27839`
(416 source files, exporter 0.3.27), with stock and the four optional model files,
call depths four/eight/sixteen, field depth four, four witnesses and two held rounds.
Every endpoint is unique and reporting omits no returned witnesses. Stock retains
the two known DART-FLOW-001 cross-argument/receiver alternatives. Modeled searches
return zero paths, but call-depth and witness-pruning limitations remain at every
depth; sixteen also reports field-depth widening. The negative remains inconclusive.

The independent error-to-completer-receiver control retains a path at every budget
and in both model sets. The error-to-first-argument route is an endpoint identity,
recorded separately. It does not supply the independent control. At depth sixteen,
individual queries took about 6.3–7.1 seconds, compared with roughly 30–54
milliseconds at eight; these query times exclude graph loading and JVM startup.

A separate bounded diagnostic run sampled eight call-depth cutoffs for the modeled
negative at eight. The saved sample traverses stream-queue caller fields and selected
constructor calls; the field and selected-call identities change. These tasks do not
meet the equivalent-state pruning precondition. The trace is diagnostic task evidence,
not an additional qualified returned witness or a justification to collapse distinct
heap demands.

`ErrorResult.complete` binds its existing error and stack-trace fields separately.
Its constructor may derive an omitted trace from `AsyncError.defaultStackTrace(error)`;
the pinned SDK reads `Error.stackTrace` when available. Existing execution controls
with separately supplied fields therefore do not establish independence for every
constructor input or default-trace case. General runtime heap, callback delivery,
omitted alternatives and all completion gates remain open. The scratch depth and
cutoff runs each passed one test; the recorded observation contract and source/model
hashes were checked before saving this review. No graph, model or search default changed.
