import 'package:test/test.dart';

import '../../src/test/resources/semantics/generic_tearoffs.dart' as fixture;

void main() {
  test(
    'generic instantiations preserve values and evaluate receivers once',
    () {
      expect(fixture.forward<String>, isA<String Function(String, String)>());
      expect(fixture.forward<int>, isA<int Function(int, int)>());
      expect(
        fixture.forward<int>,
        isNot(isA<String Function(String, String)>()),
      );
      for (final input in ['', 'input']) {
        for (final ignored in ['', 'ignored']) {
          for (final call in [
            fixture.top,
            fixture.staticMethod,
            fixture.aliases,
            fixture.namedAliases,
            fixture.defaultAliases,
          ]) {
            expect(call(input, ignored), input);
          }
          fixture.trace.clear();
          expect(fixture.bound(input, ignored), input);
          expect(fixture.trace, ['obtain:bound', 'bound:String']);
          fixture.trace.clear();
          expect(fixture.boundAliases(input, ignored), input);
          expect(fixture.trace, ['obtain:alias', 'alias:String']);
          for (final change in [false, true]) {
            expect(
              fixture.mutable(input, ignored, change),
              change ? ignored : input,
            );
            expect(
              fixture.conditional(input, ignored, change),
              change ? input : ignored,
            );
          }
          expect(fixture.returned(input, ignored), ignored);
        }
      }
      for (final input in [-1, 0, 1]) {
        expect(fixture.integers(input, 7), input);
      }
    },
  );
}
