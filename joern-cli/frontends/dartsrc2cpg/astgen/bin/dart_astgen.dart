import 'dart:convert';
import 'dart:io';

import 'package:dart_astgen/exporter.dart';

Future<void> main(List<String> args) async {
  if (args.isEmpty ||
      args.length > 4 ||
      (args.length == 4 && args[3] != '--metrics')) {
    stderr.writeln(
      'Usage: dart run bin/dart_astgen.dart ROOT [INPUT [SDK [--metrics]]]',
    );
    exitCode = 64;
    return;
  }
  final timer = Stopwatch()..start();
  try {
    await for (final record in exportProject(
      root: args[0],
      input: args.length > 1 ? args[1] : null,
      sdkPath: args.length > 2 ? args[2] : null,
    )) {
      if (args.length == 4 && record['record'] == 'summary') {
        record['elapsedMillis'] = timer.elapsedMilliseconds;
        record['peakRssBytes'] = ProcessInfo.maxRss;
      }
      stdout.writeln(jsonEncode(record));
    }
  } catch (error) {
    stderr.writeln('dart_astgen: $error');
    exitCode = 1;
  }
}
