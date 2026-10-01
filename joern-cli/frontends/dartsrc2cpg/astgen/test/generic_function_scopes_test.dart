import 'package:test/test.dart';

import '../../src/test/resources/semantics/generic_function_scopes.dart'
    as fixture;

void main() {
  test(
    'generic function aliases and callbacks retain their separate bounds',
    () {
      final value = fixture.Data();
      fixture.Poly poly = fixture.identity;
      fixture.Both both = (fixture.identity, fixture.numeric);
      fixture.Higher<fixture.Poly> higher = fixture.identity;
      expect(identical(poly(value), value), isTrue);
      expect(identical(both.$1(value), value), isTrue);
      expect(identical(higher(value), value), isTrue);
      expect(identical(fixture.Holder().callback(value), value), isTrue);
      for (final number in [-1, 0, 1]) {
        expect(both.$2(number), number);
      }
      final result = fixture.callbacks(
        fixture.identity,
        fixture.numeric,
        value,
      );
      expect(identical(result.$1, value), isTrue);
      expect(result.$2, 7);
    },
  );
}
