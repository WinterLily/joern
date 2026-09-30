import 'dart:convert';
import 'dart:io';

import 'package:dart_astgen/exporter.dart';

Future<void> main(List<String> args) async {
  if (args.isEmpty || args.length > 3) {
    stderr.writeln('Usage: dart run bin/dart_astgen.dart ROOT [INPUT [SDK]]');
    exitCode = 64;
    return;
  }
  try {
    await for (final record in exportProject(
      root: args[0],
      input: args.length > 1 ? args[1] : null,
      sdkPath: args.length > 2 ? args[2] : null,
    )) {
      stdout.writeln(jsonEncode(record));
    }
  } catch (error) {
    stderr.writeln('dart_astgen: $error');
    exitCode = 1;
  }
}
