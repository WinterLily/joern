# Dart frontend prototype

The first implementation slice is the local analyzer exporter in `astgen/`.
It does not yet construct a CPG, register a Joern language, or prove dataflow.
The directory follows the `dartsrc2cpg` name in the implementation plan.

## Development

Use Dart **3.9.2** with analyzer **8.4.1**. Dependencies are locked in
`astgen/pubspec.lock`; the exporter rejects other analysis SDK versions.
Install the exporter dependencies explicitly:

```sh
cd joern-cli/frontends/dartsrc2cpg/astgen
dart pub get --enforce-lockfile
dart analyze
dart test
dart format --output=none --set-exit-if-changed lib bin test
```

Tests create and remove temporary projects under the repository's `agents/`
directory. They cover standalone files, relative and package imports, two-file
calls, reordered named arguments and omitted defaults, inferred types, `part`
files, Unicode, deterministic output after relocation, malformed source,
unavailable dependencies, generated files, and CLI behavior.

Run from `astgen/`:

```sh
dart run bin/dart_astgen.dart /absolute/project/root
# Export one file while preserving package context and stable file identities:
dart run bin/dart_astgen.dart /absolute/project/root /absolute/project/root/lib/main.dart
# Supply an analysis SDK explicitly (required for a native executable):
dart run bin/dart_astgen.dart /absolute/project/root /absolute/project/root/lib/main.dart /absolute/dart-sdk
```

The scan uses existing package configuration. It never runs `pub get`, a build,
or code generation in the input project. Existing generated `.dart` files are
included; `.git` and `.dart_tool` contents and directory symlinks are excluded.
The root must contain the input. SDK discovery uses the running Dart executable's
location; an explicit SDK path takes precedence. A native executable still needs
the SDK and must receive its path as the third argument.

Output is JSON Lines on stdout; fatal failures go to stderr and set exit code 1.
Invalid CLI usage sets exit code 64. Source diagnostics yield partial records
and do not cause a nonzero exit. See [the protocol](astgen/PROTOCOL.md).

## Scope and next steps

The prototype exports named AST roles for the initial function/call/local/literal
subset. It preserves remaining syntax as explicitly unsupported nodes, with
source spans and generic children. This is an exporter coverage claim, not a
claim of CPG, control-flow, or dataflow support.

The Flutter-style test intentionally has no Flutter dependency installed: it
checks useful partial output and unsupported class/constructor reporting.
Resolution against a real Flutter SDK remains untested. Packaging for other
platforms, analyzer crash recovery, analysis-option exclusions, SDK-independent
execution, stable external file identities across machines, and Scala Unicode
conversion and protocol validation also remain future work. Syntax-only fallback
uses the analyzer's default language feature set when resolution is unavailable.

The repository loads with JDK 21 and Sbt 2.0.9. Inspection of the pinned CPG
1.7.78 `Languages` class found no Dart identifier. An upstream schema addition
and dependency update must be settled before frontend/console integration; this
prototype does not add a competing language constant.

Next, complete the bridge acceptance checks (including resolved Flutter and the
Scala boundary), then implement the Scala frontend and the two-file positive and
negative dataflow proof. Exporter source is owned locally for this prototype;
release infrastructure ownership remains undecided.
