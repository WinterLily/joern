import 'dart:io';

import 'package:test/test.dart';

Future<void> runPinned(String program) async {
  final scratch = Directory('../../../../agents').absolute;
  final package = Directory('${scratch.path}/dart-corpus/http_parser-4.1.2');
  final configuration = File('${package.path}/.dart_tool/package_config.json');
  expect(
    configuration.existsSync(),
    isTrue,
    reason: 'Prepare the pinned Dart corpus first',
  );
  final output = scratch.createTempSync('dart-corpus-capture-');
  try {
    final source = File('${output.path}/main.dart')..writeAsStringSync(program);
    final result = await Process.run(Platform.resolvedExecutable, [
      '--packages=${configuration.path}',
      source.path,
    ]);
    expect(result.exitCode, 0, reason: '${result.stdout}\n${result.stderr}');
  } finally {
    output.deleteSync(recursive: true);
  }
}

void main() {
  final skip = Platform.environment['DART_CORPUS_TESTS'] != '1'
      ? 'Set DART_CORPUS_TESTS=1 after preparing the pinned corpus'
      : false;
  test(
    'pinned media-type parsing invokes the callback with its captured input',
    () => runPinned('''
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
'''),
    skip: skip,
  );
  test(
    'pinned format wrapper separates message input, source and span branches',
    () => runPinned(r'''
import 'package:http_parser/src/utils.dart';
import 'package:source_span/source_span.dart';

void main() {
  final span = SourceFile.fromString('span source').span(0, 4);
  for (final input in ['', 'input', 'second']) {
    var invoked = 0;
    final result = wrapFormatException('field', input, () {
      invoked++;
      return 'independent';
    });
    if (result != 'independent' || invoked != 1) {
      throw StateError('Changed body result or invocation count');
    }
    try {
      wrapFormatException<void>('field', input, () {
        invoked++;
        throw FormatException('bad value', 'independent source', 2);
      });
      throw StateError('Missing format error');
    } on FormatException catch (error) {
      if (error.message != 'Invalid field "$input": bad value' ||
          error.source != 'independent source' || error.offset != 2 ||
          invoked != 2) {
        throw StateError('Mixed message input, source or offset');
      }
    }
    try {
      wrapFormatException<void>('field', input, () {
        invoked++;
        throw SourceSpanFormatException('bad span', span, 'span origin');
      });
      throw StateError('Missing span error');
    } on SourceSpanFormatException catch (error) {
      if (error.message != 'Invalid field: bad span' ||
          !identical(error.span, span) || error.source != 'span origin' ||
          invoked != 3) {
        throw StateError('Lost span identity or joined exception branches');
      }
    }
  }
}
'''),
    skip: skip,
  );
}
