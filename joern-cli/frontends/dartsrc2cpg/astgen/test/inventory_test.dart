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
      final index =
          jsonDecode(
                File(
                  '../conformance/${(inventory['sourceIndex'] as Map)['file']}',
                ).readAsStringSync(),
              )
              as Map<String, dynamic>;
      final sdk = index['sdk'] as Map;
      expect(sdk['version'], inventory['sdk']);
      expect(sdk['revision'], inventory['sdkRevision']);
      final candidates = (sdk['candidateCases'] as List)
          .map((pair) => (pair as List).first)
          .toSet();
      final language = index['language'] as Map;
      final sections =
          (language['baseSpecification'] as Map)['sections'] as List;
      final features = (language['featureSpecifications'] as List)
          .map((spec) => (spec as Map)['path'])
          .toSet();
      final exporter = File('lib/exporter.dart').readAsStringSync();
      final cases = RegExp(
        r'case (\w+)\(\)',
      ).allMatches(exporter).map((match) => match.group(1)).toSet();
      final contracts = inventory['contracts'] as Map;
      final referencedSections = <dynamic>{};
      for (final row in rows) {
        expect(contracts.containsKey(row['contract']), isTrue);
        expect(row['reason'], isNotEmpty);
        final references = row['references'] as Map;
        referencedSections.addAll(references['sections'] as List);
        expect(sections, containsAll(references['sections'] as List));
        expect(features, containsAll(references['features'] as List));
        expect(
          candidates,
          containsAll(references['sdkCandidateCases'] as List),
        );
        if (row['scope'] == 'dart-3.9') {
          expect([
            ...(references['sections'] as List),
            ...(references['features'] as List),
          ], isNotEmpty);
          expect(references['sdkCandidateCases'], isNotEmpty);
          expect(
            references['status'],
            'reference-only; candidate cases unreviewed and not executed',
          );
        } else {
          expect(references['sdkCandidateCases'], isEmpty);
        }
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
      final matrix =
          jsonDecode(
                File(
                  '../conformance/qualification-matrix.json',
                ).readAsStringSync(),
              )
              as Map;
      expect(
        (matrix['visitors'] as List)
            .map((row) => (row as Map)['construct'])
            .toSet(),
        constructs,
      );
      expect(
        (matrix['rules'] as List).map((row) => (row as Map)['section']).toSet(),
        sections.toSet(),
      );
      expect(
        (matrix['features'] as List).map((row) => (row as Map)['file']).toSet(),
        features,
      );
      final stages = matrix['stages'] as List;
      final profiles = matrix['profiles'] as Map;
      for (final profile in profiles.values.cast<Map>()) {
        expect(profile.keys.toSet(), stages.toSet());
        for (final cell in profile.values.cast<Map>()) {
          expect(matrix['statuses'] as List, contains(cell['status']));
          expect(cell['obligation'], isNotEmpty);
        }
      }
      final gaps = (inventory['specificationGaps'] as List).cast<Map>();
      expect(
        gaps.map((gap) => gap['section']).toSet(),
        sections.toSet().difference(referencedSections),
      );
      expect(
        gaps.every(
          (gap) => gap['kind'] == 'document-context'
              ? gap['qualification'] == 'not-applicable'
              : gap['kind'] == 'semantic-rule' &&
                    gap['qualification'] == 'unqualified',
        ),
        isTrue,
      );
    },
  );
}
