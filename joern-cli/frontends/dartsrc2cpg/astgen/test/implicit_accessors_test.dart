import 'package:test/test.dart';

import '../../src/test/resources/semantics/implicit_accessors.dart' as fixture;

void main() {
  test(
    'field accessors dispatch while constructor and super storage stay direct',
    () {
      for (final input in ['', 'input']) {
        fixture.trace.clear();
        expect(fixture.direct(input), input);
        expect(fixture.constant(input), 'constant');
        expect(fixture.inherited(input), input);
        expect(fixture.trace, isEmpty);
        expect(fixture.throughInterface(fixture.Plain('old'), input), input);
        final derived = fixture.Derived('old');
        expect(fixture.throughInterface(derived, input), 'constant');
        expect(fixture.trace, [input]);
        final fixed = fixture.Fixed('fixed');
        expect(fixture.throughInterface(fixed, input), 'fixed');
        expect(fixture.trace, [input, input]);
        expect(derived.inherited(), 'old');
        derived.replace(input);
        expect(derived.inherited(), input);
        expect(fixture.trace, [input, input]);
      }
      for (final input in [-1, 0, 1]) {
        expect(fixture.lazyIncrement(input), input);
        expect(fixture.Counter.next, input + 1);
      }
    },
  );
}
