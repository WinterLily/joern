import 'package:test/test.dart';

import '../../src/test/resources/semantics/comparison_cache.dart' as fixture;

void main() {
  test(
    'comparison invocations share constant values but retain receiver direction',
    () {
      for (final value in [null, 0, 1, 2, 3]) {
        final input = value == null ? null : fixture.Probe(value);
        for (final accept in [false, true]) {
          fixture.trace.clear();
          expect(
            fixture.constants(input, accept),
            value == 1 ? (accept ? 'first' : 'second') : 'other',
          );
          expect(fixture.trace, [if (value != null) '1==$value']);
          fixture.trace.clear();
          expect(
            fixture.equal(input, accept),
            value == 1 ? (accept ? 'first' : 'second') : 'other',
          );
          expect(fixture.trace, [if (value != null) '$value==1']);
        }
        fixture.trace.clear();
        expect(fixture.negation(input), value == 1 ? 'same' : 'different');
        expect(fixture.trace, [if (value != null) '$value==1']);
        fixture.trace.clear();
        expect(fixture.directions(input), value == 1 ? 'second' : 'other');
        expect(fixture.trace, [
          if (value != null) ...['1==$value', '$value==1'],
        ]);
        fixture.trace.clear();
        expect(
          fixture.separate(input),
          List.filled(2, value == 1 ? 'second' : 'other'),
        );
        expect(fixture.trace, [
          if (value != null) ...['$value==1', '$value==1'],
        ]);
        if (input != null) {
          fixture.trace.clear();
          expect(fixture.logical(input), value! > 1);
          expect(fixture.trace, ['$value>1']);
          fixture.trace.clear();
          expect(fixture.distinct(input), value > 1);
          expect(fixture.trace, ['$value>1', '$value>2']);
          fixture.trace.clear();
          expect(fixture.nested((input, input)), value > 1);
          expect(fixture.trace, ['$value>1', '$value>1']);
        }
      }
    },
  );
}
