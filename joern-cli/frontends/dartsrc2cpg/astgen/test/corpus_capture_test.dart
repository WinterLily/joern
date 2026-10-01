import 'dart:io';

import 'package:test/test.dart';

void main() {
  test(
    'pinned media-type parsing invokes the callback with its captured input',
    () async {
      final scratch = Directory('../../../../agents').absolute;
      final package = Directory(
        '${scratch.path}/dart-corpus/http_parser-4.1.2',
      );
      final configuration = File(
        '${package.path}/.dart_tool/package_config.json',
      );
      expect(
        configuration.existsSync(),
        isTrue,
        reason: 'Prepare the pinned Dart corpus first',
      );
      final output = scratch.createTempSync('dart-corpus-capture-');
      try {
        final source = File('${output.path}/main.dart')
          ..writeAsStringSync('''
import 'package:http_parser/http_parser.dart';

void main() {
  for (final input in ['text/plain', 'TEXT/PLAIN; charset=utf-8']) {
    final parsed = MediaType.parse(input);
    if (parsed.mimeType != 'text/plain') throw StateError('Lost captured input');
    if (input.contains(';') && parsed.parameters['charset'] != 'utf-8') {
      throw StateError('Lost captured parameter');
    }
  }
  for (final input in ['invalid-a', 'invalid-b']) {
    try {
      MediaType.parse(input);
      throw StateError('Invalid input parsed successfully');
    } on FormatException catch (error) {
      if (error.source != input) throw StateError('Mixed independent inputs');
    }
  }
}
''');
        final result = await Process.run(Platform.resolvedExecutable, [
          '--packages=${configuration.path}',
          source.path,
        ]);
        expect(
          result.exitCode,
          0,
          reason: '${result.stdout}\n${result.stderr}',
        );
      } finally {
        output.deleteSync(recursive: true);
      }
    },
    skip: Platform.environment['DART_CORPUS_TESTS'] != '1'
        ? 'Set DART_CORPUS_TESTS=1 after preparing the pinned corpus'
        : false,
  );
}
