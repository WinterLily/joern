# Analyzer bridge protocol 1 (prototype)

The stream contains one `header`, zero or more `unit` records sorted by file path,
and one `summary` with the emitted file count. Each record occupies one UTF-8 JSON
line. Consumers must reject missing/truncated streams, mismatched counts,
unsupported protocol versions, and unsupported analyzer/exporter versions before
constructing a graph. The Scala consumer and its compatibility tests are not yet
implemented. Version 1 is provisional until that boundary is tested.

The header declares `protocolVersion` (1), `exporterVersion` (0.1.0),
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
The synthetic `resolution_unavailable` diagnostic has no span. Fatal I/O or
analyzer exceptions abort the stream, so the absence of a summary is significant.
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
UTF-16 offset, element kind, and name, using the analyzer's base element for
instantiated references. They are stable for an unchanged project under relocation,
not across edits. `package:` and `dart:` identities are retained when supplied by
the analyzer; other project files use root-relative identities. External file
URIs may be absolute. Consumers should treat IDs as opaque strings. Full
canonicalization for synthetic elements and advanced language constructs is not
yet established.

Symbols include names, kinds, declaring files/libraries, offsets, and variable or
return types when applicable. Executable `parameters` lists use declaration
order. Parameter symbols include `named`, `required`, and `defaultValue` source
text (null when absent). Argument lists expose `bindings` in source order,
mapping each argument offset to a parameter ID or null. Omitted arguments are
not synthesized: compare bindings with the target's parameter list to discover
omitted defaults. Never infer parameter bindings by argument position alone.

Member dispatch, closures, constructors, and general library/module modeling
remain outside the supported semantic subset. Downstream consumers must not
interpret an unresolved target or unsupported node as proof of no dataflow.
