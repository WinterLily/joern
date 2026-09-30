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
Implicit constructors and super calls are included. Non-constant static and
top-level initializers live in guarded getters, invoked at reads. Constant
storage remains in `<clinit>` methods; compile-time constant evaluation itself
is delegated to the analyzer and is not re-executed by the graph.

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

A try statement has one catch dispatcher. It reads `<operator>.caughtException`
and `<operator>.caughtStackTrace` once into distinct temporaries, tests typed
clauses in source order, and binds each clause's locals on its selected branch.
The dispatcher has an explicit true CONDITION: incoming exceptions enter it;
unmatched typed clauses leave through an explicit rethrow. Rethrow arguments
retain the active exception and stack values. Nested functions do not inherit
that lexical rethrow context. Type tests retain both successors; this is not a
path-sensitive exception-type analysis. Calls remain conservatively throwable.
The caught-value intrinsics currently identify channels without linking thrown
payloads across handlers or calls; general exception dataflow remains open.

Finally bodies intercept pending exits. An explicit exception handled entirely
inside a cleanup preserves its pending return dependency. An exception escaping
that cleanup, a rethrow, or a replacement return discards the earlier value.
The shared CFG joins pending exits conservatively; these regression cases do not
establish arbitrary path feasibility.

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
constructors are retained. Each constant receives ordinal `index` and private
`<enumName>` storage before its constructor; a final static `values` list retains
declaration order. Concrete enum receivers resolve SDK `index`, `EnumName.name`
and default `toString` targets to generated helpers. Helpers use distinct method
identities so the shared dynamic linker preserves source overrides. Explicit
extension dispatch, `super.toString()` and bound default tear-offs are tested.
Private name storage cannot collide with a source library's `_name` field.

The executable oracle checks values identity/immutability, names, ordinals,
named constructors and overrides against Dart 3.9.2. The graph represents final
storage and list construction; it does not enforce runtime mutation errors,
constant canonicalization or activation of every static initializer. Interface
`Enum`/`Object` receivers without a concrete enum type retain external targets;
allocation-sensitive propagation through the shared heap remains conservative.

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

## Lazy and late initialization

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
Ordinary non-constant static/top-level variables use the same guarded storage
mechanism. A write before the first read skips initialization. Failed initializers
can be retried; successful values, including null, are cached. Reentrant final
initialization checks for an intervening write and throws; mutable initialization
can replace an inner value. `lazy_static_evaluation_test.dart` exercises these
cases. Initialization order follows represented reads, with the same shared
engine limitations on state predicates and recursive execution.

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

Explicit catch-body edges also exclude those blocks from legacy finally-position
fallbacks. Multiple catches without cleanup therefore keep their returns directed
to method exit; a second catch cannot become a spurious finally or return cycle.
This has a shared CFG reproducer and a Dart return-flow regression.

Primitive binary arithmetic, bitwise, logical and comparison operators preserve
operand-to-result dependencies without writing values into their other operands.
The shared summaries have C regressions as well as Dart primitive controls.
Unresolved dynamic overloads remain DYNAMIC_DISPATCH calls with conservative
external effects; they are not assigned primitive purity. Equality on non-primitive
receivers can dispatch through Object's operator target, and `!=` negates that
call. Dynamic indexed reads/writes and increments preserve receiver evaluation
and assigned-value behavior. Unknown target bodies and arbitrary virtual overrides
remain outside complete call-graph resolution.

Interpolation captures expression values and retains implicit `toString` calls,
including bounded generics and erased extension representations. Nullable values
have a separate "null" branch. Converted fragments use binary string
concatenation, so assembly cannot write one interpolation operand from another.
Virtual conversion targets remain subject to the documented dispatch limits.

The pinned backends differ in conversion order: the Dart 3.9.2 VM evaluates the
embedded expressions before conversion, while dart2js interleaves evaluation and
conversion. Adjacent literals and directly nested interpolations are flattened;
a function-call boundary completes its string argument before later expressions.
`analyzer-default` and `vm` follow the measured VM behavior; `web` follows dart2js.
The scan report records `stringConversionOrder`. These are backend-specific
contracts, not a general statement about every Dart implementation. The
[language specification](https://dart.dev/resources/language/spec/versions/DartLangSpec-v2.10.pdf)
describes per-expression conversion; the observed VM behavior is kept explicit.
The standalone runtime oracle covers JIT, AOT and dart2js/Node execution, including
exceptions and nulls. It does not qualify WebAssembly or arbitrary optimization
configurations.
