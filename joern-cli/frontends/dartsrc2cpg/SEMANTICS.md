# Dart graph conventions

The frontend uses Joern's `X2CpgFrontend`, `CpgPass`, `Ast`, standard node/edge
schema and shared overlays. Named binding uses `argumentName`, also used by the
Kotlin and Python frontends. Generated callable bodies use ordinary methods and
capture/REF edges, following the same representation conventions as Java lambda
methods. Dart-specific passes handle analyzer facts and Dart lowering rules;
shared binding/linking exceptions require a `DART` graph and boundary regressions.

## Query witnesses

The default engine selects one longest witness per endpoint pair. For Dart graphs,
`EngineConfig(maxWitnessesPerEndpoint = N)` with `N > 1` retains up to `N` distinct
paths at each intraprocedural, held-task and final selection stage. Selection
orders longer paths first and uses the existing deterministic tie breaker.
Distinct paths retain call contexts, output channels and field demands. Other
languages keep their previous selection behavior.

Bounded selection ranks only the path-length groups needed to fill the bound.
Identical entries and groups containing a single candidate require no ranking
key. Equal paths with differing task metadata still use the full tie breaker
before deduplication. Unvisited shorter groups retain the pruning diagnostic,
since different lengths cannot represent duplicate paths.

This is a bounded audit mode. Intermediate pruning can exclude a route before
final selection, and loops, call depth and other engine limits still apply.
`QueryDiagnostics` records `witness-alternatives` whenever the bound drops a
distinct path. No absence or path-feasibility guarantee follows from returning
several witnesses. The corpus harness records the bound, omitted reporting counts
and detailed paths with invisible nodes, call stacks, output flags and field
demands. Visible paths may merge details that differ only in engine context.
`maxHeldTaskIterations` separately bounds Dart held-task combination rounds.
Its default of zero preserves the previous fixed-point search. When pending
combinations remain at a positive bound, diagnostics record `held-task-iterations`;
any absent result under that limit is inconclusive. It does not bound the runtime
of one round or qualify unreturned routes. Foreign and mixed-language held-task
sets retain their previous behavior.

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
identity for type relations. Class, extension, mixin, method and alias type
parameters are nested TYPE_DECLs marked by `dart.typeParameter` annotations.
Their analyzer IDs distinguish separate declarations named `T`; bound relations
refer to the bound's declaration, including bounds on other parameters.
An omitted bound retains the Dart default `Object?` in its generic signature.

The CPG `genericSignature` property retains Dart text: analyzer function types on
methods, declared display types on members/locals, type parameter lists on type
declarations, bounds on parameter declarations and source on aliases. Generic
arguments and nullability are preserved there while interface/parameter
`typeFullName` relations refer to declarations. Nullability and promoted-type
feasibility remain conservative. Inline generic function-type scopes are
`<functionType>` TYPE_DECLs marked by `dart.functionType`, nested in their enclosing
alias, method, type or bound parameter. Anonymous analyzer identities use source
offsets; old-style function parameters retain a derived signature identity.
Unavailable enclosing declarations retain namespace placement. Fresh inference
variables without declarations use `ANY`. Explicit function instantiation retains
the analyzer's instantiated function type and complete source text on the result.
Runtime reified type arguments and bound-check exceptions still need qualification.
This is a graph of generic declarations, not separate copies for each instantiation.

## Calls, parameters and constructors

Explicit parameters start at index 1. Instance methods and generative constructors
have `this` at index 0. Instance calls carry the receiver at argument 0 and a
RECEIVER edge. Static calls and factories have no receiver. Arguments retain
source evaluation order in `order`, parameter binding in `argumentIndex`, and
named bindings in `argumentName`. Omitted optional arguments follow explicit
arguments as constant literals, using exported default source or `null`.
When resolved implementations have different defaults or additional optional
parameters, `DefaultArgumentPass` replaces declaration defaults with per-target
`<defaultArguments>` methods. Each method forwards explicitly supplied values
and supplies that target's defaults by name or positional slot. Explicit values
are evaluated in the caller; a bound tear-off reuses its captured receiver.
Adapters with the same namespace, declaration, target and supplied parameter slots
share one body. A bound declaration identifies its capture, so separate bound
receivers cannot share an adapter. Bound adapter entry points have ordinary
METHOD_REF/CAPTURE edges to the saved receiver, created alongside the original
bound reference. The original reference remains the block's resulting value;
entry-point references do not reevaluate the receiver. Copying a capture ID onto
a local alone is insufficient for capture discovery.
When a captured read reaches an enclosing parameter, Dart query expansion drops
closure invocation frames only when their METHOD_REF/CAPTURE edges establish the
lexical owner, including nested captured scopes. It retains the selected ordinary
caller and field demand. Constructor-value, saved-receiver rebinding and nested
alias regressions check used versus independent inputs at call depths four and
eight with no reported search limitations. This does not qualify arbitrary
mutable callback targets. Static storage has the separate bounded contract below.
The original declarations, call identity and dispatch kind are retained. Adapter
calls select an already resolved target statically. These extra methods consume
call depth; the `defaultArguments` report distinguishes adapted call sites,
unique target adapters, links from calls to those adapters, and unique adapters
delegating to external placeholders. External `pN` parameter stubs
retain positional slots; their bodies and named signature metadata are unqualified.
Defaults retain exported constant source; this does not model arbitrary constant
object state or establish runtime receiver contexts.

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
values with a known initializer link to that target, including stable copies and
explicit generic instantiation through those copies. Targets come from the actual
initializer value; a reference nested in a call argument or conditional branch
does not identify that value. Named binding and omitted defaults use the known
declaration's parameters, with the same per-target default adaptation for known bound calls. Mutable, conditional and returned function values and
higher-order callback targets remain dynamic; this frontend does not
perform a whole-program function-value points-to analysis.

## Expressions and control flow

Branches, loops, switches and exceptions use standard control structures and
explicit condition/body edges. Switch cases with statements have implicit breaks.
For-in loops evaluate the iterable once and lower to iterator/moveNext/current.
For statically known interface receivers, synchronous iteration retains the
analyzer-selected iterator getter, moveNext method and current getter. The
substituted iterator static type filters the observed implementation union;
this does not identify the runtime receiver instance. Declared, assigned,
collection and record-pattern loop forms have target and native member-order
controls. Pattern loops save current once before destructuring. Dynamic receivers,
type-parameter receivers without exported iteration targets and asynchronous SDK
iteration retain explicit unresolved boundaries; generic runtime substitutions,
iterator heap effects and element payload dataflow remain unqualified.
Short-circuit boolean operators use Joern's standard logical operators.

The added exception routing, lexical capture, nested block-value and constant-field
flow rules apply only to graphs whose metadata language is `DART`. Other languages
retain their previous CFG and dataflow behavior. Read-only Dart operator summaries
use `<operator>.dart.*` method full names while call names retain the standard
operators, preserving CFG recognition without changing existing operator summaries.

A try statement has one catch dispatcher. It reads `<operator>.caughtException`
and `<operator>.caughtStackTrace` once into distinct temporaries, tests typed
clauses in source order, and binds each clause's locals on its selected branch.
The dispatcher has an explicit true CONDITION: incoming exceptions enter it;
unmatched typed clauses leave through an explicit rethrow. Rethrow arguments
retain the active exception and stack values. Nested functions do not inherit
that lexical rethrow context. Type tests retain both successors; this is not a
path-sensitive exception-type analysis. Calls remain conservatively throwable.
Explicit throw operands feed their lexical catch dispatcher's value channel.
An explicit second operand feeds only the stack channel, so rethrow preserves
the two separately. These dependencies require a CFG
route that preserves the pending exception through intervening cleanup. A
cleanup-local handler receives its own exception; the suspended outer value
continues to its enclosing handler. Internal calls expose separate exception
value and stack channels. Queries follow escaping throws through callee bodies,
wrappers and rethrows with the call stack retained. Normal return summaries do
not summarize exception payloads, and caches distinguish these channels.
Handled or replaced exceptions do not escape their callee. External, unresolved
and implicit runtime exception payloads remain unmodeled; searches that reach
these boundaries report a corresponding exception-channel limitation. Call-depth
limits apply to exception traversal too. These contracts do not establish
path-sensitive exception types or general heap precision.

Finally bodies intercept pending exits. An explicit exception handled entirely
inside a cleanup preserves its pending return dependency. An exception escaping
that cleanup, a rethrow, or a replacement return discards the earlier value.
A break/continue leaving cleanup also discards the pending value, while a jump
to a loop or label inside cleanup preserves it. The same boundary checks apply
to pending return and exception values.
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

## Frontend architecture

The frontend follows Joern's exporter-to-CPG pipeline, also used by the C# and
JavaScript frontends. It implements `X2CpgFrontend`, uses the common `Ast` and CPG
schema, and runs shared metadata, type-node, linking, CFG and dataflow passes.
Analyzer identities supply resolved declarations and direct targets. Dart-specific
lowering uses ordinary CPG calls, temporaries, references and control structures;
its custom operator summaries and shared-engine extensions apply only to DART graphs.
The current AST creation is concentrated in one pass, whereas established frontends
usually split an `AstCreator` into declaration/expression/statement traits with a
scope abstraction. This organization is implementation debt; shared architecture
and passing regressions do not establish equivalent semantic maturity.

## Modern Dart lowering

Records initialize explicit field slots in source order. Positional keys are
`$1`, `$2`, etc.; named keys retain their names and do not increment positional
indices. Reads and destructuring use field accesses. Constant field paths retain
independent dependencies within the engine's field-depth limit; arbitrary aliases
and mutable collection slots remain outside that contract.

Patterns evaluate the matched value once. Variable patterns declare/reference
locals with analyzer identities; logical-or variables use their joined identity.
Record and object patterns combine a shape predicate with lazy field/getter
extraction shared within one match. Nested receiver paths keep storage separate.
List patterns test the required type and length, then call resolved index and
sublist members. Untyped list wildcards skip extraction; typed wildcards retain
their reads and type tests. Prefix positions, tail offsets and slice bounds
identify cached extractions. Map patterns test the required type and call resolved
index/containsKey members in entry order, including wildcard entries. A null read
requires both `null is V` and key presence; non-null reads bypass containsKey.
Constant key values, including aliases and null, identify shared per-match read
and presence storage. Nested receiver paths and separate matches stay distinct.
Exact mutable collection-slot dataflow and complete virtual dispatch remain open.
Constant comparisons call equality on the constant receiver, while relational
comparisons call the matched receiver. Equality preserves operand evaluation
order and skips user dispatch for null operands. Comparison results use per-match
storage keyed by the constant argument and receiver path; `!=` negates the shared
`==` result. Constant-pattern equality has a distinct key for its reversed receiver.
Extension getter/operator keys include the declaration and inferred argument type
identities, preserving reuse within a substitution and separation across substitutions.
Unavailable extension inference keeps calls separate. Logical patterns
and guards short-circuit. Cast/null-assert patterns retain their operators.
Predicate paths and virtual targets remain conservative.

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
MEMBER and a primary constructor that assigns it. Own extension-type members use
static dispatch. Inherited class members use representation erasure to constrain
the analyzer hierarchy union, including nested, instantiated and bounded wrappers.
Expression and synthetic iterator/current results retain their original static
types; `dart.receiver.erasure` tags carry known class/type-parameter constraints
through saved receivers and bound captures. Declaration erasure remains a fallback
when no instantiated constraint is available. Record/function representations,
runtime generic environments and representation storage aliasing remain
unqualified. Runtime representation erasure is not simulated. Dart class modifiers are retained as `dart.*` annotations;
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

Resolved calls carry analyzer declaration IDs. A Dart-owned pass links virtual
implementations selected by analyzer lookup across scanned class hierarchies,
including covariant returns/parameters and renamed generic parameters. Lookup
respects private library identity, and receiver static types restrict the union
to compatible observed classes. Abstract source declarations remain represented
but are excluded as executable targets. Unavailable external API declarations
remain placeholders for unresolved implementations and optional summaries.
Static members, constructors, extensions and `super` operations retain
static dispatch, including bound tear-offs and accessors/operators. Calls through
a known function value select that value; an ordinary bound instance tear-off's
wrapper still invokes its receiver virtually. `super` retains the lexical
superclass view of the same `this` object. Source instance fields have internal
implicit getter/setter bodies, unless abstract or explicitly external. Explicit
accessors retain their own bodies. Reads/writes use accessor calls when an observed
override can change the selected member; otherwise they use direct storage.
Constructor initializing formals and field initializers always write storage
directly. Super accessor calls select the lexical storage implementation. Lazy
accessor increments retain both their read and write operations.
Static storage across separate setter/getter calls remains an interprocedural
heap limitation: `lazyIncrement` in the implicit-accessor fixture has a runtime
input-to-return dependency that the current dataflow engine does not recover.
The `virtualDispatch` report records resolved calls, calls without observed
targets, external targets and unmodeled implicit accessors. The target union does
not establish runtime receiver points-to, type-check feasibility or unscanned
implementations. Reordered named inputs and output parameters have bounded
execution and receiver-isolation regressions, including implementations with
additional optional parameters. Dart output binding uses names; unavailable
external stubs with generic `pN` parameters retain positional fallback. Other
languages retain their existing binding. Unknown callable targets and external
bodies remain unqualified.
Mixin superclass operations additionally link implementations preceding the mixin
in observed applications, including superclass overrides and earlier mixins.
Lookup respects private library identities. A Dart-specific call pass adds these
edges before shared overlays. The graph retains one mixin body and unions targets
across applications, alongside its resolved constraint target. It does not select
an application-specific body for each caller. Unscanned applications, external
implementation bodies and application-specific receiver contexts remain unqualified.
Calls without a resolved target retain `<unresolved>.name`; the
frontend does not invent targets for arbitrary dynamic dispatch. External method
stubs come from the Dart pass or Joern's overlays. Their bodies and library-specific
effects are not inferred. A small shared-linker guard preserves marked Dart target
sets; unmarked Dart calls and every other language retain existing linking rules.

The shared CFG overlay approximates exception matching and path feasibility;
the explicit catch and cleanup contracts above do not qualify arbitrary paths.
Collection and field dependencies use the shared dataflow engine's heap model;
these tests do not establish full object-sensitive heap or callback analysis.
Queries now retain a requested constant field path through internal calls and
returns, including nested fields and caught objects. This rejects the former
`Box(input).other` false positive while preserving `.value` flow. Direct field
copies retain their value dependency. Opaque summaries do not promise an object
layout and remain conservative. Field prefixes default to depth four; longer
suffixes are widened to retain every descendant and report `field-depth-widening`.
This bounds recursive field contexts without treating truncation as an absence
of flow. Receiver-alias writes can still lose flow, and
field overwrites can retain old object taint; paired runtime/static fixtures
record both remaining approximations. Collection slots, general alias mutation
and allocation-sensitive heap behavior remain unqualified.

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

The Dart-only shared query prerequisite follows a static-field read backward
through CFG predecessors and resolved calls. It identifies the slot by the
qualified owner and canonical name of a STATIC member, retains selected caller
stacks, and stops each route at its nearest matching assignment. The stored RHS
becomes an ordinary value-flow task with the original field demand. Getter and
setter signatures, ASTs and CFG edges stay unchanged; the memory transition is
reported as `<STATIC_STORAGE>` in detailed witnesses.

`static_storage.dart` and its SDK oracle check direct/nested writes, unrelated
owners and fields, repeated helper calls, conditional and loop-carried writes,
overwrites on both branches, and reads saved before a subsequent write.
The original `lazyIncrement` input-to-return regression now passes. Each storage
search visits at most `EngineConfig.maxStaticStorageNodes` CFG positions (default
10,000), and also obeys call-depth, caller-expansion and output-task limits.
An unfinished search records `static-storage-search`.

The CFG builder records `dart.cfg.exit` tags on analyzed Dart bodies: `complete:2`
on the method return, `normal.after` for completed nonthrow exits and entries into cleanup, `throw.before`
for a failing call/explicit throw, and `throw.after` for an exception resumed
after cleanup. Intermediate cleanup positions also retain these roles, including nonthrow
break/continue/label resumptions. Version two distinguishes normal entry from a
protected operation that only enters cleanup after failing before completion. The
storage search keeps pending normal/thrown exit demands and restores them across
selected callee frames. Cleanup returns/throws replace the pending exit. Catch
entry edges select exceptional callee effects; normal reads select normal exits.
A completed cleanup resuming an exception remains a completed value definition;
a failure before the cleanup operation completes does not define its result.
No tags are added for foreign languages or external bodies. Saved graphs without
the contract report `static-storage-exit-metadata` and require regenerated graphs.

This traversal joins CFG routes rather than proving initialization predicates.
It records `static-storage-initialization-state` for the relevant state checks,
`static-storage-external-effects` for unresolved/external calls, and
`static-storage-exception-state` for protected calls/try regions. A negative
result with these diagnostics is inconclusive. The original `exceptional` store
now reaches its catch-time read. Cross-file controls cover rethrows, swallowed
exceptions, repeated calls, cleanup overwrites, nested cleanup and return/throw
replacement. `static-storage-implicit-exception-effects` reports unavailable
implicit failures; those routes stop. Joined exit roles report
`static-storage-joined-exits`.

Pending exit demands now travel through ordinary RHS tasks, internal returns,
parameter bindings and DDG expansion. They identify the method and exact selected
caller stack, independently of field demands and scalar/exception output channels.
Task caches, held-task combinations, cycle checks and witness selection preserve
these demands; detailed reports serialize them as `storageDemands`. A later static
read restores the pending cleanup state for that invocation. DART-FLOW-007's
original `copiedNormal` false positive is rejected, while `copiedCaught` preserves
input. Controls include a saved RHS through two helpers, an intervening other-slot
write, independent repeated calls, and simultaneous normal/caught queries from one
call site, at call depths four/eight and default/two-witness selection.

DART-FLOW-008's original `localCopyNormal` false positive is rejected. Ordinary
RHS reaching definitions are checked against backward CFG routes under the current
invocation's pending exit. Assignment values use the completed assignment as their
CFG point, since the identifier on its LHS is visited before RHS evaluation.
Crossing the relevant cleanup consumes its demand; earlier cleanup can therefore
have a different exit kind. Normal/caught local and loop copies, prior handled
cleanup, return replacement and simultaneous sinks have graph and SDK controls.
A constant-return replacement rejects the prior input, and inline cleanup retains
its stored input. The prior-cleanup review distinguishes the scalar return route
from a conservative unchanged-argument output route; it does not claim String
mutation.

This bounded availability check uses `maxStaticStorageNodes` independently for
each distinct use/definition/exit query. Unfinished checks report
`static-storage-value-search`; cross-method value contexts and unavailable call
effects report `static-storage-value-context` and
`static-storage-value-external-effects`. Implicit operator-failure origins stop
with the existing diagnostic. Foreign/default paths have no pending demand and
retain their prior behavior. The check joins remaining CFG alternatives rather
than solving predicates, allocation identities or unbounded execution. Reentrant
initialization, retry state, arbitrary callbacks and general execution-state/heap
qualification remain open.

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


Synchronous iteration lookup follows declared/promoted type-parameter bounds
without replacing the receiver parameter's symbolic identity. Synthetic current
calls use the instantiated static getter result, while the callee's generic
declaration return type remains unchanged. Chained/recursive bounds, inherited
members and nullable promotion have static regressions and bounded execution
controls. Nullable/unbounded invalid sources retain diagnostics and omit target
facts. The pinned analyzer accepts a bounded record-pattern loop that the Dart
3.9.2 compiler rejects; separate tests retain that disagreement rather than
qualifying it as executable. Runtime type environments, checked substitutions,
instance-sensitive dispatch and iterator payload/heap effects remain open.

Synchronous iteration also resolves `moveNext` and `current` through an iterator
getter return type parameter's bound, including chained bounds. The iterator
call and saved receiver retain the original scoped type parameter; the bound
supplies member lookup rather than a replacement runtime type. Concrete and
symbolic current results remain distinct from generic callee declarations.
Pinned native values/member-order controls and invalid getter override diagnostics
cover this reduced boundary. Runtime substitutions, checked bounds, precise
iterator element storage and full loop CFG/value-flow qualification remain open.

Object-pattern accessor uses carry the required type's known class erasure as an
additional dispatch constraint. Observed hierarchy candidates must satisfy both
that requirement and the matched expression's known static/representation bound;
a broad required pattern does not replace a narrower known receiver. Declaration
fallbacks retain their existing conservative contract.
Object and record fields retain analyzer-instantiated static result types. Shared
pattern getter storage presents each use's current static type and erasure, so
one case's generic substitution does not become every later case's constraint.
Type-failure exclusion, nested/record values and repeated-case getter evaluation
have bounded native/graph controls; full pattern CFG, payload/heap effects and
runtime generic environments remain unqualified.

List-pattern index results retain the required pattern's instantiated element
type, before child narrowing. Slice results retain its required List type
independently of an override's generic declaration return type. Caller generic
parameter identities remain separate from the index getter's class parameter.
Known extension-wrapper element erasures survive saved/cached extraction and
nested list patterns. Cached uses keep their own static views; this does not
qualify runtime type environments, representation storage aliases or complete
pattern CFG/payload effects.

List rest extraction omits the slice end argument when no elements follow the
rest pattern. Per-target adapters then supply the selected implementation's
default. Tail patterns pass their computed end, and explicit null remains a
supplied argument. Native and graph controls cover differing override defaults,
shared adapters, cached getter-once alternatives, skipped wildcards and a
short-list RangeError. Complete implicit exception payloads and runtime receiver
qualification remain open.

Map index calls retain instantiated nullable result metadata separately from
generic declarations and child matched types. A narrow String? getter remains
distinct from a broad Object? child view; child type tests do not replace an
Object? index result. Original nested wrapper types and representation constraints
survive saved/cached extraction, including different views across guard alternatives
and caller generic identities. Erased value types still govern null/presence checks.
The existing CPG declaration identities collapse nullable/nonnullable variants;
complete CFG, payload, heap and runtime type environments remain unqualified.
Analyzer accepts the reduced outer extension map with its own index operator,
while the pinned VM compiler crashes; separate exporter/compiler controls record
that disagreement without qualifying it as executable.


Cast and null-assertion patterns retain the inner pattern's successful static
result, separately from the enclosing input type. Saved results keep scoped
parameters and instantiated wrapper erasures, so a View<Store> refinement does
not widen its subsequent Contract getter to unrelated OtherStore. Per-use
cached extractions keep those constraints. Scalar, record and function results
retain their identities without guessed class erasures. Bounded VM controls
check invalid casts/null failures before getters, nullable generic casts and
record/function values; implicit failure payloads, runtime generic environments,
callable/heap effects and complete pattern CFG remain unqualified.
