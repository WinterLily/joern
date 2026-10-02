import 'dart:io';

import 'package:test/test.dart';

Future<void> runPinned(String packageName, String program) async {
  final scratch = Directory('../../../../agents').absolute;
  final package = Directory('${scratch.path}/dart-corpus/$packageName');
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
    'pinned path-1.9.1 flow and isolation boundaries',
    () => runPinned('path-1.9.1', r'''
import 'package:path/path.dart' as p;
void main() {
  final context = p.Context(style: p.Style.posix);
  for (final input in ['a', 'first/second', '/root/file.txt']) {
    if (context.normalize(input) != input) throw StateError('Changed normalized fast return');
  }
  if (context.normalize('a/./b/../c') != 'a/c') throw StateError('Lost normalized slow branch');
  for (final (input, base, without) in [
    ('dir/file.dart', 'file.dart', 'dir/file'),
    ('dir/plain', 'plain', 'dir/plain'),
    ('dir/.hidden', '.hidden', 'dir/.hidden'),
    ('dir/archive.tar.gz', 'archive.tar.gz', 'dir/archive.tar'),
  ]) {
    if (context.basename(input) != base || p.basename(input) != base ||
        context.withoutExtension(input) != without) {
      throw StateError('Lost path parsing or cross-file forwarding');
    }
    for (final extension in ['', '.first', '.second']) {
      if (context.setExtension(input, extension) != '$without$extension') {
        throw StateError('Mixed parsed input and extension suffix');
      }
    }
  }
}
'''),
    skip: skip,
  );
  test(
    'pinned async-2.13.0 flow and isolation boundaries',
    () => runPinned('async-2.13.0', r'''
import 'dart:async';
import 'package:async/async.dart';
class RecordingSink<T> implements EventSink<T> {
  final values = <T>[];
  final errors = <(Object, StackTrace?)>[];
  bool closed = false;
  void add(T value) => values.add(value);
  void addError(Object error, [StackTrace? trace]) => errors.add((error, trace));
  void close() { closed = true; }
}
Future<void> main() async {
  for (final text in ['first', 'second']) {
    final error = StateError(text);
    final trace = StackTrace.fromString('independent $text trace');
    final result = ErrorResult(error, trace);
    final completer = Completer<Object>();
    final checked = completer.future.then<void>((_) {
      throw StateError('Error completion produced a value');
    }, onError: (Object caught, StackTrace caughtTrace) {
      if (!identical(caught, error) || caughtTrace.toString() != trace.toString()) {
        throw StateError('Mixed error with stack trace');
      }
    });
    result.complete(completer);
    await checked;
    final sink = RecordingSink<Object>();
    result.addTo(sink);
    if (sink.errors.length != 1 || !identical(sink.errors.single.$1, error) ||
        !identical(sink.errors.single.$2, trace)) {
      throw StateError('Lost error and trace forwarding');
    }
    final independent = RecordingSink<String>();
    final values = RecordingSink<String>();
    ValueResult(text).addTo(DelegatingEventSink(values));
    Result.releaseSink(values).add(ValueResult('released $text'));
    if (values.values.join('|') != '$text|released $text' || independent.values.isNotEmpty) {
      throw StateError('Mixed sink values or independent receivers');
    }
    final operation = CancelableOperation.fromValue(text);
    if (await operation.value != text) throw StateError('Lost cascade completion input');
  }
}
'''),
    skip: skip,
  );
  test(
    'pinned http_parser-4.1.2 flow and isolation boundaries',
    () => runPinned('http_parser-4.1.2', r'''
import 'dart:convert';
import 'package:http_parser/src/chunked_coding/encoder.dart';
class RecordingSink implements Sink<List<int>> {
  final chunks = <List<int>>[];
  bool closed = false;
  void add(List<int> value) => chunks.add(value);
  void close() { closed = true; }
}
void check(List<int> actual, List<int> expected) {
  if (actual.length != expected.length) throw StateError('Lost chunk length');
  for (var i = 0; i < actual.length; i++) {
    if (actual[i] != expected[i]) throw StateError('Lost header, copied bytes or footer');
  }
}
void main() {
  for (final input in <List<int>>[[], [0], [255, 1, 0]]) {
    final expected = input.isEmpty ? ascii.encode('0\r\n\r\n') : [
      ...ascii.encode('${input.length.toRadixString(16)}\r\n'),
      ...input, ...ascii.encode('\r\n0\r\n\r\n'),
    ];
    final output = chunkedCodingEncoder.convert(input);
    check(output, expected);
    if (input.isNotEmpty) {
      final copy = List<int>.of(input);
      input[0] = 7;
      check(output, expected);
      check(chunkedCodingEncoder.convert(copy), expected);
    }
  }
  final sink = RecordingSink();
  final encoder = chunkedCodingEncoder.startChunkedConversion(sink);
  encoder.add([]);
  encoder.addSlice([99, 0, 255, 88], 1, 3, false);
  encoder.addSlice([1, 2, 3], 1, 1, true);
  check(sink.chunks[0], []);
  check(sink.chunks[1], [50, 13, 10, 0, 255, 13, 10]);
  check(sink.chunks[2], [48, 13, 10, 13, 10]);
  if (!sink.closed || sink.chunks.length != 3) throw StateError('Lost selected sink delivery');
}
'''),
    skip: skip,
  );
}
