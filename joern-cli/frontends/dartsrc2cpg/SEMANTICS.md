# Dart graph conventions

## Names, libraries and types

The analyzer's declaration IDs are authoritative, including library-private names
and references through imports, exports, prefixes and parts. Namespace block full
names identify the defining library; each source file retains its own AST.
Directives are IMPORT nodes with URI and prefix properties; their code retains
`show`/`hide`, export and part syntax. Resolving visibility is the analyzer's job,
not a second name resolver in Scala.

Classes are TYPE_DECLs, and fields and top-level variables are MEMBERs. Type and
method full names use exported IDs, so identical names in separate libraries,
accessors and constructors remain distinct. Interface types use their declaration
identity for type relations; generic arguments and nullability remain in source
and exporter display types. Type parameters retain their symbolic types. This is
a graph of generic declarations, not separate copies for each instantiation.

## Calls, parameters and constructors

Explicit parameters start at index 1. Instance methods and generative constructors
have `this` at index 0. Instance calls carry the receiver at argument 0 and a
RECEIVER edge. Static calls and factories have no receiver. Arguments retain
source evaluation order in `order`, parameter binding in `argumentIndex`, and
named bindings in `argumentName`. Omitted optional arguments follow explicit
arguments as constant literals, using exported default source or `null`.

Generative creation is a block that saves an allocation in a local, initializes
that receiver, and yields the same local. Generative constructors, including
implicit and representation constructors, return `this` in the graph; a bare
constructor return also yields `this`. This connects initialized fields to the
value used by the caller. Factory results are also saved before use so field
reads have a tracked receiver. Initializing formals and field
initializers assign fields in constructor bodies. Redirecting constructors call
their targets; redirecting factories forward parameters by position/name and
return the target creation. Super formals bind to exported superclass parameters.
Implicit constructors and super calls are included. Static and top-level field
initializers live in `<clinit>` methods. Dart's lazy initialization timing is not
modeled by scheduling these methods at particular reads.

Function values and tear-offs use METHOD_REF nodes. Function-value invocation
evaluates its target as a RECEIVER child with argument index -1, without an
ARGUMENT edge. Only actual arguments participate in argument/parameter flow;
otherwise a callback target can incorrectly carry taint between invocations. Closures have captured locals
and CLOSURE_BINDING edges with BY_REFERENCE semantics. Bound instance tear-offs
use wrapper methods capturing a receiver evaluated once. Final local function
values with a known initializer link to that target. Arbitrary mutable function
values and higher-order callback targets remain dynamic; this frontend does not
perform a whole-program function-value points-to analysis.

## Expressions and control flow

Branches, loops, switches and exceptions use standard control structures and
explicit condition/body edges. Switch cases with statements have implicit breaks.
For-in loops evaluate the iterable once and lower to iterator/moveNext/current.
Short-circuit boolean operators use Joern's standard logical operators.

Null-aware access and access chains use a temporary plus a conditional. Receivers
are evaluated once and skipped arguments stay inside the non-null branch.
Coalescing and null assignment use conditionals; assignment locations retain
single evaluation of receivers and indices. Cascades evaluate their target once.
Collections and interpolation use operator calls with children in source order.

Unknown syntax remains UNKNOWN with its source text and a warning. Exporter
unsupported-kind diagnostics are also logged, including unsupported descendants
of otherwise supported declarations. Modern syntax uses the conventions below.

## Modern Dart lowering

Records use `<operator>.record` with ordered key/value associations. Positional
keys are `$1`, `$2`, etc.; named keys retain their names. Reads and destructuring
use field accesses. The shared engine can propagate whole-record dependencies;
this representation does not promise independent taint for every record field.

Patterns evaluate the matched value once. Variable patterns declare/reference
locals with analyzer identities; logical-or variables use their joined identity.
Record/object/list/map patterns combine an opaque `<operator>.patternShape`
predicate with field/getter/index extraction. Rest patterns use
`<operator>.patternRest`, including the count of trailing elements. Typed,
relational, constant and null-check patterns use ordinary operators. Logical
patterns and guards short-circuit. Cast/null-assert patterns retain their operators.
Shape and rest operators preserve dependencies and structure, but have no exact
runtime matching or slice summaries. Predicate paths are conservative.

Destructuring includes a mismatch THROW path; exact exception types and atomic
assignment behavior on failures are not simulated. Switch expressions assign a
result temporary in ordered IF branches and read it after the selected branch.
Synthetic assignments use distinct code to avoid aliasing with their enclosing
expression in the shared reaching-definition analysis. Pattern switches keep a
SWITCH boundary for explicit breaks and ordered pattern/guard tests within it.
Analyzer exhaustiveness diagnostics remain visible; an unmatched switch
expression has a synthetic THROW exit.

Mixins, extensions and extension types have TYPE_DECL owners. Analyzer supertypes
include applied mixins. Extension methods keep an explicit receiver at index 0,
typed as the extended type, with statically selected targets; explicit extension
overrides evaluate their receiver once. Extension types have a representation
MEMBER and a primary constructor that assigns it. Runtime representation erasure
is not simulated. Dart class modifiers are retained as `dart.*` annotations;
`abstract`/`sealed` also emit ABSTRACT and `final` emits FINAL. Dart `interface`
is retained as an annotation, since it does not forbid method implementations.

Expanded collection literals save an empty accumulator and update it with explicit
`listAppend`, `setAdd`, `mapPut` or `collectionExtend` operations. Nested collection
for/if bodies update the same accumulator; the expression yields its final value.
Spreads retain an explicit spread operator. Null-aware spreads/elements evaluate
once and skip null; null-aware map keys skip value evaluation when null.
Element-value dependencies are tested separately from conditions that determine
membership or multiplicity. These controls do not establish noninterference:
selection can change observable output. The shared engine does not distinguish
exact slots, duplicate set membership or map-key overwrites across iterations.

Async/generator methods carry `dart.async`/`dart.generator` annotations. Await,
yield and yield* become `<operator>.await`, `.yield` and `.yieldAll`. Await-for
uses `<operator>.streamIterator` and a WHILE whose moveNext is awaited, followed
by current extraction and the source body. These are source-order CFGs: no
suspension/resumption graph, iterator cancellation/finalization, event scheduling,
isolate or framework lifecycle analysis is inferred. See
[runtime summary requirements](RUNTIME_SUMMARIES.md) for effects not provided by
syntax lowering.

Package configuration and language versions are owned by the analyzer. Unit
records expose the effective language version, including file overrides; missing
configuration can use the analyzer's default language version. Scans never run
pub, generators or builds. Existing generated files and workspace packages are
included. Conditional imports/exports record `selectedUri`; the header identifies
`analyzer-default` as the conditional environment. No custom declared variables
are passed; the tested `dart.library.io` conditional selects its fallback. This
is not a selectable VM/web build target. Alternative source files under the input
are still scanned independently, but references follow the selected library.

## Resolution and analysis limits

Resolved calls carry analyzer declaration IDs. Joern's default call graph overlay
links those targets and can add overrides using inheritance and compatible method
signatures. Calls without a resolved target retain `<unresolved>.name`; the
frontend does not invent targets for arbitrary dynamic dispatch. External method
stubs come from Joern's overlays. Their bodies and library-specific effects are
not inferred.

The shared CFG overlay approximates exception matching: it does not connect every
potential throwing instruction to every applicable handler, or route every abrupt
exit through finally. Catch locals and finally bodies are retained structurally.
Collection and field dependencies use the shared dataflow engine's heap model;
these tests do not establish full object-sensitive heap or callback analysis.
In particular, returned objects can carry constructor input into unrelated
constant fields. A regression records this shared-engine approximation.

## Regression coverage

All frontend fixtures enable AST schema validation, post-frontend validation at
V3 and default overlays. Dataflow fixtures additionally use OSS dataflow.

| Feature | Assertions |
| --- | --- |
| Libraries | Cross-file and re-export resolution, prefix/combinators, shared part namespace, private-name rejection |
| Types | Distinct library types, inheritance/interfaces, generic fields, instance/static receiver indices |
| Constructors | Named/factory/redirecting/implicit targets, field initialization, super formals, factory named bindings |
| Parameters | Positional/optional/named/defaulted bindings, reordered evaluation, positive/negative flow |
| Functions | Captures from enclosing method, known function values, bound/generic/constructor tear-offs, distinct anonymous IDs, positive/negative flow |
| Control flow | Conditions, loop back edges, switch labels, breaks/continues, throws/catch locals/finally structure, branch overwrite rejection |
| Expressions | Short-circuit nodes, whole null-aware chains, receiver/index single evaluation, cascades, collections, interpolation, casts and unary/compound operators |
| Fields | Initializer methods, accessor targets, positive/negative field-write flow |
| Partial graphs | Unresolved calls, explicit UNKNOWN nodes, unsupported-kind reporting |
| Integration | Two-file flow and graph reload; staged CLI and console regression |

For milestone 4 coverage and exact limits, see the [feature matrix](FEATURES.md).

## Corpus-driven lowering checks

Increment/decrement operands use analyzer read/write identities. Locals and
synthetic fields retain standard pre/post operators. Custom accessors evaluate
the receiver and getter once, call the setter with the updated value, and return
the old value for postfix or the updated value for prefix expressions. Closure
capture discovery includes read/write and invocation targets, including writes
without reads and callable parameters.

Enhanced enums are TYPE_DECLs with constant MEMBERs, constructor calls in a
static initializer, and source-declared constructors and methods. Implicit
constructors are retained. Generated enum `values` lists and `index`/`name`
implementation details are not synthesized.

Assertions branch on `<operator>.assertionsEnabled`, preserving both enabled
and disabled execution. The condition executes only on the enabled path; the
message executes only on the failure path before a THROW. This does not select
a Dart build mode or model the precise AssertionError constructor.

Labels on loops and blocks produce separate break and continue targets. Labeled
break goes after the labeled statement; labeled continue goes to the loop update
or condition, bypassing the remaining body. Switch-case labels remain unsupported.
Dynamic object-pattern fields retain field access on the matched value when the
analyzer cannot resolve a getter, instead of creating an unbound identifier.

Application corpus regressions also distinguish cascade accesses from implicit
calls nested in their arguments and closures. Only accesses marked `cascaded` by
the analyzer use the cascade temporary. Symbol literals are typed LITERALs.
Part files have distinct top-level initializer identities; parsed fallback types
use file/offset identities when declarations are unavailable. External function
tear-offs receive method stubs even without a direct CALL site.

## Late initialization

Late fields and top-level variables use analyzer-identified getter/setter methods.
Initializers live in getters rather than constructors or eager initialization
methods. Late locals keep their initializer in a closure that captures referenced
lexical storage; reads invoke it conditionally. Nested closures capture both the
late local and its initializer closure. Writes do not read the old value first.

`<operator>.isInitialized` tests storage state independently of its value, so null
is not used as the uninitialized sentinel. A successful initializer writes the
slot; an exception leaves it uninitialized. Reads without an initializer and
repeated writes to late final slots have explicit error branches. An initializer
of a late final variable also checks for reentrant initialization before storing.
These are CFG/state representations, not a path-sensitive heap interpreter: the
OSS engine does not prove initialization predicates or invocation counts.

The executable oracle covers null caching, separate objects, failed-initializer
retry, write-before-read and local capture timing. See Dart's
[late variable semantics](https://dart.dev/language/variables#late-variables).
Non-late static/top-level initializer timing remains a separate qualification
obligation; the existing `<clinit>` representation is retained for those variables.

## Exception exits and cleanup

The shared CFG builder carries pending returns, throws and outward jumps through
enclosing finally bodies. Abrupt cleanup replaces the pending exit. Nested
cleanup runs inside-out; jumps to labels within the protected body stay inside it.
The DDG excludes return values that cannot reach method exit without another
return or throw replacing them. Calls in protected regions have conservative
exceptional paths, including calls before the last statement, and empty catches
retain a continuation.

Catch-type selection remains conservative. A shared finally body joins pending
exits, so the CFG can combine incoming states and outgoing continuations; it is
not a path-feasibility proof. Existing fringe-based exception edges are retained
for frontends without complete exception representations. The Dart fixture and
execution oracle cover normal cleanup, overriding return/throw, nested cleanup,
break, continue and rethrow. Language-independent and C/C++ regressions cover
the shared changes.

Shared pattern cases combine their guards in source order with short-circuit OR
and execute their common body when any guard succeeds. Each guard retains its
own pattern-variable storage; successful guards copy values into separate body
locals. Logical-or alternatives inside one pattern share their join identity.
The pinned SDK guard-capture oracle checks which closures observe subsequent
writes; CPG regressions check separate capture identities and the grouped CFG.
Callback-container execution and path feasibility remain conservative.
