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
`limitExhaustion: not-observable` records a shared-engine API limitation: the
query result does not report whether its search was cut short. An empty result
is therefore `no-flow-observed-within-limits`, not proof of semantic absence.
Missing controls make a negative result inconclusive and fail the regression.
The harness requires the dataflow overlay and keeps semantic review `pending`.

Corpus reports include pinned source metadata, the actual exporter protocol
header, file coverage and the exact optional model text. Stock and optional
model results remain separate. Full reports are generated under
`agents/dart-corpus/*/dataflow-audit.json` and
`agents/application-corpus/results/*/dataflow-audit.json`.

## DART-FLOW-001: predicate result enters collection value flow

Owner: shared dataflow query engine and future Dart iterable models.
Disposition: reproduced, known approximation; not fixed.

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

## Further witness findings requiring reduced regressions

These were observed in the full reports; they are triage findings, not completed
source/transition reviews. Ownership starts with shared query-engine call-site
and external-call behavior, pending reduced reproduction.

| Issue | Probe | Observed route requiring review |
| --- | --- | --- |
| DART-FLOW-002 | analyzer `list-element-callback` | Leaves `items` through METHOD_PARAMETER_OUT, traverses unrelated receiver fields, and re-enters the list parameter before `items[i]`. |
| DART-FLOW-003 | http_parser `chunk-interprocedural-copy` | Reaches `bytes` through input length, buffer allocation and setRange arguments instead of a direct byte-parameter route. |
| DART-FLOW-004 | path `normalize-fast-return`; Sass `scss-source-forwarding`/`css-source-forwarding` | Passes through unrelated caller sites (`fromUri`/`readFile`) before re-entering the source method. |
| DART-FLOW-005 | LocalSend `filename-extension` | Leaves the selected return through a caller's map callback and re-enters the selected method. |

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
