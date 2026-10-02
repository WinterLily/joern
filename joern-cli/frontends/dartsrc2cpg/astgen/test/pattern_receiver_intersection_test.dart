import 'package:test/test.dart';

import '../../src/test/resources/semantics/pattern_receiver_intersection.dart'
    as patterns;

void main() {
  test('object pattern constraints retain known receiver classes', () {
    for (final first in ['value', 'independent']) {
      patterns.trace.clear();
      expect(patterns.selected(patterns.Store(first)), first);
      expect(patterns.trace, ['store']);
      patterns.trace.clear();
      expect(patterns.wrapped(patterns.View(patterns.Store(first))), first);
      expect(patterns.trace, ['store']);
      patterns.trace.clear();
      expect(patterns.nullable(patterns.Store(first)), first);
      expect(patterns.trace, ['store']);
    }
    patterns.trace.clear();
    expect(patterns.nullable(null), isNull);
    expect(patterns.trace, isEmpty);
  });

  test(
    'object pattern constraints retain generic bounds and wrapper erasures',
    () {
      for (final first in ['value', 'independent']) {
        patterns.trace.clear();
        expect(patterns.bounded(patterns.Store(first)), first);
        expect(patterns.trace, ['store']);
        patterns.trace.clear();
        expect(
          patterns.boundedView(patterns.View(patterns.Store(first))),
          first,
        );
        expect(patterns.trace, ['store']);
      }
    },
  );

  test('required receiver constraints still filter broad inputs', () {
    for (final first in ['value', 'independent']) {
      patterns.trace.clear();
      expect(patterns.narrowed(patterns.Store(first)), first);
      expect(patterns.trace, ['store']);
      patterns.trace.clear();
      expect(patterns.narrowed(patterns.OtherStore(first)), 'absent');
      expect(patterns.trace, isEmpty);
      patterns.trace.clear();
      expect(patterns.matched(patterns.Store(first)), first);
      expect(patterns.trace, ['store']);
      patterns.trace.clear();
      expect(patterns.matched(patterns.OtherStore(first)), first);
      expect(patterns.trace, ['other']);
    }
  });

  test('cached object patterns keep receiver constraints per use', () {
    for (final accept in [false, true]) {
      for (final first in ['value', 'independent']) {
        patterns.trace.clear();
        expect(
          patterns.repeated(patterns.View(patterns.Store(first)), accept),
          first,
        );
        expect(patterns.trace, ['store']);
        patterns.trace.clear();
        expect(patterns.cached(patterns.Store(first), accept), first);
        expect(patterns.trace, ['store']);
        patterns.trace.clear();
        expect(patterns.cached(patterns.OtherStore(first), accept), first);
        expect(patterns.trace, ['other']);
      }
    }
  });
}
