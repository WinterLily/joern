# Dart and Flutter feature matrix

Tested with Dart 3.9.2, analyzer 8.4.1, exporter 0.3.12 and Flutter 3.35.3
(framework revision `a402d9a437`). “Parsed” means explicit exporter nodes rather
than UNKNOWN. Resolution uses existing package configuration and SDK resources.
Dataflow claims below refer to the OSS engine with default semantics, not Dart
execution or exhaustive language conformance.

| Feature | Parsed | Graph modeling | Resolution assertions | Tested dataflow |
| --- | --- | --- | --- | --- |
| Core language | Yes | See [core conventions](SEMANTICS.md) | Cross-file functions, types, constructors, accessors, closures | Positive/negative call, parameter, field and capture fixtures |
| Records and record types | Yes | Record operator, named/positional fields, field access | Analyzer display types and local references | Bounded positional/named field isolation through calls and destructuring; constant canonicalization unqualified |
| Pattern declarations/assignments | Yes | Evaluate RHS once, bind locals/references, extraction and mismatch exit | Stable declaration IDs, including joined variables | Record fixture and scalar switch bindings |
| List/map/object/rest patterns | Yes | Shape predicates, index/field/getter extraction, rest operator | Object getter targets, bound locals and lazy per-match ordinary field storage | Stateful getter reuse and nested-path isolation; no complete shape, heap, extension-cache or rest-slice semantics |
| Constant/relational/logical/typed/null/cast patterns | Yes | Short-circuit predicates, casts and null checks/assertions | Analyzer types and references | Scalar binding flow; constant/relational user operator targets and null guards; invocation caching and path-sensitive match feasibility unqualified |
| If-case, guarded switch cases, switch expressions | Yes | Ordered tests, guard short-circuiting, result assignments, switch break boundary | Guard/body references | Positive scalar switch result and negative constant result |
| Mixins | Yes | TYPE_DECLs, inherited type identities, methods | Mixed-in method targets and forwarding application constructors | Constructor argument to superclass field; mixin dispatch |
| Extensions | Yes | TYPE_DECL owner, receiver at index 0, static target, explicit overrides | Implicit and explicit extension calls | Structural/resolution only |
| Extension types | Yes | TYPE_DECL, representation MEMBER and primary constructor assignment | Primary constructor and member targets | Structural/resolution only; runtime erasure is not simulated |
| Class modifiers | Yes | Source plus `dart.*` annotations; ABSTRACT/FINAL where applicable | Analyzer checks validity | Not a dataflow feature |
| Collection spreads and if/for elements | Yes | Ordered operands, null guards, conditional/loop CFGs | Loop local/pattern references | Explicit accumulator updates and nested-loop value flow; exact slots, deduplication and key overwrites conservative |
| Async functions and await | Yes | `dart.async` annotation, await value operator | Direct callee identity | Direct async value return/await positive and constant negative |
| Sync/async generators, yield/yield*, await-for | Yes | Generator annotation, yield operators, stream iterator with awaited moveNext | Source types and calls | Structural only; no producer-to-consumer stream flow claim |
| Workspaces and multiple packages | Yes | Existing sources across package contexts | Package configuration, cross-package calls, single-file export context | Cross-package and generated-part forwarding |
| Language versions | Yes | Effective version exported per unit; diagnostics retained | Package version and `// @dart` override; disabled-feature error | Not a dataflow feature |
| Conditional imports/exports | Yes | Original code plus selected URI | Explicit VM/web SDK environments and analyzer default, selected target | Distinct selected implementation flows and constant alternatives |
| Existing generated sources | Yes | Included normally, with shared part library | Generated part call target | Forwarding through an existing `.g.dart` |
| Flutter widgets and callbacks | Yes | Constructor arguments, METHOD_REFs, captured locals | Real Flutter SDK constructors and `dart:ui` | Callback body dependency and constant negative; no event delivery claim |

`DartFrontendTests` validates schema, V3 post-frontend invariants and overlays for
every fixture. `exporter_test.dart` checks diagnostics, explicit syntax coverage,
identities, package context and deterministic export. Flutter tests are opt-in
and fail if their prepared package configuration is missing; see the fixture's
[preparation and query assertions](src/test/resources/flutter/README.md).

Generic and legacy function aliases are TYPE_DECLs with aliased types; type
literals are TYPE_REFs. Named switch labels and continue-to-case retain their
CFG targets, including jumps past a target pattern guard. Null-aware collection
elements and map keys/values have explicit guards and evaluation-order tests.
The [pinned inventory](conformance/inventory.json) classifies the analyzer AST
surface separately from semantic qualification.

Unsupported syntax still produces diagnostics and UNKNOWN nodes. Exact pattern
failure exceptions, general higher-order
callback dispatch, stream scheduling and path-sensitive catch selection are not
established by these tests. Future syntax accepted by the analyzer is not
implicitly supported. See [runtime summary requirements](RUNTIME_SUMMARIES.md).

Corpus hardening also covers enhanced enums (constants, constructors and method
bodies), labeled loop break/continue, assertion evaluation, increment/decrement
references and accessor effects, and dynamic object-pattern field access.
Assertions have an explicit enabled/disabled branch; no build mode is assumed.
Generated enum `values`, ordinal/name storage and concrete receiver accessors
are represented, with runtime and override regressions. General interface
dispatch and allocation-sensitive enum heap flow remain conservative.

The [coverage completion plan](COVERAGE_PLAN.md) defines the remaining semantic
audit, conformance and release gates. Passing corpus endpoint queries does not
by itself validate every hop of a returned dataflow witness.

Resolved user-defined arithmetic, equality, unary and index operators retain
method calls. Compound/index updates evaluate receiver and index once, preserve
prefix/postfix values, and yield the assigned value after void setters. Null-aware
updates guard the entire index/right-hand evaluation. SDK primitive operators
retain shared operator semantics; unresolved dynamic dispatch remains conservative.
Bounded Dart execution traces and argument-versus-constant dataflow tests cover
these contracts; operator syntax alone does not establish exact heap behavior.
