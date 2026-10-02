import 'package:test/test.dart';

import '../../src/test/resources/semantics/list_pattern_results.dart'
    as patterns;

void main() {
  test(
    'list extraction retains instantiated scalar, slice and tail results',
    () {
      for (final text in ['first', 'second']) {
        patterns.trace.clear();
        expect(patterns.scalar(patterns.Values([text])), text);
        expect(patterns.trace, ['length', 'index:0']);
        patterns.trace.clear();
        final slice = patterns.sliced(patterns.Values(['head', text]));
        expect(patterns.trace, ['length', 'slice:1:null']);
        expect(slice, [text]);
        patterns.trace.clear();
        expect(patterns.tail(patterns.Values(['head', text])), text);
        expect(patterns.trace, ['length', 'index:1']);
      }
    },
  );

  test(
    'element types precede child tests and cached reads retain each view',
    () {
      patterns.trace.clear();
      expect(patterns.typed(patterns.Values<Object>(['text'])), 'text');
      expect(patterns.trace, ['length', 'index:0']);
      patterns.trace.clear();
      expect(patterns.typed(patterns.Values<Object>([42])), 'miss');
      expect(patterns.trace, ['length', 'index:0']);
      for (final value in <num>[2, 2.5]) {
        patterns.trace.clear();
        expect(patterns.cached(patterns.Values<num>([value])), value);
        expect(patterns.trace, ['length', 'index:0']);
      }
    },
  );

  test('list results retain nested wrapper representation constraints', () {
    for (final text in ['first', 'second']) {
      patterns.trace.clear();
      expect(
        patterns.wrapped(
          patterns.Values([patterns.View(patterns.Store(text))]),
        ),
        text,
      );
      expect(patterns.trace, ['length', 'index:0', 'store']);
      patterns.trace.clear();
      expect(
        patterns.nested(
          patterns.Values([
            patterns.Values([patterns.View(patterns.Store(text))]),
          ]),
        ),
        text,
      );
      expect(patterns.trace, [
        'length',
        'index:0',
        'length',
        'index:0',
        'store',
      ]);
    }
  });

  test('generic list results use the caller type environment', () {
    expect(patterns.generic(patterns.Values(['text'])), 'text');
    expect(patterns.generic(patterns.Values([42])), 42);
    expect(
      () => patterns.generic(patterns.Values<String>([])),
      throwsStateError,
    );
  });
}
