# Reserved server/parser holdout

Shelf 1.4.2 (`lib`, `example`) and yaml 3.1.3 (`lib`) were selected after the
initial seven packages and three applications. Archive and selected-source
hashes are in `projects.json`; dependency resolution uses `pubspec.lock`.

Prepare explicitly with Dart 3.9.2, then run the existing graph/audit suite:

```sh
python3 joern-cli/frontends/dartsrc2cpg/scripts/holdout.py --from-cache "$HOME/.pub-cache"
DART_HOLDOUT_TESTS=1 sbt 'dartsrc2cpg/testOnly *DartCorpusTests'
```

Use `--prepare` instead of `--from-cache` to permit pinned downloads. Output is
under `agents/dart-holdout`. No package build scripts or code generation run.

The first shelf run found duplicate closure identities in a field initializer
expanded into multiple constructors. The regression now distinguishes those
expansions and their receiver captures. This is one discovered defect, not an
estimate of general precision. Shelf's late field initializer now lives in a guarded getter, with a
separate initialization-state predicate and runtime oracle. YAML passed structural checks on first run.
Four initial endpoint checks pair request/body and parser/source forwarding with
status/recovery-mode isolation controls. They do not cover HTTP delivery or the
complete YAML parser pipeline. After using these releases to fix a defect, they
remain regression projects; future generalization claims need fresh holdouts.

The [holdout witness review](../holdout-witness-review.json) covers all four
queries in both modes at call/field depth four, four witnesses and two held rounds.
It preserves three distinct paths and classifies all 19 transitions. Shelf's
external URI conversion remains a return approximation. YAML's three internal
bindings use source slot one; recovery mode is a separate parameter. Both
isolation queries have same-source, same-budget positive controls and no recorded
query limitation. This qualifies these bounded endpoint observations.

The native controls execute the actual pinned libraries, including the unchanged
Shelf example handler, without starting a server:

```sh
cd joern-cli/frontends/dartsrc2cpg/astgen
DART_HOLDOUT_TESTS=1 dart test test/holdout_boundaries_test.dart
```

Three independent URI/document inputs check response text/status and loaded
content. YAML runs with both recovery flags; missing-colon scanner errors recover
only with the explicit flag, while an incomplete flow sequence still throws.
These cases do not establish general parser recovery or HTTP event delivery.
