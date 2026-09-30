# Dart and Flutter feature matrix

Tested with Dart 3.9.2, analyzer 8.4.1, exporter 0.3.2 and Flutter 3.35.3
(framework revision `a402d9a437`). “Parsed” means explicit exporter nodes rather
than UNKNOWN. Resolution uses existing package configuration and SDK resources.
Dataflow claims below refer to the OSS engine with default semantics, not Dart
execution or exhaustive language conformance.

| Feature | Parsed | Graph modeling | Resolution assertions | Tested dataflow |
| --- | --- | --- | --- | --- |
| Core language | Yes | See [core conventions](SEMANTICS.md) | Cross-file functions, types, constructors, accessors, closures | Positive/negative call, parameter, field and capture fixtures |
| Records and record types | Yes | Record operator, named/positional fields, field access | Analyzer display types and local references | Whole-record dependency through destructuring; constant-record negative; no field-isolation claim |
| Pattern declarations/assignments | Yes | Evaluate RHS once, bind locals/references, extraction and mismatch exit | Stable declaration IDs, including joined variables | Record fixture and scalar switch bindings |
| List/map/object/rest patterns | Yes | Shape predicates, index/field/getter extraction, rest operator | Object getter targets and bound locals | Structural only; no complete shape, heap or rest-slice semantics |
| Constant/relational/logical/typed/null/cast patterns | Yes | Short-circuit predicates, casts and null checks/assertions | Analyzer types and references | Scalar binding flow; no path-sensitive match feasibility claim |
| If-case, guarded switch cases, switch expressions | Yes | Ordered tests, guard short-circuiting, result assignments, switch break boundary | Guard/body references | Positive scalar switch result and negative constant result |
| Mixins | Yes | TYPE_DECLs, inherited type identities, methods | Mixed-in method targets | Structural/resolution only |
| Extensions | Yes | TYPE_DECL owner, receiver at index 0, static target, explicit overrides | Implicit and explicit extension calls | Structural/resolution only |
| Extension types | Yes | TYPE_DECL, representation MEMBER and primary constructor assignment | Primary constructor and member targets | Structural/resolution only; runtime erasure is not simulated |
| Class modifiers | Yes | Source plus `dart.*` annotations; ABSTRACT/FINAL where applicable | Analyzer checks validity | Not a dataflow feature |
| Collection spreads and if/for elements | Yes | Ordered operands, null guards, conditional/loop CFGs | Loop local/pattern references | Structural/evaluation tests; no precise collection membership or accumulation model |
| Async functions and await | Yes | `dart.async` annotation, await value operator | Direct callee identity | Direct async value return/await positive and constant negative |
| Sync/async generators, yield/yield*, await-for | Yes | Generator annotation, yield operators, stream iterator with awaited moveNext | Source types and calls | Structural only; no producer-to-consumer stream flow claim |
| Workspaces and multiple packages | Yes | Existing sources across package contexts | Package configuration, cross-package calls, single-file export context | Cross-package and generated-part forwarding |
| Language versions | Yes | Effective version exported per unit; diagnostics retained | Package version and `// @dart` override; disabled-feature error | Not a dataflow feature |
| Conditional imports/exports | Yes | Original code plus selected URI | Analyzer default environment, fallback target | Selected package implementation only |
| Existing generated sources | Yes | Included normally, with shared part library | Generated part call target | Forwarding through an existing `.g.dart` |
| Flutter widgets and callbacks | Yes | Constructor arguments, METHOD_REFs, captured locals | Real Flutter SDK constructors and `dart:ui` | Callback body dependency and constant negative; no event delivery claim |

`DartFrontendTests` validates schema, V3 post-frontend invariants and overlays for
every fixture. `exporter_test.dart` checks diagnostics, explicit syntax coverage,
identities, package context and deterministic export. Flutter tests are opt-in
and fail if their prepared package configuration is missing; see the fixture's
[preparation and query assertions](src/test/resources/flutter/README.md).

Unsupported syntax still produces diagnostics and UNKNOWN nodes. For example,
generic type aliases retain UNKNOWN declarations. Named switch
labels/continue-to-case, exact pattern failure exceptions, general higher-order
callback dispatch, stream scheduling and precise exception routing are not
established by these tests. Future syntax accepted by the analyzer is not
implicitly supported. See [runtime summary requirements](RUNTIME_SUMMARIES.md).

Corpus hardening also covers enhanced enums (constants, constructors and method
bodies), labeled loop break/continue, assertion evaluation, increment/decrement
references and accessor effects, and dynamic object-pattern field access.
Assertions have an explicit enabled/disabled branch; no build mode is assumed.
Enum runtime-generated `values`, `index` and `name` behavior is not synthesized.

The [coverage completion plan](COVERAGE_PLAN.md) defines the remaining semantic
audit, conformance and release gates. Passing corpus endpoint queries does not
by itself validate every hop of a returned dataflow witness.
