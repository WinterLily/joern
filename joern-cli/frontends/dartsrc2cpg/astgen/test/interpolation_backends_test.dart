import 'dart:io';

import 'package:test/test.dart';

void main() {
  test(
    'pinned VM and dart2js interpolation orders',
    () async {
      final scratch = Directory('../../../../agents')
        ..createSync(recursive: true);
      final output = scratch.createTempSync('dart-interpolation-');
      final source = File(
        '../src/test/resources/semantics/interpolation_runtime.dart',
      ).absolute.path;
      Future<void> run(String executable, List<String> arguments) async {
        final result = await Process.run(executable, arguments);
        expect(
          result.exitCode,
          0,
          reason: '${result.stdout}\n${result.stderr}',
        );
      }

      try {
        await run(Platform.resolvedExecutable, [source]);
        final native =
            '${output.absolute.path}/oracle${Platform.isWindows ? '.exe' : ''}';
        await run(Platform.resolvedExecutable, [
          'compile',
          'exe',
          source,
          '-o',
          native,
        ]);
        await run(native, []);
        final javascript = '${output.absolute.path}/oracle.js';
        await run(Platform.resolvedExecutable, [
          'compile',
          'js',
          source,
          '-o',
          javascript,
        ]);
        await run('node', [
          '-e',
          'globalThis.self = globalThis; require(process.argv[1]);',
          javascript,
        ]);
      } finally {
        output.deleteSync(recursive: true);
      }
    },
    skip: Platform.environment['DART_RUNTIME_TESTS'] != '1'
        ? 'Set DART_RUNTIME_TESTS=1 with Node.js installed'
        : false,
    timeout: const Timeout(Duration(minutes: 2)),
  );
}
