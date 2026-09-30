import 'dart:convert';
import 'dart:io';

import 'package:dart_astgen/exporter.dart';
import 'package:test/test.dart';

import '../../conformance/upstream/guard_capture.dart' as guards;
import '../../conformance/upstream/late_field_initializers.dart' as late_fields;
import '../../conformance/upstream/null_aware_evaluation.dart' as null_aware;

void main() {
  test('SDK null-aware evaluation order oracle', null_aware.main);
  test('SDK late field initializer oracle', late_fields.main);
  test('SDK pattern guard capture oracle', guards.main);

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
