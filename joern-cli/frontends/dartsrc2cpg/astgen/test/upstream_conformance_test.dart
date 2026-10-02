import 'dart:convert';
import 'dart:io';

import 'package:dart_astgen/exporter.dart';
import 'package:test/test.dart';

import '../../conformance/upstream/for_in_side_effects.dart' as iteration;
import '../../conformance/upstream/guard_capture.dart' as guards;
import '../../conformance/upstream/late_field_initializers.dart' as late_fields;
import '../../conformance/upstream/null_aware_evaluation.dart' as null_aware;

void main() {
  test('SDK synchronous iterator getter side-effect oracle', iteration.main);
  test('SDK null-aware evaluation order oracle', null_aware.main);
  test('SDK late field initializer oracle', late_fields.main);
  test('SDK pattern guard capture oracle', guards.main);

  test(
    'SDK iterator getter side effects survive dart2js compilation',
    () async {
      final scratch = Directory('../../../../agents')
        ..createSync(recursive: true);
      final output = scratch.createTempSync('dart-sdk-iteration-');
      final source = File(
        '../conformance/upstream/for_in_side_effects.dart',
      ).absolute.path;
      try {
        final javascript = '${output.absolute.path}/oracle.js';
        final compilation = await Process.run(Platform.resolvedExecutable, [
          'compile',
          'js',
          source,
          '-o',
          javascript,
        ]);
        expect(
          compilation.exitCode,
          0,
          reason: '${compilation.stdout}\n${compilation.stderr}',
        );
        final execution = await Process.run('node', [
          '-e',
          'globalThis.self = globalThis; require(process.argv[1]);',
          javascript,
        ]);
        expect(
          execution.exitCode,
          0,
          reason: '${execution.stdout}\n${execution.stderr}',
        );
      } finally {
        output.deleteSync(recursive: true);
      }
    },
    skip: Platform.environment['DART_RUNTIME_TESTS'] != '1'
        ? 'Set DART_RUNTIME_TESTS=1 with Node.js installed'
        : false,
    timeout: const Timeout(Duration(minutes: 2)),
  );

  test('classify pinned SDK valid and deliberate diagnostic cases', () async {
    final upstream = Directory('../conformance/upstream');
    final manifest =
        jsonDecode(File('${upstream.path}/manifest.json').readAsStringSync())
            as Map<String, dynamic>;
    final scratch = Directory('../../../../agents')
      ..createSync(recursive: true);
    final project = scratch.createTempSync('dart-upstream-');
    try {
      File('${project.path}/pubspec.yaml').writeAsStringSync(
        'name: upstream_cases\nenvironment:\n  sdk: ^3.9.2\n',
      );
      File(
        '${upstream.path}/expect.dart',
      ).copySync('${project.path}/expect.dart');
      for (final entry in manifest['cases'] as List) {
        final name = entry['file'] as String;
        File('${upstream.path}/$name').copySync('${project.path}/$name');
        final records = await exportProject(
          root: project.path,
          input: '${project.path}/$name',
        ).toList();
        final unit = records.singleWhere((r) => r['record'] == 'unit');
        expect(unit['unsupportedKinds'], isEmpty, reason: name);
        if (name == 'for_in_side_effects.dart') {
          final loop = (unit['nodes'] as List).singleWhere(
            (node) => node['kind'] == 'ForEachParts',
          );
          final iterator = (unit['symbols'] as List).singleWhere(
            (symbol) => symbol['id'] == loop['iteratorTarget'],
          );
          expect(iterator['name'], 'iterator');
          expect(iterator['file'], name);
          expect(iterator['kind'], 'GETTER');
          expect(iterator['external'], isNot(isTrue));
          expect(loop['moveNextTarget'], isNotNull);
          expect(loop['currentTarget'], isNotNull);
        }
        if (name == 'guard_capture.dart') {
          final joins = (unit['nodes'] as List)
              .where((node) => node['kind'] == 'SwitchPatternCase')
              .expand((node) => node['joins'] as List)
              .toList();
          expect(joins.length, 6);
          expect(joins.map((join) => join['source']).toSet().length, 6);
          expect(joins.map((join) => join['target']).toSet().length, 2);
          expect(
            joins.every((join) => join['source'] != join['target']),
            isTrue,
          );
        }
        if (entry['classification'] == 'valid') {
          expect(unit['status'], 'resolved', reason: name);
        } else {
          expect(unit['status'], 'partial', reason: name);
          final codes = (unit['diagnostics'] as List)
              .where((d) => d['severity'] == 'ERROR')
              .map((d) => (d['code'] as String).toLowerCase())
              .toList();
          expect(
            codes,
            unorderedEquals(entry['expectedDiagnostics']),
            reason: name,
          );
        }
      }
    } finally {
      project.deleteSync(recursive: true);
    }
  });
}
