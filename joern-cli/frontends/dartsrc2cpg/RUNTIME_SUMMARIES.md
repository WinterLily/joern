# Runtime library summary requirements

Syntax lowering and library behavior are separate. This milestone installs no
Dart library-specific dataflow summaries. Ordinary resolved source calls use
method bodies; external calls use the OSS engine's default conservative behavior.
The direct async fixture proves source argument/return/await dependencies only.
The Flutter fixture proves a captured value reaches the sink expression inside
a callback; it does not prove that Flutter invokes that callback.

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
