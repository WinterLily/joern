import 'package:test/test.dart';

import '../../src/test/resources/semantics/pattern_cache.dart' as fixture;

void main() {
  test('pattern getter results are lazy and shared within one match', () {
    for (final value in ['input', 'independent']) {
      for (final invoke in [
        fixture.cases,
        fixture.statements,
        fixture.logical,
      ]) {
        fixture.trace.clear();
        expect(invoke(fixture.Probe('item', value)), value);
        expect(fixture.trace, ['item']);
      }
      for (final accept in [false, true]) {
        fixture.trace.clear();
        expect(fixture.guarded(fixture.Probe('item', value), accept), value);
        expect(fixture.trace, ['item']);
        fixture.trace.clear();
        expect(fixture.guarded(null, accept), 'absent');
        expect(fixture.trace, isEmpty);
      }
      fixture.trace.clear();
      expect(
        fixture.nested(
          fixture.Pair(
            fixture.Probe('left', value),
            fixture.Probe('right', 'other'),
          ),
        ),
        '$value:other',
      );
      expect(fixture.trace, ['left', 'right']);
      fixture.trace.clear();
      expect(fixture.wildcard(fixture.Probe('item', value)), true);
      expect(fixture.trace, ['item']);
      fixture.trace.clear();
      expect(fixture.separate(fixture.Probe('item', value)), '$value:changed');
      expect(fixture.trace, ['item', 'item']);
      for (final invoke in [
        fixture.direct,
        fixture.wrapped,
        fixture.nestedDirect,
        fixture.selected,
      ]) {
        fixture.trace.clear();
        expect(invoke(value), value);
        expect(fixture.trace, ['left']);
      }
      fixture.trace.clear();
      expect(fixture.independent(value), 'other');
      expect(fixture.trace, ['left', 'right']);
    }
  });
}
