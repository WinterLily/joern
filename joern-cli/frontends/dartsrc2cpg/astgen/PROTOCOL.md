# Analyzer bridge protocol 1

The stream contains one `header`, zero or more `unit` records sorted by file path,
and one `summary` with the emitted file count. Each record occupies one UTF-8 JSON
line. Consumers must reject missing/truncated streams, mismatched counts,
unsupported protocol versions, and unsupported analyzer/exporter versions before
constructing a graph. The Scala consumer validates the protocol, analyzer, SDK and offset encoding
before constructing a graph. Exporter 0.3 adds modern-language fields to protocol 1.

The header declares `protocolVersion` (1), `exporterVersion` (0.3.18),
`analyzerVersion` (8.4.1), `sdkVersion` (3.9.2), and `offsetEncoding` (`utf-16`).
No timestamps or checkout root are emitted. The exporter builds records one file
at a time; the analyzer may retain project state internally.

## File records

Each unit has a root-relative, URI-escaped `file`, a `library` identity, exact
`source` text, `status`, `diagnostics`, `unsupportedKinds`, `nodes`, and `symbols`.
Status is `resolved` when resolution completed without error diagnostics,
`partial` when resolution completed with errors, or `parsed` when the analyzer
could not supply a resolved unit and syntax fallback was used. Warnings do not
change status. `resolved` does not imply complete exporter syntax coverage:
consumers must also inspect `unsupportedKinds`.

Diagnostics include the analyzer code, severity, message, and source span.
The synthetic `resolution_unavailable` diagnostic has no span. Per-file I/O, resolution exceptions and missing analysis contexts produce parsed
fallback units with `resolution_unavailable` diagnostics. Unreadable source has
empty source text. Fatal discovery/context-initialization errors still abort the
stream, so the absence of a summary is significant.
There is no automatic fetching of missing packages.

Offsets and lengths are **zero-based UTF-16 code units**, with exclusive end
`offset + length`; lines and columns are **one-based**, also in UTF-16 units.
Offsets apply to `source` without newline normalization. Scala/Java String slices
use these units directly; any CPG column conversion must be tested separately.

## Nodes and symbols

Nodes have file-local preorder integer IDs, beginning with compilation unit 0,
and include `kind`, span, location, and an ordered `children` array of
`{role, node}` edges. Role names are explicit for supported syntax (for example,
`receiver`, `arguments`, `initializer`, `left`, `right`, `body`). Repeated roles
preserve source order. Arguments remain in evaluation order, including named
arguments; traversal is not a control-flow graph. Expressions include their
analyzer type, or null when unavailable. Function bodies record async/generator
flags without promising their control-flow semantics.

Unmodeled syntax has kind `Unsupported`, a diagnostic-only analyzer syntax name,
exact source span, and generic `child` edges. Syntax names are not stable protocol
tags. Supported nodes may have unsupported descendants. Tokens, annotations and
modifiers are recoverable from source; not all have dedicated protocol fields.

Declarations expose `declaration` symbol IDs; simple identifiers expose
`reference`; method invocations expose `target`. Null means unresolved.
Symbols are deduplicated and sorted by ID per file; cross-file repetitions refer
to the same declaration. IDs combine the declaring source URI, declaration name
canonical UTF-16 fragment offset, element kind, and name, using the analyzer's base element for
instantiated references. They are stable for an unchanged project under relocation,
not across edits. `package:` and `dart:` identities are retained when supplied by
the analyzer; other project files use root-relative identities. External file
URIs may be absolute. Consumers should treat IDs as opaque strings. Unnamed constructors and anonymous functions use canonical fragment offsets,
which remain available when name offsets are null. Synthetic function-type
parameters without a usable offset are anchored to their owner and parameter
slot/type. IDs remain opaque to consumers.

Symbols include names, kinds, declaring files/libraries, offsets, and variable or
return types when applicable. Executable `parameters` lists use declaration
order. Parameter symbols include `named`, `required`, and `defaultValue` source
text (null when absent). Argument lists expose `bindings` in source order,
mapping each argument offset to a parameter ID or null. Omitted arguments are
not synthesized: compare bindings with the target's parameter list to discover
omitted defaults. Never infer parameter bindings by argument position alone.

Core nodes include classes/members, constructors and initializers, function
expressions/references, control structures, and core operators/collections.
Executable and variable symbols expose static/private/synthetic flags. Variables
also expose finality; property accessors identify their backing variable.
Constructors identify factory status and super targets. Classes expose display
supertypes and canonical `superDeclarations`. `typeId` and `returnTypeId` provide
canonical interface identities alongside the original display types.

Assignment expressions expose `read` and `write` targets separately: analyzer
identifiers on the left of a write do not necessarily carry a reference. Access
nodes record null awareness. Function expressions identify their executable
symbol; implicit constructors are recorded on classes. Super formals identify
the corresponding super parameter. Parameter and argument roles retain source
order throughout.

See [CPG lowering conventions](../SEMANTICS.md) for how these facts become graph
nodes and edges. An unresolved target or unsupported node is never evidence of
absence of dataflow.

## Modern syntax additions (exporter 0.3)

The header includes `conditionalEnvironment: "analyzer-default"`. No custom
conditional variables are supplied. Import/export nodes include `selectedUri`
when available and retain all configuration children. This records the analyzer's
selection, not an inferred execution platform.

Units include the effective `languageVersion`. Package configuration and per-file
overrides are honored by the analyzer; without configuration its default may be
newer than the runtime SDK. Disabled features produce diagnostics and recovery
ASTs, which need not retain the disabled syntax's original node kind.

Record literal/type nodes retain fields in source order. Pattern nodes retain
named child roles, binding/reference IDs, field names/getter references and guards.
Declared pattern variables and references canonicalize logical-or joins to the
same ID. Switch-expression cases have `guard` and `expression` children; if-case
uses a `case` child. For-loop parts may have a `pattern` instead of `variable`.

Mixin/extension/extension-type declarations expose member children and declaration
IDs. Extensions include `onType`, and extension symbols include `extendedType`.
Representation declarations expose field identity and primary `constructor` ID.
Class/mixin modifier names are explicit. Collection spreads record `nullAware`;
collection if/for retain branch and loop roles. For statements/elements record
`await`; yields record `star`. Function-body async/generator flags remain explicit.
These fields describe syntax and resolution, not runtime summaries.

## Optional operational measurements

The CLI accepts `ROOT INPUT SDK --metrics`; only this form adds `elapsedMillis`
and `peakRssBytes` to the summary. These nondeterministic process measurements
are absent from default exports. Protocol consumers may ignore these optional
fields. The Scala runner requests them for scan reports.

Exporter 0.3.1 adds `read`/`write` identities to prefix/postfix expressions,
`EnumDeclaration` with `constant`/`member` children, and `EnumConstantDeclaration`
with its declaration, constructor `target`, and optional `arguments`. It also
exports assertion `condition`/`message` children and `LabeledStatement` with a
`labels` string list and a `statement` child. Consumers must use update identities
rather than assuming an increment operand has its own `reference`.

Exporter 0.3.2 adds `cascaded` to method invocations, property accesses and index
expressions, and exports `SymbolLiteral` as a typed literal. Only marked nodes
use the current cascade target; unqualified calls nested in arguments or closures
retain their lexical receiver. Explicit scan files excluded by analyzer lint
options still resolve in the enclosing analysis context.

Exporter 0.3.3 adds generic and legacy type aliases (`aliasedType`), class mixin
applications (`constructors`), type literals (`referencedType`), implicit call
tear-offs (`target`), null-aware elements and map-entry guards (`nullAwareKey`,
`nullAwareValue`). Switch members retain `labels`. Synthetic parameter identities
include their owning declaration to distinguish forwarding constructors.

The CLI also accepts `--environment=vm|web|analyzer-default` and records the
selection as `conditionalEnvironment`. VM/web declared library variables come
from the pinned SDK's `lib/libraries.json` target and include graph; the default
retains analyzer behavior. Duplicate or unknown flags fail before export.

Exporter 0.3.4 adds `operatorTarget` to binary, prefix, postfix, index and
assignment expressions. The identity names the resolved operator method;
compound assignments retain separate `read` and `write` accessor identities.

Exporter 0.3.5 records `late` on variable symbols and the generated `getter`
and `setter` identities for late fields/top-level variables. Local late
initializers retain ordinary syntax; deferred execution is a lowering concern.

Exporter 0.3.6 records `collectionKind` (`list`, `set`, `map`, or `unresolved`)
on collection literals, using analyzer resolution to distinguish set/map updates.

Exporter 0.3.7 separates pattern variables captured by shared-case guards from
the body join variable. `SwitchPatternCase.joins` maps each guard's `source`
identity to its body `target`; copies happen only after the guard succeeds.
Logical-or joins within a single pattern still canonicalize their branches.
Joined pattern identities use their own fragment offset and a join discriminator, so separate patterns
with the same variable name in one function cannot collide.

Exporter 0.3.8 records `const` independently of `final` and exports accessor
identities for non-constant static/top-level variables as well as late fields.
The consumer places non-constant static initializers in guarded getters; constant
initialization remains structural compile-time storage.

Exporter 0.3.9 records `stringConversion`, `conversionNullable` and optional
`conversionTarget` on interpolation expressions. The target is resolved after
following type-parameter bounds and extension-type erasure; unknown dynamic
receivers remain unresolved. Nullable values take a literal "null" branch before
user conversion. Lowering assembles the converted fragments with read-only binary
concatenation, preserving explicit user `toString` calls.

Exporter 0.3.10 adds the synthetic static `values` field identity to
`EnumDeclaration`, and declaration-order `ordinal`, resolved `type` and `typeId`
to `EnumConstantDeclaration`. The frontend uses these to initialize enum storage
and resolve concrete enum SDK accessors without replacing user overrides.

Exporter 0.3.11 adds the optional `type` child of `CatchClause`, preserving the
resolved exception filter separately from its exception and stack parameters.

Exporter 0.3.12 adds `operatorTarget` to constant and relational patterns.
Constant equality resolves on the constant expression's type; relational equality
resolves on the matched value's type, including nullable interface types. Other
relational operators retain the analyzer's resolved target. The consumer evaluates
both equality operands once, skips user dispatch when either is null, and negates
the equality result for `!=`.

Exporter 0.3.13 adds `requiredType`, `requiredTypeId`, `lengthTarget`, `indexTarget`
and `sublistTarget` to `ListPattern`. Member lookup uses the matched interface
where available, falling back to the required list interface. Lowering preserves
length checks, lazy invocation reuse, user-defined indexing and rest slicing;
untyped wildcard elements do not read an index. A trailing rest slice uses the
optional null end argument, matching the pinned runtime without an extra length
read. General virtual target completeness and collection-slot dataflow remain
separate qualification obligations.

Exporter 0.3.14 adds `requiredType`, `requiredTypeId`, `valueType`, `indexTarget`
and `containsKeyTarget` to `MapPattern`, using the same member lookup as list
patterns. `valueType` retains nullability and type parameters for `null is V`.
`MapPatternEntry.keyIdentity` identifies equal analyzer constant values within
one unit, including constant aliases and null keys. Invalid/unresolved constant
keys omit this identity and keep separate source-local storage. The consumer
uses these identities only within a single matching construct and receiver path;
read and presence invocations have separate lazy storage. Protocol version 1
remains unchanged; the frontend requires this exporter revision.

Exporter 0.3.15 adds `constantIdentity` to constant and relational patterns.
The identity uses the same unit-local constant-value table as map key identities.
Pattern fields and relational operators resolved to an extension also record
`extensionTarget`, `extensionTypeArguments` and `extensionArgumentIdentities`.
Argument identities use analyzer type equality, preserving distinct declarations
with the same display name. Generic arguments are recovered through the pinned
analyzer's extension applicability inference for the already resolved extension;
the public pattern elements can omit substitutions that leave signatures unchanged.
If inference is unavailable, omitted argument identities prevent invocation reuse.
The consumer shares equality results between `==` and `!=`, applies negation after
the cache read, and keeps constant-receiver equality separate from relational
matched-receiver equality. Extension keys include the declaration and all inferred
argument identities. Protocol 1 remains unchanged; the frontend requires 0.3.15.

## Scoped generic declarations (0.3.16)

`TypeParameter.declaration` and type-parameter `typeId` values use analyzer
declaration identities. Their symbols retain `owner`, `boundType` and
`boundTypeId`; implicit bounds retain `Object?` and the Object declaration.
Explicit bounds on other parameters preserve those parameter identities.
Executable symbols retain `genericSignature` as the analyzer's displayed
function type. This metadata does not represent separate generic instantiations.

## Mixin super application targets (0.3.18)

Expression nodes with a direct superclass operation inside a mixin declaration
record `mixinSuper`, its lexical mixin declaration ID. Assignment/update nodes
retain this alongside their existing read/write member identities; this does not
make an operator on a property's value a superclass operator.

Interface symbols with applied mixins record `mixinSuperTargets`. Each entry has
the mixin ID, member `name`, executable `kind` and resolved implementation `target`.
Lookup visits preceding mixins in reverse application order, then the superclass's
implementation hierarchy. The applying class's own members and later mixins are
excluded. Private lookup uses the mixin's library. The pinned analyzer's internal
super-invoked-name inventory limits entries to members actually invoked by that
mixin. These facts identify declarations; generic substitutions and named-parameter
compatibility remain separate obligations.

## Anonymous generic function scopes (0.3.17)

`GenericFunctionType.typeDeclaration` identifies its analyzer pseudo-declaration,
anchored to the fragment's source offset. `FunctionTypedFormalParameter` retains
its parameter declaration separately from a derived `typeDeclaration` ending in
`:<functionType>`. Both retain displayed function types. Method return annotations
are exported as syntax children, including generic function types. Fresh inference
variables with no source fragment or enclosing element have `typeId` `ANY`; they
do not acquire a shared declaration identity from a missing offset.
