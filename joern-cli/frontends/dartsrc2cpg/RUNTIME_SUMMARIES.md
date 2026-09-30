# Runtime library summary requirements

Syntax lowering and library behavior are separate. Ordinary resolved source calls
use method bodies; external calls use the OSS engine's default conservative
behavior unless an explicit summary is loaded.
The direct async fixture proves source argument/return/await dependencies only.
The Flutter fixture proves a captured value reaches the sink expression inside
a callback; it does not prove that Flutter invokes that callback.

## Optional Completer summary

The distribution includes `dataflow/async.semantics`, an opt-in summary for
`dart:async`'s `Completer.completeError(error, stackTrace)`. It preserves both
inputs in the receiver, keeps each input's identity, and prevents error/trace
cross-contamination. The method has no returned value. It does not model delivery
to a Future consumer. Focused positive/negative tests and the real `async`
package exercise this contract.

Load it for queries against an existing OSS dataflow overlay:

```scala
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.semanticsloader.FullNameSemanticsParser
import io.joern.dataflowengineoss.queryengine.EngineContext
val rules = new FullNameSemanticsParser()
  .parseFile("/absolute/path/to/dartsrc2cpg/dataflow/async.semantics")
  .map(_.copy(regex = true))
val semantics = DefaultSemantics().plus(rules)
semantics.initialize(cpg)
val dartContext = EngineContext(semantics = semantics)
// Pass dartContext explicitly to a reachableByFlows query:
// sinks.reachableByFlows(sources)(dartContext)
```

Regex matching is required because analyzer declaration offsets vary between SDK
versions. The file encodes the declaration separator as `\x23` because the
semantics format treats a literal hash as a comment delimiter. This summary is
not enabled automatically by `run.ossdataflow`; the corpus reports stock and
modeled outcomes separately.

## Optional worker_manager summary

`dataflow/worker_manager.semantics` models `Executor.execute` from worker_manager
7.2.6, used by Saber. Task and priority inputs are stored in the executor; the
returned cancelable result carries the task dependency. Neither input is copied
into the other. Application checks verify closure capture, preservation of the
returned encryption dependency, and rejection of path-to-priority flow. The
summary does not prove that a worker runs, nor model scheduling or cancellation.

Load it using the same parser and `regex = true` setting as above. To combine
models, concatenate their parsed rules before `DefaultSemantics().plus`.
Models are not loaded automatically.

## Optional iterable summary

`dataflow/iterable.semantics` targets Dart SDK 3.9.2 `Iterable.where`, `map`
and `join`. For pure callbacks with known identities, `where` preserves element
values without treating predicate results as elements, `map` uses callback
results, and `join` preserves element/separator dependencies. Tests share the
Dart execution fixture `iterable_selection.dart`: predicate capture has no
explicit value dependency, identity mapping preserves input, and constant
mapping excludes it. Selection, callback side effects and lazy iteration timing
are outside this model. Unknown callback identities retain query-engine limits.

The source/receiver rules require the argument-specific return-summary validator
in the shared dataflow engine. Earlier behavior admitted every argument whenever
any argument had a return mapping. Stock results remain separately reported;
loading this summary is an explicit choice of its narrower contract.

## Remaining models

Future summary work needs explicit contracts and positive/negative tests:

| Library API | Required value/callback relationship | Exclusions to test |
| --- | --- | --- |
| `Future.value`, `Future.sync`, `Future.then` | Payload to awaited result; completion value to callback parameter; callback return to chained future | Constant callback returns; unused input; separate futures; error-only handlers |
| `Future.catchError`, `whenComplete` | Separate success/error channels; completion callback does not replace successful payload | Handler filters, thrown errors, unrelated completion values |
| `Stream.value`, `fromIterable`, `map`, `asyncMap` | Source element to transform parameter and transformed element to consumers | Independent streams; constant transforms; filtered elements |
| `Stream.listen`, `forEach`, await-for | Event payload to consumer parameter/loop local | Error/done callbacks, cancellation, unrelated subscriptions |
| `yield`, `yield*` | Yielded values or delegated stream elements to the enclosing generator's output | Unrelated generators and values after termination |
| Flutter callback properties such as `onPressed` | An explicitly selected framework model may invoke the stored METHOD_REF | Registration alone must not imply invocation or any lifecycle order |

Index-based call summaries alone cannot resolve arbitrary callback values or
represent future/stream payload storage and scheduling. Those features need a
separate model and tests before being advertised. No isolate messaging,
event-loop ordering, cancellation timing, widget lifecycle, rendering, or
framework-wide taint propagation is claimed.
