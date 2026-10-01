import 'package:test/test.dart';
import '../../src/test/resources/semantics/witness_alternatives.dart'
    as fixture;

void main() {
  test('three value routes and repeated calls preserve argument isolation', () {
    for (final input in ['', 'input']) {
      for (final first in [false, true]) {
        for (final second in [false, true]) {
          final calls = first
              ? <String>[]
              : second
              ? ['relay']
              : ['longer', 'relay'];
          fixture.trace.clear();
          expect(fixture.choice(input, first, second), input);
          expect(fixture.trace, calls);
          fixture.trace.clear();
          expect(fixture.repeated(input, first, second), input);
          expect(fixture.trace, [...calls, ...calls]);
          fixture.trace.clear();
          expect(fixture.independent(input, first, second), 'constant');
          expect(fixture.trace, [...calls, ...calls]);
        }
      }
    }
  });
}
