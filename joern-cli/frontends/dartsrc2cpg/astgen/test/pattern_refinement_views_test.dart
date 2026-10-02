import 'package:test/test.dart';

import '../../src/test/resources/semantics/pattern_receiver_intersection.dart'
    as values;
import '../../src/test/resources/semantics/pattern_refinement_views.dart'
    as patterns;

void main() {
  test('pattern refinements retain receiver values and getter order', () {
    for (final first in ['value', 'independent']) {
      for (final invoke in [patterns.casted, patterns.plainCast]) {
        values.trace.clear();
        expect(invoke(values.Store(first)), first);
        expect(values.trace, ['store']);
        values.trace.clear();
        expect(
          () => invoke(values.OtherStore('other')),
          throwsA(isA<TypeError>()),
        );
        expect(values.trace, isEmpty);
      }
      values.trace.clear();
      expect(patterns.asserted(values.View(values.Store(first))), first);
      expect(values.trace, ['store']);
      values.trace.clear();
      expect(patterns.plainAssert(values.Store(first)), first);
      expect(values.trace, ['store']);
    }
    for (final invoke in [
      () => patterns.asserted(null),
      () => patterns.plainAssert(null),
    ]) {
      values.trace.clear();
      expect(invoke, throwsA(isA<TypeError>()));
      expect(values.trace, isEmpty);
    }
  });

  test(
    'generic pattern refinements retain bounds and nullable cast results',
    () {
      for (final first in ['value', 'independent']) {
        values.trace.clear();
        expect(
          patterns.genericWrapperCast<values.Store>(values.Store(first)),
          first,
        );
        expect(values.trace, ['store']);
        values.trace.clear();
        expect(patterns.boundedAssert(values.View(values.Store(first))), first);
        expect(values.trace, ['store']);
        expect(patterns.genericCast<String>(first), first);
        expect(patterns.genericAssert<String>(first), first);
      }
      expect(patterns.genericCast<String?>(null), isNull);
      values.trace.clear();
      expect(
        () => patterns.genericWrapperCast<values.Store>(
          values.OtherStore('other'),
        ),
        throwsA(isA<TypeError>()),
      );
      expect(
        () => patterns.boundedAssert<values.Store>(null),
        throwsA(isA<TypeError>()),
      );
      expect(
        () => patterns.genericCast<String>(null),
        throwsA(isA<TypeError>()),
      );
      expect(
        () => patterns.genericAssert<String>(null),
        throwsA(isA<TypeError>()),
      );
      expect(values.trace, isEmpty);
    },
  );

  test('cached refined patterns share getters across guard alternatives', () {
    for (final first in ['value', 'independent']) {
      for (final accept in [false, true]) {
        values.trace.clear();
        expect(patterns.cachedCast(values.Store(first), accept), first);
        expect(values.trace, ['store']);
        values.trace.clear();
        expect(
          patterns.cachedAssert(values.View(values.Store(first)), accept),
          first,
        );
        expect(values.trace, ['store']);
        values.trace.clear();
        expect(
          () => patterns.cachedCast(values.OtherStore('other'), accept),
          throwsA(isA<TypeError>()),
        );
        expect(
          () => patterns.cachedAssert(null, accept),
          throwsA(isA<TypeError>()),
        );
        expect(values.trace, isEmpty);
      }
    }
  });

  test(
    'scalar record and function refinements preserve independent results',
    () {
      for (final first in ['value', 'independent']) {
        expect(patterns.scalarCast(first), first);
        expect(patterns.scalarAssert(first), first);
        expect(patterns.recordCast((first, 7)), (first, 7));
        String identity(String input) => '$first:$input';
        final selected = patterns.functionCast(identity);
        expect(identical(selected, identity), isTrue);
        expect(selected('argument'), '$first:argument');
      }
      expect(() => patterns.scalarCast(123), throwsA(isA<TypeError>()));
      expect(() => patterns.scalarAssert(null), throwsA(isA<TypeError>()));
      expect(
        () => patterns.recordCast(('value', 'wrong')),
        throwsA(isA<TypeError>()),
      );
      expect(() => patterns.functionCast(123), throwsA(isA<TypeError>()));
    },
  );
}
