import 'dart:io';

import 'package:test/test.dart';

Future<void> runHoldout(String name, String program) async {
  final scratch = Directory('../../../../agents').absolute;
  final configuration = File(
    '${scratch.path}/dart-holdout/$name/.dart_tool/package_config.json',
  );
  expect(
    configuration.existsSync(),
    isTrue,
    reason: 'Prepare the pinned Dart holdout first',
  );
  final output = scratch.createTempSync('dart-holdout-oracle-');
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
  final skip = Platform.environment['DART_HOLDOUT_TESTS'] != '1'
      ? 'Set DART_HOLDOUT_TESTS=1 after preparing the pinned holdout'
      : false;
  test(
    'pinned Shelf example retains URI text with an independent response status',
    () async {
      final example = File(
        '../../../../agents/dart-holdout/shelf-1.4.2/example/example.dart',
      ).readAsStringSync();
      expect('void main() async {'.allMatches(example).length, 1);
      await runHoldout(
        'shelf-1.4.2',
        example.replaceFirst(
              'void main() async {',
              'void serveExample() async {',
            ) +
            r'''
Future<void> main() async {
  for (final path in ['', 'alpha', 'second/part?x=value']) {
    final request = Request('GET', Uri.parse('http://localhost/$path'));
    final response = _echoRequest(request);
    if (response.statusCode != 200 ||
        await response.readAsString() != 'Request for "$path"') {
      throw StateError('Mixed body, independent request or status');
    }
  }
}
''',
      );
    },
    skip: skip,
  );
  test(
    'pinned YAML forwards document text with explicit independent recovery mode',
    () => runHoldout('yaml-3.1.3', r'''
import 'dart:convert';
import 'package:yaml/yaml.dart';
import 'package:yaml/src/error_listener.dart';
import 'package:yaml/src/loader.dart';

void main() {
  for (final input in ['', 'input', 'second']) {
    final document = 'key: ${jsonEncode(input)}\n';
    for (final recover in [false, true]) {
      final value = loadYaml(document, recover: recover);
      final loaded = Loader(document, recover: recover).load();
      if (value['key'] != input || loaded?.contents.value['key'] != input) {
        throw StateError('Lost document input or mixed invocations');
      }
    }
  }
  for (final invalid in [
    'key: value\nmissing\nnext: item\n',
    'key: value\nsecond missing\nnext: item\n',
  ]) {
    var rejected = false;
    try {
      loadYaml(invalid);
    } on YamlException {
      rejected = true;
    }
    if (!rejected) throw StateError('Recovery enabled without its flag');
    final errors = ErrorCollector();
    loadYaml(invalid, recover: true, errorListener: errors);
    if (errors.errors.isEmpty) throw StateError('Missing recovery diagnostics');
  }
  var rejected = false;
  try {
    loadYaml('[', recover: true);
  } on YamlException {
    rejected = true;
  }
  if (!rejected) throw StateError('Unexpected parser-error recovery');
}
'''),
    skip: skip,
  );
}
