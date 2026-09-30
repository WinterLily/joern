import 'dart:convert';
import 'dart:io';

import 'package:test/test.dart';

Map<String, dynamic> read(String path) =>
    jsonDecode(File(path).readAsStringSync()) as Map<String, dynamic>;

void main() {
  test(
    'every corpus expectation and retained witness has a complete review',
    () {
      final review = read('../corpus/witness-reviews.json');
      final probes = {
        ...read('../corpus/dataflow-probes.json'),
        ...read('../corpus/applications/dataflow-probes.json'),
      };
      final classes = review['transitionClasses'] as Map;
      final projects = review['projects'] as List;
      expect(
        projects.map((p) => p['source']['name']).toSet(),
        probes.keys.toSet(),
      );
      expect(
        review['analysisSources']['sha256'],
        matches(RegExp(r'^[a-f0-9]{64}$')),
      );
      var queries = 0;
      var positives = 0;
      var selectedPaths = 0;
      var uniquePaths = 0;
      var transitions = 0;
      var identities = 0;
      for (final project in projects) {
        final checks = project['checks'] as List;
        final expected = probes[project['source']['name']] as List;
        expect(checks.map((c) => c['probe']).toList(), expected);
        final nodes = project['nodes'] as Map;
        final paths = project['paths'] as Map;
        final used = <String>{};
        for (final check in checks) {
          queries++;
          final positive = check['probe']['expected'] as bool;
          if (positive) positives++;
          expect(check['sourceReview'], isNotEmpty);
          expect(check['routeReview'], isNotEmpty);
          expect((check['modes'] as Map).keys.toSet(), {'stock', 'modeled'});
          for (final mode in ['stock', 'modeled']) {
            final result = check['modes'][mode];
            expect(result['omittedWitnesses'], 0);
            expect(result['sources'], 1);
            expect(result['sinks'], 1);
            final selected = (result['paths'] as List).cast<String>();
            selectedPaths += selected.length;
            used.addAll(selected);
            expect(selected.every(paths.containsKey), isTrue);
            if (positive) expect(selected, isNotEmpty);
            if (!positive) {
              final control = checks.singleWhere(
                (c) => c['probe']['id'] == result['positiveControl'],
              );
              expect(control['probe']['source'], check['probe']['source']);
              expect(control['modes'][mode]['distinctEndpoints'], isTrue);
              expect(control['modes'][mode]['passed'], isTrue);
              expect(result['positiveControlSatisfied'], isTrue);
            }
            if (selected.isEmpty &&
                (result['limitations'] as List).isNotEmpty) {
              expect(result['outcome'], 'inconclusive-query-limits');
            }
          }
        }
        expect(used, paths.keys.toSet());
        for (final path in paths.values) {
          final ids = path['nodes'] as List;
          final steps = path['transitions'] as List;
          expect(ids, isNotEmpty);
          expect(steps.length, ids.length - 1);
          expect(ids.every(nodes.containsKey), isTrue);
          expect(steps.every(classes.containsKey), isTrue);
          uniquePaths++;
          transitions += steps.length;
          if (ids.length == 1) identities++;
          for (var i = 0; i < steps.length; i++) {
            final from = nodes[ids[i]];
            final to = nodes[ids[i + 1]];
            if (steps[i] == 'parameter-binding' ||
                steps[i] == 'receiver-binding') {
              expect(
                (from['argumentsOf'] as List).any(
                  (call) =>
                      call['argumentIndex'] == to['parameterIndex'] &&
                      (call['callees'] as List).any(
                        (callee) => callee['fullName'] == to['method'],
                      ),
                ),
                isTrue,
              );
            }
            if (steps[i] == 'call-return') {
              expect(
                (to['call']['callees'] as List).any(
                  (callee) => callee['fullName'] == from['method'],
                ),
                isTrue,
              );
            }
          }
        }
      }
      expect(review['totals'], {
        'queries': queries,
        'positiveQueries': positives,
        'negativeQueries': queries - positives,
        'selectedPaths': selectedPaths,
        'uniquePaths': uniquePaths,
        'uniqueTransitions': transitions,
        'identityPaths': identities,
      });
    },
  );
}
