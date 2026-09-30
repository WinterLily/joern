import 'package:test/test.dart';

import '../../src/test/resources/semantics/iterable_selection.dart';

void main() {
  test(
    'where selects original elements without returning predicate values',
    () {
      for (final input in ['', 'secret', 'different']) {
        expect(selected(input), input.isEmpty ? '' : 'fixed');
        expect(elements(input), input);
        expect(unrelated(input), 'fixed');
        expect(mapped(input), input);
        expect(constantMapped(input), 'fixed');
      }
    },
  );

  test('where evaluates predicates lazily in element order', () {
    final trace = <String>[];
    final filtered = ['keep', 'drop', 'keep again'].where((value) {
      trace.add(value);
      return value.startsWith('keep');
    });
    expect(trace, isEmpty);
    expect(filtered.toList(), ['keep', 'keep again']);
    expect(trace, ['keep', 'drop', 'keep again']);
  });
}
