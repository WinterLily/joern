import 'dart:io';

import 'package:test/test.dart';

import '../../src/test/resources/semantics/generic_iteration.dart' as generic;
import '../../src/test/resources/semantics/synchronous_iteration.dart'
    as iteration;

void main() {
  setUp(iteration.trace.clear);

  test(
    'generic iteration bounds preserve member order and concrete values',
    () {
      final values = iteration.Values(['first', 'second']);
      for (final collect in [
        generic.bounded<iteration.Values<String>>,
        generic.chained<
          String,
          iteration.Values<String>,
          iteration.Values<String>
        >,
        generic.symbolic<String>,
        generic.boundedInterface<iteration.Values<String>>,
        generic.nullableBound<iteration.Values<String>?>,
      ]) {
        iteration.trace.clear();
        expect(collect(values), ['first', 'second']);
        expect(iteration.trace, [
          'iterator',
          'moveNext',
          'current',
          'moveNext',
          'current',
          'moveNext',
        ]);
      }
      iteration.trace.clear();
      expect(generic.inherited(generic.InheritedValues(['inherited'])), [
        'inherited',
      ]);
      expect(iteration.trace, ['iterator', 'moveNext', 'current', 'moveNext']);
      iteration.trace.clear();
      expect(generic.nullableBound<iteration.Values<String>?>(null), isEmpty);
      expect(iteration.trace, isEmpty);
    },
  );

  test(
    'recursive bounds and concrete record iteration retain element identities',
    () {
      final leaf = generic.Leaf();
      leaf.items.add(leaf);
      expect(generic.recursive(leaf).single, same(leaf));
      expect(iteration.trace, ['iterator', 'moveNext', 'current', 'moveNext']);
      iteration.trace.clear();
      expect(iteration.patterned(iteration.Values([('left', 'right')])), [
        'left:right',
      ]);
      expect(iteration.trace, ['iterator', 'moveNext', 'current', 'moveNext']);
    },
  );
  test(
    'pinned compiler reports the bounded record-pattern disagreement',
    () async {
      final scratch = Directory('../../../../agents')
        ..createSync(recursive: true);
      final output = scratch.createTempSync('dart-generic-pattern-');
      try {
        final compilation = await Process.run(Platform.resolvedExecutable, [
          'compile',
          'kernel',
          '../src/test/resources/semantics/generic_iteration_pattern.dart',
          '-o',
          '${output.path}/oracle.dill',
        ]);
        expect(compilation.exitCode, isNot(0));
        expect(
          compilation.stderr,
          contains(
            "The type 'T' used in the 'for' loop must implement 'Iterable<dynamic>'",
          ),
        );
        expect(File('${output.path}/oracle.dill').existsSync(), isFalse);
      } finally {
        output.deleteSync(recursive: true);
      }
    },
  );
}
