import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:test/test.dart';

void main() {
  test(
    'pinned analyzer visitor surface has a complete classified inventory',
    () async {
      final uri = await Isolate.resolvePackageUri(
        Uri.parse('package:analyzer/src/dart/ast/ast.g.dart'),
      );
      final source = File.fromUri(uri!).readAsStringSync();
      final constructs = RegExp(
        r'R\? visit\w+\((\w+) node\);',
      ).allMatches(source).map((match) => match.group(1)!).toSet();
      final inventory =
          jsonDecode(File('../conformance/inventory.json').readAsStringSync())
              as Map<String, dynamic>;
      final rows = (inventory['constructs'] as List)
          .cast<Map<String, dynamic>>();
      expect(rows.map((row) => row['construct']).toSet(), constructs);
      expect(rows.length, constructs.length);
      final exporter = File('lib/exporter.dart').readAsStringSync();
      final cases = RegExp(
        r'case (\w+)\(\)',
      ).allMatches(exporter).map((match) => match.group(1)).toSet();
      final contracts = inventory['contracts'] as Map;
      for (final row in rows) {
        expect(contracts.containsKey(row['contract']), isTrue);
        expect(row['reason'], isNotEmpty);
        final route = row['exporterCase'];
        if (route != null) {
          expect(cases, contains(route), reason: row['construct']);
        } else {
          expect(row['qualification'], anyOf('structural-only', 'unsupported'));
        }
        final contract = contracts[row['contract']] as Map;
        expect(
          contract.keys,
          containsAll([
            'exporter',
            'lowering',
            'resolution',
            'cfg',
            'valueFlow',
            'negative',
            'interaction',
          ]),
        );
      }
    },
  );
}
