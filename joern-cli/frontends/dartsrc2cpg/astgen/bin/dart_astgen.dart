import 'dart:convert';
import 'dart:io';

import 'package:dart_astgen/exporter.dart';

Future<void> main(List<String> args) async {
  if (args.isEmpty ||
      args.length > 5 ||
      args
          .skip(3)
          .any(
            (arg) => ![
              '--metrics',
              '--environment=analyzer-default',
              '--environment=vm',
              '--environment=web',
            ].contains(arg),
          ) ||
      args.skip(3).where((arg) => arg.startsWith('--environment=')).length >
          1 ||
      args.skip(3).where((arg) => arg == '--metrics').length > 1) {
    stderr.writeln(
      'Usage: dart run bin/dart_astgen.dart ROOT [INPUT [SDK [--metrics] [--environment=vm|web|analyzer-default]]]',
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
      environment:
          args
              .skip(3)
              .where((arg) => arg.startsWith('--environment='))
              .map((arg) => arg.split('=').last)
              .firstOrNull ??
          'analyzer-default',
    )) {
      if (args.contains('--metrics') && record['record'] == 'summary') {
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
