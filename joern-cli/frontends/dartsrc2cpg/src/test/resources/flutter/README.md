# Resolved Flutter fixture

Pinned SDK: Flutter 3.35.3, revision `a402d9a437`, Dart 3.9.2. Dependencies are
locked in `pubspec.lock`. Prepare dependencies explicitly from the repository
root using an installed matching Flutter SDK:

```sh
mkdir -p agents/flutter-fixture
cp -R joern-cli/frontends/dartsrc2cpg/src/test/resources/flutter/lib agents/flutter-fixture/
cp joern-cli/frontends/dartsrc2cpg/src/test/resources/flutter/pubspec.* agents/flutter-fixture/
"$FLUTTER_ROOT/bin/flutter" pub get --enforce-lockfile --directory agents/flutter-fixture
export DART_FLUTTER_TESTS=1
# Optional override if prepared elsewhere:
export DART_FLUTTER_PACKAGE_CONFIG="$PWD/agents/flutter-fixture/.dart_tool/package_config.json"
(cd joern-cli/frontends/dartsrc2cpg/astgen && dart test)
sbt 'dartsrc2cpg/test'
```

The tests copy this configuration and source into isolated projects under
`agents/`. They invoke only the exporter and graph passes: no dependency fetch,
Flutter build, code generation or application execution occurs during scans.
Ordinary unit runs omit `DART_FLUTTER_TESTS` and skip the SDK integration tests.
A configured run fails rather than skips when dependencies are missing.

The exporter test requires error-free resolution of five widget constructors
and a separate `dart:ui.Color` reference. The frontend test asserts:

```scala
cpg.call.methodFullName("package:flutter/.*text_button.dart.*").size // 2
cpg.call.methodFullName("package:flutter/.*text_button.dart.*")
  .argument.argumentNameExact("onPressed").isMethodRef.size // 2
cpg.closureBinding.size // 1
cpg.call.codeExact("sink(input)").argument
  .reachableByFlows(cpg.identifier.nameExact("input")).nonEmpty // true
cpg.call.codeExact("sink('constant')").argument
  .reachableByFlows(cpg.identifier.nameExact("input")).nonEmpty // false
```

These queries describe callback bodies and captured values. They do not infer
whether a button is pressed or when the framework invokes a callback.
